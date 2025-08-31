package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import models.Rooms
import kotlinx.coroutines.runBlocking
import utils.connectedClients
import utils.presenceMap
import utils.receiptsMap
import utils.typingMap
import utils.deviceKeys
import utils.oneTimeKeys
import utils.crossSigningKeys
import utils.deviceListStreamIds
import utils.StateResolver
import utils.MatrixAuth

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
        if (!isValidServerName(serverName)) {
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
    val authParams = MatrixAuth.parseAuthorization(authHeader) ?: return null
    return authParams["origin"]
}

fun Application.federationV1Routes() {
    routing {
        route("/_matrix") {
            route("/federation") {
                route("/v1") {
                    get("/version") {
                        // For now, skip auth for version, as per spec it's public
                        call.respond(mapOf(
                            "server" to mapOf(
                                "name" to "FERRETCANNON",
                                "version" to "1.0.0"
                            ),
                            "spec_versions" to mapOf(
                                "federation" to "v1.15",
                                "client_server" to "v1.15"
                            )
                        ))
                    }
                    put("/send/{txnId}") {
                        val txnId = call.parameters["txnId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }
                        // Process transaction
                        val result = processTransaction(body)
                        if (result.containsKey("errcode")) {
                            call.respond(HttpStatusCode.BadRequest, result)
                        } else {
                            call.respond(HttpStatusCode.OK, result)
                        }
                    }
                    // Placeholder endpoints
                    get("/event_auth/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"]
                        val eventId = call.parameters["eventId"]
                        if (roomId == null || eventId == null) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@get
                        }

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }
                        // Placeholder: return auth chain
                        val authChain = listOf<Map<String, Any>>() // Empty for now
                        call.respond(mapOf(
                            "origin" to "localhost",
                            "origin_server_ts" to System.currentTimeMillis(),
                            "pdus" to authChain
                        ))
                    }
                    get("/backfill/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                        val minDepth = call.request.queryParameters["min_depth"]?.toIntOrNull() ?: 0

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        try {
                            // Get historical events for backfilling
                            val events = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.depth greaterEq minDepth) and
                                    (Events.outlier eq false) and
                                    (Events.softFailed eq false)
                                }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(limit)
                                    .map { row ->
                                        try {
                                            // Convert database row back to event JSON
                                            mapOf(
                                                "event_id" to row[Events.eventId],
                                                "type" to row[Events.type],
                                                "room_id" to row[Events.roomId],
                                                "sender" to row[Events.sender],
                                                "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                                "auth_events" to Json.parseToJsonElement(row[Events.authEvents]).jsonArray,
                                                "prev_events" to Json.parseToJsonElement(row[Events.prevEvents]).jsonArray,
                                                "depth" to row[Events.depth],
                                                "hashes" to Json.parseToJsonElement(row[Events.hashes]).jsonObject,
                                                "signatures" to Json.parseToJsonElement(row[Events.signatures]).jsonObject,
                                                "origin_server_ts" to row[Events.originServerTs],
                                                "state_key" to row[Events.stateKey],
                                                "unsigned" to if (row[Events.unsigned] != null) Json.parseToJsonElement(row[Events.unsigned]!!).jsonObject else null
                                            ).filterValues { it != null }
                                        } catch (e: Exception) {
                                            println("Error parsing event ${row[Events.eventId]}: ${e.message}")
                                            null
                                        }
                                    }.filterNotNull()
                            }

                            call.respond(mapOf(
                                "origin" to "localhost",
                                "origin_server_ts" to System.currentTimeMillis(),
                                "pdus" to events
                            ))
                        } catch (e: Exception) {
                            println("Backfill error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                        }
                    }
                    post("/get_missing_events/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@post
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@post
                        }

                        try {
                            val requestBody = call.receiveText()
                            val requestJson = Json.parseToJsonElement(requestBody).jsonObject

                            val earliestEvents = requestJson["earliest_events"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            val latestEvents = requestJson["latest_events"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            val limit = requestJson["limit"]?.jsonPrimitive?.int ?: 10

                            if (earliestEvents.isEmpty() || latestEvents.isEmpty()) {
                                return@post call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing earliest_events or latest_events"))
                            }

                            // Find missing events using a breadth-first search
                            val missingEvents = findMissingEvents(roomId, earliestEvents, latestEvents, limit)

                            call.respond(mapOf(
                                "events" to missingEvents
                            ))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/event/{eventId}") {
                        val eventId = call.parameters["eventId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        val event = transaction {
                            Events.select { Events.eventId eq eventId }.singleOrNull()
                        }

                        if (event == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Event not found"))
                            return@get
                        }

                        // Convert database row to event format
                        val eventData = mapOf(
                            "event_id" to event[Events.eventId],
                            "type" to event[Events.type],
                            "room_id" to event[Events.roomId],
                            "sender" to event[Events.sender],
                            "content" to Json.parseToJsonElement(event[Events.content]).jsonObject,
                            "auth_events" to Json.parseToJsonElement(event[Events.authEvents]).jsonArray,
                            "prev_events" to Json.parseToJsonElement(event[Events.prevEvents]).jsonArray,
                            "depth" to event[Events.depth],
                            "hashes" to Json.parseToJsonElement(event[Events.hashes]).jsonObject,
                            "signatures" to Json.parseToJsonElement(event[Events.signatures]).jsonObject,
                            "origin_server_ts" to event[Events.originServerTs],
                            "state_key" to event[Events.stateKey],
                            "unsigned" to if (event[Events.unsigned] != null) Json.parseToJsonElement(event[Events.unsigned]!!).jsonObject else null
                        ).filterValues { it != null }

                        call.respond(eventData)
                    }
                    get("/state/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.request.queryParameters["event_id"]

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        val stateEvents = if (eventId != null) {
                            // Get state at specific event
                            getStateAtEvent(roomId, eventId)
                        } else {
                            // Get current state
                            getCurrentStateEvents(roomId)
                        }

                        call.respond(mapOf(
                            "origin" to "localhost",
                            "origin_server_ts" to System.currentTimeMillis(),
                            "pdus" to stateEvents
                        ))
                    }
                    get("/state_ids/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.request.queryParameters["event_id"]

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        val stateEventIds = if (eventId != null) {
                            // Get state event IDs at specific event
                            getStateEventIdsAtEvent(roomId, eventId)
                        } else {
                            // Get current state event IDs
                            getCurrentStateEventIds(roomId)
                        }

                        call.respond(mapOf(
                            "origin" to "localhost",
                            "origin_server_ts" to System.currentTimeMillis(),
                            "pdu_ids" to stateEventIds
                        ))
                    }
                    get("/timestamp_to_event/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        val ts = call.request.queryParameters["ts"]?.toLongOrNull()
                        val dir = call.request.queryParameters["dir"] ?: "f"

                        if (ts == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing or invalid ts parameter"))
                            return@get
                        }

                        if (dir !in setOf("f", "b")) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid dir parameter"))
                            return@get
                        }

                        try {
                            // Find the event closest to the given timestamp
                            val event = transaction {
                                val query = Events.select { Events.roomId eq roomId }
                                    .orderBy(if (dir == "f") Events.originServerTs else Events.originServerTs, if (dir == "f") SortOrder.ASC else SortOrder.DESC)

                                if (dir == "f") {
                                    // Forward direction: find first event at or after ts
                                    query.andWhere { Events.originServerTs greaterEq ts }
                                } else {
                                    // Backward direction: find first event at or before ts
                                    query.andWhere { Events.originServerTs lessEq ts }
                                }

                                query.firstOrNull()
                            }

                            if (event == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "No event found near timestamp"))
                                return@get
                            }

                            // Convert to event format
                            val eventData = mapOf(
                                "event_id" to event[Events.eventId],
                                "origin_server_ts" to event[Events.originServerTs]
                            )

                            call.respond(eventData)
                        } catch (e: Exception) {
                            println("Timestamp to event error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    get("/make_join/{roomId}/{userId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        try {
                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@get
                            }

                            // Get current state to check join rules
                            val roomState = stateResolver.getResolvedState(roomId)

                            // Check if user is already a member
                            val membershipKey = "m.room.member:$userId"
                            val existingMembership = roomState[membershipKey]
                            if (existingMembership != null) {
                                val membership = existingMembership["membership"]?.jsonPrimitive?.content
                                if (membership == "join" || membership == "invite") {
                                    call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_ALREADY_JOINED", "error" to "User is already joined or invited"))
                                    return@get
                                }
                            }

                            // Check join rules
                            val joinRules = roomState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
                            if (joinRules == "invite") {
                                // Check if user has invite
                                val inviteMembership = roomState["m.room.member:$userId"]
                                if (inviteMembership?.get("membership")?.jsonPrimitive?.content != "invite") {
                                    call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Room requires invite"))
                                    return@get
                                }
                            }

                            // Get latest event for prev_events
                            val latestEvent = transaction {
                                Events.select { Events.roomId eq roomId }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .firstOrNull()
                            }

                            val prevEvents = if (latestEvent != null) {
                                listOf(latestEvent[Events.eventId])
                            } else {
                                emptyList<String>()
                            }

                            val depth = if (latestEvent != null) latestEvent[Events.depth] + 1 else 1

                            // Get auth events from current state
                            val authEvents = mutableListOf<String>()
                            val requiredStateKeys = listOf("m.room.create:", "m.room.join_rules:", "m.room.power_levels:")

                            for (stateKey in requiredStateKeys) {
                                val stateEvent = roomState[stateKey]
                                if (stateEvent != null) {
                                    // Find the event ID for this state event
                                    val (type, stateKeyValue) = stateKey.split(":", limit = 2)
                                    val eventRow = transaction {
                                        Events.select {
                                            (Events.roomId eq roomId) and
                                            (Events.type eq type) and
                                            (Events.stateKey eq stateKeyValue) and
                                            (Events.outlier eq false) and
                                            (Events.softFailed eq false)
                                        }.orderBy(Events.originServerTs, SortOrder.DESC).firstOrNull()
                                    }
                                    if (eventRow != null) {
                                        authEvents.add(eventRow[Events.eventId])
                                    }
                                }
                            }

                            // Generate temporary event ID
                            val tempEventId = "\$${System.currentTimeMillis()}_join"

                            // Create join event template
                            val joinEvent = mapOf(
                                "event_id" to tempEventId,
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "content" to mapOf("membership" to "join"),
                                "state_key" to userId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "depth" to depth,
                                "prev_events" to prevEvents,
                                "auth_events" to authEvents,
                                "origin" to "localhost"
                            )

                            call.respond(joinEvent)
                        } catch (e: Exception) {
                            println("Make join error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    put("/send_join/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@put
                        }

                        try {
                            val joinEvent = Json.parseToJsonElement(body).jsonObject

                            // Validate the event
                            if (joinEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Event ID mismatch"))
                                return@put
                            }

                            if (joinEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Room ID mismatch"))
                                return@put
                            }

                            if (joinEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid event type"))
                                return@put
                            }

                            // Process the join event as a PDU
                            val result = processPDU(Json.parseToJsonElement(body))
                            if (result != null) {
                                call.respond(HttpStatusCode.BadRequest, result)
                                return@put
                            }

                            // Get the processed event from database
                            val processedEvent = transaction {
                                Events.select { Events.eventId eq eventId }.singleOrNull()
                            }

                            if (processedEvent == null) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to "Failed to store event"))
                                return@put
                            }

                            // Return the event
                            val eventData = mapOf(
                                "event_id" to processedEvent[Events.eventId],
                                "type" to processedEvent[Events.type],
                                "room_id" to processedEvent[Events.roomId],
                                "sender" to processedEvent[Events.sender],
                                "content" to Json.parseToJsonElement(processedEvent[Events.content]).jsonObject,
                                "auth_events" to Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray,
                                "prev_events" to Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray,
                                "depth" to processedEvent[Events.depth],
                                "hashes" to Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject,
                                "signatures" to Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject,
                                "origin_server_ts" to processedEvent[Events.originServerTs],
                                "state_key" to processedEvent[Events.stateKey],
                                "unsigned" to if (processedEvent[Events.unsigned] != null) Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject else null
                            ).filterValues { it != null }

                            call.respond(eventData)
                        } catch (e: Exception) {
                            println("Send join error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/make_knock/{roomId}/{userId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        try {
                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@get
                            }

                            // Get current state to check knock rules
                            val roomState = stateResolver.getResolvedState(roomId)

                            // Check if user is already a member
                            val membershipKey = "m.room.member:$userId"
                            val existingMembership = roomState[membershipKey]
                            if (existingMembership != null) {
                                val membership = existingMembership["membership"]?.jsonPrimitive?.content
                                if (membership == "join" || membership == "invite" || membership == "knock") {
                                    call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_ALREADY_JOINED", "error" to "User is already joined, invited, or knocking"))
                                    return@get
                                }
                            }

                            // Check join rules - knocking is allowed for rooms that allow it
                            val joinRules = roomState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
                            if (joinRules == "public") {
                                // For public rooms, users can join directly, no need to knock
                                call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_CANNOT_KNOCK", "error" to "Room is public, use make_join instead"))
                                return@get
                            }

                            // Get latest event for prev_events
                            val latestEvent = transaction {
                                Events.select { Events.roomId eq roomId }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .firstOrNull()
                            }

                            val prevEvents = if (latestEvent != null) {
                                listOf(latestEvent[Events.eventId])
                            } else {
                                emptyList<String>()
                            }

                            val depth = if (latestEvent != null) latestEvent[Events.depth] + 1 else 1

                            // Get auth events from current state
                            val authEvents = mutableListOf<String>()
                            val requiredStateKeys = listOf("m.room.create:", "m.room.join_rules:", "m.room.power_levels:")

                            for (stateKey in requiredStateKeys) {
                                val stateEvent = roomState[stateKey]
                                if (stateEvent != null) {
                                    // Find the event ID for this state event
                                    val (type, stateKeyValue) = stateKey.split(":", limit = 2)
                                    val eventRow = transaction {
                                        Events.select {
                                            (Events.roomId eq roomId) and
                                            (Events.type eq type) and
                                            (Events.stateKey eq stateKeyValue) and
                                            (Events.outlier eq false) and
                                            (Events.softFailed eq false)
                                        }.orderBy(Events.originServerTs, SortOrder.DESC).firstOrNull()
                                    }
                                    if (eventRow != null) {
                                        authEvents.add(eventRow[Events.eventId])
                                    }
                                }
                            }

                            // Generate temporary event ID
                            val tempEventId = "\$${System.currentTimeMillis()}_knock"

                            // Create knock event template
                            val knockEvent = mapOf(
                                "event_id" to tempEventId,
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "content" to mapOf("membership" to "knock"),
                                "state_key" to userId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "depth" to depth,
                                "prev_events" to prevEvents,
                                "auth_events" to authEvents,
                                "origin" to "localhost"
                            )

                            call.respond(knockEvent)
                        } catch (e: Exception) {
                            println("Make knock error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    put("/send_knock/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@put
                        }

                        try {
                            val knockEvent = Json.parseToJsonElement(body).jsonObject

                            // Validate the event
                            if (knockEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Event ID mismatch"))
                                return@put
                            }

                            if (knockEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Room ID mismatch"))
                                return@put
                            }

                            if (knockEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid event type"))
                                return@put
                            }

                            val membership = knockEvent["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content
                            if (membership != "knock") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid membership type"))
                                return@put
                            }

                            // Process the knock event as a PDU
                            val result = processPDU(Json.parseToJsonElement(body))
                            if (result != null) {
                                call.respond(HttpStatusCode.BadRequest, result)
                                return@put
                            }

                            // Get the processed event from database
                            val processedEvent = transaction {
                                Events.select { Events.eventId eq eventId }.singleOrNull()
                            }

                            if (processedEvent == null) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to "Failed to store event"))
                                return@put
                            }

                            // Return the event
                            val eventData = mapOf(
                                "event_id" to processedEvent[Events.eventId],
                                "type" to processedEvent[Events.type],
                                "room_id" to processedEvent[Events.roomId],
                                "sender" to processedEvent[Events.sender],
                                "content" to Json.parseToJsonElement(processedEvent[Events.content]).jsonObject,
                                "auth_events" to Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray,
                                "prev_events" to Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray,
                                "depth" to processedEvent[Events.depth],
                                "hashes" to Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject,
                                "signatures" to Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject,
                                "origin_server_ts" to processedEvent[Events.originServerTs],
                                "state_key" to processedEvent[Events.stateKey],
                                "unsigned" to if (processedEvent[Events.unsigned] != null) Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject else null
                            ).filterValues { it != null }

                            call.respond(eventData)
                        } catch (e: Exception) {
                            println("Send knock error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    put("/invite/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@put
                        }

                        try {
                            val inviteEvent = Json.parseToJsonElement(body).jsonObject

                            // Validate the event
                            if (inviteEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Event ID mismatch"))
                                return@put
                            }

                            if (inviteEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Room ID mismatch"))
                                return@put
                            }

                            if (inviteEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid event type"))
                                return@put
                            }

                            val membership = inviteEvent["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content
                            if (membership != "invite") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid membership type"))
                                return@put
                            }

                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@put
                            }

                            // Get current state to validate invite
                            val roomState = stateResolver.getResolvedState(roomId)
                            val stateKey = inviteEvent["state_key"]?.jsonPrimitive?.content ?: ""
                            val sender = inviteEvent["sender"]?.jsonPrimitive?.content ?: ""

                            // Check if user is already a member
                            val membershipKey = "m.room.member:$stateKey"
                            val existingMembership = roomState[membershipKey]
                            if (existingMembership != null) {
                                val currentMembership = existingMembership["membership"]?.jsonPrimitive?.content
                                if (currentMembership == "join" || currentMembership == "invite") {
                                    call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_ALREADY_JOINED", "error" to "User is already joined or invited"))
                                    return@put
                                }
                            }

                            // Process the invite event as a PDU
                            val result = processPDU(Json.parseToJsonElement(body))
                            if (result != null) {
                                call.respond(HttpStatusCode.BadRequest, result)
                                return@put
                            }

                            // Get the processed event from database
                            val processedEvent = transaction {
                                Events.select { Events.eventId eq eventId }.singleOrNull()
                            }

                            if (processedEvent == null) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to "Failed to store event"))
                                return@put
                            }

                            // Return the event
                            val eventData = mapOf(
                                "event_id" to processedEvent[Events.eventId],
                                "type" to processedEvent[Events.type],
                                "room_id" to processedEvent[Events.roomId],
                                "sender" to processedEvent[Events.sender],
                                "content" to Json.parseToJsonElement(processedEvent[Events.content]).jsonObject,
                                "auth_events" to Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray,
                                "prev_events" to Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray,
                                "depth" to processedEvent[Events.depth],
                                "hashes" to Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject,
                                "signatures" to Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject,
                                "origin_server_ts" to processedEvent[Events.originServerTs],
                                "state_key" to processedEvent[Events.stateKey],
                                "unsigned" to if (processedEvent[Events.unsigned] != null) Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject else null
                            ).filterValues { it != null }

                            call.respond(eventData)
                        } catch (e: Exception) {
                            println("Invite error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/make_leave/{roomId}/{userId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@get
                        }

                        try {
                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@get
                            }

                            // Get current state to validate leave
                            val roomState = stateResolver.getResolvedState(roomId)

                            // Check if user is actually in the room
                            val membershipKey = "m.room.member:$userId"
                            val existingMembership = roomState[membershipKey]
                            if (existingMembership == null) {
                                call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_NOT_MEMBER", "error" to "User is not a member of this room"))
                                return@get
                            }

                            val currentMembership = existingMembership["membership"]?.jsonPrimitive?.content
                            if (currentMembership == "leave" || currentMembership == "ban") {
                                call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_ALREADY_LEFT", "error" to "User has already left or been banned from this room"))
                                return@get
                            }

                            // Get latest event for prev_events
                            val latestEvent = transaction {
                                Events.select { Events.roomId eq roomId }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .firstOrNull()
                            }

                            val prevEvents = if (latestEvent != null) {
                                listOf(latestEvent[Events.eventId])
                            } else {
                                emptyList<String>()
                            }

                            val depth = if (latestEvent != null) latestEvent[Events.depth] + 1 else 1

                            // Get auth events from current state
                            val authEvents = mutableListOf<String>()
                            val requiredStateKeys = listOf("m.room.create:", "m.room.join_rules:", "m.room.power_levels:")

                            for (stateKey in requiredStateKeys) {
                                val stateEvent = roomState[stateKey]
                                if (stateEvent != null) {
                                    // Find the event ID for this state event
                                    val (type, stateKeyValue) = stateKey.split(":", limit = 2)
                                    val eventRow = transaction {
                                        Events.select {
                                            (Events.roomId eq roomId) and
                                            (Events.type eq type) and
                                            (Events.stateKey eq stateKeyValue) and
                                            (Events.outlier eq false) and
                                            (Events.softFailed eq false)
                                        }.orderBy(Events.originServerTs, SortOrder.DESC).firstOrNull()
                                    }
                                    if (eventRow != null) {
                                        authEvents.add(eventRow[Events.eventId])
                                    }
                                }
                            }

                            // Generate temporary event ID
                            val tempEventId = "\$${System.currentTimeMillis()}_leave"

                            // Create leave event template
                            val leaveEvent = mapOf(
                                "event_id" to tempEventId,
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "content" to mapOf("membership" to "leave"),
                                "state_key" to userId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "depth" to depth,
                                "prev_events" to prevEvents,
                                "auth_events" to authEvents,
                                "origin" to "localhost"
                            )

                            call.respond(leaveEvent)
                        } catch (e: Exception) {
                            println("Make leave error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    put("/send_leave/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("errcode" to "M_FORBIDDEN", "error" to "Server access denied by ACL"))
                            return@put
                        }

                        try {
                            val leaveEvent = Json.parseToJsonElement(body).jsonObject

                            // Validate the event
                            if (leaveEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Event ID mismatch"))
                                return@put
                            }

                            if (leaveEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Room ID mismatch"))
                                return@put
                            }

                            if (leaveEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid event type"))
                                return@put
                            }

                            val membership = leaveEvent["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content
                            if (membership != "leave") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid membership type"))
                                return@put
                            }

                            // Process the leave event as a PDU
                            val result = processPDU(Json.parseToJsonElement(body))
                            if (result != null) {
                                call.respond(HttpStatusCode.BadRequest, result)
                                return@put
                            }

                            // Get the processed event from database
                            val processedEvent = transaction {
                                Events.select { Events.eventId eq eventId }.singleOrNull()
                            }

                            if (processedEvent == null) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to "Failed to store event"))
                                return@put
                            }

                            // Return the event
                            val eventData = mapOf(
                                "event_id" to processedEvent[Events.eventId],
                                "type" to processedEvent[Events.type],
                                "room_id" to processedEvent[Events.roomId],
                                "sender" to processedEvent[Events.sender],
                                "content" to Json.parseToJsonElement(processedEvent[Events.content]).jsonObject,
                                "auth_events" to Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray,
                                "prev_events" to Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray,
                                "depth" to processedEvent[Events.depth],
                                "hashes" to Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject,
                                "signatures" to Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject,
                                "origin_server_ts" to processedEvent[Events.originServerTs],
                                "state_key" to processedEvent[Events.stateKey],
                                "unsigned" to if (processedEvent[Events.unsigned] != null) Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject else null
                            ).filterValues { it != null }

                            call.respond(eventData)
                        } catch (e: Exception) {
                            println("Send leave error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    put("/3pid/onbind") {
                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }

                        try {
                            val bindRequest = Json.parseToJsonElement(body).jsonObject

                            // Validate the request
                            val medium = bindRequest["medium"]?.jsonPrimitive?.content
                            val address = bindRequest["address"]?.jsonPrimitive?.content
                            val mxid = bindRequest["mxid"]?.jsonPrimitive?.content

                            if (medium == null || address == null || mxid == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing required fields"))
                                return@put
                            }

                            // In a real implementation, this would:
                            // 1. Validate that the third-party invite was actually sent
                            // 2. Check that the MXID matches the invite
                            // 3. Mark the invite as redeemed
                            // 4. Possibly send a membership event

                            // For now, just acknowledge the binding
                            println("Third-party invite bound: $medium:$address -> $mxid")

                            call.respond(mapOf("success" to true))
                        } catch (e: Exception) {
                            println("3PID onbind error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    put("/exchange_third_party_invite/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@put
                        }

                        try {
                            val exchangeRequest = Json.parseToJsonElement(body).jsonObject

                            // Validate the request
                            val medium = exchangeRequest["medium"]?.jsonPrimitive?.content
                            val address = exchangeRequest["address"]?.jsonPrimitive?.content
                            val sender = exchangeRequest["sender"]?.jsonPrimitive?.content

                            if (medium == null || address == null || sender == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing required fields"))
                                return@put
                            }

                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@put
                            }

                            // In a real implementation, this would:
                            // 1. Validate the third-party invite token
                            // 2. Check that the sender has permission to invite
                            // 3. Create a membership event with invite status
                            // 4. Return the invite event

                            // For now, create a basic invite event
                            val inviteEvent: Map<String, Any> = mapOf(
                                "event_id" to "\$${System.currentTimeMillis()}_invite",
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to sender,
                                "content" to mapOf<String, Any>(
                                    "membership" to "invite",
                                    "third_party_invite" to mapOf<String, Any>(
                                        "display_name" to address,
                                        "signed" to mapOf<String, Any>(
                                            "mxid" to "@$address:$medium",
                                            "token" to "placeholder_token",
                                            "signatures" to emptyMap<String, Any>()
                                        )
                                    )
                                ),
                                "state_key" to "@$address:$medium",
                                "origin_server_ts" to System.currentTimeMillis(),
                                "origin" to "localhost"
                            )

                            call.respond(inviteEvent)
                        } catch (e: Exception) {
                            println("Exchange third party invite error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/publicRooms") {
                        try {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                            val since = call.request.queryParameters["since"]

                            // Get published rooms with pagination
                            val publishedRooms = transaction {
                                val query = Rooms.select { Rooms.published eq true }
                                    .orderBy(Rooms.roomId)
                                    .limit(limit)

                                if (since != null) {
                                    // Simple pagination by room_id (in a real implementation, you'd use proper pagination tokens)
                                    query.andWhere { Rooms.roomId greater since }
                                }

                                query.map { roomRow ->
                                    val roomId = roomRow[Rooms.roomId]
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

                                    mapOf<String, Any?>(
                                        "room_id" to roomId,
                                        "name" to name,
                                        "topic" to topic,
                                        "canonical_alias" to canonicalAlias,
                                        "num_joined_members" to joinedMembers,
                                        "world_readable" to worldReadable,
                                        "guest_can_join" to guestCanJoin,
                                        "avatar_url" to avatarUrl
                                    ).filterValues { it != null }
                                }
                            }

                            call.respond(mapOf(
                                "chunk" to publishedRooms,
                                "total_room_count_estimate" to publishedRooms.size
                            ))
                        } catch (e: Exception) {
                            println("Public rooms error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    post("/publicRooms") {
                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@post
                        }

                        try {
                            val requestBody = Json.parseToJsonElement(body).jsonObject
                            val roomId = requestBody["room_id"]?.jsonPrimitive?.content
                            val visibility = requestBody["visibility"]?.jsonPrimitive?.content ?: "public"

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing room_id"))
                                return@post
                            }

                            if (visibility != "public") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Only public visibility is supported"))
                                return@post
                            }

                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@post
                            }

                            // Update room's published status
                            transaction {
                                Rooms.update({ Rooms.roomId eq roomId }) {
                                    it[published] = true
                                }
                            }

                            call.respond(mapOf("success" to true))
                        } catch (e: Exception) {
                            println("Publish room error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/hierarchy/{roomId}") {
                        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        try {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                            val maxDepth = call.request.queryParameters["max_depth"]?.toIntOrNull() ?: 3
                            val from = call.request.queryParameters["from"]
                            val suggestedOnly = call.request.queryParameters["suggested_only"]?.toBoolean() ?: false

                            // Check if room exists and is a space
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@get
                            }

                            val currentState = stateResolver.getResolvedState(roomId)
                            val createEvent = currentState["m.room.create:"]

                            // Check if this is actually a space
                            val roomType = createEvent?.get("type")?.jsonPrimitive?.content
                            if (roomType != "m.space") {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Room is not a space"))
                                return@get
                            }

                            // Get child rooms from space state
                            val children = mutableListOf<Map<String, Any?>>()
                            var nextToken: String? = null

                            for ((stateKey, stateEvent) in currentState) {
                                if (stateKey.startsWith("m.space.child:")) {
                                    val childRoomId = stateKey.substringAfter("m.space.child:")
                                    val content = stateEvent

                                    // Check if this child should be included
                                    val suggested = content["suggested"]?.jsonPrimitive?.boolean ?: false
                                    if (suggestedOnly && !suggested) continue

                                    // Get child room information
                                    val childInfo = getRoomInfo(childRoomId)
                                    if (childInfo != null) {
                                        children.add(childInfo)

                                        // Simple pagination (in a real implementation, you'd use proper tokens)
                                        if (children.size >= limit) {
                                            nextToken = childRoomId
                                            break
                                        }
                                    }
                                }
                            }

                            val response = mutableMapOf<String, Any?>(
                                "rooms" to children
                            )

                            if (nextToken != null) {
                                response["next_batch"] = nextToken
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Hierarchy error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    get("/query/directory") {
                        val roomAlias = call.request.queryParameters["room_alias"]

                        if (roomAlias == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing room_alias parameter"))
                            return@get
                        }

                        try {
                            // Look up room by alias
                            val roomId = findRoomByAlias(roomAlias)

                            if (roomId == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room alias not found"))
                                return@get
                            }

                            // Get room information
                            val roomInfo = getRoomInfo(roomId)
                            if (roomInfo == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                                return@get
                            }

                            val servers = listOf("localhost") // In a real implementation, this would include all servers that know about the room

                            call.respond(mapOf(
                                "room_id" to roomId,
                                "servers" to servers
                            ))
                        } catch (e: Exception) {
                            println("Query directory error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    get("/query/profile") {
                        val userId = call.request.queryParameters["user_id"]
                        val field = call.request.queryParameters["field"]

                        if (userId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing user_id parameter"))
                            return@get
                        }

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        try {
                            // Get user profile information
                            val profile = getUserProfile(userId, field)
                            if (profile != null) {
                                call.respond(profile)
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "User not found"))
                            }
                        } catch (e: Exception) {
                            println("Query profile error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    get("/query/{queryType}") {
                        val queryType = call.parameters["queryType"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        try {
                            when (queryType) {
                                "directory" -> {
                                    // This is handled by the specific directory endpoint above
                                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Use /query/directory endpoint"))
                                }
                                "profile" -> {
                                    // This is handled by the specific profile endpoint above
                                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Use /query/profile endpoint"))
                                }
                                else -> {
                                    // Unknown query type
                                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Unknown query type: $queryType"))
                                }
                            }
                        } catch (e: Exception) {
                            println("Query error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    get("/openid/userinfo") {
                        val accessToken = call.request.queryParameters["access_token"]
                        if (accessToken == null) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf(
                                "errcode" to "M_UNKNOWN_TOKEN",
                                "error" to "Access token required"
                            ))
                            return@get
                        }

                        // Find user by access token
                        val userId = utils.users.entries.find { it.value == accessToken }?.key
                        if (userId == null) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf(
                                "errcode" to "M_UNKNOWN_TOKEN",
                                "error" to "Access token unknown or expired"
                            ))
                            return@get
                        }

                        call.respond(mapOf("sub" to userId))
                    }
                    get("/user/devices/{userId}") {
                        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        try {
                            // Get user's devices
                            val userDevices = deviceKeys[userId] ?: emptyMap()

                            // Convert devices to the expected format
                            val devices = userDevices.map { (deviceId, deviceInfo) ->
                                mapOf<String, Any?>(
                                    "device_id" to deviceId,
                                    "device_display_name" to deviceInfo["device_display_name"],
                                    "keys" to deviceInfo["keys"]
                                ).filterValues { it != null }
                            }

                            // Get cross-signing keys
                            val masterKey = utils.crossSigningKeys["${userId}_master"]
                            val selfSigningKey = utils.crossSigningKeys["${userId}_self_signing"]

                            // Get stream ID for device list
                            val streamId = utils.deviceListStreamIds.getOrPut(userId) { 0L }

                            val response = mutableMapOf<String, Any>(
                                "devices" to devices,
                                "stream_id" to streamId,
                                "user_id" to userId
                            )

                            if (masterKey != null) {
                                response["master_key"] = masterKey
                            }

                            if (selfSigningKey != null) {
                                response["self_signing_key"] = selfSigningKey
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Get devices error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    post("/user/keys/claim") {
                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@post
                        }

                        try {
                            val requestBody = Json.parseToJsonElement(body).jsonObject
                            val oneTimeKeys = requestBody["one_time_keys"]?.jsonObject ?: JsonObject(emptyMap())

                            val claimedKeys = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()

                            // Process each user's requested keys
                            for (userId in oneTimeKeys.keys) {
                                val userRequestedKeys = oneTimeKeys[userId]?.jsonObject ?: JsonObject(emptyMap())
                                val userClaimedKeys = mutableMapOf<String, Map<String, Any?>>()

                                for (deviceKeyId in userRequestedKeys.keys) {
                                    // Parse the key ID (format: algorithm:key_id)
                                    val parts = deviceKeyId.split(":", limit = 2)
                                    if (parts.size != 2) continue

                                    val algorithm = parts[0]
                                    val keyId = parts[1]

                                    // Find available one-time key for this user and algorithm
                                    val globalUserOneTimeKeys = utils.oneTimeKeys[userId] ?: emptyMap<String, Map<String, Any?>>()
                                    val availableKey = globalUserOneTimeKeys.entries.find { (key, _) ->
                                        key.startsWith("$algorithm:")
                                    }

                                    if (availableKey != null) {
                                        val (foundKeyId, keyData) = availableKey
                                        userClaimedKeys[foundKeyId] = mapOf(
                                            "key" to (keyData["key"] as? String),
                                            "signatures" to keyData["signatures"]
                                        )

                                        // Remove the claimed key from available keys
                                        val userKeysMap = utils.oneTimeKeys.getOrPut(userId) { mutableMapOf<String, Map<String, Any?>>() }
                                        userKeysMap.remove(foundKeyId)
                                    }
                                }

                                if (userClaimedKeys.isNotEmpty()) {
                                    claimedKeys[userId] = userClaimedKeys
                                }
                            }

                            call.respond(mapOf("one_time_keys" to claimedKeys))
                        } catch (e: Exception) {
                            println("Claim keys error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    post("/user/keys/query") {
                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@post
                        }

                        try {
                            val requestBody = Json.parseToJsonElement(body).jsonObject
                            val deviceKeys = requestBody["device_keys"]?.jsonObject ?: JsonObject(emptyMap())

                            val response = mutableMapOf<String, Any>()

                            // Process device keys
                            val deviceKeysResponse = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
                            for (userId in deviceKeys.keys) {
                                val requestedDevicesJson = deviceKeys[userId]
                                val globalUserDevices = utils.deviceKeys[userId] ?: emptyMap<String, Map<String, Any?>>()
                                val userDeviceKeys = mutableMapOf<String, Map<String, Any?>>()

                                if (requestedDevicesJson is JsonNull) {
                                    // Return all devices for this user
                                    for (deviceId in globalUserDevices.keys) {
                                        val deviceInfo = globalUserDevices[deviceId]
                                        if (deviceInfo != null) {
                                            userDeviceKeys[deviceId] = deviceInfo
                                        }
                                    }
                                } else if (requestedDevicesJson is JsonObject) {
                                    val deviceList = requestedDevicesJson["device_ids"]?.jsonArray ?: JsonArray(emptyList())
                                    // Return only requested devices
                                    for (deviceElement in deviceList) {
                                        val deviceId = deviceElement.jsonPrimitive.content
                                        val deviceInfo = globalUserDevices[deviceId]
                                        if (deviceInfo != null) {
                                            userDeviceKeys[deviceId] = deviceInfo
                                        }
                                    }
                                }

                                if (userDeviceKeys.isNotEmpty()) {
                                    deviceKeysResponse[userId] = userDeviceKeys
                                }
                            }

                            if (deviceKeysResponse.isNotEmpty()) {
                                response["device_keys"] = deviceKeysResponse
                            }

                            // Add cross-signing keys if available
                            val masterKeys = mutableMapOf<String, Map<String, Any?>>()
                            val selfSigningKeys = mutableMapOf<String, Map<String, Any?>>()

                            for (userId in deviceKeys.keys) {
                                val masterKey = utils.crossSigningKeys["${userId}_master"]
                                val selfSigningKey = utils.crossSigningKeys["${userId}_self_signing"]

                                if (masterKey != null) {
                                    masterKeys[userId] = masterKey
                                }
                                if (selfSigningKey != null) {
                                    selfSigningKeys[userId] = selfSigningKey
                                }
                            }

                            if (masterKeys.isNotEmpty()) {
                                response["master_keys"] = masterKeys
                            }
                            if (selfSigningKeys.isNotEmpty()) {
                                response["self_signing_keys"] = selfSigningKeys
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Query keys error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/media/download/{mediaId}") {
                        val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        try {
                            // Parse media ID from mxc://server/mediaId format
                            // For now, we'll simulate media storage - in a real implementation,
                            // this would look up the media in a database or file system

                            // Check if media exists (placeholder - always return not found for now)
                            val mediaExists = false // TODO: Implement actual media lookup
                            val mediaData = ByteArray(0) // TODO: Load actual media data
                            val contentType = "application/octet-stream" // TODO: Determine actual content type

                            if (!mediaExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Media not found"))
                                return@get
                            }

                            // Return multipart response as per spec
                            val boundary = "boundary_${System.currentTimeMillis()}"

                            // Create metadata part
                            val metadata = mapOf(
                                "content_uri" to "mxc://localhost/$mediaId",
                                "content_type" to contentType,
                                "content_length" to mediaData.size
                            )

                            val metadataJson = Json.encodeToString(JsonObject.serializer(), JsonObject(metadata.mapValues { JsonPrimitive(it.value.toString()) }))

                            // Create the multipart response
                            val multipartContent = """
--$boundary
Content-Type: application/json

$metadataJson
--$boundary
Content-Type: $contentType

${String(mediaData)}
--$boundary--
                            """.trimIndent()

                            call.respondText(
                                contentType = ContentType.MultiPart.Mixed.withParameter("boundary", boundary),
                                text = multipartContent
                            )

                        } catch (e: Exception) {
                            println("Media download error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                    get("/media/thumbnail/{mediaId}") {
                        val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        try {
                            // Parse query parameters
                            val width = call.request.queryParameters["width"]?.toIntOrNull()
                            val height = call.request.queryParameters["height"]?.toIntOrNull()
                            val method = call.request.queryParameters["method"] ?: "scale"
                            val animated = call.request.queryParameters["animated"]?.toBoolean() ?: false
                            val timeoutMs = call.request.queryParameters["timeout_ms"]?.toLongOrNull() ?: 20000L

                            // Validate required parameters
                            if (width == null || height == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing width or height parameter"))
                                return@get
                            }

                            // Validate method parameter
                            if (method !in setOf("crop", "scale")) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid method parameter"))
                                return@get
                            }

                            // Validate dimensions
                            if (width <= 0 || height <= 0 || width > 1000 || height > 1000) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid dimensions"))
                                return@get
                            }

                            // Check if media exists (placeholder - always return not found for now)
                            val mediaExists = false // TODO: Implement actual media lookup
                            val thumbnailData = ByteArray(0) // TODO: Generate actual thumbnail
                            val contentType = "image/jpeg" // TODO: Determine actual content type

                            if (!mediaExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Media not found"))
                                return@get
                            }

                            // Return multipart response as per spec
                            val boundary = "boundary_${System.currentTimeMillis()}"

                            // Create metadata part
                            val metadata = mapOf(
                                "content_uri" to "mxc://localhost/$mediaId",
                                "content_type" to contentType,
                                "content_length" to thumbnailData.size,
                                "width" to width,
                                "height" to height,
                                "method" to method,
                                "animated" to animated
                            )

                            val metadataJson = Json.encodeToString(JsonObject.serializer(), JsonObject(metadata.mapValues { JsonPrimitive(it.value.toString()) }))

                            // Create the multipart response
                            val multipartContent = """
--$boundary
Content-Type: application/json

$metadataJson
--$boundary
Content-Type: $contentType

${String(thumbnailData)}
--$boundary--
                            """.trimIndent()

                            call.respondText(
                                contentType = ContentType.MultiPart.Mixed.withParameter("boundary", boundary),
                                text = multipartContent
                            )

                        } catch (e: Exception) {
                            println("Media thumbnail error: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
                        }
                    }
                }
            }
        }
    }
}

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
                it[eventId] = eventId
                it[Events.roomId] = roomId
                it[type] = event["type"]?.jsonPrimitive?.content ?: ""
                it[sender] = sender
                it[content] = event["content"]?.toString() ?: ""
                it[authEvents] = event["auth_events"]?.toString() ?: ""
                it[prevEvents] = event["prev_events"]?.toString() ?: ""
                it[depth] = event["depth"]?.jsonPrimitive?.int ?: 0
                it[hashes] = event["hashes"]?.toString() ?: ""
                it[signatures] = event["signatures"]?.toString() ?: ""
                it[originServerTs] = event["origin_server_ts"]?.jsonPrimitive?.long ?: 0
                it[stateKey] = event["state_key"]?.jsonPrimitive?.content
                it[unsigned] = event["unsigned"]?.toString()
                it[softFailed] = softFail
                it[outlier] = false
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

fun getAuthState(event: JsonObject, roomId: String): Map<String, JsonObject> {
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
                for ((roomId, roomReceipts) in content) {
                    // Validate room_id format
                    if (!roomId.startsWith("!") || !roomId.contains(":")) {
                        println("Invalid room_id format: $roomId")
                        continue
                    }

                    val roomMap = receiptsMap.getOrPut(roomId) { mutableMapOf() }

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
                                                // Store read receipt with user and timestamp
                                                val receiptKey = "$eventId:$userId"
                                                roomMap[receiptKey] = ts
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
                for ((roomId, roomReceipts) in receiptsMap) {
                    val roomData = mutableMapOf<String, Any>()
                    for ((receiptKey, timestamp) in roomReceipts) {
                        if (receiptKey.contains(":")) {
                            val (eventId, userId) = receiptKey.split(":", limit = 2)
                            if (!roomData.containsKey(eventId)) {
                                roomData[eventId] = mutableMapOf<String, Any>()
                            }
                            (roomData[eventId] as? MutableMap<String, Any>)?.let { eventMap ->
                                eventMap[userId] = mapOf("ts" to timestamp)
                            }
                        }
                    }
                    if (roomData.isNotEmpty()) {
                        simplifiedReceipts[roomId] = roomData
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
                val lastActiveAgo = content["last_active_ago"]?.jsonPrimitive?.long

                // Create comprehensive presence data
                val presenceData = mutableMapOf<String, Any?>(
                    "user_id" to userId,
                    "presence" to presence,
                    "last_active_ago" to (lastActiveAgo ?: System.currentTimeMillis())
                )

                if (statusMsg != null) {
                    presenceData["status_msg"] = statusMsg
                }

                if (currentlyActive != null) {
                    presenceData["currently_active"] = currentlyActive
                }

                // Update presence with timestamp
                val presenceWithTimestamp = mapOf(
                    "presence" to presence,
                    "status_msg" to statusMsg,
                    "currently_active" to currentlyActive,
                    "last_active_ago" to (lastActiveAgo ?: System.currentTimeMillis()),
                    "updated_at" to System.currentTimeMillis()
                )

                presenceMap[userId] = presenceWithTimestamp

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

fun getStateAtEvent(roomId: String, eventId: String): List<Map<String, Any?>> {
    // This is a simplified implementation - in a real implementation,
    // you'd need to reconstruct the state at a specific event
    // For now, return current state
    return getCurrentStateEvents(roomId)
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
        // In a real implementation, this would query a user profile database
        // For now, return basic profile information
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
        println("Error getting user profile for $userId: ${e.message}")
        null
    }
}
