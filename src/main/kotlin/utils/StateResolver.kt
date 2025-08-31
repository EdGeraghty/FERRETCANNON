package utils

import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import models.Rooms
import models.StateGroups

/**
 * Matrix State Resolution Algorithm Implementation
 *
 * Based on https://spec.matrix.org/v1.15/server-server-api/#room-state-resolution
 */
class StateResolver {

    /**
     * Resolve the current state of a room from a set of events
     */
    fun resolveState(roomId: String, events: List<JsonObject>): Map<String, JsonObject> {
        if (events.isEmpty()) return emptyMap()

        // Group events by state key
        val stateEvents = events.filter { it["state_key"] != null }
        val groupedEvents = stateEvents.groupBy { event ->
            val type = event["type"]?.jsonPrimitive?.content ?: ""
            val stateKey = event["state_key"]?.jsonPrimitive?.content ?: ""
            "$type:$stateKey"
        }

        val resolvedState = mutableMapOf<String, JsonObject>()

        // For each state key, find the winning event
        for ((stateKey, eventList) in groupedEvents) {
            val winner = findWinningEvent(eventList)
            if (winner != null) {
                resolvedState[stateKey] = winner
            }
        }

        return resolvedState
    }

    /**
     * Find the winning event for a given state key using Matrix's resolution algorithm
     */
    private fun findWinningEvent(events: List<JsonObject>): JsonObject? {
        if (events.isEmpty()) return null
        if (events.size == 1) return events.first()

        // Sort by power level, then by timestamp, then by event ID
        val sortedEvents = events.sortedWith(compareByDescending<JsonObject> { event ->
            getPowerLevel(event)
        }.thenByDescending { event ->
            event["origin_server_ts"]?.jsonPrimitive?.long ?: 0
        }.thenByDescending { event ->
            event["event_id"]?.jsonPrimitive?.content ?: ""
        })

        return sortedEvents.first()
    }

    /**
     * Get the power level of the sender of an event
     */
    private fun getPowerLevel(event: JsonObject): Int {
        val sender = event["sender"]?.jsonPrimitive?.content ?: return 0
        val roomId = event["room_id"]?.jsonPrimitive?.content ?: return 0

        // Get current power levels from auth events or current state
        val authEvents = event["auth_events"]?.jsonArray ?: return 0

        // Look for m.room.power_levels in auth events
        for (authEventId in authEvents) {
            val authEvent = getEventById(authEventId.jsonPrimitive.content)
            if (authEvent != null) {
                val type = authEvent["type"]?.jsonPrimitive?.content
                if (type == "m.room.power_levels") {
                    val content = authEvent["content"]?.jsonObject
                    val usersPowerLevels = content?.get("users")?.jsonObject
                    return usersPowerLevels?.get(sender)?.jsonPrimitive?.int ?: 0
                }
            }
        }

        // Default power level
        return 0
    }

    /**
     * Check if an event is authorized based on current state
     */
    fun checkAuthRules(event: JsonObject, currentState: Map<String, JsonObject>): Boolean {
        val type = event["type"]?.jsonPrimitive?.content ?: return false
        val sender = event["sender"]?.jsonPrimitive?.content ?: return false
        val content = event["content"]?.jsonObject ?: return false

        when (type) {
            "m.room.create" -> {
                // Only allow if room doesn't exist yet
                return currentState["m.room.create:"] == null
            }

            "m.room.member" -> {
                return checkMembershipAuth(event, currentState)
            }

            "m.room.power_levels" -> {
                return checkPowerLevelsAuth(event, currentState)
            }

            "m.room.message" -> {
                return checkMessageAuth(event, currentState)
            }

            else -> {
                // For other events, check if sender has required power level
                val powerLevels = currentState["m.room.power_levels:"] ?: return true
                val userLevel = getUserPowerLevel(sender, powerLevels)
                val requiredLevel = getRequiredPowerLevel(type, powerLevels)
                return userLevel >= requiredLevel
            }
        }
    }

