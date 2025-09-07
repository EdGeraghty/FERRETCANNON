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
import models.Rooms
import utils.StateResolver
import utils.MatrixAuth

fun Route.federationV1Membership() {
    get("/make_join/{roomId}/{userId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

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
            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
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
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_ALREADY_JOINED")
                        put("error", "User is already joined or invited")
                    })
                    return@get
                }
            }

            // Check join rules
            val joinRules = roomState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
            if (joinRules == "invite") {
                // Check if user has invite
                val inviteMembership = roomState["m.room.member:$userId"]
                if (inviteMembership?.get("membership")?.jsonPrimitive?.content != "invite") {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_FORBIDDEN")
                        put("error", "Room requires invite")
                    })
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
            val joinEvent = buildJsonObject {
                put("event_id", tempEventId)
                put("type", "m.room.member")
                put("room_id", roomId)
                put("sender", userId)
                putJsonObject("content") {
                    put("membership", "join")
                }
                put("state_key", userId)
                put("origin_server_ts", System.currentTimeMillis())
                put("depth", depth)
                put("prev_events", Json.encodeToJsonElement(prevEvents))
                put("auth_events", Json.encodeToJsonElement(authEvents))
                put("origin", "localhost")
            }

            call.respond(joinEvent)
        } catch (e: Exception) {
            println("Make join error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
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

            // Return the event
            val eventData = buildJsonObject {
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
                put("state_key", processedEvent[Events.stateKey])
                if (processedEvent[Events.unsigned] != null) {
                    put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                }
            }

            call.respond(eventData)
        } catch (e: Exception) {
            println("Send join error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
    get("/make_knock/{roomId}/{userId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

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
            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
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
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_ALREADY_JOINED")
                        put("error", "User is already joined, invited, or knocking")
                    })
                    return@get
                }
            }

            // Check join rules - knocking is allowed for rooms that allow it
            val joinRules = roomState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
            if (joinRules == "public") {
                // For public rooms, users can join directly, no need to knock
                call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                    put("errcode", "M_CANNOT_KNOCK")
                    put("error", "Room is public, use make_join instead")
                })
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
            val knockEvent = buildJsonObject {
                put("event_id", tempEventId)
                put("type", "m.room.member")
                put("room_id", roomId)
                put("sender", userId)
                putJsonObject("content") {
                    put("membership", "knock")
                }
                put("state_key", userId)
                put("origin_server_ts", System.currentTimeMillis())
                put("depth", depth)
                put("prev_events", Json.encodeToJsonElement(prevEvents))
                put("auth_events", Json.encodeToJsonElement(authEvents))
                put("origin", "localhost")
            }

            call.respond(knockEvent)
        } catch (e: Exception) {
            println("Make knock error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
    put("/send_knock/{roomId}/{eventId}") {
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
            val knockEvent = Json.parseToJsonElement(body).jsonObject

            // Validate the event
            if (knockEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Event ID mismatch")
                })
                return@put
            }

            if (knockEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Room ID mismatch")
                })
                return@put
            }

            if (knockEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid event type")
                })
                return@put
            }

            val membership = knockEvent["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content
            if (membership != "knock") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid membership type")
                })
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
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("errcode", "M_UNKNOWN")
                    put("error", "Failed to store event")
                })
                return@put
            }

            // Return the event
            val eventData = buildJsonObject {
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
                put("state_key", processedEvent[Events.stateKey])
                if (processedEvent[Events.unsigned] != null) {
                    put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                }
            }

            call.respond(eventData)
        } catch (e: Exception) {
            println("Send knock error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
    put("/invite/{roomId}/{eventId}") {
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
            val inviteEvent = Json.parseToJsonElement(body).jsonObject

            // Validate the event
            if (inviteEvent["event_id"]?.jsonPrimitive?.content != eventId) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Event ID mismatch")
                })
                return@put
            }

            if (inviteEvent["room_id"]?.jsonPrimitive?.content != roomId) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Room ID mismatch")
                })
                return@put
            }

            if (inviteEvent["type"]?.jsonPrimitive?.content != "m.room.member") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid event type")
                })
                return@put
            }

            val membership = inviteEvent["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content
            if (membership != "invite") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid membership type")
                })
                return@put
            }

            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
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
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_ALREADY_JOINED")
                        put("error", "User is already joined or invited")
                    })
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
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("errcode", "M_UNKNOWN")
                    put("error", "Failed to store event")
                })
                return@put
            }

            // Return the event
            val eventData = buildJsonObject {
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
                put("state_key", processedEvent[Events.stateKey])
                if (processedEvent[Events.unsigned] != null) {
                    put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                }
            }

            call.respond(eventData)
        } catch (e: Exception) {
            println("Invite error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
    get("/make_leave/{roomId}/{userId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

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
            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
                return@get
            }

            // Get current state to validate leave
            val roomState = stateResolver.getResolvedState(roomId)

            // Check if user is actually in the room
            val membershipKey = "m.room.member:$userId"
            val existingMembership = roomState[membershipKey]
            if (existingMembership == null) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                    put("errcode", "M_NOT_MEMBER")
                    put("error", "User is not a member of this room")
                })
                return@get
            }

            val currentMembership = existingMembership["membership"]?.jsonPrimitive?.content
            if (currentMembership == "leave" || currentMembership == "ban") {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                    put("errcode", "M_ALREADY_LEFT")
                    put("error", "User has already left or been banned from this room")
                })
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
            val leaveEvent = buildJsonObject {
                put("event_id", tempEventId)
                put("type", "m.room.member")
                put("room_id", roomId)
                put("sender", userId)
                putJsonObject("content") {
                    put("membership", "leave")
                }
                put("state_key", userId)
                put("origin_server_ts", System.currentTimeMillis())
                put("depth", depth)
                put("prev_events", Json.encodeToJsonElement(prevEvents))
                put("auth_events", Json.encodeToJsonElement(authEvents))
                put("origin", "localhost")
            }

            call.respond(leaveEvent)
        } catch (e: Exception) {
            println("Make leave error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
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

            val membership = leaveEvent["content"]?.jsonObject?.get("membership")?.jsonPrimitive?.content
            if (membership != "leave") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid membership type")
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

            // Return the event
            val eventData = buildJsonObject {
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
                put("state_key", processedEvent[Events.stateKey])
                if (processedEvent[Events.unsigned] != null) {
                    put("unsigned", Json.parseToJsonElement(processedEvent[Events.unsigned]!!).jsonObject)
                }
            }

            call.respond(eventData)
        } catch (e: Exception) {
            println("Send leave error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
}
