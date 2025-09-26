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
import utils.AuthUtils
import utils.StateResolver
import utils.MatrixAuth
import routes.server_server.federation.v1.broadcastEDU
import routes.client_server.client.common.*

fun Route.roomMembershipRoutes(config: ServerConfig) {
    val stateResolver = StateResolver()

    // POST /rooms/{roomId}/invite - Invite a user to a room
    post("/rooms/{roomId}/invite") {
        println("Invite endpoint called")
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

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val inviteeUserId = jsonBody["user_id"]?.jsonPrimitive?.content

            if (inviteeUserId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing user_id parameter"
                ))
                return@post
            }

            // Validate user ID format
            if (!inviteeUserId.startsWith("@") || !inviteeUserId.contains(":")) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Invalid user_id format"
                ))
                return@post
            }

            val roomServer = if (roomId.contains(":")) roomId.substringAfter(":") else config.federation.serverName
            val isLocalRoom = roomServer == config.federation.serverName

            println("Invite attempt: roomId=$roomId, roomServer=$roomServer, isLocalRoom=$isLocalRoom, invitee=$inviteeUserId")

            if (!isLocalRoom) {
                // Remote room invite
                val currentTime = System.currentTimeMillis()
                val inviteEventId = "\$${currentTime}_invite_${inviteeUserId.hashCode()}"

                val inviteContent = JsonObject(mutableMapOf(
                    "membership" to JsonPrimitive("invite")
                ))

                val inviteEvent = buildJsonObject {
                    put("event_id", inviteEventId)
                    put("type", "m.room.member")
                    put("room_id", roomId)
                    put("sender", userId)
                    put("content", inviteContent)
                    put("origin_server_ts", currentTime)
                    put("state_key", inviteeUserId)
                    put("prev_events", JsonArray(emptyList()))
                    put("auth_events", JsonArray(emptyList()))
                    put("depth", 1)
                    put("hashes", Json.parseToJsonElement("{}"))
                    put("signatures", Json.parseToJsonElement("{}"))
                    put("origin", config.federation.serverName)
                }

                val signedEvent = MatrixAuth.hashAndSignEvent(inviteEvent, config.federation.serverName)

                runBlocking {
                    try {
                        MatrixAuth.sendFederationInvite(roomServer, roomId, signedEvent, config)
                        call.respondText("{}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        println("Failed to send remote invite: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                            "errcode" to "M_UNKNOWN",
                            "error" to "Failed to send invite to remote server"
                        ))
                    }
                }
                return@post
            }

            // Local room logic
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

            // Check if sender is joined to the room
            val senderMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (senderMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Sender is not joined to this room"
                ))
                return@post
            }

            // Check if invitee is already a member
            val inviteeMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq inviteeUserId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (inviteeMembership == "join" || inviteeMembership == "invite") {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_ALREADY_JOINED",
                    "error" to "User is already joined or invited"
                ))
                return@post
            }

            // Check invite permissions
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

            if (effectiveSenderPowerLevel < invitePowerLevel) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Insufficient power level to invite users"
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

            // Generate invite event ID
            val inviteEventId = "\$${currentTime}_invite_${inviteeUserId.hashCode()}"

            // Create invite event content
            val inviteContent = JsonObject(mutableMapOf(
                "membership" to JsonPrimitive("invite")
            ))

            // Store invite event
            transaction {
                Events.insert {
                    it[Events.eventId] = inviteEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = "m.room.member"
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), inviteContent)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = inviteeUserId
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

            // Check if invitee is on a remote server
            val inviteeServer = inviteeUserId.substringAfter(":")
            val localServer = config.federation.serverName

            if (inviteeServer != localServer) {
                // Send invite via federation
                runBlocking {
                    try {
                        val inviteEvent = buildJsonObject {
                            put("event_id", inviteEventId)
                            put("type", "m.room.member")
                            put("room_id", roomId)
                            put("sender", userId)
                            put("content", inviteContent)
                            put("origin_server_ts", currentTime)
                            put("state_key", inviteeUserId)
                            put("prev_events", Json.parseToJsonElement(prevEvents))
                            put("auth_events", Json.parseToJsonElement(authEvents))
                            put("depth", depth)
                            put("hashes", Json.parseToJsonElement("{}"))
                            put("signatures", Json.parseToJsonElement("{}"))
                            put("origin", localServer)
                        }

                        val signedEvent = MatrixAuth.hashAndSignEvent(inviteEvent, localServer)
                        runBlocking {
                            MatrixAuth.sendFederationInvite(inviteeServer, roomId, signedEvent, config)
                        }
                    } catch (e: Exception) {
                        println("Failed to send federation invite: ${e.message}")
                        // Continue anyway - the invite is stored locally
                    }
                }
            }

            // Broadcast invite event locally
            runBlocking {
                val inviteEvent = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive(inviteEventId),
                    "type" to JsonPrimitive("m.room.member"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "content" to inviteContent,
                    "state_key" to JsonPrimitive(inviteeUserId)
                ))
                broadcastEDU(roomId, inviteEvent)
            }

            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
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

            val memberEventJson = JsonObject(mutableMapOf(
                "event_id" to JsonPrimitive(memberEventId),
                "type" to JsonPrimitive("m.room.member"),
                "sender" to JsonPrimitive(userId),
                "content" to memberContent,
                "origin_server_ts" to JsonPrimitive(currentTime),
                "state_key" to JsonPrimitive(userId),
                "prev_events" to JsonArray(if (latestEvent != null) listOf(JsonArray(listOf(JsonPrimitive(latestEvent[Events.eventId]), JsonObject(mutableMapOf())))) else emptyList()),
                "auth_events" to JsonArray(emptyList()), // Will be filled below
                "depth" to JsonPrimitive(depth)
            ))

            // Get auth events as list of pairs
            val authEventList = transaction {
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
}