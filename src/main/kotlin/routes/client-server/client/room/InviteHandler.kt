package routes.client_server.client.room

import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import config.ServerConfig
import models.Rooms
import models.Events
import utils.StateResolver
import utils.MatrixAuth
import routes.server_server.federation.v1.broadcastEDU

/**
 * Handles room invite operations (both local and federated)
 */
object InviteHandler {
    
    data class InviteResult(
        val success: Boolean,
        val eventId: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )
    
    /**
     * Send an invite (local or federated)
     */
    suspend fun sendInvite(
        inviterUserId: String,
        inviteeUserId: String,
        roomId: String,
        config: ServerConfig,
        stateResolver: StateResolver
    ): InviteResult {
        // Validate user ID format
        if (!inviteeUserId.startsWith("@") || !inviteeUserId.contains(":")) {
            return InviteResult(
                success = false,
                errorCode = "M_INVALID_PARAM",
                errorMessage = "Invalid user_id format"
            )
        }
        
        val roomServer = if (roomId.contains(":")) roomId.substringAfter(":") else config.federation.serverName
        val isLocalRoom = roomServer == config.federation.serverName
        
        println("Invite attempt: roomId=$roomId, roomServer=$roomServer, isLocalRoom=$isLocalRoom, invitee=$inviteeUserId")
        
        if (!isLocalRoom) {
            return sendRemoteRoomInvite(inviterUserId, inviteeUserId, roomId, config)
        }
        
        // Check if room exists
        val roomExists = transaction {
            Rooms.select { Rooms.roomId eq roomId }.count() > 0
        }
        
        if (!roomExists) {
            return InviteResult(
                success = false,
                errorCode = "M_NOT_FOUND",
                errorMessage = "Room not found"
            )
        }
        
        // Check sender membership
        val senderMembership = LocalMembershipHandler.getCurrentMembership(roomId, inviterUserId).membership
        
        if (senderMembership != "join") {
            return InviteResult(
                success = false,
                errorCode = "M_FORBIDDEN",
                errorMessage = "Sender is not joined to this room"
            )
        }
        
        // Check invitee membership
        val inviteeMembership = LocalMembershipHandler.getCurrentMembership(roomId, inviteeUserId).membership
        
        if (inviteeMembership == "join" || inviteeMembership == "invite") {
            return InviteResult(
                success = false,
                errorCode = "M_ALREADY_JOINED",
                errorMessage = "User is already joined or invited"
            )
        }
        
        // Check invite permissions
        val hasPermission = checkInvitePermissions(roomId, inviterUserId, stateResolver)
        if (!hasPermission) {
            return InviteResult(
                success = false,
                errorCode = "M_FORBIDDEN",
                errorMessage = "Insufficient power level to invite users"
            )
        }
        
        // Create invite event
        val currentTime = System.currentTimeMillis()
        val inviteEventId = "\$${currentTime}_invite_${inviteeUserId.hashCode()}"
        
        val (prevEvents, depth, authEvents) = getEventMetadata(roomId)
        
        val inviteContent = JsonObject(mutableMapOf(
            "membership" to JsonPrimitive("invite")
        ))
        
        // Store invite event
        transaction {
            Events.insert {
                it[Events.eventId] = inviteEventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.member"
                it[Events.sender] = inviterUserId
                it[Events.content] = Json.encodeToString(JsonObject.serializer(), inviteContent)
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = inviteeUserId
                it[Events.prevEvents] = prevEvents
                it[Events.authEvents] = authEvents
                it[Events.depth] = depth
                it[Events.hashes] = "{}"
                it[Events.signatures] = "{}"
            }
            println("INVITE: Stored invite event: eventId=$inviteEventId, roomId=$roomId, invitee=$inviteeUserId")
        }
        
        // Update resolved state
        updateResolvedState(roomId, stateResolver)
        
        // Check if invitee is on a remote server
        val inviteeServer = inviteeUserId.substringAfter(":")
        if (inviteeServer != config.federation.serverName) {
            sendFederationInvite(roomId, inviterUserId, inviteeUserId, currentTime, prevEvents, authEvents, depth, config)
        }
        
        // Broadcast invite event locally
        val inviteEvent = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(inviteEventId),
            "type" to JsonPrimitive("m.room.member"),
            "sender" to JsonPrimitive(inviterUserId),
            "room_id" to JsonPrimitive(roomId),
            "origin_server_ts" to JsonPrimitive(currentTime),
            "content" to inviteContent,
            "state_key" to JsonPrimitive(inviteeUserId)
        ))
        broadcastEDU(roomId, inviteEvent)
        
        return InviteResult(success = true, eventId = inviteEventId)
    }
    
    private suspend fun sendRemoteRoomInvite(
        inviterUserId: String,
        inviteeUserId: String,
        roomId: String,
        config: ServerConfig
    ): InviteResult {
        val currentTime = System.currentTimeMillis()
        val inviteEventId = "\$${currentTime}_invite_${inviteeUserId.hashCode()}"
        val roomServer = roomId.substringAfter(":")
        
        val inviteContent = JsonObject(mutableMapOf(
            "membership" to JsonPrimitive("invite")
        ))
        
        val inviteEvent = buildJsonObject {
            put("type", "m.room.member")
            put("room_id", roomId)
            put("sender", inviterUserId)
            put("content", inviteContent)
            put("origin_server_ts", currentTime)
            put("state_key", inviteeUserId)
            put("origin", config.federation.serverName)
        }
        
        val signedEvent = MatrixAuth.hashAndSignEvent(inviteEvent, config.federation.serverName)
        
        return try {
            MatrixAuth.sendFederationInvite(roomServer, roomId, signedEvent, config)
            InviteResult(success = true, eventId = inviteEventId)
        } catch (e: Exception) {
            println("Failed to send remote invite: ${e.message}")
            InviteResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Failed to send invite to remote server"
            )
        }
    }
    
    private suspend fun sendFederationInvite(
        roomId: String,
        inviterUserId: String,
        inviteeUserId: String,
        currentTime: Long,
        prevEvents: String,
        authEvents: String,
        depth: Int,
        config: ServerConfig
    ) {
        try {
            val inviteeServer = inviteeUserId.substringAfter(":")
            val inviteContent = JsonObject(mutableMapOf("membership" to JsonPrimitive("invite")))
            
            val inviteEvent = buildJsonObject {
                put("type", "m.room.member")
                put("room_id", roomId)
                put("sender", inviterUserId)
                put("content", inviteContent)
                put("origin_server_ts", currentTime)
                put("state_key", inviteeUserId)
                put("prev_events", Json.parseToJsonElement(prevEvents))
                put("auth_events", Json.parseToJsonElement(authEvents))
                put("depth", depth)
                put("hashes", Json.parseToJsonElement("{}"))
                put("signatures", Json.parseToJsonElement("{}"))
                put("origin", config.federation.serverName)
            }
            
            val signedEvent = MatrixAuth.hashAndSignEvent(inviteEvent, config.federation.serverName)
            println("Sending federation invite to $inviteeServer for room $roomId")
            MatrixAuth.sendFederationInvite(inviteeServer, roomId, signedEvent, config)
        } catch (e: Exception) {
            println("Failed to send federation invite: ${e.message}")
        }
    }
    
    private fun checkInvitePermissions(
        roomId: String,
        userId: String,
        stateResolver: StateResolver
    ): Boolean {
        val roomState = stateResolver.getResolvedState(roomId)
        val powerLevels = roomState["m.room.power_levels:"]?.get("content")
        val invitePowerLevel = powerLevels?.jsonObject?.get("invite")?.jsonPrimitive?.int ?: 0
        val senderPowerLevel = powerLevels?.jsonObject?.get("users")?.jsonObject?.get(userId)?.jsonPrimitive?.int ?: 0
        val defaultUserPowerLevel = powerLevels?.jsonObject?.get("users_default")?.jsonPrimitive?.int ?: 0
        
        val effectiveSenderPowerLevel = if (powerLevels?.jsonObject?.get("users")?.jsonObject?.containsKey(userId) == true) {
            senderPowerLevel
        } else {
            defaultUserPowerLevel
        }
        
        return effectiveSenderPowerLevel >= invitePowerLevel
    }
    
    private data class EventMetadata(
        val prevEvents: String,
        val depth: Int,
        val authEvents: String
    )
    
    private fun getEventMetadata(roomId: String): EventMetadata {
        val latestEvent = transaction {
            Events.select { Events.roomId eq roomId }
                .orderBy(Events.originServerTs, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        }
        
        val prevEvents = if (latestEvent != null) {
            "[[\"${latestEvent[Events.eventId]}\",{}]]"
        } else {
            "[]"
        }
        
        val depth = if (latestEvent != null) {
            latestEvent[Events.depth] + 1
        } else {
            1
        }
        
        val authEvents = transaction {
            val createEvent = Events.select {
                (Events.roomId eq roomId) and (Events.type eq "m.room.create")
            }.singleOrNull()
            
            val powerLevelsEvent = Events.select {
                (Events.roomId eq roomId) and (Events.type eq "m.room.power_levels")
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            
            val authList = mutableListOf<String>()
            createEvent?.let { authList.add("\"${it[Events.eventId]}\"") }
            powerLevelsEvent?.let { authList.add("\"${it[Events.eventId]}\"") }
            "[${authList.joinToString(",")}]"
        }
        
        return EventMetadata(prevEvents, depth, authEvents)
    }
    
    private fun updateResolvedState(roomId: String, stateResolver: StateResolver) {
        val allEvents = transaction {
            Events.select { Events.roomId eq roomId }
                .map { row ->
                    JsonObject(mutableMapOf(
                        "event_id" to JsonPrimitive(row[Events.eventId]),
                        "type" to JsonPrimitive(row[Events.type]),
                        "sender" to JsonPrimitive(row[Events.sender]),
                        "origin_server_ts" to JsonPrimitive(row[Events.originServerTs]),
                        "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                        "state_key" to JsonPrimitive(row[Events.stateKey] ?: "")
                    ))
                }
        }
        val roomVersion = transaction {
            Rooms.select { Rooms.roomId eq roomId }.single()[Rooms.roomVersion]
        }
        val newResolvedState = stateResolver.resolveState(allEvents, roomVersion)
        stateResolver.updateResolvedState(roomId, newResolvedState)
    }
}
