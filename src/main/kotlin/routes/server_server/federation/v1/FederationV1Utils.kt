package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
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

val stateResolver = StateResolver()

// Server ACL checking function
fun checkServerACL(roomId: String, serverName: String): Boolean {
    return try {
        val roomState = stateResolver.getResolvedState(roomId)
        val aclEvent = roomState["m.room.server_acl:"]

        if (aclEvent == null) {
            // No ACL configured, allow all servers
            return true
        }

        val allowList = aclEvent["allow"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val denyList = aclEvent["deny"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val allowIpLiterals = aclEvent["allow_ip_literals"]?.jsonPrimitive?.boolean ?: true
        val denyIpLiterals = aclEvent["deny_ip_literals"]?.jsonPrimitive?.boolean ?: false

        // Validate server name format
        if (!MatrixAuth.isValidServerName(serverName)) {
            println("Invalid server name format: $serverName")
            return false
        }

        // Check if server is an IP literal
        val isIpLiteral = serverName.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) ||
                         serverName.matches(Regex("^\\[.*\\]$")) ||
                         serverName.matches(Regex("^[0-9a-fA-F:]+$"))

        // If it's an IP literal, check IP literal rules
        if (isIpLiteral) {
            if (denyIpLiterals) return false
            if (!allowIpLiterals) return false
        }

        // Check deny list first (deny takes precedence)
        for (pattern in denyList) {
            if (matchesServerPattern(serverName, pattern)) {
                return false
            }
        }

        // If allow list is empty, allow all (except those denied)
        if (allowList.isEmpty()) {
            return true
        }

        // Check allow list
        for (pattern in allowList) {
            if (matchesServerPattern(serverName, pattern)) {
                return true
            }
        }

        // Not in allow list
        return false
    } catch (e: Exception) {
        println("Error checking server ACL for room $roomId and server $serverName: ${e.message}")
        // On error, deny access for security
        false
    }
}

// Helper function to match server name against pattern
fun matchesServerPattern(serverName: String, pattern: String): Boolean {
    // Convert pattern to regex
    val regexPattern = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
    return serverName.matches(Regex("^$regexPattern$"))
}

// Extract server name from authorization header
fun extractServerNameFromAuth(authHeader: String): String? {
    return try {
        val authParams = MatrixAuth.parseAuthorization(authHeader)
        authParams?.get("origin")
    } catch (e: Exception) {
        null
    }
}

fun getAuthState(event: JsonObject, _roomId: String): Map<String, JsonObject> {
    val authEvents = event["auth_events"]?.jsonArray ?: return emptyMap()
    val state = mutableMapOf<String, JsonObject>()
    transaction {
        for (authEventId in authEvents) {
            val id = authEventId.jsonPrimitive.content
            val row = Events.select { Events.eventId eq id }.singleOrNull()
            if (row != null) {
                val type = row[Events.type]
                val stateKey = row[Events.stateKey] ?: ""
                val key = "$type:$stateKey"
                val eventJson = Json.parseToJsonElement(row[Events.content]).jsonObject
                state[key] = eventJson
            }
        }
    }
    return state
}

fun findMissingEvents(roomId: String, earliestEvents: List<String>, latestEvents: List<String>, limit: Int): List<Map<String, Any?>> {
    return transaction {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        val missingEvents = mutableListOf<Map<String, Any?>>()

        // Start from latest events
        latestEvents.forEach { queue.add(it) }
        earliestEvents.forEach { visited.add(it) }

        while (queue.isNotEmpty() && missingEvents.size < limit) {
            val eventId = queue.removeFirst()

            if (visited.contains(eventId)) continue
            visited.add(eventId)

            // Get the event from database
            val eventRow = Events.select {
                (Events.eventId eq eventId) and
                (Events.roomId eq roomId) and
                (Events.outlier eq false)
            }.singleOrNull()

            if (eventRow != null) {
                // Convert to event format
                val event = mapOf(
                    "event_id" to eventRow[Events.eventId],
                    "type" to eventRow[Events.type],
                    "room_id" to eventRow[Events.roomId],
                    "sender" to eventRow[Events.sender],
                    "content" to Json.parseToJsonElement(eventRow[Events.content]).jsonObject,
                    "auth_events" to Json.parseToJsonElement(eventRow[Events.authEvents]).jsonArray,
                    "prev_events" to Json.parseToJsonElement(eventRow[Events.prevEvents]).jsonArray,
                    "depth" to eventRow[Events.depth],
                    "hashes" to Json.parseToJsonElement(eventRow[Events.hashes]).jsonObject,
                    "signatures" to Json.parseToJsonElement(eventRow[Events.signatures]).jsonObject,
                    "origin_server_ts" to eventRow[Events.originServerTs],
                    "state_key" to eventRow[Events.stateKey],
                    "unsigned" to if (eventRow[Events.unsigned] != null) Json.parseToJsonElement(eventRow[Events.unsigned]!!).jsonObject else null
                ).filterValues { it != null }

                missingEvents.add(event)

                // Add prev_events to queue
                val prevEvents = Json.parseToJsonElement(eventRow[Events.prevEvents]).jsonArray
                prevEvents.forEach { prevEventId ->
                    val prevId = prevEventId.jsonPrimitive.content
                    if (!visited.contains(prevId)) {
                        queue.add(prevId)
                    }
                }
            }
        }

        // Return in reverse order (oldest first)
        missingEvents.reversed()
    }
}

fun getCurrentStateEvents(roomId: String): List<Map<String, Any?>> {
    return transaction {
        val currentState = stateResolver.getResolvedState(roomId)

        currentState.map { entry: Map.Entry<String, JsonObject> ->
            val stateKey = entry.key
            val stateEvent = entry.value
            // Find the actual event in the database
            val (type, stateKeyValue) = stateKey.split(":", limit = 2)
            val eventRow = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq type) and
                (Events.stateKey eq stateKeyValue) and
                (Events.outlier eq false) and
                (Events.softFailed eq false)
            }.orderBy(Events.originServerTs, SortOrder.DESC).firstOrNull()

            if (eventRow != null) {
                mapOf(
                    "event_id" to eventRow[Events.eventId],
                    "type" to eventRow[Events.type],
                    "room_id" to eventRow[Events.roomId],
                    "sender" to eventRow[Events.sender],
                    "content" to Json.parseToJsonElement(eventRow[Events.content]).jsonObject,
                    "auth_events" to Json.parseToJsonElement(eventRow[Events.authEvents]).jsonArray,
                    "prev_events" to Json.parseToJsonElement(eventRow[Events.prevEvents]).jsonArray,
                    "depth" to eventRow[Events.depth],
                    "hashes" to Json.parseToJsonElement(eventRow[Events.hashes]).jsonObject,
                    "signatures" to Json.parseToJsonElement(eventRow[Events.signatures]).jsonObject,
                    "origin_server_ts" to eventRow[Events.originServerTs],
                    "state_key" to eventRow[Events.stateKey],
                    "unsigned" to if (eventRow[Events.unsigned] != null) Json.parseToJsonElement(eventRow[Events.unsigned]!!).jsonObject else null
                ).filterValues { it != null }
            } else {
                null
            }
        }.filterNotNull()
    }
}

