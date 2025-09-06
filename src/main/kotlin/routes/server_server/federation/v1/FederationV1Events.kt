package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import utils.StateResolver
import utils.MatrixAuth

fun Route.federationV1Events() {
    route("/event_auth") {
        get("/{roomId}/{eventId}") {
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
                "origin" to utils.ServerNameResolver.getServerName(),
                "origin_server_ts" to System.currentTimeMillis(),
                "pdus" to authChain
            ))
        }
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
}
