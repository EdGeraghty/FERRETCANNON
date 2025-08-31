package routes.server_server.federation.v2

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import models.Rooms
import utils.MatrixAuth
import utils.stateResolver
import routes.server_server.federation.v1.checkServerACL
import routes.server_server.federation.v1.extractServerNameFromAuth
import routes.server_server.federation.v1.processPDU

fun Application.federationV2Routes() {
    routing {
        route("/_matrix") {
            route("/federation") {
                route("/v2") {
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

                            // Get current state for v2 response
                            val currentState = transaction {
                                val stateEvents = Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.outlier eq false) and
                                    (Events.softFailed eq false)
                                }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(50) // Limit for performance
                                    .map { row ->
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
                                    }
                            }

                            // Return the v2 response format
                            val response = mapOf(
                                "event" to mapOf(
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
                                ).filterValues { it != null },
                                "state" to currentState,
                                "auth_chain" to emptyList<Map<String, Any>>() // Simplified for now
                            )

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Send join v2 error: ${e.message}")
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

                            // Return the v2 response format
                            val response = mapOf(
                                "event" to mapOf(
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
                            )

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Invite v2 error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
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

                            // Return the v2 response format
                            val response = mapOf(
                                "event" to mapOf(
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
                            )

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Send leave v2 error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                }
            }
        }
    }
}
