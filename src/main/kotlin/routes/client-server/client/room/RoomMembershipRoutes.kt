package routes.client_server.client.room

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
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
                        println("Sending federation invite to $inviteeServer for room $roomId")
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

            // Check current membership FIRST (before checking if room exists locally)
            // This is crucial for federation - we might have an invite to a remote room
            val (currentMembership, inviteSender) = transaction {
                val memberEvent = Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.orderBy(Events.originServerTs, SortOrder.DESC).limit(1).singleOrNull()
                
                if (memberEvent != null) {
                    val content = Json.parseToJsonElement(memberEvent[Events.content]).jsonObject
                    val membership = content["membership"]?.jsonPrimitive?.content
                    val sender = memberEvent[Events.sender]
                    Pair(membership, sender)
                } else {
                    Pair(null, null)
                }
            }

            if (currentMembership == "join") {
                call.respond(mutableMapOf(
                    "room_id" to roomId
                ))
                return@post
            }
            
            // If user has a pending invite, handle federated join
            if (currentMembership == "invite" && inviteSender != null) {
                println("User $userId has invite from $inviteSender, performing federated join")
                
                // Extract the server name from the inviter's user ID
                val inviterServer = inviteSender.substringAfter(":")
                println("Attempting federated join via server: $inviterServer")
                
                // Get the invite event to extract necessary information
                val inviteEvent = transaction {
                    Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.member") and
                        (Events.stateKey eq userId) and
                        (Events.sender eq inviteSender)
                    }.orderBy(Events.originServerTs, SortOrder.DESC).limit(1).singleOrNull()
                }
                
                if (inviteEvent == null) {
                    call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                        "errcode" to "M_UNKNOWN",
                        "error" to "Invite event not found"
                    ))
                    return@post
                }
                
                // Create the join event
                val currentTime = System.currentTimeMillis()
                val eventId = "\$${java.util.UUID.randomUUID()}"
                
                // Get auth_events and prev_events from the invite
                val inviteEventId = inviteEvent[Events.eventId]
                val authEventsJson = inviteEvent[Events.authEvents]
                val depth = inviteEvent[Events.depth] + 1
                
                val joinEventContent = buildJsonObject {
                    put("membership", "join")
                }
                
                val joinEvent = buildJsonObject {
                    put("type", "m.room.member")
                    put("state_key", userId)
                    put("sender", userId)
                    put("room_id", roomId)
                    put("content", joinEventContent)
                    put("origin_server_ts", currentTime)
                    put("auth_events", Json.parseToJsonElement(authEventsJson))
                    put("prev_events", buildJsonArray { add(buildJsonArray { add(inviteEventId); add(buildJsonObject {}) }) })
                    put("depth", depth)
                }
                
                // Hash and sign the event
                val signedJoinEvent = MatrixAuth.hashAndSignEvent(joinEvent, config.federation.serverName)
                
                // Add event_id
                val finalJoinEvent = signedJoinEvent.toMutableMap()
                finalJoinEvent["event_id"] = JsonPrimitive(eventId)
                val finalJoinEventObj = JsonObject(finalJoinEvent)
                
                // Send to remote server via federation
                val httpClient = HttpClient(OkHttp) {
                    install(ContentNegotiation) {
                        json(Json { 
                            ignoreUnknownKeys = true 
                        })
                    }
                }
                
                try {
                    // Resolve the inviter server using .well-known delegation
                    val serverDetails = utils.ServerDiscovery.resolveServerName(inviterServer)
                    if (serverDetails == null) {
                        println("Failed to resolve server: $inviterServer")
                        httpClient.close()
                        call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                            "errcode" to "M_UNKNOWN",
                            "error" to "Failed to resolve federation server: $inviterServer"
                        ))
                        return@post
                    }
                    
                    val federationUrl = "https://${serverDetails.host}:${serverDetails.port}/_matrix/federation/v2/send_join/$roomId/$eventId"
                    println("Resolved $inviterServer to ${serverDetails.host}:${serverDetails.port}")
                    println("Sending federation join to: $federationUrl")
                    
                    // Create authorization header for federation request
                    val requestJson = buildJsonObject {
                        put("method", "PUT")
                        put("uri", "/_matrix/federation/v2/send_join/$roomId/$eventId")
                        put("origin", config.federation.serverName)
                        put("destination", inviterServer)
                    }
                    val signature = utils.MatrixAuth.signJson(requestJson)
                    val keyId = utils.ServerKeys.getKeyId()
                    val authHeader = "X-Matrix origin=\"${config.federation.serverName}\",key=\"$keyId\",sig=\"$signature\""
                    println("Authorization header: $authHeader")
                    
                    val response = httpClient.put(federationUrl) {
                        header("Authorization", authHeader)
                        contentType(ContentType.Application.Json)
                        setBody(finalJoinEventObj.toString())
                    }
                    
                    if (!response.status.isSuccess()) {
                        val errorBody = response.bodyAsText()
                        println("Federation join failed: ${response.status} - $errorBody")
                        httpClient.close()
                        call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                            "errcode" to "M_UNKNOWN",
                            "error" to "Federation join failed: ${response.status}"
                        ))
                        return@post
                    }
                    
                    val responseBody = response.bodyAsText()
                    println("Federation join response: $responseBody")
                    val responseJson = Json.parseToJsonElement(responseBody).jsonObject
                    
                    // Extract state and auth_chain from response
                    val stateEvents = responseJson["state"]?.jsonArray ?: JsonArray(emptyList())
                    val authChain = responseJson["auth_chain"]?.jsonArray ?: JsonArray(emptyList())
                    
                    // Store all state events in database
                    transaction {
                        // First, create the room entry
                        Rooms.insertIgnore {
                            it[Rooms.roomId] = roomId
                            it[Rooms.creator] = "" // Will be updated from m.room.create event
                            it[Rooms.name] = null
                            it[Rooms.topic] = null
                            it[Rooms.visibility] = "private"
                            it[Rooms.roomVersion] = "11"
                            it[Rooms.isDirect] = false
                            it[Rooms.currentState] = "{}"
                            it[Rooms.stateGroups] = "{}"
                            it[Rooms.published] = false
                        }
                        
                        // Store our join event
                        val joinHashes = finalJoinEventObj["hashes"]?.toString() ?: "{}"
                        val joinSignatures = finalJoinEventObj["signatures"]?.toString() ?: "{}"
                        
                        Events.insertIgnore {
                            it[Events.eventId] = eventId
                            it[Events.roomId] = roomId
                            it[Events.type] = "m.room.member"
                            it[Events.sender] = userId
                            it[Events.content] = joinEventContent.toString()
                            it[Events.authEvents] = authEventsJson
                            it[Events.prevEvents] = buildJsonArray { add(buildJsonArray { add(inviteEventId); add(buildJsonObject {}) }) }.toString()
                            it[Events.depth] = depth.toInt()
                            it[Events.hashes] = joinHashes
                            it[Events.signatures] = joinSignatures
                            it[Events.originServerTs] = currentTime
                            it[Events.stateKey] = userId
                            it[Events.unsigned] = "{}"
                            it[Events.softFailed] = false
                            it[Events.outlier] = false
                        }
                        
                        // Store state events
                        for (stateEventElement in stateEvents) {
                            val stateEvent = stateEventElement.jsonObject
                            val evId = stateEvent["event_id"]?.jsonPrimitive?.content ?: continue
                            
                            val stateType = stateEvent["type"]?.jsonPrimitive?.content ?: ""
                            val stateSender = stateEvent["sender"]?.jsonPrimitive?.content ?: ""
                            val stateContent = stateEvent["content"]?.toString() ?: "{}"
                            val stateAuthEvents = stateEvent["auth_events"]?.toString() ?: "[]"
                            val statePrevEvents = stateEvent["prev_events"]?.toString() ?: "[]"
                            val stateDepth = (stateEvent["depth"]?.jsonPrimitive?.long ?: 0).toInt()
                            val stateHashes = stateEvent["hashes"]?.toString() ?: "{}"
                            val stateSignatures = stateEvent["signatures"]?.toString() ?: "{}"
                            val stateOriginTs = stateEvent["origin_server_ts"]?.jsonPrimitive?.long ?: currentTime
                            val stateKey = stateEvent["state_key"]?.jsonPrimitive?.content
                            val stateUnsigned = stateEvent["unsigned"]?.toString() ?: "{}"
                            
                            Events.insertIgnore {
                                it[Events.eventId] = evId
                                it[Events.roomId] = roomId
                                it[Events.type] = stateType
                                it[Events.sender] = stateSender
                                it[Events.content] = stateContent
                                it[Events.authEvents] = stateAuthEvents
                                it[Events.prevEvents] = statePrevEvents
                                it[Events.depth] = stateDepth
                                it[Events.hashes] = stateHashes
                                it[Events.signatures] = stateSignatures
                                it[Events.originServerTs] = stateOriginTs
                                it[Events.stateKey] = stateKey
                                it[Events.unsigned] = stateUnsigned
                                it[Events.softFailed] = false
                                it[Events.outlier] = false
                            }
                        }
                        
                        // Store auth chain events
                        for (authEventElement in authChain) {
                            val authEvent = authEventElement.jsonObject
                            val evId = authEvent["event_id"]?.jsonPrimitive?.content ?: continue
                            
                            val authType = authEvent["type"]?.jsonPrimitive?.content ?: ""
                            val authSender = authEvent["sender"]?.jsonPrimitive?.content ?: ""
                            val authContent = authEvent["content"]?.toString() ?: "{}"
                            val authAuthEvents = authEvent["auth_events"]?.toString() ?: "[]"
                            val authPrevEvents = authEvent["prev_events"]?.toString() ?: "[]"
                            val authDepth = (authEvent["depth"]?.jsonPrimitive?.long ?: 0).toInt()
                            val authHashes = authEvent["hashes"]?.toString() ?: "{}"
                            val authSignatures = authEvent["signatures"]?.toString() ?: "{}"
                            val authOriginTs = authEvent["origin_server_ts"]?.jsonPrimitive?.long ?: currentTime
                            val authStateKey = authEvent["state_key"]?.jsonPrimitive?.content
                            val authUnsigned = authEvent["unsigned"]?.toString() ?: "{}"
                            
                            Events.insertIgnore {
                                it[Events.eventId] = evId
                                it[Events.roomId] = roomId
                                it[Events.type] = authType
                                it[Events.sender] = authSender
                                it[Events.content] = authContent
                                it[Events.authEvents] = authAuthEvents
                                it[Events.prevEvents] = authPrevEvents
                                it[Events.depth] = authDepth
                                it[Events.hashes] = authHashes
                                it[Events.signatures] = authSignatures
                                it[Events.originServerTs] = authOriginTs
                                it[Events.stateKey] = authStateKey
                                it[Events.unsigned] = authUnsigned
                                it[Events.softFailed] = false
                                it[Events.outlier] = false
                            }
                        }
                    }
                    
                    println("Successfully joined federated room $roomId")
                    httpClient.close()
                    call.respond(mutableMapOf("room_id" to roomId))
                    return@post
                    
                } catch (e: Exception) {
                    println("Federation join error: ${e.message}")
                    e.printStackTrace()
                    httpClient.close()
                    call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                        "errcode" to "M_UNKNOWN",
                        "error" to "Federation join failed: ${e.message}"
                    ))
                    return@post
                }
            }

            // For local joins (no invite), check if room exists
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