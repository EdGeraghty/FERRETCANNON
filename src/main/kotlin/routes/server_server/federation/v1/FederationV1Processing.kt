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
import utils.directToDeviceMessages
import models.AccessTokens
import utils.StateResolver
import utils.MatrixAuth
import utils.ServerKeys
import models.Events
import models.Rooms

fun processPDU(pdu: JsonElement, providedEventId: String? = null): JsonElement? {
    return try {
        println("processPDU: Starting PDU processing")
        var event = pdu.jsonObject

        // 1. Check validity: has room_id
        if (event["room_id"] == null) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing room_id")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        val roomId = event["room_id"]?.jsonPrimitive?.content ?: return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing room_id")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // Validate room_id format (Matrix spec allows both opaque and legacy formats)
        println("processPDU: room_id value: '$roomId'")
        if (!roomId.startsWith("!")) {
            println("processPDU: room_id validation failed - must start with '!'")
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

        // 3. Check validity: has event_id (or will have one added)
        var eventId: String?
        if (event["event_id"] != null) {
            eventId = event["event_id"]?.jsonPrimitive?.content
            // Validate event_id format if present
            if (eventId != null && !eventId.startsWith("$")) {
                return buildJsonObject {
                    put("errcode", "M_INVALID_EVENT")
                    put("error", "Invalid event_id format")
                }.toString().let { Json.parseToJsonElement(it).jsonObject }
            }
        } else if (providedEventId != null) {
            // Will be added after hash verification
            eventId = providedEventId
        } else {
            return buildJsonObject {
                put("errcode", "M_INVALID_EVENT")
                put("error", "Missing event_id")
            }.toString().let { Json.parseToJsonElement(it).jsonObject }
        }

        // 4. Check validity: has type
        if (event["type"] == null) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Missing type")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 5. Check signatures FIRST (signatures are computed without event_id)
        val sigValid = runBlocking { MatrixAuth.verifyEventSignatures(event) }
        if (!sigValid) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Invalid signature")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 6. Check hash
        if (!MatrixAuth.verifyEventHash(event)) return buildJsonObject {
            put("errcode", "M_INVALID_EVENT")
            put("error", "Invalid hash")
        }.toString().let { Json.parseToJsonElement(it).jsonObject }

        // 7. Add event_id if provided (after signature and hash verification)
        if (providedEventId != null && !event.containsKey("event_id")) {
            event = buildJsonObject {
                event.forEach { (key, value) -> put(key, value) }
                put("event_id", providedEventId)
            }
            eventId = providedEventId // Update the variable too
            println("processPDU: Added event_id $providedEventId after signature and hash verification")
        }

        // 8. Get current state first to check if this is an invite to unknown room
        val currentState = stateResolver.getResolvedState(roomId)

        // 9. Special handling for federation invites (Matrix spec: invites are accepted on trust from remote servers)
        // For federation invites, we skip auth_events check as we don't have the full auth chain from the remote server
        val isInvite = event["type"]?.jsonPrimitive?.content == "m.room.member" &&
                event["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content == "invite"
        
        val isInviteToUnknownRoom = isInvite && currentState.isEmpty()

        println("processPDU: Room $roomId - isInvite: $isInvite, isInviteToUnknownRoom: $isInviteToUnknownRoom")

        // 10. Check auth based on auth_events (skip for ALL federation invites)
        // Per Matrix spec, invites via federation are accepted on signature verification alone
        if (!isInvite) {
            val authState = getAuthState(event)
            println("processPDU: Checking auth rules with authState size: ${authState.size}")
            if (!stateResolver.checkAuthRules(event, authState)) return buildJsonObject {
                put("errcode", "M_INVALID_EVENT")
                put("error", "Auth check failed")
            }.toString().let { Json.parseToJsonElement(it).jsonObject }
        } else {
            println("processPDU: Skipping auth_events check for federation invite (accepted on signature verification)")
        }

        // 11. Determine if event should be soft-failed
        // Per Matrix spec, federation invites are accepted on signature verification alone
        val softFail = if (isInvite) {
            println("processPDU: Federation invite - not soft-failing")
            false // Never soft-fail federation invites
        } else if (isInviteToUnknownRoom) {
            // For invites to unknown rooms, we need to create the room first
            // This will be handled by the federation invite logic
            println("Processing invite to unknown room $roomId - allowing")
            false // Don't soft fail invites to unknown rooms
        } else {
            val authResult = stateResolver.checkAuthRules(event, currentState)
            println("processPDU: Auth check result for existing room: $authResult")
            !authResult
        }

        // 12. Handle room creation for invites to unknown rooms
        if (isInviteToUnknownRoom) {
            println("Creating room $roomId for federation invite")
            // Create the room with minimal state
            transaction {
                // Check if room already exists (race condition protection)
                val existingRoom = Rooms.select { Rooms.roomId eq roomId }.singleOrNull()
                if (existingRoom == null) {
                    // Try to extract creator from m.room.create event in stripped state
                    val createEvent = Events.select { 
                        (Events.roomId eq roomId) and 
                        (Events.type eq "m.room.create") and
                        (Events.outlier eq true)
                    }.singleOrNull()
                    
                    val creator = if (createEvent != null) {
                        createEvent[Events.sender]
                    } else {
                        // Fallback: use the sender of the invite event
                        event["sender"]?.jsonPrimitive?.content ?: "unknown"
                    }
                    
                    println("Creating room $roomId with creator=$creator")
                    
                    Rooms.insert {
                        it[Rooms.roomId] = roomId
                        it[Rooms.creator] = creator
                        it[Rooms.name] = null
                        it[Rooms.topic] = null
                        it[Rooms.visibility] = "private"
                        it[Rooms.roomVersion] = "12"  // Default room version
                        it[Rooms.isDirect] = false
                        it[Rooms.currentState] = "{}"  // Will be updated as events are processed
                        it[Rooms.stateGroups] = "{}"
                        it[Rooms.published] = false
                    }
                    println("Created room $roomId for federation invite")
                }
            }
        }

        // 13. If state event, update current state
        if (event["state_key"] != null) {
            val stateForUpdate = if (isInviteToUnknownRoom) {
                mutableMapOf<String, JsonObject>()
            } else {
                currentState.toMutableMap()
            }
            val type = event["type"]?.jsonPrimitive?.content ?: ""
            val stateKey = event["state_key"]?.jsonPrimitive?.content ?: ""
            val key = "$type:$stateKey"
            stateForUpdate[key] = event["content"]?.jsonObject ?: JsonObject(mutableMapOf())
            stateResolver.updateResolvedState(roomId, stateForUpdate)
        }

        // 14. Store the event
        if (eventId == null) {
            return buildJsonObject {
                put("errcode", "M_INVALID_EVENT")
                put("error", "Event ID not available")
            }.toString().let { Json.parseToJsonElement(it).jsonObject }
        }
        
        transaction {
            println("processPDU: Starting database transaction for event storage")
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
        println("processPDU: Successfully completed processing for $eventId")
        null // Success
    } catch (e: Exception) {
        println("processPDU: Exception occurred: ${e.message}")
        e.printStackTrace()
        buildJsonObject {
            put("errcode", "M_BAD_JSON")
            put("error", "Invalid PDU JSON: ${e.message}")
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
            broadcastTypingUpdate(roomId)
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

                // Store receipt in database
                ReceiptsStorage.addReceipt(roomId, userId, eventId, "m.read", receiptObj["thread_id"]?.jsonPrimitive?.content)
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
        messages.forEach { (targetUserId, userMessagesObj) ->
            val userMessages = userMessagesObj.jsonObject

            userMessages.forEach { (deviceId, messageContent) ->
                val message = mutableMapOf(
                    "sender" to sender,
                    "type" to type,
                    "content" to messageContent,
                    "device_id" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                )

                // Store message for delivery to target user
                val targetUserMessages = directToDeviceMessages.getOrPut(targetUserId) { mutableListOf() }
                targetUserMessages.add(message)

                // Clean up old messages (keep only last 100 per user)
                if (targetUserMessages.size > 100) {
                    targetUserMessages.removeAt(0)
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

suspend fun broadcastTypingUpdate(roomId: String) {
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
