package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.websocket.Frame
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Users
import kotlinx.coroutines.runBlocking
import utils.connectedClients
import utils.PresenceStorage
import utils.ReceiptsStorage
import utils.ServerKeysStorage
import utils.typingMap
import utils.MediaStorage
import utils.deviceKeys
import utils.oneTimeKeys
import utils.crossSigningKeys
import utils.deviceListStreamIds
import utils.directToDeviceMessages
import models.AccessTokens
import utils.StateResolver
import utils.MatrixAuth
import utils.ServerKeys
import models.Events
import models.Rooms

fun processPDU(pdu: JsonElement): JsonElement? {
    return try {
        val event = pdu.jsonObject

        // 1. Check validity: has room_id
        if (event["room_id"] == null) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing room_id")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        val roomId = event["room_id"]?.jsonPrimitive?.content ?: return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing room_id")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // Validate room_id format
        if (!roomId.startsWith("!") || !roomId.contains(":")) {
            return buildJsonObject {
                put("errcode", "M_INVALID_EVENT")
                put("error", "Invalid room_id format")
            }.toString().let { Json.parseToJsonElement(it).jsonObject }
        }

        // 2. Check validity: has sender
        if (event["sender"] == null) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing sender")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        val sender = event["sender"]?.jsonPrimitive?.content ?: return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing sender")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // Validate sender format
        if (!sender.startsWith("@") || !sender.contains(":")) {
            return buildJsonObject {
                put("errcode", "M_INVALID_EVENT")
                put("error", "Invalid sender format")
            }.toString().let { Json.parseToJsonElement(it).jsonObject }
        }

        // 3. Check validity: has event_id
        if (event["event_id"] == null) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing event_id")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        val eventId = event["event_id"]?.jsonPrimitive?.content ?: return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing event_id")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // Validate event_id format
        if (!eventId.startsWith("$")) {
            return buildJsonObject {
                put("errcode", "M_INVALID_EVENT")
                put("error", "Invalid event_id format")
            }.toString().let { Json.parseToJsonElement(it).jsonObject }
        }

        // 4. Check validity: has type
        if (event["type"] == null) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing type")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 5. Check hash
        if (!MatrixAuth.verifyEventHash(event)) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Invalid hash")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 6. Check signatures
        val sigValid = runBlocking { MatrixAuth.verifyEventSignatures(event) }
        if (!sigValid) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Invalid signature")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 7. Get auth state from auth_events
        val authState = getAuthState(event, roomId)

        // 8. Check auth based on auth_events
        if (!stateResolver.checkAuthRules(event, authState)) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Auth check failed")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 9. Get current state
        val currentState = stateResolver.getResolvedState(roomId)

        // 10. Check auth based on current state
        val softFail = !stateResolver.checkAuthRules(event, currentState)

        // 11. If state event, update current state
        if (event["state_key"] != null) {
            val newState = currentState.toMutableMap()
            val type = event["type"]?.jsonPrimitive?.content ?: ""
            val stateKey = event["state_key"]?.jsonPrimitive?.content ?: ""
            val key = "$type:$stateKey"
            newState[key] = event["content"]?.jsonObject ?: JsonObject(mutableMapOf())
            stateResolver.updateResolvedState(roomId, newState)
        }

        // 12. Store the event
        transaction {
            Events.insert {
                it[Events.eventId] = eventId
                it[Events.roomId] = roomId
                it[Events.type] = event["type"]?.jsonPrimitive?.content ?: ""
                it[Events.sender] = sender
                it[Events.content] = event["content"]?.toString() ?: ""
                it[Events.authEvents] = event["auth_events"]?.toString() ?: ""
                it[Events.prevEvents] = event["prev_events"]?.toString() ?: ""
                it[Events.depth] = event["depth"]?.jsonPrimitive?.int ?: 0
                it[Events.hashes] = event["hashes"]?.toString() ?: ""
                it[Events.signatures] = event["signatures"]?.toString() ?: ""
                it[Events.originServerTs] = event["origin_server_ts"]?.jsonPrimitive?.long ?: 0
                it[Events.stateKey] = event["state_key"]?.jsonPrimitive?.content
                it[Events.unsigned] = event["unsigned"]?.toString()
                it[Events.softFailed] = softFail
                it[Events.outlier] = false
            }
        }

        // Broadcast to connected clients
        if (!softFail) {
            runBlocking { broadcastPDU(roomId, event) }
        }

        if (softFail) {
            // Mark as soft failed, don't relay
            println("PDU soft failed: $eventId")
        } else {
            println("PDU stored: $eventId")
        }
        null // Success
    } catch (e: Exception) {
        buildJsonObject {
            put("errcode", "M_BAD_JSON")
            put("error", "Invalid PDU JSON")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }
    }
}

