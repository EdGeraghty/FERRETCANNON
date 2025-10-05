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

                            // Get current state for v2 response
                            val currentState: List<JsonObject> = transaction {
                                val stateEvents = Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.outlier eq false) and
                                    (Events.softFailed eq false)
                                }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(50) // Limit for performance
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
                                return@transaction stateEvents
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
                        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                        val eventId = call.parameters["eventId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

                        println("Federation invite received: roomId=$roomId, eventId=$eventId")

                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            println("Federation invite auth failed")
                            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                put("errcode", "M_UNAUTHORIZED")
                                put("error", "Invalid signature")
                            })
                            return@put
                        }

                        println("Federation invite auth succeeded")

                        // Check Server ACL
                        val serverName = extractServerNameFromAuth(authHeader)
                        println("Federation invite: checking ACL for server $serverName in room $roomId")
                        if (serverName != null && !checkServerACL(roomId, serverName)) {
                            println("Federation invite: ACL denied for server $serverName")
                            call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                                put("errcode", "M_FORBIDDEN")
                                put("error", "Server access denied by ACL")
                            })
                            return@put
                        }
                        println("Federation invite: ACL check passed for server $serverName")

                        try {
                            println("Federation invite: about to parse body JSON")
                            val inviteEvent = Json.parseToJsonElement(body).jsonObject
                            println("Federation invite: parsed body JSON successfully")

                            // Validate the event
                            println("Federation invite: validating event structure")
                            val nestedEvent = inviteEvent["event"]?.jsonObject
                            if (nestedEvent == null) {
                                println("Federation invite: Missing nested event structure")
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Missing event structure")
                                })
                                return@put
                            }
                            println("Federation invite: event structure validation passed")

                            println("Federation invite: validating room_id")
                            val roomIdFromEvent = nestedEvent["room_id"]?.jsonPrimitive?.content
                            if (roomIdFromEvent != roomId) {
                                println("Federation invite: Room ID mismatch - expected: $roomId, got: $roomIdFromEvent")
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Room ID mismatch")
                                })
                                return@put
                            }
                            println("Federation invite: room_id validation passed")

                            println("Federation invite: validating event type")
                            val eventType = nestedEvent["type"]?.jsonPrimitive?.content
                            if (eventType != "m.room.member") {
                                println("Federation invite: Invalid event type - expected: m.room.member, got: $eventType")
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_INVALID_PARAM")
                                    put("error", "Invalid event type")
                                })
                                return@put
                            }
                            println("Federation invite: event type validation passed")

                            // Extract invite_room_state for auth chain
                            val inviteRoomState = inviteEvent["invite_room_state"]?.jsonArray
                            println("Federation invite: invite_room_state present: ${inviteRoomState != null}, count: ${inviteRoomState?.size ?: 0}")
                            
                            // Store the invite_room_state events temporarily for auth checks
                            // These are stripped state events that provide context for authorization
                            if (inviteRoomState != null) {
                                transaction {
                                    for (strippedEvent in inviteRoomState) {
                                        val strippedObj = strippedEvent.jsonObject
                                        val type = strippedObj["type"]?.jsonPrimitive?.content ?: continue
                                        val sender = strippedObj["sender"]?.jsonPrimitive?.content ?: continue
                                        val stateKey = strippedObj["state_key"]?.jsonPrimitive?.content ?: ""
                                        val content = strippedObj["content"]?.jsonObject ?: continue
                                        
                                        // Generate a synthetic event ID for these stripped events
                                        val syntheticEventId = "\$stripped_${type}_${stateKey}_${System.currentTimeMillis()}"
                                        
                                        println("Federation invite: storing stripped state event type=$type, state_key=$stateKey")
                                        
                                        // Check if already exists
                                        val existing = Events.select { 
                                            (Events.roomId eq roomId) and 
                                            (Events.type eq type) and 
                                            (Events.stateKey eq stateKey) and
                                            (Events.outlier eq true)
                                        }.singleOrNull()
                                        
                                        if (existing == null) {
                                            Events.insert {
                                                it[Events.eventId] = syntheticEventId
                                                it[Events.roomId] = roomId
                                                it[Events.type] = type
                                                it[Events.sender] = sender
                                                it[Events.content] = content.toString()
                                                it[Events.authEvents] = "[]"
                                                it[Events.prevEvents] = "[]"
                                                it[Events.depth] = 0
                                                it[Events.hashes] = "{}"
                                                it[Events.signatures] = "{}"
                                                it[Events.originServerTs] = System.currentTimeMillis()
                                                it[Events.stateKey] = stateKey
                                                it[Events.unsigned] = null
                                                it[Events.softFailed] = false
                                                it[Events.outlier] = true // Mark as outlier since it's from invite_room_state
                                            }
                                        }
                                    }
                                }
                            }

                            println("Federation invite: event validation passed, calling processPDU for $eventId")
                            // Process the invite event as a PDU - extract the nested event and add event_id
                            println("Federation invite: original nested event structure: ${nestedEvent.keys}")
                            println("Federation invite: original nested event has event_id: ${nestedEvent.containsKey("event_id")}")
                            
                            // Pass the original event without adding event_id for proper hash verification
                            println("Federation invite: calling processPDU with original event structure: ${nestedEvent.keys}")
                            val result = processPDU(nestedEvent, eventId)
                            if (result != null) {
                                println("Federation invite processPDU failed: $result")
                                call.respond(HttpStatusCode.BadRequest, result)
                                return@put
                            }

                            println("Federation invite processPDU succeeded")

                            // Get the processed event from database
                            println("Federation invite: fetching processed event from database")
                            val processedEvent = transaction {
                                Events.select { Events.eventId eq eventId }.singleOrNull()
                            }

                            if (processedEvent == null) {
                                println("Federation invite: ERROR - event not found in database after processPDU!")
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Failed to store event")
                                })
                                return@put
                            }

                            println("Federation invite: building response event object")
                            // Return the v2 response format: {"event": ...}
                            // Note: v2 returns just the object, NOT a tuple like v1
                            val eventObject = buildJsonObject {
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
                            
                            println("Federation invite: building final response wrapper")
                            val response = buildJsonObject {
                                put("event", eventObject)
                            }

                            println("Federation invite: sending 200 OK response")
                            call.respond(HttpStatusCode.OK, response)
                            println("Federation invite: response sent successfully")
                        } catch (e: Exception) {
                            println("Invite v2 error: ${e.message}")
                            println("Invite v2 stack trace: ${e.stackTraceToString()}")
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("errcode", "M_BAD_JSON")
                                put("error", "Invalid JSON: ${e.message}")
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