    private fun checkMembershipAuth(event: JsonObject, currentState: Map<String, JsonObject>): Boolean {
        val sender = event["sender"]?.jsonPrimitive?.content ?: return false
        val stateKey = event["state_key"]?.jsonPrimitive?.content ?: return false
        val membership = event["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content ?: return false

        val targetUser = stateKey
        val isSelf = sender == targetUser

        when (membership) {
            "join" -> {
                if (isSelf) {
                    // User can join if they have invite or room is public
                    val joinRule = currentState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
                    if (joinRule == "public") return true

                    // Check if user was invited
                    val invite = currentState["m.room.member:$targetUser"]
                    return invite?.get("membership")?.jsonPrimitive?.content == "invite"
                } else {
                    // Someone else trying to join user - not allowed
                    return false
                }
            }

            "invite" -> {
                // Check if sender has permission to invite
                val powerLevels = currentState["m.room.power_levels:"] ?: return false
                val senderLevel = getUserPowerLevel(sender, powerLevels)
                val inviteLevel = powerLevels["invite"]?.jsonPrimitive?.int ?: powerLevels["state_default"]?.jsonPrimitive?.int ?: 0
                return senderLevel >= inviteLevel
            }

            "knock" -> {
                if (isSelf) {
                    // User can knock if they are not already a member and room is not public
                    val joinRule = currentState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
                    if (joinRule == "public") return false // Public rooms don't need knocking

                    // Check if user is already a member
                    val existingMember = currentState["m.room.member:$targetUser"]
                    val existingMembership = existingMember?.get("membership")?.jsonPrimitive?.content
                    return existingMembership != "join" && existingMembership != "invite" && existingMembership != "knock"
                } else {
                    // Someone else trying to knock for user - not allowed
                    return false
                }
            }

            else -> return false
        }
    }

    private fun checkPowerLevelsAuth(event: JsonObject, currentState: Map<String, JsonObject>): Boolean {
        val sender = event["sender"]?.jsonPrimitive?.content ?: return false
        val currentPowerLevels = currentState["m.room.power_levels:"] ?: return false

        // Check if sender has permission to change power levels
        val senderLevel = getUserPowerLevel(sender, currentPowerLevels)
        val requiredLevel = currentPowerLevels["events"]?.jsonObject?.get("m.room.power_levels")?.jsonPrimitive?.int
            ?: currentPowerLevels["state_default"]?.jsonPrimitive?.int ?: 50

        return senderLevel >= requiredLevel
    }

    private fun checkMessageAuth(event: JsonObject, currentState: Map<String, JsonObject>): Boolean {
        val sender = event["sender"]?.jsonPrimitive?.content ?: return false
        val powerLevels = currentState["m.room.power_levels:"] ?: return false

        val senderLevel = getUserPowerLevel(sender, powerLevels)
        val requiredLevel = powerLevels["events"]?.jsonObject?.get("m.room.message")?.jsonPrimitive?.int
            ?: powerLevels["events_default"]?.jsonPrimitive?.int ?: 0

        return senderLevel >= requiredLevel
    }

    private fun getUserPowerLevel(userId: String, powerLevels: JsonObject): Int {
        val usersPowerLevels = powerLevels["users"]?.jsonObject ?: return 0
        return usersPowerLevels[userId]?.jsonPrimitive?.int ?: powerLevels["users_default"]?.jsonPrimitive?.int ?: 0
    }

    private fun getRequiredPowerLevel(eventType: String, powerLevels: JsonObject): Int {
        return powerLevels["events"]?.jsonObject?.get(eventType)?.jsonPrimitive?.int
            ?: powerLevels["state_default"]?.jsonPrimitive?.int ?: 50
    }

    /**
     * Get an event by its ID from the database
     */
    private fun getEventById(eventId: String): JsonObject? {
        return transaction {
            val row = Events.select { Events.eventId eq eventId }.singleOrNull()
            if (row != null) {
                val content = row[Events.content]
                Json.parseToJsonElement(content).jsonObject
            } else {
                null
            }
        }
    }

    /**
     * Update the resolved state for a room
     */
    fun updateResolvedState(roomId: String, newState: Map<String, JsonObject>) {
        transaction {
            val existing = Rooms.select { Rooms.roomId eq roomId }.singleOrNull()
            if (existing != null) {
                Rooms.update({ Rooms.roomId eq roomId }) {
                    it[Rooms.currentState] = Json.encodeToString(JsonObject.serializer(), JsonObject(newState))
                }
            } else {
                Rooms.insert {
                    it[Rooms.roomId] = roomId
                    it[Rooms.currentState] = Json.encodeToString(JsonObject.serializer(), JsonObject(newState))
                }
            }
        }
    }

    /**
     * Get the resolved state for a room
     */
    fun getResolvedState(roomId: String): Map<String, JsonObject> {
        return transaction {
            val row = Rooms.select { Rooms.roomId eq roomId }.singleOrNull()
            if (row != null) {
                Json.decodeFromString(row[Rooms.currentState])
            } else {
                emptyMap()
            }
        }
    }
}
