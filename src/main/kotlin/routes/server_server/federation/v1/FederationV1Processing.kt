package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.websocket.Frame
import kotlinx.serialization.json.*
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
import utils.accessTokens
import models.AccessTokens
import utils.StateResolver
import utils.MatrixAuth
import utils.ServerKeys
import models.Events
import models.Rooms

fun processPDU(pdu: JsonElement): Map<String, String>? {
    return try {
        val event = pdu.jsonObject

        // 1. Check validity: has room_id
        if (event["room_id"] == null) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing room_id")

        val roomId = event["room_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing room_id")

        // Validate room_id format
        if (!roomId.startsWith("!") || !roomId.contains(":")) {
            return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Invalid room_id format")
        }

        // 2. Check validity: has sender
        if (event["sender"] == null) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing sender")

        val sender = event["sender"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing sender")

        // Validate sender format
        if (!sender.startsWith("@") || !sender.contains(":")) {
            return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Invalid sender format")
        }

        // 3. Check validity: has event_id
        if (event["event_id"] == null) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing event_id")

        val eventId = event["event_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing event_id")

        // Validate event_id format
        if (!eventId.startsWith("$")) {
            return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Invalid event_id format")
        }

        // 4. Check validity: has type
        if (event["type"] == null) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Missing type")

        // 5. Check hash
        if (!MatrixAuth.verifyEventHash(event)) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Invalid hash")

        // 6. Check signatures
        val sigValid = runBlocking { MatrixAuth.verifyEventSignatures(event) }
        if (!sigValid) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Invalid signature")

        // 7. Get auth state from auth_events
        val authState = getAuthState(event, roomId)

        // 8. Check auth based on auth_events
        if (!stateResolver.checkAuthRules(event, authState)) return mapOf("errcode" to "M_INVALID_EVENT", "error" to "Auth check failed")

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
            newState[key] = event["content"]?.jsonObject ?: JsonObject(emptyMap())
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
        mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid PDU JSON")
    }
}

fun processTransaction(body: String): Map<String, Any> {
    return try {
        val json = Json.parseToJsonElement(body).jsonObject
        val pdus = json["pdus"]?.jsonArray ?: JsonArray(emptyList())
        val edus = json["edus"]?.jsonArray ?: JsonArray(emptyList())

        // Validate limits
        if (pdus.size > 50 || edus.size > 100) {
            return mapOf("errcode" to "M_TOO_LARGE", "error" to "Transaction too large")
        }

        // Get server name from transaction origin
        val origin = json["origin"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing origin")

        // Process PDUs with ACL checks
        for (pdu in pdus) {
            val pduObj = pdu.jsonObject
            val roomId = pduObj["room_id"]?.jsonPrimitive?.content

            if (roomId != null) {
                // Check Server ACL for this PDU
                if (!checkServerACL(roomId, origin)) {
                    println("PDU rejected due to ACL: room $roomId, server $origin")
                    continue // Skip this PDU but don't fail the transaction
                }
            }

            val result = processPDU(pdu)
            if (result != null) {
                println("PDU processing error: $result")
            }
        }

        // Process EDUs with ACL checks for room-specific EDUs
        for (edu in edus) {
            val eduObj = edu.jsonObject
            val eduType = eduObj["edu_type"]?.jsonPrimitive?.content

            // Check ACL for room-specific EDUs
            when (eduType) {
                "m.typing" -> {
                    val content = eduObj["content"]?.jsonObject
                    val roomId = content?.get("room_id")?.jsonPrimitive?.content
                    if (roomId != null && !checkServerACL(roomId, origin)) {
                        println("Typing EDU rejected due to ACL: room $roomId, server $origin")
                        continue // Skip this EDU
                    }
                }
                "m.receipt" -> {
                    val content = eduObj["content"]?.jsonObject
                    if (content != null) {
                        // Check ACL for each room mentioned in receipts
                        for ((roomId, _) in content) {
                            if (!checkServerACL(roomId, origin)) {
                                println("Receipt EDU rejected due to ACL: room $roomId, server $origin")
                                continue // Skip receipts for this room
                            }
                        }
                    }
                }
            }

            val result = processEDU(edu)
            if (result != null) {
                println("EDU processing error: $result")
            }
        }

        // Return success
        emptyMap() // 200 OK with empty body
    } catch (e: Exception) {
        mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON")
    }
}

fun processEDU(edu: JsonElement): Map<String, String>? {
    return try {
        val eduObj = edu.jsonObject
        val eduType = eduObj["edu_type"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing edu_type")

        when (eduType) {
            "m.typing" -> {
                val content = eduObj["content"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing content")
                val roomId = content["room_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing room_id")
                val userId = content["user_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing user_id")
                val typing = content["typing"]?.jsonPrimitive?.boolean ?: false

                // Additional validation
                if (!userId.startsWith("@") || !userId.contains(":")) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid user_id format")
                }

                if (!roomId.startsWith("!") || !roomId.contains(":")) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid room_id format")
                }

                // Verify user is in room
                val currentState = stateResolver.getResolvedState(roomId)
                val membership = currentState["m.room.member:$userId"]
                if (membership?.get("membership")?.jsonPrimitive?.content != "join") {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "User not in room")
                }

                // Update typing status with timestamp
                val typingUsers = typingMap.getOrPut(roomId) { mutableMapOf() }
                if (typing) {
                    typingUsers[userId] = System.currentTimeMillis()
                } else {
                    typingUsers.remove(userId)
                }

                // Clean up expired typing notifications (older than 30 seconds)
                val currentTime = System.currentTimeMillis()
                typingUsers.entries.removeIf { (_, timestamp) ->
                    currentTime - timestamp > 30000 // 30 seconds
                }

                // Broadcast to clients in room
                runBlocking { broadcastEDU(roomId, eduObj) }

                // Also broadcast current typing status to all clients in the room
                val currentTypingUsers = typingUsers.keys.toList()
                val typingStatusEDU = JsonObject(mapOf(
                    "edu_type" to JsonPrimitive("m.typing"),
                    "content" to JsonObject(mapOf(
                        "room_id" to JsonPrimitive(roomId),
                        "user_ids" to JsonArray(currentTypingUsers.map { JsonPrimitive(it) })
                    )),
                    "origin" to JsonPrimitive("localhost"),
                    "origin_server_ts" to JsonPrimitive(System.currentTimeMillis())
                ))
                runBlocking { broadcastEDU(roomId, typingStatusEDU) }

                null
            }
            "m.receipt" -> {
                val content = eduObj["content"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing content")

                // Handle receipts: content is {roomId: {eventId: {userId: {receiptType: {data: {ts: ts}}}}}}
                val processedRooms = mutableSetOf<String>()
                for ((roomId, roomReceipts) in content) {
                    // Validate room_id format
                    if (!roomId.startsWith("!") || !roomId.contains(":")) {
                        println("Invalid room_id format: $roomId")
                        continue
                    }

                    processedRooms.add(roomId)

                    for ((eventId, userReceipts) in roomReceipts.jsonObject) {
                        // Validate event_id format
                        if (!eventId.startsWith("$")) {
                            println("Invalid event_id format: $eventId")
                            continue
                        }

                        for ((userId, receiptData) in userReceipts.jsonObject) {
                            // Validate user_id format
                            if (!userId.startsWith("@") || !userId.contains(":")) {
                                println("Invalid user_id format: $userId")
                                continue
                            }

                            // Verify user is in room
                            try {
                                val currentState = stateResolver.getResolvedState(roomId)
                                val membership = currentState["m.room.member:$userId"]
                                if (membership?.get("membership")?.jsonPrimitive?.content != "join") {
                                    println("User $userId is not a member of room $roomId")
                                    continue
                                }

                                // Handle different receipt types
                                for ((receiptType, receiptInfo) in receiptData.jsonObject) {
                                    when (receiptType) {
                                        "m.read" -> {
                                            val ts = receiptInfo.jsonObject["ts"]?.jsonPrimitive?.long
                                            if (ts != null) {
                                                // Store read receipt using database
                                                ReceiptsStorage.addReceipt(roomId, userId, eventId, receiptType)
                                                println("Read receipt: user $userId read event $eventId in room $roomId at $ts")
                                            }
                                        }
                                        else -> {
                                            println("Unknown receipt type: $receiptType")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("Error processing receipt for user $userId in room $roomId: ${e.message}")
                                continue
                            }
                        }
                    }
                }

                // Broadcast receipts to all clients (receipts are typically broadcast to all clients)
                runBlocking { broadcastEDU(null, eduObj) }

                // Also broadcast a simplified version for better client compatibility
                val simplifiedReceipts = mutableMapOf<String, Any>()
                for (roomId in processedRooms) {
                    val allReceipts = ReceiptsStorage.getReceiptsForRoom(roomId)
                    for ((userId, receiptData) in allReceipts) {
                        val eventId = receiptData["event_id"] as? String ?: continue
                        val timestamp = receiptData["ts"] as? Long ?: continue

                        if (!simplifiedReceipts.containsKey(roomId)) {
                            simplifiedReceipts[roomId] = mutableMapOf<String, Any>()
                        }
                        val roomData = simplifiedReceipts[roomId] as? MutableMap<String, Any> ?: continue

                        if (!roomData.containsKey(eventId)) {
                            roomData[eventId] = mutableMapOf<String, Any>()
                        }
                        val eventData = roomData[eventId] as? MutableMap<String, Any> ?: continue
                        eventData[userId] = mapOf("ts" to timestamp)
                    }
                }

                if (simplifiedReceipts.isNotEmpty()) {
                    val simplifiedEDU = JsonObject(mapOf(
                        "edu_type" to JsonPrimitive("m.receipt"),
                        "content" to JsonObject(simplifiedReceipts.mapValues { (_, roomData) ->
                            JsonObject((roomData as? Map<String, Any> ?: emptyMap()).mapValues { (_, eventData) ->
                                JsonObject((eventData as? Map<String, Any> ?: emptyMap()).mapValues { (_, userData) ->
                                    JsonObject((userData as? Map<String, Any> ?: emptyMap()).mapValues { (_, value) ->
                                        when (value) {
                                            is Long -> JsonPrimitive(value)
                                            else -> JsonPrimitive(value.toString())
                                        }
                                    })
                                })
                            })
                        }),
                        "origin" to JsonPrimitive("localhost"),
                        "origin_server_ts" to JsonPrimitive(System.currentTimeMillis())
                    ))
                    runBlocking { broadcastEDU(null, simplifiedEDU) }
                }

                null
            }
            "m.presence" -> {
                val content = eduObj["content"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing content")
                val userId = content["user_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing user_id")
                val presence = content["presence"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing presence")

                // Validate user_id format
                if (!userId.startsWith("@") || !userId.contains(":")) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid user_id format")
                }

                // Validate presence state
                val validPresenceStates = setOf("online", "offline", "unavailable")
                if (presence !in validPresenceStates) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid presence state")
                }

                // Extract additional presence fields
                val statusMsg = content["status_msg"]?.jsonPrimitive?.content
                val currentlyActive = content["currently_active"]?.jsonPrimitive?.boolean
                val lastActiveAgo = content["last_active_ago"]?.jsonPrimitive?.long ?: 0

                // Update presence using database
                PresenceStorage.updatePresence(userId, presence, statusMsg, lastActiveAgo)

                // Create comprehensive presence data for broadcasting
                val presenceData = mutableMapOf<String, Any?>(
                    "user_id" to userId,
                    "presence" to presence,
                    "last_active_ago" to lastActiveAgo
                )

                if (statusMsg != null) {
                    presenceData["status_msg"] = statusMsg
                }

                if (currentlyActive != null) {
                    presenceData["currently_active"] = currentlyActive
                }

                // Broadcast presence update to all clients
                runBlocking { broadcastEDU(null, eduObj) }

                // Also broadcast the updated presence data for better client compatibility
                val enhancedPresenceEDU = JsonObject(mapOf(
                    "edu_type" to JsonPrimitive("m.presence"),
                    "content" to JsonObject(presenceData.mapValues { (_, value) ->
                        when (value) {
                            is String -> JsonPrimitive(value)
                            is Long -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            else -> JsonPrimitive(value.toString())
                        }
                    }),
                    "origin" to JsonPrimitive("localhost"),
                    "origin_server_ts" to JsonPrimitive(System.currentTimeMillis())
                ))
                runBlocking { broadcastEDU(null, enhancedPresenceEDU) }

                null
            }
            "m.device_list_update" -> {
                val content = eduObj["content"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing content")
                val userId = content["user_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing user_id")
                val deviceId = content["device_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing device_id")
                val streamId = content["stream_id"]?.jsonPrimitive?.long ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing stream_id")

                // Validate user_id format
                if (!userId.startsWith("@") || !userId.contains(":")) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid user_id format")
                }

                // Get or create user's device map
                val userDevices = utils.deviceKeys.getOrPut(userId) { mutableMapOf() }

                // Check if this is a deletion
                val deleted = content["deleted"]?.jsonPrimitive?.boolean ?: false

                if (deleted) {
                    // Remove the device
                    userDevices.remove(deviceId)
                } else {
                    // Update or add the device
                    val deviceInfo = mutableMapOf<String, Any?>(
                        "device_id" to deviceId,
                        "user_id" to userId,
                        "keys" to content["keys"],
                        "device_display_name" to content["device_display_name"]
                    )

                    // Add unsigned info if present
                    val unsigned = content["unsigned"]
                    if (unsigned != null) {
                        deviceInfo["unsigned"] = unsigned
                    }

                    userDevices[deviceId] = deviceInfo
                }

                // Update stream ID
                utils.deviceListStreamIds[userId] = streamId

                // Broadcast the device list update to all clients
                runBlocking { broadcastEDU(null, eduObj) }

                null
            }
            "m.signing_key_update" -> {
                val content = eduObj["content"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing content")
                val userId = content["user_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing user_id")

                // Validate user_id format
                if (!userId.startsWith("@") || !userId.contains(":")) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid user_id format")
                }

                // Extract cross-signing keys
                val masterKey = content["master_key"]
                val selfSigningKey = content["self_signing_key"]
                val userSigningKey = content["user_signing_key"]

                // Update cross-signing keys in storage
                if (masterKey != null) {
                    utils.crossSigningKeys["${userId}_master"] = masterKey.jsonObject
                }
                if (selfSigningKey != null) {
                    utils.crossSigningKeys["${userId}_self_signing"] = selfSigningKey.jsonObject
                }
                if (userSigningKey != null) {
                    utils.crossSigningKeys["${userId}_user_signing"] = userSigningKey.jsonObject
                }

                // Broadcast the signing key update to all clients
                runBlocking { broadcastEDU(null, eduObj) }

                null
            }
            "m.direct_to_device" -> {
                val content = eduObj["content"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing content")
                val sender = content["sender"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing sender")
                val type = content["type"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing type")
                val messageId = content["message_id"]?.jsonPrimitive?.content ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing message_id")

                // Validate sender format
                if (!sender.startsWith("@") || !sender.contains(":")) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Invalid sender format")
                }

                // Validate message_id length (max 32 codepoints as per spec)
                if (messageId.length > 32) {
                    return mapOf("errcode" to "M_INVALID_EDU", "error" to "Message ID too long")
                }

                // Extract messages - this is a map of user_id -> device_id -> message
                val messages = content["messages"]?.jsonObject ?: return mapOf("errcode" to "M_INVALID_EDU", "error" to "Missing messages")

                // Process messages for each user
                for (targetUserId in messages.keys) {
                    // Validate target user_id format
                    if (!targetUserId.startsWith("@") || !targetUserId.contains(":")) {
                        println("Invalid target user_id format: $targetUserId")
                        continue
                    }

                    val userMessageMap = messages[targetUserId]?.jsonObject ?: continue

                    // Handle wildcard device (*) - send to all devices of the user
                    if (userMessageMap.containsKey("*")) {
                        val wildcardMessage = userMessageMap["*"]
                        if (wildcardMessage != null) {
                            // Get all devices for this user
                            val userDevices = utils.deviceKeys[targetUserId] ?: emptyMap()

                            // Send to all devices
                            for ((deviceId, _) in userDevices) {
                                try {
                                    // Create device-specific message
                                    val deviceMessage = mapOf(
                                        "sender" to sender,
                                        "type" to type,
                                        "content" to wildcardMessage,
                                        "device_id" to deviceId,
                                        "message_id" to messageId,
                                        "received_at" to System.currentTimeMillis()
                                    )

                                    println("Direct-to-device message (wildcard) from $sender to $targetUserId:$deviceId of type $type (ID: $messageId)")

                                    // Create a device-specific EDU for delivery
                                    val deviceEDU = JsonObject(mapOf(
                                        "edu_type" to JsonPrimitive("m.direct_to_device"),
                                        "content" to JsonObject(mapOf(
                                            "sender" to JsonPrimitive(sender),
                                            "type" to JsonPrimitive(type),
                                            "content" to wildcardMessage,
                                            "device_id" to JsonPrimitive(deviceId),
                                            "message_id" to JsonPrimitive(messageId)
                                        )),
                                        "origin" to JsonPrimitive("localhost"),
                                        "origin_server_ts" to JsonPrimitive(System.currentTimeMillis())
                                    ))

                                    // Broadcast to all clients (in production, filter by device)
                                    runBlocking { broadcastEDU(null, deviceEDU) }

                                } catch (e: Exception) {
                                    println("Error processing wildcard direct-to-device message for device $deviceId: ${e.message}")
                                    continue
                                }
                            }
                        }
                    } else {
                        // Process messages for specific devices
                        for ((deviceId, messageContent) in userMessageMap) {
                            try {
                                // Validate device_id is not empty
                                if (deviceId.isEmpty()) {
                                    println("Empty device_id for user $targetUserId")
                                    continue
                                }

                                // Store the direct-to-device message for delivery
                                val messageData = mapOf(
                                    "sender" to sender,
                                    "type" to type,
                                    "content" to messageContent,
                                    "device_id" to deviceId,
                                    "message_id" to messageId,
                                    "received_at" to System.currentTimeMillis()
                                )

                                println("Direct-to-device message from $sender to $targetUserId:$deviceId of type $type (ID: $messageId)")

                                // Create a device-specific EDU for delivery
                                val deviceEDU = JsonObject(mapOf(
                                    "edu_type" to JsonPrimitive("m.direct_to_device"),
                                    "content" to JsonObject(mapOf(
                                        "sender" to JsonPrimitive(sender),
                                        "type" to JsonPrimitive(type),
                                        "content" to messageContent,
                                        "device_id" to JsonPrimitive(deviceId),
                                        "message_id" to JsonPrimitive(messageId)
                                    )),
                                    "origin" to JsonPrimitive("localhost"),
                                    "origin_server_ts" to JsonPrimitive(System.currentTimeMillis())
                                ))

                                // Broadcast to all clients (in production, filter by device)
                                runBlocking { broadcastEDU(null, deviceEDU) }

                            } catch (e: Exception) {
                                println("Error processing direct-to-device message for device $deviceId: ${e.message}")
                                continue
                            }
                        }
                    }
                }

                null
            }
            else -> {
                // Unknown EDU type
                mapOf("errcode" to "M_INVALID_EDU", "error" to "Unknown EDU type: $eduType")
            }
        }
    } catch (e: Exception) {
        mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid EDU JSON")
    }
}

suspend fun broadcastEDU(roomId: String?, edu: JsonObject) {
    val message = Json.encodeToString(JsonObject.serializer(), edu)
    if (roomId != null) {
        // Send to clients in room
        connectedClients[roomId]?.forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                // Client disconnected
            }
        }
    } else {
        // Send to all clients (for presence)
        connectedClients.values.flatten().forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                // Client disconnected
            }
        }
    }
}

suspend fun broadcastPDU(roomId: String, pdu: JsonObject) {
    val message = Json.encodeToString(JsonObject.serializer(), pdu)
    // Send to clients in room
    connectedClients[roomId]?.forEach { session ->
        try {
            session.send(Frame.Text(message))
        } catch (e: Exception) {
            // Client disconnected
        }
    }
}
