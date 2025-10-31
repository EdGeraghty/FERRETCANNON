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

fun Route.federationV1State() {
    get("/state/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val eventId = call.request.queryParameters["event_id"]

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        // Check Server ACL
        val serverName = extractServerNameFromAuth(authHeader)
        if (serverName != null && !checkServerACL(roomId, serverName)) {
            call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                put("errcode", "M_FORBIDDEN")
                put("error", "Server access denied by ACL")
            })
            return@get
        }

        val stateEvents = if (eventId != null) {
            // Get state at specific event
            getStateAtEvent(roomId)
        } else {
            // Get current state
            getCurrentStateEvents(roomId)
        }

        call.respond(buildJsonObject {
            put("origin", "localhost")
            put("origin_server_ts", System.currentTimeMillis())
            put("pdus", Json.encodeToJsonElement(stateEvents))
        })
    }
    get("/state_ids/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val eventId = call.request.queryParameters["event_id"]

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        // Check Server ACL
        val serverName = extractServerNameFromAuth(authHeader)
        if (serverName != null && !checkServerACL(roomId, serverName)) {
            call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                put("errcode", "M_FORBIDDEN")
                put("error", "Server access denied by ACL")
            })
            return@get
        }

        val stateEventIds = if (eventId != null) {
            // Get state event IDs at specific event
            getStateEventIdsAtEvent(roomId)
        } else {
            // Get current state event IDs
            getCurrentStateEventIds(roomId)
        }

        call.respond(buildJsonObject {
            put("origin", "localhost")
            put("origin_server_ts", System.currentTimeMillis())
            put("pdu_ids", Json.encodeToJsonElement(stateEventIds))
        })
    }
}
