package routes.client_server.client.room

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
import models.Receipts
import models.AccountData
import utils.AuthUtils
import utils.StateResolver
import utils.MatrixPagination
import routes.server_server.federation.v1.broadcastEDU
import utils.typingMap
import routes.client_server.client.common.*

fun Route.roomRoutes() {
    val stateResolver = StateResolver()

        // GET /rooms/{roomId}/state/{eventType}/{stateKey?} - Get specific state event
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

    // PUT /rooms/{roomId}/send/{eventType}/{txnId} - Send state event
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

    // PUT /rooms/{roomId}/state/{eventType}/{stateKey?} - Send state event
    // Note: stateKey is optional in the client API (empty state_key is represented by a trailing slash),
    // so accept an optional parameter and treat missing as empty string.
    put("/rooms/{roomId}/state/{eventType}/{stateKey?}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            // Treat a missing stateKey as the empty string (state_key == "")
            val stateKey = call.parameters["stateKey"] ?: ""

            if (roomId == null || eventType == null) {
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

    // Also accept PUT /rooms/{roomId}/state/{eventType} to handle requests that omit the final segment
    // (some clients send a trailing slash which is interpreted as an empty state_key). Treat stateKey as empty string.
    put("/rooms/{roomId}/state/{eventType}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            val stateKey = "" // explicit empty state key for this route

            if (roomId == null || eventType == null) {
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
            @Suppress("UNUSED_VARIABLE")
            val _at = call.request.queryParameters["at"] // Historical point - for now, we ignore this and return current state

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

    // GET /rooms/{roomId}/messages - Get room message history
    get("/rooms/{roomId}/messages") {
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
            val from = call.request.queryParameters["from"]
            val dir = call.request.queryParameters["dir"] ?: "b"
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 10).coerceAtMost(100)

            // Determine order
            val order = if (dir == "b") SortOrder.DESC else SortOrder.ASC

            // Build query
            var query = Events.select { Events.roomId eq roomId }
            if (from != null) {
                val fromTs = transaction {
                    Events.select { Events.eventId eq from }
                        .singleOrNull()
                        ?.get(Events.originServerTs)
                }
                if (fromTs != null) {
                    query = if (dir == "b") {
                        query.andWhere { Events.originServerTs less fromTs }
                    } else {
                        query.andWhere { Events.originServerTs greater fromTs }
                    }
                }
            }

            // Get events
            val events = transaction {
                query.orderBy(Events.originServerTs, order)
                    .limit(limit)
                    .map { row ->
                        buildJsonObject {
                            put("event_id", row[Events.eventId])
                            put("type", row[Events.type])
                            put("sender", row[Events.sender])
                            put("origin_server_ts", row[Events.originServerTs])
                            put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                            put("room_id", row[Events.roomId])
                            row[Events.stateKey]?.let { put("state_key", it) }
                            put("unsigned", buildJsonObject { })
                        }
                    }
            }

            // Build response
            val response = buildJsonObject {
                putJsonArray("chunk") {
                    events.forEach { add(it) }
                }
                if (events.isNotEmpty()) {
                    val start = events.first()["event_id"]!!.jsonPrimitive.content
                    val end = events.last()["event_id"]!!.jsonPrimitive.content
                    put("start", start)
                    put("end", end)
                } else {
                    put("start", from ?: "")
                    put("end", from ?: "")
                }
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /rooms/{roomId}/read_markers - Set read markers
    post("/rooms/{roomId}/read_markers") {
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
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val markers = Json.parseToJsonElement(requestBody).jsonObject

            // Update fully read marker
            markers["m.fully_read"]?.jsonPrimitive?.content?.let { eventId ->
                transaction {
                    val existing = AccountData.select {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq "m.fully_read") and
                        (AccountData.roomId eq roomId)
                    }.singleOrNull()

                    val content = """{"event_id":"$eventId"}"""

                    if (existing != null) {
                        AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.fully_read") and (AccountData.roomId eq roomId) }) {
                            it[AccountData.content] = content
                            it[AccountData.lastModified] = System.currentTimeMillis()
                        }
                    } else {
                        AccountData.insert {
                            it[AccountData.userId] = userId
                            it[AccountData.type] = "m.fully_read"
                            it[AccountData.roomId] = roomId
                            it[AccountData.content] = content
                        }
                    }
                }
            }

            // Update read marker
            markers["m.read"]?.jsonPrimitive?.content?.let { eventId ->
                transaction {
                    val existing = AccountData.select {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq "m.read") and
                        (AccountData.roomId eq roomId)
                    }.singleOrNull()

                    val content = """{"event_id":"$eventId"}"""

                    if (existing != null) {
                        AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.read") and (AccountData.roomId eq roomId) }) {
                            it[AccountData.content] = content
                            it[AccountData.lastModified] = System.currentTimeMillis()
                        }
                    } else {
                        AccountData.insert {
                            it[AccountData.userId] = userId
                            it[AccountData.type] = "m.read"
                            it[AccountData.roomId] = roomId
                            it[AccountData.content] = content
                        }
                    }
                }
            }

            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}
