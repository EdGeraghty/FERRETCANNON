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
}