fun processTransaction(body: String): JsonElement {
    return try {
        val transaction = Json.parseToJsonElement(body).jsonObject
        val pdus = transaction["pdus"]?.jsonArray ?: JsonArray(emptyList())
        val edus = transaction["edus"]?.jsonArray ?: JsonArray(emptyList())

        val results = mutableListOf<JsonElement>()

        // Process PDUs
        for (pdu in pdus) {
            val result = processPDU(pdu)
            results.add(result ?: buildJsonObject {
                put("error", "Failed to process PDU")
            })
        }

        // Process EDUs
        for (edu in edus) {
            val result = processEDU(edu)
            results.add(result ?: buildJsonObject {
                put("error", "Failed to process EDU")
            })
        }

        // Return results
        JsonArray(results)
    } catch (e: Exception) {
        buildJsonObject {
            put("errcode", "M_BAD_JSON")
            put("error", "Invalid transaction format: ${e.message}")
        }
    }
}

fun processEDU(edu: JsonElement): JsonElement? {
    return try {
        val eduObj = edu.jsonObject
        val eduType = eduObj["edu_type"]?.jsonPrimitive?.content ?: return buildJsonObject {
            put("error", "Missing edu_type")
        }

        when (eduType) {
            "m.presence" -> processPresenceEDU(eduObj)
            "m.typing" -> processTypingEDU(eduObj)
            "m.receipt" -> processReceiptEDU(eduObj)
            "m.direct_to_device" -> processDirectToDeviceEDU(eduObj)
            else -> buildJsonObject {
                put("error", "Unknown EDU type: $eduType")
            }
        }
    } catch (e: Exception) {
        buildJsonObject {
            put("error", "Failed to process EDU: ${e.message}")
        }
    }
}

// Placeholder EDU processing functions
fun processPresenceEDU(edu: JsonObject): JsonElement {
    try {
        val userId = edu["user_id"]?.jsonPrimitive?.content
        val presence = edu["presence"]?.jsonPrimitive?.content ?: "offline"
        val statusMsg = edu["status_msg"]?.jsonPrimitive?.content
        val lastActiveAgo = edu["last_active_ago"]?.jsonPrimitive?.long ?: 0

        if (userId == null) {
            return buildJsonObject {
                put("error", "Missing user_id in presence EDU")
                put("processed", false)
            }
        }

        // Validate presence state
        val validPresenceStates = setOf("online", "offline", "unavailable", "busy")
        if (presence !in validPresenceStates) {
            return buildJsonObject {
                put("error", "Invalid presence state: $presence")
                put("processed", false)
            }
        }

        // Update presence in database
        PresenceStorage.updatePresence(userId, presence, statusMsg, lastActiveAgo)

        // Broadcast presence update to connected clients
        runBlocking {
            broadcastPresenceUpdate(userId, presence, statusMsg, lastActiveAgo)
        }

        return buildJsonObject {
            put("processed", true)
            put("type", "m.presence")
            put("user_id", userId)
            put("presence", presence)
        }

    } catch (e: Exception) {
        return buildJsonObject {
            put("error", "Failed to process presence EDU: ${e.message}")
            put("processed", false)
        }
    }
}

