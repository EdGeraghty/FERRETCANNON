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
import utils.StateResolver
import routes.server_server.federation.v1.checkServerACL
import routes.server_server.federation.v1.extractServerNameFromAuth
import routes.server_server.federation.v1.processPDU
import routes.server_server.federation.v1.getCurrentStateEvents
import config.ConfigLoader

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
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("errcode", "M_UNAUTHORIZED")
                                put("error", "Invalid signature")
                            })
                            return@put
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                                put("errcode", "M_FORBIDDEN")
                                put("error", "Server access denied by ACL")
                            })
                            return@put
                        }

                        // Use getCurrentStateEvents to retrieve current state
                        val currentState = getCurrentStateEvents(roomId).map { event ->
                            buildJsonObject {
                                put("event_id", event["event_id"] as String)
                                put("type", event["type"] as String)
                                put("room_id", event["room_id"] as String)
                                put("sender", event["sender"] as String)
                                put("content", event["content"] as JsonObject)
                                put("auth_events", event["auth_events"] as JsonArray)
                                put("prev_events", event["prev_events"] as JsonArray)
                                put("depth", event["depth"] as Int)
                                put("hashes", event["hashes"] as JsonObject)
                                put("signatures", event["signatures"] as JsonObject)
                                put("origin_server_ts", event["origin_server_ts"] as Long)
                                if (event["state_key"] != null) put("state_key", event["state_key"] as String)
                                if (event["unsigned"] != null) put("unsigned", event["unsigned"] as JsonObject)
                            }
                        }

                        try {
                            val joinEvent = Json.parseToJsonElement(body).jsonObject

                            // Validate the event
                            if (joinEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Event ID mismatch")
                                })
                                return@put
                            }

                            if (joinEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Room ID mismatch")
                                })
                                return@put
                            }

                            if (joinEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Invalid event type")
                                })
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
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Failed to store event")
                                })
                                return@put
                            }

                            // Return the v2 response format
                            val response = buildJsonObject {
                                putJsonObject("event") {
                                    put("event_id", processedEvent[Events.eventId])
                                    put("type", processedEvent[Events.type])
                                    put("room_id", processedEvent[Events.roomId])
                                    put("sender", processedEvent[Events.sender])
                                    put("content", Json.parseToJsonElement(processedEvent[Events.content]).jsonObject)
                                    put("auth_events", Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray)
                                    put("prev_events", Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray)
                                    put("depth", processedEvent[Events.depth])
                                    put("hashes", Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject)
                                    put("signatures", Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject)
                                    put("origin_server_ts", processedEvent[Events.originServerTs])
                                    if (processedEvent[Events.stateKey] != null) put("state_key", processedEvent[Events.stateKey])
                                    if (processedEvent[Events.unsigned] != null) put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                                }
                                put("state", JsonArray(currentState.map { Json.encodeToJsonElement(it) }))
                                put("auth_chain", JsonArray(emptyList<JsonElement>()))
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Send join v2 error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("errcode", "M_BAD_JSON")
                                put("error", "Invalid JSON")
                            })
                        }
                    }
                    put("/invite/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("errcode", "M_MISSING_PARAM")
                            put("error", "Missing roomId in URL")
                        })

                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("errcode", "M_MISSING_PARAM")
                            put("error", "Missing eventId in URL")
                        })

                        try {
                            val body = call.receiveText()
                            val authHeader = call.request.headers["Authorization"]

                            if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                    put("errcode", "M_UNAUTHORIZED")
                                    put("error", "Invalid signature")
                                })
                                return@put
                            }

                            // Use getCurrentStateEvents to retrieve current state
                            val currentState = getCurrentStateEvents(roomId).map { event ->
                                buildJsonObject {
                                    put("event_id", event["event_id"] as String)
                                    put("type", event["type"] as String)
                                    put("room_id", event["room_id"] as String)
                                    put("sender", event["sender"] as String)
                                    put("content", event["content"] as JsonObject)
                                    put("auth_events", event["auth_events"] as JsonArray)
                                    put("prev_events", event["prev_events"] as JsonArray)
                                    put("depth", event["depth"] as Int)
                                    put("hashes", event["hashes"] as JsonObject)
                                    put("signatures", event["signatures"] as JsonObject)
                                    put("origin_server_ts", event["origin_server_ts"] as Long)
                                    if (event["state_key"] != null) put("state_key", event["state_key"] as String)
                                    if (event["unsigned"] != null) put("unsigned", event["unsigned"] as JsonObject)
                                }
                            }

                            val inviteEvent = Json.parseToJsonElement(body).jsonObject
                            val nestedEvent = inviteEvent["event"]?.jsonObject ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("errcode", "M_BAD_JSON")
                                put("error", "Missing nested event structure")
                            })

                            val roomIdFromEvent = nestedEvent["room_id"]?.jsonPrimitive?.content
                            if (roomIdFromEvent != roomId) {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Room ID mismatch")
                                })
                                return@put
                            }

                            val result = processPDU(nestedEvent, eventId)
                            if (result != null) {
                                call.respond(HttpStatusCode.BadRequest, result)
                                return@put
                            }

                            val processedEvent = transaction {
                                Events.select { Events.eventId eq eventId }.singleOrNull()
                            } ?: return@put call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                put("errcode", "M_UNKNOWN")
                                put("error", "Failed to store event")
                            })

                            println("/v2/invite: Constructing response with processedEvent: $processedEvent")

                            // Fetch the auth chain for the event
                            val authChain: List<JsonObject> = transaction {
                                val authEventIds = Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray.map { it.jsonPrimitive.content }
                                Events.select { Events.eventId inList authEventIds }
                                    .map { row ->
                                        buildJsonObject {
                                            put("event_id", row[Events.eventId])
                                            put("type", row[Events.type])
                                            put("room_id", row[Events.roomId])
                                            put("sender", row[Events.sender])
                                            put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                                            put("auth_events", Json.parseToJsonElement(row[Events.authEvents]).jsonArray)
                                            put("prev_events", Json.parseToJsonElement(row[Events.prevEvents]).jsonArray)
                                            put("depth", row[Events.depth])
                                            put("hashes", Json.parseToJsonElement(row[Events.hashes]).jsonObject)
                                            put("signatures", Json.parseToJsonElement(row[Events.signatures]).jsonObject)
                                            put("origin_server_ts", row[Events.originServerTs])
                                            if (row[Events.stateKey] != null) put("state_key", row[Events.stateKey])
                                            if (row[Events.unsigned] != null) put("unsigned", Json.parseToJsonElement(row[Events.unsigned]!!).jsonObject)
                                        }
                                    }
                            }

                            // Return the v2 response format
                            val response = buildJsonObject {
                                put("event", buildJsonObject {
                                    put("event_id", processedEvent[Events.eventId])
                                    put("type", processedEvent[Events.type])
                                    put("room_id", processedEvent[Events.roomId])
                                    put("sender", processedEvent[Events.sender])
                                    put("content", Json.parseToJsonElement(processedEvent[Events.content]).jsonObject)
                                    put("auth_events", Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray)
                                    put("prev_events", Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray)
                                    put("depth", processedEvent[Events.depth])
                                    put("hashes", Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject)
                                    put("signatures", Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject)
                                    put("origin_server_ts", processedEvent[Events.originServerTs])
                                    if (processedEvent[Events.stateKey] != null) put("state_key", processedEvent[Events.stateKey])
                                    if (processedEvent[Events.unsigned] != null) put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                                })
                                put("state", JsonArray(currentState.map { Json.encodeToJsonElement(it) }))
                                put("auth_chain", JsonArray(authChain.map { Json.encodeToJsonElement(it) }))
                            }

                            call.respond(HttpStatusCode.OK, response)
                        } catch (e: Exception) {
                            println("Error in /v2/invite: ${e.message}")
                            e.printStackTrace()
                            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                put("errcode", "M_UNKNOWN")
                                put("error", "Unexpected error: ${e.message}")
                                put("stacktrace", e.stackTraceToString())
                            })
                        }
                    }
                    put("/send_leave/{roomId}/{eventId}") {
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("errcode", "M_UNAUTHORIZED")
                                put("error", "Invalid signature")
                            })
                            return@put
                        }

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                                put("errcode", "M_FORBIDDEN")
                                put("error", "Server access denied by ACL")
                            })
                            return@put
                        }

                        try {
                            val leaveEvent = Json.parseToJsonElement(body).jsonObject

                            // Validate the event
                            if (leaveEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Event ID mismatch")
                                })
                                return@put
                            }

                            if (leaveEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Room ID mismatch")
                                })
                                return@put
                            }

                            if (leaveEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Invalid event type")
                                })
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
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Failed to store event")
                                })
                                return@put
                            }

                            // Return the v2 response format
                            val response = buildJsonObject {
                                putJsonObject("event") {
                                    put("event_id", processedEvent[Events.eventId])
                                    put("type", processedEvent[Events.type])
                                    put("room_id", processedEvent[Events.roomId])
                                    put("sender", processedEvent[Events.sender])
                                    put("content", Json.parseToJsonElement(processedEvent[Events.content]).jsonObject)
                                    put("auth_events", Json.parseToJsonElement(processedEvent[Events.authEvents]).jsonArray)
                                    put("prev_events", Json.parseToJsonElement(processedEvent[Events.prevEvents]).jsonArray)
                                    put("depth", processedEvent[Events.depth])
                                    put("hashes", Json.parseToJsonElement(processedEvent[Events.hashes]).jsonObject)
                                    put("signatures", Json.parseToJsonElement(processedEvent[Events.signatures]).jsonObject)
                                    put("origin_server_ts", processedEvent[Events.originServerTs])
                                    if (processedEvent[Events.stateKey] != null) put("state_key", processedEvent[Events.stateKey])
                                    if (processedEvent[Events.unsigned] != null) put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                                }
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Send leave v2 error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("errcode", "M_BAD_JSON")
                                put("error", "Invalid JSON")
                            })
                        }
                    }
                }
            }
        }
    }
}