fun getCurrentStateEventIds(roomId: String): List<String> {
    return getCurrentStateEvents(roomId).map { it["event_id"] as String }
}

fun getStateAtEvent(_roomId: String, _eventId: String): List<Map<String, Any?>> {
    // This is a simplified implementation - in a real implementation,
    // you'd need to reconstruct the state at a specific event
    // For now, return current state
    return getCurrentStateEvents(_roomId)
}

fun getStateEventIdsAtEvent(roomId: String, eventId: String): List<String> {
    return getStateAtEvent(roomId, eventId).map { it["event_id"] as String }
}

fun getRoomInfo(roomId: String): Map<String, Any?>? {
    return try {
        val currentState = stateResolver.getResolvedState(roomId)

        // Extract room information from state
        val name = currentState["m.room.name:"]?.get("name")?.jsonPrimitive?.content
        val topic = currentState["m.room.topic:"]?.get("topic")?.jsonPrimitive?.content
        val canonicalAlias = currentState["m.room.canonical_alias:"]?.get("alias")?.jsonPrimitive?.content
        val avatarUrl = currentState["m.room.avatar:"]?.get("url")?.jsonPrimitive?.content

        // Count joined members
        val joinedMembers = currentState.entries.count { (key, value) ->
            key.startsWith("m.room.member:") &&
            value["membership"]?.jsonPrimitive?.content == "join"
        }

        // Check room settings
        val joinRules = currentState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
        val worldReadable = currentState["m.room.history_visibility:"]?.get("history_visibility")?.jsonPrimitive?.content == "world_readable"
        val guestCanJoin = joinRules == "public"

        // Get room type (for spaces)
        val createEvent = currentState["m.room.create."]
        val roomType = createEvent?.get("type")?.jsonPrimitive?.content

        mapOf<String, Any?>(
            "room_id" to roomId,
            "name" to name,
            "topic" to topic,
            "canonical_alias" to canonicalAlias,
            "num_joined_members" to joinedMembers,
            "world_readable" to worldReadable,
            "guest_can_join" to guestCanJoin,
            "avatar_url" to avatarUrl,
            "room_type" to roomType
        ).filterValues { it != null }
    } catch (e: Exception) {
        println("Error getting room info for $roomId: ${e.message}")
        null
    }
}

