package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import config.ServerConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Rooms
import models.Events
import models.RoomAliases
import utils.AuthUtils
import utils.StateResolver
import routes.server_server.federation.v1.broadcastEDU

fun Route.roomRoutes(config: ServerConfig) {
    val stateResolver = StateResolver()

    // PUT /rooms/{roomId}/send/{eventType}/{txnId} - Send event to room
    put("/rooms/{roomId}/send/{eventType}/{txnId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            val txnId = call.parameters["txnId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (roomId == null || eventType == null || txnId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@put
            }

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

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

            // Generate event ID
            val eventId = "\$${currentTime}_${txnId}"

            // Store event
            transaction {
                Events.insert {
                    it[Events.eventId] = eventId
                    it[Events.roomId] = roomId
                    it[Events.type] = eventType
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), jsonBody)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = null // Regular events don't have state keys
                    it[Events.prevEvents] = prevEvents
                    it[Events.authEvents] = "[]" // Simplified
                    it[Events.depth] = depth
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }
            }

            // Broadcast event
            runBlocking {
                val eventJson = JsonObject(mapOf(
                    "event_id" to JsonPrimitive(eventId),
                    "type" to JsonPrimitive(eventType),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to jsonBody
                ))
                broadcastEDU(roomId, eventJson)
            }

            call.respond(mapOf(
                "event_id" to eventId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /createRoom - Create a new room
    post("/createRoom") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val roomName = jsonBody["name"]?.jsonPrimitive?.content
            val roomTopic = jsonBody["topic"]?.jsonPrimitive?.content
            val roomAlias = jsonBody["room_alias_name"]?.jsonPrimitive?.content
            val preset = jsonBody["preset"]?.jsonPrimitive?.content ?: "private_chat"
            val visibility = jsonBody["visibility"]?.jsonPrimitive?.content ?: "private"

            // Generate room ID
            val roomId = "!${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().replace("-", "")}:${config.federation.serverName}"

            val currentTime = System.currentTimeMillis()

            transaction {
                // Create room entry
                Rooms.insert {
                    it[Rooms.roomId] = roomId
                    it[Rooms.creator] = userId
                    it[Rooms.name] = roomName
                    it[Rooms.topic] = roomTopic
                    it[Rooms.visibility] = visibility
                    it[Rooms.roomVersion] = "9"
                    it[Rooms.isDirect] = preset == "trusted_private_chat"
                    it[Rooms.currentState] = "{}" // Initialize with empty JSON object
                    it[Rooms.stateGroups] = "{}" // Initialize with empty JSON object
                }

                // Generate event IDs
                val createEventId = "\$${currentTime}_create"
                val memberEventId = "\$${currentTime}_member"
                val powerLevelsEventId = "\$${currentTime}_power_levels"
                val joinRulesEventId = "\$${currentTime}_join_rules"
                val historyVisibilityEventId = "\$${currentTime}_history_visibility"

                // Create m.room.create event
                val createContent = JsonObject(mapOf(
                    "creator" to JsonPrimitive(userId),
                    "room_version" to JsonPrimitive("9"),
                    "predecessor" to JsonNull
                ))

                Events.insert {
                    it[Events.eventId] = createEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.create"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), createContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = ""
                    it[Events.prevEvents] = "[]"
                    it[Events.authEvents] = "[]"
                    it[Events.depth] = 1
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }

                // Create m.room.member event for creator
                val memberContent = JsonObject(mapOf(
                    "membership" to JsonPrimitive("join"),
                    "displayname" to JsonPrimitive(userId.split(":")[0].substring(1)) // Extract localpart
                ))

                Events.insert {
                    it[Events.eventId] = memberEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.member"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), memberContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = userId
                    it[Events.prevEvents] = "[[\"$createEventId\",{}]]"
                    it[Events.authEvents] = "[[\"$createEventId\",{}]]"
                    it[Events.depth] = 2
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }

                // Create m.room.power_levels event
                val powerLevelsContent = JsonObject(mapOf(
                    "users" to JsonObject(mapOf(userId to JsonPrimitive(100))),
                    "users_default" to JsonPrimitive(0),
                    "events" to JsonObject(emptyMap()),
                    "events_default" to JsonPrimitive(0),
                    "state_default" to JsonPrimitive(50),
                    "ban" to JsonPrimitive(50),
                    "kick" to JsonPrimitive(50),
                    "redact" to JsonPrimitive(50),
                    "invite" to JsonPrimitive(0)
                ))

                Events.insert {
                    it[Events.eventId] = powerLevelsEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.power_levels"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), powerLevelsContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = ""
                    it[Events.prevEvents] = "[[\"$memberEventId\",{}]]"
                    it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}]]"
                    it[Events.depth] = 3
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }

                // Create m.room.join_rules event
                val joinRule = when (preset) {
                    "public_chat" -> "public"
                    else -> "invite"
                }
                val joinRulesContent = JsonObject(mapOf(
                    "join_rule" to JsonPrimitive(joinRule)
                ))

                Events.insert {
                    it[Events.eventId] = joinRulesEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.join_rules"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), joinRulesContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = ""
                    it[Events.prevEvents] = "[[\"$powerLevelsEventId\",{}]]"
                    it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
                    it[Events.depth] = 4
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }

                // Create m.room.history_visibility event
                val historyVisibilityContent = JsonObject(mapOf(
                    "history_visibility" to JsonPrimitive("shared")
                ))

                Events.insert {
                    it[Events.eventId] = historyVisibilityEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.history_visibility"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), historyVisibilityContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = ""
                    it[Events.prevEvents] = "[[\"$joinRulesEventId\",{}]]"
                    it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
                    it[Events.depth] = 5
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }

                // Create room alias if specified
                if (roomAlias != null) {
                    val fullAlias = "#$roomAlias:${config.federation.serverName}"
                    RoomAliases.insert {
                        it[RoomAliases.roomId] = roomId
                        it[RoomAliases.alias] = fullAlias
                        it[RoomAliases.servers] = "[\"${config.federation.serverName}\"]"
                    }
                }
            }

            // Broadcast room creation events
            runBlocking {
                val createEvent = JsonObject(mapOf(
                    "event_id" to JsonPrimitive("\$${currentTime}_create"),
                    "type" to JsonPrimitive("m.room.create"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to JsonObject(mapOf(
                        "creator" to JsonPrimitive(userId),
                        "room_version" to JsonPrimitive("9")
                    )),
                    "state_key" to JsonPrimitive("")
                ))
                broadcastEDU(roomId, createEvent)

                val memberEvent = JsonObject(mapOf(
                    "event_id" to JsonPrimitive("\$${currentTime}_member"),
                    "type" to JsonPrimitive("m.room.member"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to JsonObject(mapOf(
                        "membership" to JsonPrimitive("join"),
                        "displayname" to JsonPrimitive(userId.split(":")[0].substring(1))
                    )),
                    "state_key" to JsonPrimitive(userId)
                ))
                broadcastEDU(roomId, memberEvent)
            }

            call.respond(mapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /rooms/{roomId}/join - Join a room
    post("/rooms/{roomId}/join") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Room not found"
                ))
                return@post
            }

            // Check current membership
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership == "join") {
                call.respond(mapOf(
                    "room_id" to roomId
                ))
                return@post
            }

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
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.create")
                }.singleOrNull()

                val powerLevelsEvent = Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.power_levels")
                }.orderBy(Events.originServerTs, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()

                val authList = mutableListOf<String>()
                createEvent?.let { authList.add("\"${it[Events.eventId]}\"") }
                powerLevelsEvent?.let { authList.add("\"${it[Events.eventId]}\"") }
                "[${authList.joinToString(",")}]"
            }

            // Generate member event ID
            val memberEventId = "\$${currentTime}_join_${userId.hashCode()}"

            // Create member event content
            val memberContent = JsonObject(mapOf(
                "membership" to JsonPrimitive("join"),
                "displayname" to JsonPrimitive(userId.split(":")[0].substring(1))
            ))

            // Store join event
            transaction {
                Events.insert {
                    it[Events.eventId] = memberEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.member"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), memberContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = userId
                    it[Events.prevEvents] = prevEvents
                    it[Events.authEvents] = authEvents
                    it[Events.depth] = depth
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }
            }

            // Broadcast join event
            runBlocking {
                val joinEvent = JsonObject(mapOf(
                    "event_id" to JsonPrimitive(memberEventId),
                    "type" to JsonPrimitive("m.room.member"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to memberContent,
                    "state_key" to JsonPrimitive(userId)
                ))
                broadcastEDU(roomId, joinEvent)
            }

            call.respond(mapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /rooms/{roomId}/leave - Leave a room
    post("/rooms/{roomId}/leave") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            // TODO: Remove user from room membership
            // TODO: Send leave event

            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /rooms/{roomId}/state - Get room state
    get("/rooms/{roomId}/state") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@get
            }

            // Get the resolved state for the room
            val resolvedState = stateResolver.getResolvedState(roomId)

            // Convert the state map to an array of state events
            val stateEvents = resolvedState.values.toList()

            call.respond(stateEvents)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
