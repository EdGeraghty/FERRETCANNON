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
 * Handles local room membership operations
 */
object LocalMembershipHandler {
    
    data class MembershipResult(
        val success: Boolean,
        val eventId: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )
    
    data class MembershipInfo(
        val membership: String?,
        val sender: String?
    )
    
    /**
     * Get current membership state for a user in a room
     */
    fun getCurrentMembership(roomId: String, userId: String): MembershipInfo {
        return transaction {
            val memberEvent = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq "m.room.member") and
                (Events.stateKey eq userId)
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            
            if (memberEvent != null) {
                val content = Json.parseToJsonElement(memberEvent[Events.content]).jsonObject
                val membership = content["membership"]?.jsonPrimitive?.content
                val sender = memberEvent[Events.sender]
                MembershipInfo(membership, sender)
            } else {
                MembershipInfo(null, null)
            }
        }
    }
    
    /**
     * Create a local join event
     */
    suspend fun createLocalJoin(
        roomId: String,
        userId: String,
        config: ServerConfig,
        stateResolver: StateResolver
    ): MembershipResult {
        val currentTime = System.currentTimeMillis()
        
        // Get latest event for prev_events
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
        
        // Get auth events (create and power levels)
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
        
        val memberEventId = "\$${currentTime}_join_${userId.hashCode()}"
        
        val memberContent = JsonObject(mutableMapOf(
            "membership" to JsonPrimitive("join"),
            "displayname" to JsonPrimitive(userId.split(":")[0].substring(1))
        ))
        
        val memberEventJson = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(memberEventId),
            "type" to JsonPrimitive("m.room.member"),
            "sender" to JsonPrimitive(userId),
            "content" to memberContent,
            "origin_server_ts" to JsonPrimitive(currentTime),
            "state_key" to JsonPrimitive(userId),
            "prev_events" to JsonArray(if (latestEvent != null) 
                listOf(JsonArray(listOf(JsonPrimitive(latestEvent[Events.eventId]), JsonObject(mutableMapOf())))) 
                else emptyList()
            ),
            "auth_events" to JsonArray(emptyList()),
            "depth" to JsonPrimitive(depth)
        ))
        
        // Get auth events as list of pairs
        val authEventList = transaction {
            val createEvent = Events.select {
                (Events.roomId eq roomId) and (Events.type eq "m.room.create")
            }.singleOrNull()
            
            val powerLevelsEvent = Events.select {
                (Events.roomId eq roomId) and (Events.type eq "m.room.power_levels")
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            
            listOfNotNull(
                createEvent?.let { JsonArray(listOf(JsonPrimitive(it[Events.eventId]), JsonObject(mutableMapOf()))) },
                powerLevelsEvent?.let { JsonArray(listOf(JsonPrimitive(it[Events.eventId]), JsonObject(mutableMapOf()))) }
            )
        }
        
        val signedMemberEventJson = JsonObject(memberEventJson.toMutableMap().apply {
            put("auth_events", JsonArray(authEventList))
        })
        
        val signedMemberEvent = MatrixAuth.hashAndSignEvent(signedMemberEventJson, config.federation.serverName)
        
        // Store join event
        transaction {
            Events.insert {
                it[Events.eventId] = signedMemberEvent["event_id"]?.jsonPrimitive?.content ?: memberEventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.member"
                it[Events.sender] = userId
                it[Events.content] = Json.encodeToString(JsonObject.serializer(), memberContent)
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = userId
                it[Events.prevEvents] = prevEvents
                it[Events.authEvents] = authEvents
                it[Events.depth] = depth
                it[Events.hashes] = signedMemberEvent["hashes"]?.toString() ?: "{}"
                it[Events.signatures] = signedMemberEvent["signatures"]?.toString() ?: "{}"
            }
        }
        
        // Update resolved state
        updateResolvedState(roomId, stateResolver)
        
        // Broadcast join event
        val joinEvent = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(memberEventId),
            "type" to JsonPrimitive("m.room.member"),
            "sender" to JsonPrimitive(userId),
            "room_id" to JsonPrimitive(roomId),
            "origin_server_ts" to JsonPrimitive(currentTime),
            "content" to memberContent,
            "state_key" to JsonPrimitive(userId)
        ))
        broadcastEDU(roomId, joinEvent)
        
        return MembershipResult(success = true, eventId = memberEventId)
    }
    
    /**
     * Create a local leave event
     */
    suspend fun createLocalLeave(
        roomId: String,
        userId: String,
        stateResolver: StateResolver
    ): MembershipResult {
        val currentTime = System.currentTimeMillis()
        
        // Get latest event for prev_events
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
        
        // Get auth events
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
        
        val leaveEventId = "\$${currentTime}_leave_${userId.hashCode()}"
        
        val leaveContent = JsonObject(mutableMapOf(
            "membership" to JsonPrimitive("leave")
        ))
        
        // Store leave event
        transaction {
            Events.insert {
                it[Events.eventId] = leaveEventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.member"
                it[Events.sender] = userId
                it[Events.content] = Json.encodeToString(JsonObject.serializer(), leaveContent)
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = userId
                it[Events.prevEvents] = prevEvents
                it[Events.authEvents] = authEvents
                it[Events.depth] = depth
                it[Events.hashes] = "{}"
                it[Events.signatures] = "{}"
            }
        }
        
        // Update resolved state
        updateResolvedState(roomId, stateResolver)
        
        // Broadcast leave event
        val leaveEvent = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(leaveEventId),
            "type" to JsonPrimitive("m.room.member"),
            "sender" to JsonPrimitive(userId),
            "room_id" to JsonPrimitive(roomId),
            "origin_server_ts" to JsonPrimitive(currentTime),
            "content" to leaveContent,
            "state_key" to JsonPrimitive(userId)
        ))
        broadcastEDU(roomId, leaveEvent)
        
        return MembershipResult(success = true, eventId = leaveEventId)
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
