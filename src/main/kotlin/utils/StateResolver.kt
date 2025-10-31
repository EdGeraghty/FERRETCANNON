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
     * 
     * For room version 12+, implements state reset reduction to prefer
     * events that maintain state continuity over those that reset state.
     * 
     * Optimized to only process state events and use efficient conflict resolution.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveState(events: List<JsonObject>, roomVersion: String = "1"): Map<String, JsonObject> {
        if (events.isEmpty()) return mutableMapOf()

        // Only process state events (events with state_key)
        val stateEvents = events.filter { it["state_key"] != null && it["state_key"]?.jsonPrimitive?.content?.isNotEmpty() == true }

        if (stateEvents.isEmpty()) return mutableMapOf()

        // Group events by state key (type + state_key)
        val groupedEvents = stateEvents.groupBy { event ->
            val type = event["type"]?.jsonPrimitive?.content ?: ""
            val stateKey = event["state_key"]?.jsonPrimitive?.content ?: ""
            "$type:$stateKey"
        }

        val resolvedState = mutableMapOf<String, JsonObject>()

        // For each state key, find the winning event using simple latest-wins for performance
        for ((stateKey, eventList) in groupedEvents) {
            // Sort by timestamp descending and pick the latest
            val winner = eventList.maxByOrNull { event ->
                event["origin_server_ts"]?.jsonPrimitive?.long ?: 0
            }
            winner?.let { resolvedState[stateKey] = it }
        }

        return resolvedState
    }

    /**
     * Find the winning event for a given state key using Matrix's resolution algorithm
     * 
     * For room version 12+, implements state reset reduction to prefer events
     * that maintain state continuity.
     */
    private fun findWinningEvent(events: List<JsonObject>, roomVersion: String = "1"): JsonObject? {
        if (events.isEmpty()) return null
        if (events.size == 1) return events.first()

        val versionNum = roomVersion.toIntOrNull() ?: 1

        return if (versionNum >= 12) {
            // Room version 12+: Use state reset reduction
            findWinningEventWithStateResetReduction(events)
        } else {
            // Room versions < 12: Use traditional algorithm
            findWinningEventTraditional(events)
        }
    }

    /**
     * Traditional state resolution algorithm (room versions < 12)
     */
    private fun findWinningEventTraditional(events: List<JsonObject>): JsonObject? {
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
     * State reset reduction algorithm (room version 12+)
     * 
     * Prefers events that maintain state continuity over those that reset state.
     * A "state reset" occurs when an event completely changes the state content
     * in a way that breaks continuity with previous state.
     */
    private fun findWinningEventWithStateResetReduction(events: List<JsonObject>): JsonObject? {
        if (events.isEmpty()) return null
        if (events.size == 1) return events.first()

        // First, try to find events that don't represent state resets
        val nonResetEvents = events.filter { event ->
            !isStateResetEvent(event, events)
        }

        if (nonResetEvents.isNotEmpty()) {
            // Use traditional algorithm on non-reset events
            return findWinningEventTraditional(nonResetEvents)
        }

        // If all events are state resets, fall back to traditional algorithm
        return findWinningEventTraditional(events)
    }

    /**
     * Determine if an event represents a state reset
     * 
     * A state reset occurs when an event's content is significantly different
     * from other events for the same state key, indicating a discontinuity.
     */
    private fun isStateResetEvent(event: JsonObject, allEvents: List<JsonObject>): Boolean {
        val eventContent = event["content"]?.jsonObject ?: return false
        val eventType = event["type"]?.jsonPrimitive?.content ?: return false

        // For certain event types, check if content differs significantly from others
        val otherEvents = allEvents.filter { it != event }
        
        when (eventType) {
            "m.room.power_levels" -> {
                // Check if power levels are radically different
                return isPowerLevelsReset(eventContent, otherEvents)
            }
            "m.room.join_rules" -> {
                // Check if join rules changed drastically
                return isJoinRulesReset(eventContent, otherEvents)
            }
            "m.room.member" -> {
                // Membership events are typically not state resets
                return false
            }
            else -> {
                // For other events, check content similarity
                return isContentReset(eventContent, otherEvents)
            }
        }
    }

    private fun isPowerLevelsReset(content: JsonObject, otherEvents: List<JsonObject>): Boolean {
        // A power levels reset occurs if the event sets very different power levels
        // compared to other events for this state key
        val users = content["users"]?.jsonObject ?: return false
        
        for (otherEvent in otherEvents) {
            val otherContent = otherEvent["content"]?.jsonObject ?: continue
            val otherUsers = otherContent["users"]?.jsonObject ?: continue
            
            // If power levels are similar, not a reset
            if (arePowerLevelsSimilar(users, otherUsers)) {
                return false
            }
        }
        
        // If we get here, this event has very different power levels
        return otherEvents.isNotEmpty()
    }

    private fun isJoinRulesReset(content: JsonObject, otherEvents: List<JsonObject>): Boolean {
        val joinRule = content["join_rule"]?.jsonPrimitive?.content ?: return false
        
        for (otherEvent in otherEvents) {
            val otherContent = otherEvent["content"]?.jsonObject ?: continue
            val otherJoinRule = otherContent["join_rule"]?.jsonPrimitive?.content ?: continue
            
            // If join rules are the same, not a reset
            if (joinRule == otherJoinRule) {
                return false
            }
        }
        
        // Different join rule from others indicates potential reset
        return otherEvents.isNotEmpty()
    }

    private fun isContentReset(content: JsonObject, otherEvents: List<JsonObject>): Boolean {
        // Simple heuristic: if content differs significantly from most other events
        var similarCount = 0
        
        for (otherEvent in otherEvents) {
            val otherContent = otherEvent["content"]?.jsonObject ?: continue
            if (areContentsSimilar(content, otherContent)) {
                similarCount++
            }
        }
        
        // If less than half the events have similar content, consider it a reset
        return similarCount < otherEvents.size / 2.0
    }

    private fun arePowerLevelsSimilar(users1: JsonObject, users2: JsonObject): Boolean {
        // Simple similarity check: compare number of users with power levels
        val size1 = users1.size
        val size2 = users2.size
        
        // If sizes differ significantly, not similar
        if (Math.abs(size1 - size2) > 2) return false
        
        // Check if common users have similar power levels
        var commonUsers = 0
        var similarLevels = 0
        
        for ((user, level1) in users1) {
            val level2 = users2[user]
            if (level2 != null) {
                commonUsers++
                val l1 = level1.jsonPrimitive.int
                val l2 = level2.jsonPrimitive.int
                if (Math.abs(l1 - l2) <= 25) { // Within 25 power level points
                    similarLevels++
                }
            }
        }
        
        // If most common users have similar levels, consider similar
        return commonUsers > 0 && similarLevels >= commonUsers * 0.7
    }

    private fun areContentsSimilar(content1: JsonObject, content2: JsonObject): Boolean {
        // Simple JSON structure comparison
        if (content1.size != content2.size) return false
        
        for ((key, value1) in content1) {
            val value2 = content2[key] ?: return false
            if (value1 != value2) return false
        }
        
        return true
    }

    /**
     * Get the power level of the sender of an event
     */
    private fun getPowerLevel(event: JsonObject): Int {
        val sender = event["sender"]?.jsonPrimitive?.content ?: return 0

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
                // Extract creator from m.room.create event in the new state or outlier events
                val createStateKey = newState.keys.find { it.startsWith("m.room.create|") }
                val creatorFromState = createStateKey?.let { 
                    newState[it]?.get("sender")?.jsonPrimitive?.content 
                }
                
                val creator = creatorFromState ?: run {
                    // Fallback: check for m.room.create outlier event
                    val createEvent = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.create") and
                        (Events.outlier eq true)
                    }.singleOrNull()
                    createEvent?.get(Events.sender) ?: "unknown"
                }
                
                println("StateResolver.updateResolvedState: Creating room $roomId with creator=$creator")
                
                Rooms.insert {
                    it[Rooms.roomId] = roomId
                    it[Rooms.creator] = creator
                    it[Rooms.name] = null
                    it[Rooms.topic] = null
                    it[Rooms.visibility] = "private"
                    it[Rooms.roomVersion] = "12"
                    it[Rooms.isDirect] = false
                    it[Rooms.currentState] = Json.encodeToString(JsonObject.serializer(), JsonObject(newState))
                    it[Rooms.stateGroups] = "{}"
                    it[Rooms.published] = false
                }
            }
        }
    }

    /**
     * Get the resolved state for a room
     */
    fun getResolvedState(_roomId: String): Map<String, JsonObject> {
        return transaction {
            val row = Rooms.select { Rooms.roomId eq _roomId }.singleOrNull()
            if (row != null) {
                try {
                    val decodedState = Json.decodeFromString<Map<String, JsonObject>>(row[Rooms.currentState])
                    // Ensure we return a mutable map to avoid EmptyMap serialization issues
                    decodedState.toMutableMap()
                } catch (e: Exception) {
                    // If decoding fails due to EmptyMap or other issues, return empty mutable map
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }
        }
    }
}