fun processTypingEDU(edu: JsonObject): JsonElement {
    try {
        val roomId = edu["room_id"]?.jsonPrimitive?.content
        val userId = edu["user_id"]?.jsonPrimitive?.content
        val typing = edu["typing"]?.jsonPrimitive?.boolean ?: false

        if (roomId == null || userId == null) {
            return buildJsonObject {
                put("error", "Missing room_id or user_id in typing EDU")
                put("processed", false)
            }
        }

        val currentTime = System.currentTimeMillis()

        // Update typing state in memory
        val roomTyping = typingMap.getOrPut(roomId) { mutableMapOf() }

        if (typing) {
            // User started typing
            roomTyping[userId] = currentTime
        } else {
            // User stopped typing
            roomTyping.remove(userId)
        }

        // Clean up old typing entries (older than 30 seconds)
        val thirtySecondsAgo = currentTime - 30000
        roomTyping.entries.removeIf { it.value < thirtySecondsAgo }

        // Broadcast typing update to room clients
        runBlocking {
            broadcastTypingUpdate(roomId, userId, typing)
        }

        return buildJsonObject {
            put("processed", true)
            put("type", "m.typing")
            put("room_id", roomId)
            put("user_id", userId)
            put("typing", typing)
        }

    } catch (e: Exception) {
        return buildJsonObject {
            put("error", "Failed to process typing EDU: ${e.message}")
            put("processed", false)
        }
    }
}