fun findRoomByAlias(roomAlias: String): String? {
    return try {
        // Look through all rooms to find one with this alias
        val rooms = transaction {
            Rooms.selectAll().map { it[Rooms.roomId] }
        }

        for (roomId in rooms) {
            val currentState = stateResolver.getResolvedState(roomId)

            // Check canonical alias
            val canonicalAlias = currentState["m.room.canonical_alias:"]?.get("alias")?.jsonPrimitive?.content
            if (canonicalAlias == roomAlias) {
                return roomId
            }

            // Check alternative aliases
            val aliases = currentState["m.room.aliases:"]?.get("aliases")?.jsonArray
            if (aliases != null) {
                for (alias in aliases) {
                    if (alias.jsonPrimitive.content == roomAlias) {
                        return roomId
                    }
                }
            }
        }

        null // Alias not found
    } catch (e: Exception) {
        println("Error finding room by alias $roomAlias: ${e.message}")
        null
    }
}

fun getUserProfile(userId: String, field: String?): Map<String, Any?>? {
    return try {
        // First check if user exists in the Users table
        val userRow = transaction {
            Users.select { Users.userId eq userId }.singleOrNull()
        }

        if (userRow == null) {
            // User doesn't exist in our database, try to find profile info from room state
            return getUserProfileFromRoomState(userId, field)
        }

        // User exists, get profile from Users table
        val profile = mutableMapOf<String, Any?>()

        when (field) {
            null -> {
                // Return all fields
                val displayName = userRow[Users.displayName]
                val avatarUrl = userRow[Users.avatarUrl]

                if (displayName != null) profile["displayname"] = displayName
                if (avatarUrl != null) profile["avatar_url"] = avatarUrl
            }
            "displayname" -> {
                val displayName = userRow[Users.displayName]
                if (displayName != null) profile["displayname"] = displayName
            }
            "avatar_url" -> {
                val avatarUrl = userRow[Users.avatarUrl]
                if (avatarUrl != null) profile["avatar_url"] = avatarUrl
            }
            else -> {
                // Unknown field
                return null
            }
        }

        if (profile.isEmpty()) {
            // If no profile data in Users table, try room state as fallback
            return getUserProfileFromRoomState(userId, field)
        }

        profile
    } catch (e: Exception) {
        println("Error getting user profile for $userId: ${e.message}")
        null
    }
}

// Fallback function to get user profile from room state events
fun getUserProfileFromRoomState(userId: String, field: String?): Map<String, Any?>? {
    return try {
        val profile = mutableMapOf<String, Any?>()

        // Get user display name from current state of rooms the user is in
        val displayName = transaction {
            val rooms = Rooms.selectAll().map { it[Rooms.roomId] }
            for (roomId in rooms) {
                val currentState = stateResolver.getResolvedState(roomId)
                val memberEvent = currentState["m.room.member:$userId"]
                if (memberEvent != null) {
                    val displayNameValue = memberEvent["displayname"]?.jsonPrimitive?.content
                    if (displayNameValue != null) {
                        return@transaction displayNameValue
                    }
                }
            }
            null
        }

        // Get user avatar from current state
        val avatarUrl = transaction {
            val rooms = Rooms.selectAll().map { it[Rooms.roomId] }
            for (roomId in rooms) {
                val currentState = stateResolver.getResolvedState(roomId)
                val memberEvent = currentState["m.room.member:$userId"]
                if (memberEvent != null) {
                    val avatarValue = memberEvent["avatar_url"]?.jsonPrimitive?.content
                    if (avatarValue != null) {
                        return@transaction avatarValue
                    }
                }
            }
            null
        }

        when (field) {
            null -> {
                // Return all fields
                if (displayName != null) profile["displayname"] = displayName
                if (avatarUrl != null) profile["avatar_url"] = avatarUrl
            }
            "displayname" -> {
                if (displayName != null) profile["displayname"] = displayName
            }
            "avatar_url" -> {
                if (avatarUrl != null) profile["avatar_url"] = avatarUrl
            }
            else -> {
                // Unknown field
                return null
            }
        }

        if (profile.isEmpty()) null else profile
    } catch (e: Exception) {
        println("Error getting user profile from room state for $userId: ${e.message}")
        null
    }
}
