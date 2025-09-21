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
import models.Receipts
import utils.AuthUtils
import utils.StateResolver
import routes.server_server.federation.v1.broadcastEDU
import routes.client_server.client.MATRIX_USER_ID_KEY
import utils.typingMap

fun Route.roomRoutes(config: ServerConfig) {
    val stateResolver = StateResolver()

        // GET /rooms/{roomId}/state/{eventType}/{stateKey} - Get specific state event
    get("/rooms/{roomId}/state/{eventType}/{stateKey?}") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            val stateKey = call.parameters["stateKey"] ?: "" // Default to empty string for events with no state key
            val format = call.request.queryParameters["format"] ?: "content"

            if (roomId == null || eventType == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@get
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
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@get
            }

            // Get the specific state event
            val stateEvent = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq eventType) and
                    (Events.stateKey eq stateKey)
                }.orderBy(Events.originServerTs, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
            }

            if (stateEvent == null) {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "State event not found"
                ))
                return@get
            }

            if (format == "event") {
                // Return full event format
                val eventJson = buildJsonObject {
                    put("event_id", stateEvent[Events.eventId])
                    put("type", stateEvent[Events.type])
                    put("sender", stateEvent[Events.sender])
                    put("origin_server_ts", stateEvent[Events.originServerTs])
                    put("content", Json.parseToJsonElement(stateEvent[Events.content]).jsonObject)
                    put("state_key", stateEvent[Events.stateKey])
                    put("unsigned", buildJsonObject { })
                }
                call.respond(eventJson)
            } else {
                // Return content only (default)
                call.respond(Json.parseToJsonElement(stateEvent[Events.content]).jsonObject)
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /rooms/{roomId}/state/{eventType}/{stateKey} - Send state event
    put("/rooms/{roomId}/send/{eventType}/{txnId}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            val txnId = call.parameters["txnId"]

            if (roomId == null || eventType == null || txnId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
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
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
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
                val eventJson = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive(eventId),
                    "type" to JsonPrimitive(eventType),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to jsonBody
                ))
                broadcastEDU(roomId, eventJson)
            }

            call.respond(mutableMapOf(
                "event_id" to eventId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /createRoom - Create a new room
    post("/createRoom") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val roomName = jsonBody["name"]?.jsonPrimitive?.content
            val roomTopic = jsonBody["topic"]?.jsonPrimitive?.content
            val roomAlias = jsonBody["room_alias_name"]?.jsonPrimitive?.content
            val preset = jsonBody["preset"]?.jsonPrimitive?.content ?: "private_chat"
            val visibility = jsonBody["visibility"]?.jsonPrimitive?.content ?: "private"

            // Determine join rule based on preset
            val joinRule = when (preset) {
                "public_chat" -> "public"
                else -> "invite"
            }

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
                    it[Rooms.roomVersion] = "12"
                    it[Rooms.isDirect] = preset == "trusted_private_chat"
                    it[Rooms.currentState] = Json.encodeToString(JsonObject.serializer(), JsonObject(mutableMapOf())) // Initialize with empty JSON object
                    it[Rooms.stateGroups] = Json.encodeToString(JsonObject.serializer(), JsonObject(mutableMapOf())) // Initialize with empty JSON object
                }

                // Generate event IDs
                val createEventId = "\$${currentTime}_create"
                val memberEventId = "\$${currentTime}_member"
                val powerLevelsEventId = "\$${currentTime}_power_levels"
                val joinRulesEventId = "\$${currentTime}_join_rules"
                val historyVisibilityEventId = "\$${currentTime}_history_visibility"

                // Create m.room.create event
                val createContent = JsonObject(mutableMapOf(
                    "creator" to JsonPrimitive(userId),
                    "room_version" to JsonPrimitive("12"),
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
                val memberContent = JsonObject(mutableMapOf(
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
                val powerLevelsContent = JsonObject(mutableMapOf(
                    "users" to JsonObject(mutableMapOf(userId to JsonPrimitive(100))),
                    "users_default" to JsonPrimitive(0),
                    "events" to JsonObject(mutableMapOf<String, JsonElement>()),
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
                val joinRulesContent = JsonObject(mutableMapOf(
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
                val historyVisibilityContent = JsonObject(mutableMapOf(
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

            // Initialize resolved state with all events from the database
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
            val initialResolvedState = stateResolver.resolveState(allEvents, "12")
            stateResolver.updateResolvedState(roomId, initialResolvedState)

            // Broadcast room creation events
            runBlocking {
                val createEvent = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive("\$${currentTime}_create"),
                    "type" to JsonPrimitive("m.room.create"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to JsonObject(mutableMapOf(
                        "creator" to JsonPrimitive(userId),
                        "room_version" to JsonPrimitive("12")
                    )),
                    "state_key" to JsonPrimitive("")
                ))
                broadcastEDU(roomId, createEvent)

                val memberEvent = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive("\$${currentTime}_member"),
                    "type" to JsonPrimitive("m.room.member"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to JsonObject(mutableMapOf(
                        "membership" to JsonPrimitive("join"),
                        "displayname" to JsonPrimitive(userId.split(":")[0].substring(1))
                    )),
                    "state_key" to JsonPrimitive(userId)
                ))
                broadcastEDU(roomId, memberEvent)
            }

            call.respond(mutableMapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /rooms/{roomId}/join - Join a room
    post("/rooms/{roomId}/join") {
        try {
            val userId = call.validateAccessToken() ?: return@post
            val roomId = call.parameters["roomId"]

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
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
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
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
                call.respond(mutableMapOf(
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
            val memberContent = JsonObject(mutableMapOf(
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

            // Update resolved state
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

            // Broadcast join event
            runBlocking {
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
            }

            call.respond(mutableMapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /rooms/{roomId}/leave - Leave a room
    post("/rooms/{roomId}/leave") {
        try {
            val userId = call.validateAccessToken() ?: return@post
            val roomId = call.parameters["roomId"]

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
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
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
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

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_NOT_MEMBER",
                    "error" to "User is not joined to this room"
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

            // Generate leave event ID
            val leaveEventId = "\$${currentTime}_leave_${userId.hashCode()}"

            // Create leave event content
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

            // Broadcast leave event
            runBlocking {
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
            }

            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // GET /rooms/{roomId}/state - Get room state
    get("/rooms/{roomId}/state") {
        try {
            call.validateAccessToken() ?: return@get
            val roomId = call.parameters["roomId"]

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@get
            }

            // Get the resolved state for the room
            val resolvedState = stateResolver.getResolvedState(roomId)

            // If resolved state is empty, fall back to getting current state from events
            val stateEvents = if (resolvedState.isNotEmpty()) {
                // Ensure the create event is always included
                val events = resolvedState.values.toList().toMutableList()
                val hasCreateEvent = events.any { it["type"]?.jsonPrimitive?.content == "m.room.create" }
                if (!hasCreateEvent) {
                    // Try to get the create event from the database
                    val createEvent = transaction {
                        Events.select {
                            (Events.roomId eq roomId) and
                            (Events.type eq "m.room.create")
                        }.singleOrNull()?.let { row ->
                            buildJsonObject {
                                put("event_id", row[Events.eventId])
                                put("type", row[Events.type])
                                put("sender", row[Events.sender])
                                put("origin_server_ts", row[Events.originServerTs])
                                put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                                put("state_key", row[Events.stateKey] ?: "")
                                put("unsigned", buildJsonObject { })
                            }
                        }
                    }
                    createEvent?.let { events.add(it) }
                }
                events
            } else {
                // Get current state events from the database
                transaction {
                    Events.select { Events.roomId eq roomId }
                        .mapNotNull { row ->
                            // Only include events that have a state_key (state events)
                            row[Events.stateKey]?.let { stateKey ->
                                buildJsonObject {
                                    put("event_id", row[Events.eventId])
                                    put("type", row[Events.type])
                                    put("sender", row[Events.sender])
                                    put("origin_server_ts", row[Events.originServerTs])
                                    put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                                    put("state_key", stateKey)
                                    put("unsigned", buildJsonObject { })
                                }
                            }
                        }
                }
            }

            call.respond(stateEvents)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /rooms/{roomId}/state/{eventType}/{stateKey} - Send state event
    put("/rooms/{roomId}/state/{eventType}/{stateKey}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            val stateKey = call.parameters["stateKey"]

            if (roomId == null || eventType == null || stateKey == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
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
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
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

            // Generate event ID
            val eventId = "\$${currentTime}_${stateKey.hashCode()}"

            // Store state event
            transaction {
                Events.insert {
                    it[Events.eventId] = eventId
                    it[Events.roomId] = roomId
                    it[Events.type] = eventType
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), jsonBody)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = stateKey
                    it[Events.prevEvents] = prevEvents
                    it[Events.authEvents] = authEvents
                    it[Events.depth] = depth
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }
            }

            // Update resolved state
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

            // Broadcast state event
            runBlocking {
                val eventJson = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive(eventId),
                    "type" to JsonPrimitive(eventType),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to jsonBody,
                    "state_key" to JsonPrimitive(stateKey)
                ))
                broadcastEDU(roomId, eventJson)
            }

            call.respond(mutableMapOf(
                "event_id" to eventId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // GET /rooms/{roomId}/members - Get room members
    get("/rooms/{roomId}/members") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val roomId = call.parameters["roomId"]

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@get
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
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@get
            }

            // Parse query parameters
            val membership = call.request.queryParameters["membership"] ?: "join"
            val notMembership = call.request.queryParameters["not_membership"]
            val at = call.request.queryParameters["at"] // Historical point - for now, we ignore this and return current state

            // Note: Historical membership queries (at parameter) are not fully implemented yet
            // For now, we return current membership state regardless of the 'at' parameter

            // Get room members based on membership status
            val members = transaction {
                val query = Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey.isNotNull())
                }

                // Apply membership filter
                if (membership != "join") {
                    query.andWhere {
                        Events.content like "%\"membership\":\"$membership\"%"
                    }
                }

                // Apply not_membership filter
                if (notMembership != null) {
                    query.andWhere {
                        Events.content notLike "%\"membership\":\"$notMembership\"%"
                    }
                }

                query.map { row ->
                    buildJsonObject {
                        put("event_id", row[Events.eventId])
                        put("type", row[Events.type])
                        put("sender", row[Events.sender])
                        put("origin_server_ts", row[Events.originServerTs])
                        put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                        put("state_key", row[Events.stateKey])
                        put("room_id", row[Events.roomId])
                    }
                }
            }

            call.respond(buildJsonObject {
                putJsonArray("chunk") {
                    members.forEach { member ->
                        add(member)
                    }
                }
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /rooms/{roomId}/typing/{userId} - Send typing notification
    put("/rooms/{roomId}/typing/{userId}") {
        try {
            val authenticatedUserId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"]
            val userId = call.parameters["userId"]

            if (roomId == null || userId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@put
            }

            // Verify that the authenticated user matches the userId in the path
            if (authenticatedUserId != userId) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set typing status for yourself"
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
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val typing = jsonBody["typing"]?.jsonPrimitive?.boolean ?: false
            val timeout = jsonBody["timeout"]?.jsonPrimitive?.long ?: 30000 // Default 30 seconds

            val currentTime = System.currentTimeMillis()

            // Update typing state
            val roomTyping = typingMap.getOrPut(roomId) { mutableMapOf() }

            if (typing) {
                // User started typing
                roomTyping[userId] = currentTime + timeout
            } else {
                // User stopped typing
                roomTyping.remove(userId)
            }

            // Clean up old typing entries (older than current time)
            roomTyping.entries.removeIf { it.value < currentTime }

            // Broadcast typing update to room clients
            runBlocking {
                val typingEvent = JsonObject(mutableMapOf(
                    "type" to JsonPrimitive("m.typing"),
                    "room_id" to JsonPrimitive(roomId),
                    "content" to JsonObject(mutableMapOf(
                        "user_ids" to JsonArray(roomTyping.keys.map { JsonPrimitive(it) })
                    ))
                ))
                broadcastEDU(roomId, typingEvent)
            }

            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // DELETE /rooms/{roomId}/typing/{userId} - Stop typing notification
    delete("/rooms/{roomId}/typing/{userId}") {
        try {
            val authenticatedUserId = call.validateAccessToken() ?: return@delete
            val roomId = call.parameters["roomId"]
            val userId = call.parameters["userId"]

            if (roomId == null || userId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@delete
            }

            // Verify that the authenticated user matches the userId in the path
            if (authenticatedUserId != userId) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set typing status for yourself"
                ))
                return@delete
            }

            // Remove user from typing state
            val roomTyping = typingMap[roomId]
            roomTyping?.remove(userId)

            // Broadcast typing update to room clients
            runBlocking {
                val typingEvent = JsonObject(mutableMapOf(
                    "type" to JsonPrimitive("m.typing"),
                    "room_id" to JsonPrimitive(roomId),
                    "content" to JsonObject(mutableMapOf(
                        "user_ids" to JsonArray(roomTyping?.keys?.map { JsonPrimitive(it) } ?: emptyList())
                    ))
                ))
                broadcastEDU(roomId, typingEvent)
            }

            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}