fun processReceiptEDU(edu: JsonObject): JsonElement {
    try {
        val roomId = edu["room_id"]?.jsonPrimitive?.content
        val receipts = edu["receipts"]?.jsonObject

        if (roomId == null || receipts == null) {
            return buildJsonObject {
                put("error", "Missing room_id or receipts in receipt EDU")
                put("processed", false)
            }
        }

        // Process each receipt
        receipts.forEach { (userId, userReceipts) ->
            val userReceiptsObj = userReceipts.jsonObject

            userReceiptsObj.forEach { (eventId, receiptData) ->
                val receiptObj = receiptData.jsonObject
                val ts = receiptObj["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                val threadId = receiptObj["thread_id"]?.jsonPrimitive?.content

                // Store receipt in database
                ReceiptsStorage.addReceipt(roomId, userId, eventId, "m.read", threadId)
            }
        }

        // Broadcast receipt update to room clients
        runBlocking {
            broadcastReceiptUpdate(roomId, receipts)
        }

        return buildJsonObject {
            put("processed", true)
            put("type", "m.receipt")
            put("room_id", roomId)
        }

    } catch (e: Exception) {
        return buildJsonObject {
            put("error", "Failed to process receipt EDU: ${e.message}")
            put("processed", false)
        }
    }
}

fun processDirectToDeviceEDU(edu: JsonObject): JsonElement {
    try {
        val sender = edu["sender"]?.jsonPrimitive?.content
        val type = edu["type"]?.jsonPrimitive?.content
        val messages = edu["messages"]?.jsonObject

        if (sender == null || type == null || messages == null) {
            return buildJsonObject {
                put("error", "Missing sender, type, or messages in direct-to-device EDU")
                put("processed", false)
            }
        }

        // Process messages for each target user
        messages.forEach { (targetUserId, userMessages) ->
            val userMessagesObj = userMessages.jsonObject

            userMessagesObj.forEach { (deviceId, messageContent) ->
                val message = mapOf(
                    "sender" to sender,
                    "type" to type,
                    "content" to messageContent,
                    "device_id" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                )

                // Store message for delivery to target user
                val userMessages = directToDeviceMessages.getOrPut(targetUserId) { mutableListOf() }
                userMessages.add(message)

                // Clean up old messages (keep only last 100 per user)
                if (userMessages.size > 100) {
                    userMessages.removeAt(0)
                }
            }
        }

        return buildJsonObject {
            put("processed", true)
            put("type", "m.direct_to_device")
            put("sender", sender)
        }

    } catch (e: Exception) {
        return buildJsonObject {
            put("error", "Failed to process direct-to-device EDU: ${e.message}")
            put("processed", false)
        }
    }
}

suspend fun broadcastReceiptUpdate(roomId: String, receipts: JsonObject) {
    try {
        // Create receipt EDU to broadcast
        val receiptEdu = buildJsonObject {
            put("edu_type", "m.receipt")
            put("content", buildJsonObject {
                put("room_id", roomId)
                put("receipts", receipts)
            })
        }

        // Broadcast to all clients in the room
        val clients = connectedClients[roomId] ?: return
        clients.forEach { session ->
            try {
                val eduJson = Json.encodeToString(JsonObject.serializer(), receiptEdu)
                session.send(Frame.Text(eduJson))
            } catch (e: Exception) {
                // Client disconnected, will be cleaned up by the session handler
                println("Failed to send receipt EDU to client: ${e.message}")
            }
        }
    } catch (e: Exception) {
        println("Error broadcasting receipt update: ${e.message}")
    }
}

suspend fun broadcastPDU(roomId: String, event: JsonObject) {
    try {
        val clients = connectedClients[roomId] ?: return
        val eventJson = Json.encodeToString(JsonObject.serializer(), event)

        clients.forEach { session ->
            try {
                session.send(Frame.Text(eventJson))
            } catch (e: Exception) {
                // Client disconnected, will be cleaned up by the session handler
                println("Failed to send PDU to client: ${e.message}")
            }
        }
    } catch (e: Exception) {
        println("Error broadcasting PDU: ${e.message}")
    }
}

suspend fun broadcastPresenceUpdate(userId: String, presence: String, statusMsg: String?, lastActiveAgo: Long) {
    try {
        // Create presence EDU to broadcast
        val presenceEdu = buildJsonObject {
            put("edu_type", "m.presence")
            put("content", buildJsonObject {
                put("user_id", userId)
                put("presence", presence)
                if (statusMsg != null) {
                    put("status_msg", statusMsg)
                }
                put("last_active_ago", lastActiveAgo)
                put("currently_active", presence == "online")
            })
        }

        // Broadcast to all connected clients (presence updates go to all clients)
        connectedClients.values.forEach { clients ->
            clients.forEach { session ->
                try {
                    val eduJson = Json.encodeToString(JsonObject.serializer(), presenceEdu)
                    session.send(Frame.Text(eduJson))
                } catch (e: Exception) {
                    // Client disconnected, will be cleaned up by the session handler
                    println("Failed to send presence EDU to client: ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        println("Error broadcasting presence update: ${e.message}")
    }
}

suspend fun broadcastTypingUpdate(roomId: String, userId: String, typing: Boolean) {
    try {
        // Get current typing users for the room
        val currentTypingUsers = typingMap[roomId] ?: mutableMapOf()
        val userIds = currentTypingUsers.keys.toList()

        // Create typing EDU to broadcast
        val typingEdu = buildJsonObject {
            put("edu_type", "m.typing")
            put("content", buildJsonObject {
                put("room_id", roomId)
                put("user_ids", JsonArray(userIds.map { JsonPrimitive(it) }))
            })
        }

        // Broadcast to all clients in the room
        val clients = connectedClients[roomId] ?: return
        clients.forEach { session ->
            try {
                val eduJson = Json.encodeToString(JsonObject.serializer(), typingEdu)
                session.send(Frame.Text(eduJson))
            } catch (e: Exception) {
                // Client disconnected, will be cleaned up by the session handler
                println("Failed to send typing EDU to client: ${e.message}")
            }
        }
    } catch (e: Exception) {
        println("Error broadcasting typing update: ${e.message}")
    }
}

suspend fun broadcastEDU(roomId: String, edu: JsonObject) {
    try {
        val clients = connectedClients[roomId] ?: return
        val eduJson = Json.encodeToString(JsonObject.serializer(), edu)

        clients.forEach { session ->
            try {
                session.send(Frame.Text(eduJson))
            } catch (e: Exception) {
                // Client disconnected, will be cleaned up by the session handler
                println("Failed to send EDU to client: ${e.message}")
            }
        }
    } catch (e: Exception) {
        println("Error broadcasting EDU: ${e.message}")
    }
}
