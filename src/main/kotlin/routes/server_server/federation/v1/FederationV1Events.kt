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
            
            // Get the auth chain for the specified event
            val authChain = transaction {
                val authEvents = mutableListOf<JsonObject>()
                val event = Events.select { Events.eventId eq eventId }.singleOrNull()
                
                if (event != null) {
                    // Get auth_events from the event
                    val authEventsJson = event[Events.authEvents]
                    val authEventIds = try {
                        Json.parseToJsonElement(authEventsJson).jsonArray.mapNotNull {
                            it.jsonArray.getOrNull(0)?.jsonPrimitive?.content
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                    
                    // Fetch all auth events recursively
                    val visited = mutableSetOf<String>()
                    val queue = ArrayDeque(authEventIds)
                    
                    while (queue.isNotEmpty()) {
                        val currentEventId = queue.removeFirst()
                        if (currentEventId in visited) continue
                        visited.add(currentEventId)
                        
                        val authEvent = Events.select { Events.eventId eq currentEventId }.singleOrNull()
                        if (authEvent != null) {
                            // Build the event JSON
                            val eventJson = buildJsonObject {
                                put("event_id", authEvent[Events.eventId])
                                put("room_id", authEvent[Events.roomId])
                                put("type", authEvent[Events.type])
                                put("sender", authEvent[Events.sender])
                                put("content", Json.parseToJsonElement(authEvent[Events.content]))
                                put("origin_server_ts", authEvent[Events.originServerTs])
                                authEvent[Events.stateKey]?.let { put("state_key", it) }
                                put("prev_events", Json.parseToJsonElement(authEvent[Events.prevEvents]))
                                put("auth_events", Json.parseToJsonElement(authEvent[Events.authEvents]))
                                put("depth", authEvent[Events.depth])
                                put("hashes", Json.parseToJsonElement(authEvent[Events.hashes]))
                                put("signatures", Json.parseToJsonElement(authEvent[Events.signatures]))
                            }
                            authEvents.add(eventJson)
                            
                            // Add this event's auth events to queue
                            try {
                                val nestedAuthEventIds = Json.parseToJsonElement(authEvent[Events.authEvents]).jsonArray.mapNotNull {
                                    it.jsonArray.getOrNull(0)?.jsonPrimitive?.content
                                }
                                queue.addAll(nestedAuthEventIds)
                            } catch (e: Exception) {
                                // Ignore parse errors
                            }
                        }
                    }
                }
                authEvents
            }
            
            call.respond(buildJsonObject {
                put("origin", utils.ServerNameResolver.getServerName())
                put("origin_server_ts", System.currentTimeMillis())
                putJsonArray("auth_chain") {
                    authChain.forEach { add(it) }
                }
            })
        }
    }
    get("/backfill/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        val minDepth = call.request.queryParameters["min_depth"]?.toIntOrNull() ?: 0

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
                                put("state_key", row[Events.stateKey])
                                if (row[Events.unsigned] != null) {
                                    put("unsigned", Json.parseToJsonElement(row[Events.unsigned]!!).jsonObject)
                                }
                            }
                        } catch (e: Exception) {
                            println("Error parsing event ${row[Events.eventId]}: ${e.message}")
                            null
                        }
                    }.filterNotNull()
            }

            call.respond(buildJsonObject {
                put("origin", "localhost")
                put("origin_server_ts", System.currentTimeMillis())
                put("pdus", Json.encodeToJsonElement(events))
            })
        } catch (e: Exception) {
            println("Backfill error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", e.message ?: "Unknown error")
            })
        }
    }
    post("/get_missing_events/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@post
        }

        // Check Server ACL
        val serverName = extractServerNameFromAuth(authHeader)
        if (serverName != null && !checkServerACL(roomId, serverName)) {
            call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                put("errcode", "M_FORBIDDEN")
                put("error", "Server access denied by ACL")
            })
            return@post
        }

        try {
            val requestBody = call.receiveText()
            val requestJson = Json.parseToJsonElement(requestBody).jsonObject

            val earliestEvents = requestJson["earliest_events"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val latestEvents = requestJson["latest_events"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val limit = requestJson["limit"]?.jsonPrimitive?.int ?: 10

            if (earliestEvents.isEmpty() || latestEvents.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing earliest_events or latest_events")
                })
            }

            // Find missing events using a breadth-first search
            val missingEvents = findMissingEvents(roomId, earliestEvents, latestEvents, limit)

            // Per spec, include origin and origin_server_ts and return PDUs
            call.respond(buildJsonObject {
                put("origin", utils.ServerNameResolver.getServerName())
                put("origin_server_ts", System.currentTimeMillis())
                putJsonArray("pdus") {
                    missingEvents.forEach { add(Json.encodeToJsonElement(it)) }
                }
            })
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
    get("/event/{eventId}") {
        val eventId = call.parameters["eventId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        val event = transaction {
            Events.select { Events.eventId eq eventId }.singleOrNull()
        }

        if (event == null) {
            call.respond(HttpStatusCode.NotFound, buildJsonObject {
                put("errcode", "M_NOT_FOUND")
                put("error", "Event not found")
            })
            return@get
        }

        // Convert database row to event format
        val eventData = buildJsonObject {
            put("event_id", event[Events.eventId])
            put("type", event[Events.type])
            put("room_id", event[Events.roomId])
            put("sender", event[Events.sender])
            put("content", Json.parseToJsonElement(event[Events.content]).jsonObject)
            put("auth_events", Json.parseToJsonElement(event[Events.authEvents]).jsonArray)
            put("prev_events", Json.parseToJsonElement(event[Events.prevEvents]).jsonArray)
            put("depth", event[Events.depth])
            put("hashes", Json.parseToJsonElement(event[Events.hashes]).jsonObject)
            put("signatures", Json.parseToJsonElement(event[Events.signatures]).jsonObject)
            put("origin_server_ts", event[Events.originServerTs])
            put("state_key", event[Events.stateKey])
            if (event[Events.unsigned] != null) {
                put("unsigned", Json.parseToJsonElement(event[Events.unsigned]!!).jsonObject)
            }
        }

        call.respond(eventData)
    }
    get("/timestamp_to_event/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

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

        val ts = call.request.queryParameters["ts"]?.toLongOrNull()
        val dir = call.request.queryParameters["dir"] ?: "f"

        if (ts == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing or invalid ts parameter")
            })
            return@get
        }

        if (dir !in setOf("f", "b")) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Invalid dir parameter")
            })
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
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No event found near timestamp")
                })
                return@get
            }

            // Convert to event format
            val eventData = buildJsonObject {
                put("event_id", event[Events.eventId])
                put("origin_server_ts", event[Events.originServerTs])
            }

            call.respond(eventData)
        } catch (e: Exception) {
            println("Timestamp to event error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
}
