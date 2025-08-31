package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.util.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import models.Events
import models.Rooms
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import kotlinx.serialization.json.*
import utils.users
import utils.typingMap
import utils.connectedClients
import routes.server_server.federation.v1.broadcastEDU
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import models.AccountData
import io.ktor.websocket.Frame

fun Application.clientRoutes() {
    // Request size limiting - simplified version
    intercept(ApplicationCallPipeline.Call) {
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > 1024 * 1024) { // 1MB limit
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "errcode" to "M_TOO_LARGE",
                "error" to "Request too large"
            ))
            finish()
        }
    }

    install(Authentication) {
        bearer("matrix-auth") {
            authenticate { tokenCredential ->
                // Simple token validation - in real implementation, validate against DB
                val userId = users.entries.find { it.value == tokenCredential.token }?.key
                if (userId != null) {
                    UserIdPrincipal(userId)
                } else {
                    null
                }
            }
        }
    }

    // Enhanced authentication middleware for Matrix access tokens
    intercept(ApplicationCallPipeline.Call) {
        // Extract access token from multiple sources as per Matrix spec
        val accessToken = call.request.queryParameters["access_token"] ?:
                         call.request.headers["Authorization"]?.removePrefix("Bearer ") ?:
                         call.request.headers["Authorization"]?.removePrefix("Bearer")?.trim()

        if (accessToken != null) {
            // Validate token format and lookup user
            val userId = users.entries.find { it.value == accessToken }?.key

            if (userId != null) {
                // Store authenticated user information
                call.attributes.put(AttributeKey("matrix-user"), UserIdPrincipal(userId))
                call.attributes.put(AttributeKey("matrix-token"), accessToken)
                call.attributes.put(AttributeKey("matrix-user-id"), userId)

                // Extract device ID from token (in a real implementation, this would be stored separately)
                val deviceId = accessToken.substringAfter("token_").substringAfter("_").let { "device_$it" }
                call.attributes.put(AttributeKey("matrix-device-id"), deviceId)
            } else {
                // Invalid token - will be handled by individual endpoints
                call.attributes.put(AttributeKey("matrix-invalid-token"), accessToken)
            }
        } else {
            // No token provided - will be handled by individual endpoints
            call.attributes.put(AttributeKey("matrix-no-token"), true)
        }
    }

    // Helper function for token validation and error responses
    suspend fun ApplicationCall.validateAccessToken(): String? {
        val accessToken = attributes.getOrNull(AttributeKey<String>("matrix-token"))
        val invalidToken = attributes.getOrNull(AttributeKey<String>("matrix-invalid-token"))
        val noToken = attributes.getOrNull(AttributeKey<Boolean>("matrix-no-token"))

        return when {
            noToken == true -> {
                respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                null
            }
            invalidToken != null -> {
                respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_UNKNOWN_TOKEN",
                    "error" to "Unrecognised access token"
                ))
                null
            }
            accessToken == null -> {
                respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                null
            }
            else -> accessToken
        }
    }

    // Helper function to get authenticated user information
    fun ApplicationCall.getAuthenticatedUser(): Triple<String, String, String>? {
        val userId = attributes.getOrNull(AttributeKey<String>("matrix-user-id"))
        val deviceId = attributes.getOrNull(AttributeKey<String>("matrix-device-id"))
        val token = attributes.getOrNull(AttributeKey<String>("matrix-token"))

        return if (userId != null && deviceId != null && token != null) {
            Triple(userId, deviceId, token)
        } else null
    }

    routing {
        // Helper function to broadcast events to room clients
        suspend fun broadcastEvent(roomId: String, event: Map<String, Any>) {
            val message = Json.encodeToString(JsonObject.serializer(), JsonObject(event.mapValues { JsonPrimitive(it.value.toString()) }))
            connectedClients[roomId]?.forEach { session ->
                try {
                    session.send(Frame.Text(message))
                } catch (e: Exception) {
                    // Client disconnected
                }
            }
        }

        route("/_matrix") {
            route("/client") {
                route("/v3") {
                    get("/login") {
                        // Get available login flows
                        call.respond(mapOf(
                            "flows" to listOf(
                                mapOf(
                                    "type" to "m.login.password",
                                    "get_login_token" to true
                                ),
                                mapOf(
                                    "type" to "m.login.token"
                                ),
                                mapOf(
                                    "type" to "m.login.oauth2"
                                ),
                                mapOf(
                                    "type" to "m.login.sso",
                                    "identity_providers" to listOf(
                                        mapOf(
                                            "id" to "oidc",
                                            "name" to "OpenID Connect",
                                            "icon" to null,
                                            "brand" to null
                                        ),
                                        mapOf(
                                            "id" to "oauth2",
                                            "name" to "OAuth 2.0",
                                            "icon" to null,
                                            "brand" to null
                                        ),
                                        mapOf(
                                            "id" to "saml",
                                            "name" to "SAML",
                                            "icon" to null,
                                            "brand" to null
                                        )
                                    )
                                ),
                                mapOf(
                                    "type" to "m.login.application_service"
                                )
                            )
                        ))
                    }

                    // Server capabilities endpoint
                    get("/capabilities") {
                        call.respond(mapOf(
                            "capabilities" to mapOf(
                                "m.change_password" to mapOf(
                                    "enabled" to true
                                ),
                                "m.room_versions" to mapOf(
                                    "default" to "9",
                                    "available" to mapOf(
                                        "1" to "stable",
                                        "2" to "stable",
                                        "3" to "stable",
                                        "4" to "stable",
                                        "5" to "stable",
                                        "6" to "stable",
                                        "7" to "stable",
                                        "8" to "stable",
                                        "9" to "stable"
                                    )
                                ),
                                "m.set_displayname" to mapOf(
                                    "enabled" to true
                                ),
                                "m.set_avatar_url" to mapOf(
                                    "enabled" to true
                                ),
                                "m.3pid_changes" to mapOf(
                                    "enabled" to false
                                ),
                                "m.get_login_token" to mapOf(
                                    "enabled" to true
                                )
                            )
                        ))
                    }

                    // Login fallback for clients that don't support the API
                    get("/login/fallback") {
                        // Return HTML page for fallback login
                        call.respondText("""
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <title>Matrix Login</title>
                                <style>
                                    body { font-family: Arial, sans-serif; margin: 40px; }
                                    .login-form { max-width: 300px; margin: 0 auto; }
                                    input { display: block; width: 100%; margin: 10px 0; padding: 8px; }
                                    button { background: #007acc; color: white; padding: 10px; border: none; width: 100%; }
                                </style>
                            </head>
                            <body>
                                <div class="login-form">
                                    <h2>Matrix Login</h2>
                                    <form method="POST" action="/_matrix/client/v3/login">
                                        <input type="hidden" name="type" value="m.login.password">
                                        <input type="text" name="user" placeholder="Username" required>
                                        <input type="password" name="password" placeholder="Password" required>
                                        <input type="text" name="device_id" placeholder="Device ID (optional)">
                                        <button type="submit">Login</button>
                                    </form>
                                </div>
                            </body>
                            </html>
                        """.trimIndent(), ContentType.Text.Html)
                    }

                    // PUT /rooms/{roomId}/typing/{userId} - Send typing notification
                    put("/rooms/{roomId}/typing/{userId}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.parameters["userId"]

                            if (roomId == null || userId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_MISSING_PARAM",
                                    "error" to "Missing roomId or userId parameter"
                                ))
                                return@put
                            }

                            // Validate access token
                            val accessToken = call.validateAccessToken() ?: return@put

                            // Validate that the authenticated user matches the userId in the path
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot set typing status for other users"
                                ))
                                return@put
                            }

                            // Parse request body
                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val typing = json["typing"]?.jsonPrimitive?.boolean ?: false
                            val timeout = json["timeout"]?.jsonPrimitive?.long ?: 30000

                            // Update typing status in shared state
                            val currentTime = System.currentTimeMillis()
                            val expiryTime = if (typing) currentTime + timeout else currentTime

                            // Initialize room typing map if it doesn't exist
                            if (!typingMap.containsKey(roomId)) {
                                typingMap[roomId] = mutableMapOf()
                            }

                            if (typing) {
                                typingMap[roomId]!![userId] = expiryTime
                            } else {
                                typingMap[roomId]!!.remove(userId)
                                // Clean up empty room entries
                                if (typingMap[roomId]!!.isEmpty()) {
                                    typingMap.remove(roomId)
                                }
                            }

                            // Broadcast typing status change via WebSocket
                            val typingEvent = JsonObject(mapOf(
                                "type" to JsonPrimitive("m.typing"),
                                "room_id" to JsonPrimitive(roomId),
                                "content" to JsonObject(mapOf(
                                    "user_ids" to JsonArray(typingMap[roomId]?.keys?.map { JsonPrimitive(it) } ?: emptyList())
                                ))
                            ))

                            // Broadcast to all connected clients in the room
                            runBlocking {
                                broadcastEDU(roomId, typingEvent)
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())
                        } catch (e: Exception) {
                            when (e) {
                                is kotlinx.serialization.SerializationException -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_BAD_JSON",
                                        "error" to "Invalid JSON"
                                    ))
                                }
                                else -> {
                                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Internal server error"
                                    ))
                                }
                            }
                        }
                    }

                    // POST /rooms/{roomId}/upgrade - Upgrade room to new version
                    post("/rooms/{roomId}/upgrade") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))
                            val roomId = call.parameters["roomId"]

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@post
                            }

                            // Parse request body
                            val requestBody = call.receiveText()
                            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
                            val newVersion = jsonBody["new_version"]?.jsonPrimitive?.content

                            if (newVersion == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing new_version parameter"
                                ))
                                return@post
                            }

                            // Validate new version
                            val supportedVersions = setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")
                            if (newVersion !in supportedVersions) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_UNSUPPORTED_ROOM_VERSION",
                                    "error" to "Unsupported room version: $newVersion"
                                ))
                                return@post
                            }

                            // Check if user is joined to the room
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
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "User is not joined to this room"
                                ))
                                return@post
                            }

                            // Generate new room ID for upgraded room
                            val newRoomId = "!${System.currentTimeMillis()}_${userId.substringAfter("@").substringBefore(":")}:localhost"

                            // Create the upgraded room
                            transaction {
                                Rooms.insert {
                                    it[Rooms.roomId] = newRoomId
                                    it[Rooms.creator] = userId
                                    it[published] = false
                                }
                            }

                            // Add tombstone event to original room
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = "\$${System.currentTimeMillis()}_tombstone"
                                    it[Events.type] = "m.room.tombstone"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = ""
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                                        "body" to JsonPrimitive("This room has been replaced"),
                                        "replacement_room" to JsonPrimitive(newRoomId)
                                    )))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]" // Simplified
                                    it[Events.authEvents] = "[]" // Simplified
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(mapOf(
                                "replacement_room" to newRoomId
                            ))

                        } catch (e: Exception) {
                            when (e) {
                                is kotlinx.serialization.SerializationException -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_BAD_JSON",
                                        "error" to "Invalid JSON"
                                    ))
                                }
                                else -> {
                                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Internal server error"
                                    ))
                                }
                            }
                        }
                    }

                    // Sync endpoint - receives typing notifications and other events
                    get("/sync") {
                        try {
                            val userId = call.principal<UserIdPrincipal>()?.name
                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            val since = call.request.queryParameters["since"]
                            val timeout = call.request.queryParameters["timeout"]?.toLongOrNull() ?: 30000
                            val fullState = call.request.queryParameters["full_state"]?.toBoolean() ?: false
                            val setPresence = call.request.queryParameters["set_presence"]

                            // For demo purposes, we'll return a basic sync response
                            // In a real implementation, this would include actual room events, state, etc.

                            // Get current typing status for all rooms
                            val rooms = mutableMapOf<String, Any>()
                            val ephemeral = mutableMapOf<String, Any>()

                            // Check typing status for rooms the user is in
                            for ((roomId, typingUsers) in typingMap) {
                                // Clean up expired typing notifications
                                val currentTime = System.currentTimeMillis()
                                typingUsers.entries.removeIf { (_, expiryTime) ->
                                    currentTime > expiryTime
                                }

                                if (typingUsers.isNotEmpty()) {
                                    ephemeral[roomId] = mapOf(
                                        "events" to listOf(
                                            mapOf(
                                                "type" to "m.typing",
                                                "content" to mapOf(
                                                    "user_ids" to typingUsers.keys.toList()
                                                )
                                            )
                                        )
                                    )
                                }
                            }

                            // Get account data for the user
                            val globalAccountData = transaction {
                                AccountData.select {
                                    (AccountData.userId eq userId) and
                                    (AccountData.roomId.isNull())
                                }.map { row ->
                                    mapOf(
                                        "type" to row[AccountData.type],
                                        "content" to Json.parseToJsonElement(row[AccountData.content]).jsonObject
                                    )
                                }
                            }

                            // Get room-specific account data
                            val roomAccountData = transaction {
                                AccountData.select {
                                    (AccountData.userId eq userId) and
                                    (AccountData.roomId.isNotNull())
                                }.groupBy({ it[AccountData.roomId] }, { row ->
                                    mapOf(
                                        "type" to row[AccountData.type],
                                        "content" to Json.parseToJsonElement(row[AccountData.content]).jsonObject
                                    )
                                })
                            }

                            val response = mutableMapOf<String, Any>(
                                "next_batch" to System.currentTimeMillis().toString(),
                                "rooms" to mapOf(
                                    "join" to rooms,
                                    "invite" to emptyMap<String, Any>(),
                                    "leave" to emptyMap<String, Any>()
                                ),
                                "presence" to mapOf(
                                    "events" to emptyList<Map<String, Any>>()
                                )
                            )

                            // Add global account data if any
                            if (globalAccountData.isNotEmpty()) {
                                response["account_data"] = mapOf(
                                    "events" to globalAccountData
                                )
                            }

                            // Add ephemeral events and room account data if any
                            if (ephemeral.isNotEmpty() || roomAccountData.isNotEmpty()) {
                                val joinRooms = mutableMapOf<String, MutableMap<String, Any>>()

                                // Add ephemeral events
                                ephemeral.forEach { (roomId, events) ->
                                    joinRooms[roomId] = mutableMapOf("ephemeral" to events)
                                }

                                // Add room account data
                                roomAccountData.forEach { (roomId, accountData) ->
                                    if (roomId != null) {
                                        if (!joinRooms.containsKey(roomId)) {
                                            joinRooms[roomId] = mutableMapOf()
                                        }
                                        joinRooms[roomId]!!["account_data"] = mapOf("events" to accountData)
                                    }
                                }

                                response["rooms"] = mapOf(
                                    "join" to joinRooms,
                                    "invite" to emptyMap<String, Any>(),
                                    "leave" to emptyMap<String, Any>()
                                )
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /search - Search for events
                    post("/search") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            // Parse search request
                            val requestBody = call.receiveText()
                            val searchRequest = Json.parseToJsonElement(requestBody).jsonObject

                            val searchCategories = searchRequest["search_categories"]?.jsonObject
                            val roomEvents = searchCategories?.get("room_events")?.jsonObject

                            if (roomEvents == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing search_categories.room_events"
                                ))
                                return@post
                            }

                            val searchTerm = roomEvents["search_term"]?.jsonPrimitive?.content
                            val keys = roomEvents["keys"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("content.body")
                            val filter = roomEvents["filter"]?.jsonObject
                            val orderBy = roomEvents["order_by"]?.jsonPrimitive?.content ?: "recent"
                            val includeState = roomEvents["include_state"]?.jsonPrimitive?.boolean ?: false
                            val groupings = roomEvents["groupings"]?.jsonObject

                            if (searchTerm == null || searchTerm.isEmpty()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing or empty search_term"
                                ))
                                return@post
                            }

                            // Get rooms the user is joined to
                            val joinedRoomIds = transaction {
                                Events.select {
                                    (Events.type eq "m.room.member") and
                                    (Events.stateKey eq userId)
                                }.mapNotNull { row ->
                                    val content = Json.parseToJsonElement(row[Events.content]).jsonObject
                                    val membership = content["membership"]?.jsonPrimitive?.content
                                    if (membership == "join") row[Events.roomId] else null
                                }.distinct()
                            }

                            if (joinedRoomIds.isEmpty()) {
                                call.respond(mapOf(
                                    "search_categories" to mapOf(
                                        "room_events" to mapOf(
                                            "count" to 0,
                                            "results" to emptyList<Map<String, Any>>(),
                                            "highlights" to emptyList<String>(),
                                            "state" to emptyMap<String, Any>(),
                                            "groups" to emptyMap<String, Any>()
                                        )
                                    )
                                ))
                                return@post
                            }

                            // Search for events containing the search term
                            val matchingEvents = transaction {
                                Events.select {
                                    (Events.roomId inList joinedRoomIds) and
                                    (Events.type eq "m.room.message") and
                                    (Events.content.like("%$searchTerm%"))
                                }.orderBy(Events.originServerTs, SortOrder.DESC)
                                .map { row ->
                                    val content = Json.parseToJsonElement(row[Events.content]).jsonObject
                                    val body = content["body"]?.jsonPrimitive?.content ?: ""

                                    // Check if the search term appears in the specified keys
                                    val matches = keys.any { key ->
                                        when (key) {
                                            "content.body" -> body.contains(searchTerm, ignoreCase = true)
                                            else -> false // Simplified - only support body search for now
                                        }
                                    }

                                    if (matches) {
                                        mapOf(
                                            "rank" to 1.0, // Simplified ranking
                                            "result" to mapOf(
                                                "event_id" to row[Events.eventId],
                                                "type" to row[Events.type],
                                                "sender" to row[Events.sender],
                                                "origin_server_ts" to row[Events.originServerTs],
                                                "content" to content,
                                                "room_id" to row[Events.roomId]
                                            )
                                        )
                                    } else null
                                }.filterNotNull()
                            }

                            // Create highlights (simplified)
                            val highlights = if (matchingEvents.isNotEmpty()) {
                                listOf(searchTerm)
                            } else {
                                emptyList<String>()
                            }

                            val response = mapOf(
                                "search_categories" to mapOf(
                                    "room_events" to mapOf(
                                        "count" to matchingEvents.size,
                                        "results" to matchingEvents,
                                        "highlights" to highlights,
                                        "state" to emptyMap<String, Any>(),
                                        "groups" to emptyMap<String, Any>()
                                    )
                                )
                            )

                            call.respond(response)

                        } catch (e: Exception) {
                            when (e) {
                                is kotlinx.serialization.SerializationException -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_BAD_JSON",
                                        "error" to "Invalid JSON"
                                    ))
                                }
                                else -> {
                                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Internal server error"
                                    ))
                                }
                            }
                        }
                    }

                    // GET /_matrix/client/v3/events - Get events for room previews (public rooms)
                    get("/events") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))
                            val roomId = call.request.queryParameters["room_id"]
                            val timeout = call.request.queryParameters["timeout"]?.toLongOrNull() ?: 30000L

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing room_id parameter"
                                ))
                                return@get
                            }

                            // Check if room exists and is public
                            val room = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.singleOrNull()
                            }

                            if (room == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Room not found"
                                ))
                                return@get
                            }

                            // Check room visibility (simplified - assume public for now)
                            val roomVisibility = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.type eq "m.room.history_visibility") and
                                    (Events.stateKey eq "")
                                }.singleOrNull()?.let { row ->
                                    Json.parseToJsonElement(row[Events.content]).jsonObject["history_visibility"]?.jsonPrimitive?.content
                                } ?: "shared" // Default visibility
                            }

                            // For room previews, only allow if history is world_readable
                            if (roomVisibility != "world_readable") {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Room history is not publicly readable"
                                ))
                                return@get
                            }

                            // Get recent events from the room (simplified)
                            val events = transaction {
                                Events.select { Events.roomId eq roomId }
                                    .orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(10)
                                    .map { row ->
                                        mapOf(
                                            "event_id" to row[Events.eventId],
                                            "type" to row[Events.type],
                                            "sender" to row[Events.sender],
                                            "origin_server_ts" to row[Events.originServerTs],
                                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                            "room_id" to row[Events.roomId]
                                        )
                                    }
                            }

                            call.respond(mapOf(
                                "events" to events
                            ))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // Login endpoint
                    post("/login") {
                        try {
                            // Validate content type
                            val contentType = call.request.contentType()
                            if (contentType != ContentType.Application.Json) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_NOT_JSON",
                                    "error" to "Content-Type must be application/json"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject

                            // Validate required fields
                            val userId = json["user"]?.jsonPrimitive?.content
                            val password = json["password"]?.jsonPrimitive?.content
                            val token = json["token"]?.jsonPrimitive?.content
                            val appServiceToken = json["access_token"]?.jsonPrimitive?.content
                            val type = json["type"]?.jsonPrimitive?.content ?: "m.login.password"
                            val deviceId = json["device_id"]?.jsonPrimitive?.content
                            val initialDeviceDisplayName = json["initial_device_display_name"]?.jsonPrimitive?.content

                            if (userId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_MISSING_PARAM",
                                    "error" to "Missing 'user' parameter"
                                ))
                                return@post
                            }

                            // Validate authentication based on type
                            when (type) {
                                "m.login.password" -> {
                                    if (password == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_MISSING_PARAM",
                                            "error" to "Missing 'password' parameter"
                                        ))
                                        return@post
                                    }
                                    // Simple authentication - in real implementation, verify password hash
                                    if (password != "pass") {
                                        call.respond(HttpStatusCode.Forbidden, mapOf(
                                            "errcode" to "M_FORBIDDEN",
                                            "error" to "Invalid password"
                                        ))
                                        return@post
                                    }
                                }
                                "m.login.token" -> {
                                    if (token == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_MISSING_PARAM",
                                            "error" to "Missing 'token' parameter"
                                        ))
                                        return@post
                                    }
                                    // In a real implementation, validate the login token
                                    // For now, accept any token for demo purposes
                                }
                                "m.login.oauth2" -> {
                                    val oauth2Token = json["token"]?.jsonPrimitive?.content
                                    if (oauth2Token == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_MISSING_PARAM",
                                            "error" to "Missing OAuth 2.0 token"
                                        ))
                                        return@post
                                    }
                                    // In a real implementation, validate OAuth 2.0 token with provider
                                    // For demo purposes, accept OAuth tokens
                                }
                                "m.login.application_service" -> {
                                    if (appServiceToken == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_MISSING_PARAM",
                                            "error" to "Missing 'access_token' parameter"
                                        ))
                                        return@post
                                    }
                                    // In a real implementation, validate the application service token
                                    // For now, accept any token for demo purposes
                                }
                                else -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Unsupported login type: $type"
                                    ))
                                    return@post
                                }
                            }

                            // Generate access token
                            val accessToken = "token_${userId}_${System.currentTimeMillis()}"
                            val finalDeviceId = deviceId ?: "device_${System.currentTimeMillis()}"

                            // Store user session
                            users[userId] = accessToken

                            call.respond(mapOf(
                                "user_id" to userId,
                                "access_token" to accessToken,
                                "home_server" to "localhost:8080",
                                "device_id" to finalDeviceId,
                                "well_known" to mapOf(
                                    "m.homeserver" to mapOf(
                                        "base_url" to "https://localhost:8080"
                                    )
                                )
                            ))
                        } catch (e: Exception) {
                            when (e) {
                                is kotlinx.serialization.SerializationException -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_BAD_JSON",
                                        "error" to "Invalid JSON"
                                    ))
                                }
                                else -> {
                                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Internal server error"
                                    ))
                                }
                            }
                        }
                    }

                    // Registration endpoint
                    post("/register") {
                        try {
                            // Validate content type
                            val contentType = call.request.contentType()
                            if (contentType != ContentType.Application.Json) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_NOT_JSON",
                                    "error" to "Content-Type must be application/json"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject

                            // Check if this is a guest registration
                            val kind = json["kind"]?.jsonPrimitive?.content
                            val isGuest = kind == "guest"

                            // For guest registration, we don't require authentication
                            if (!isGuest) {
                                // Check if user-interactive authentication is required
                                val auth = json["auth"]?.jsonObject
                                if (auth == null) {
                                    // Return UIA flows for non-guest registration
                                    call.respond(HttpStatusCode.Unauthorized, mapOf(
                                        "errcode" to "M_MISSING_PARAM",
                                        "error" to "Missing authentication data",
                                        "flows" to listOf(
                                            mapOf(
                                                "stages" to listOf("m.login.password")
                                            ),
                                            mapOf(
                                                "stages" to listOf("m.login.oauth2")
                                            )
                                        ),
                                        "params" to mapOf<String, Any>(),
                                        "session" to "session_${System.currentTimeMillis()}"
                                    ))
                                    return@post
                                }

                                // Validate UIA
                                val authType = auth["type"]?.jsonPrimitive?.content
                                when (authType) {
                                    "m.login.password" -> {
                                        // In a real implementation, validate password strength, etc.
                                        // For demo purposes, accept any password
                                    }
                                    "m.login.oauth2" -> {
                                        // In a real implementation, validate OAuth token
                                        // For demo purposes, accept OAuth auth
                                    }
                                    else -> {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_UNKNOWN",
                                            "error" to "Unsupported authentication type: $authType"
                                        ))
                                        return@post
                                    }
                                }
                            }

                            // Handle guest account upgrade
                            val guestAccessToken = json["guest_access_token"]?.jsonPrimitive?.content
                            if (guestAccessToken != null && !isGuest) {
                                // Validate guest access token
                                val guestUserId = users.entries.find { it.value == guestAccessToken }?.key
                                if (guestUserId == null || !guestUserId.startsWith("@guest_")) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_INVALID_PARAM",
                                        "error" to "Invalid guest access token"
                                    ))
                                    return@post
                                }

                                // For upgrade, username must match the guest's username
                                val requestedUsername = json["username"]?.jsonPrimitive?.content
                                if (requestedUsername != null && !guestUserId.contains(requestedUsername)) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_INVALID_PARAM",
                                        "error" to "Username must match guest account"
                                    ))
                                    return@post
                                }
                            }

                            // Extract registration parameters
                            val username = json["username"]?.jsonPrimitive?.content
                            val password = json["password"]?.jsonPrimitive?.content
                            val deviceId = json["device_id"]?.jsonPrimitive?.content
                            val initialDeviceDisplayName = json["initial_device_display_name"]?.jsonPrimitive?.content
                            val inhibitLogin = json["inhibit_login"]?.jsonPrimitive?.boolean ?: false

                            // For guest registration, generate a username if not provided
                            val finalUsername = if (isGuest && username == null) {
                                "guest_${System.currentTimeMillis()}"
                            } else {
                                username
                            }

                            if (finalUsername == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_MISSING_PARAM",
                                    "error" to "Missing 'username' parameter"
                                ))
                                return@post
                            }

                            // Validate username format
                            if (!finalUsername.matches(Regex("^[a-zA-Z0-9._=-]+\$"))) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_USERNAME",
                                    "error" to "Invalid username format"
                                ))
                                return@post
                            }

                            // Check if username is available (in real implementation, check database)
                            if (users.containsKey("@$finalUsername:localhost")) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_USER_IN_USE",
                                    "error" to "Username already taken"
                                ))
                                return@post
                            }

                            // For non-guest registration, require password
                            if (!isGuest && password == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_MISSING_PARAM",
                                    "error" to "Missing 'password' parameter"
                                ))
                                return@post
                            }

                            // Generate user ID
                            val userId = "@$finalUsername:localhost"

                            // Generate access token (unless inhibited)
                            val accessToken = if (!inhibitLogin) {
                                "token_${finalUsername}_${System.currentTimeMillis()}"
                            } else {
                                null
                            }

                            val finalDeviceId = deviceId ?: "device_${System.currentTimeMillis()}"

                            // Store user (in real implementation, store in database)
                            if (accessToken != null) {
                                users[userId] = accessToken
                            }

                            // Prepare response
                            val response = mutableMapOf<String, Any>(
                                "user_id" to userId,
                                "home_server" to "localhost:8080",
                                "device_id" to finalDeviceId
                            )

                            // Add access token if login is not inhibited
                            if (accessToken != null) {
                                response["access_token"] = accessToken
                            }

                            call.respond(response)
                        } catch (e: Exception) {
                            when (e) {
                                is kotlinx.serialization.SerializationException -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_BAD_JSON",
                                        "error" to "Invalid JSON"
                                    ))
                                }
                                else -> {
                                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Internal server error"
                                    ))
                                }
                            }
                        }
                    }

                    // ===== USER PROFILE MANAGEMENT =====

                    // GET /profile/{userId} - Get user profile
                    get("/profile/{userId}") {
                        try {
                            val userId = call.parameters["userId"]
                            if (userId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId parameter"
                                ))
                                return@get
                            }

                            // Get user profile from database
                            val profile = transaction {
                                // For now, return basic profile info
                                // In a real implementation, this would query a users table
                                mapOf(
                                    "displayname" to userId.substringAfter("@").substringBefore(":"),
                                    "avatar_url" to null
                                )
                            }

                            call.respond(profile)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /profile/{userId}/displayname - Set display name
                    put("/profile/{userId}/displayname") {
                        try {
                            val userId = call.parameters["userId"]
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId parameter"
                                ))
                                return@put
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            // Users can only modify their own profile
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot modify other users' profiles"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val displayname = json["displayname"]?.jsonPrimitive?.content

                            // Validate displayname
                            if (displayname != null && displayname.length > 255) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Display name too long"
                                ))
                                return@put
                            }

                            // Store display name (in real implementation, update user profile in database)
                            // For now, we'll just acknowledge the request
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /profile/{userId}/avatar_url - Set avatar URL
                    put("/profile/{userId}/avatar_url") {
                        try {
                            val userId = call.parameters["userId"]
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId parameter"
                                ))
                                return@put
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            // Users can only modify their own profile
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot modify other users' profiles"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val avatarUrl = json["avatar_url"]?.jsonPrimitive?.content

                            // Validate avatar URL format (should be a Matrix content URI)
                            if (avatarUrl != null && !avatarUrl.startsWith("mxc://")) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Avatar URL must be a Matrix content URI (mxc://)"
                                ))
                                return@put
                            }

                            // Store avatar URL (in real implementation, update user profile in database)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== DEVICE MANAGEMENT =====

                    // GET /user/devices - Get user's devices
                    get("/user/devices") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get user's devices (simplified - in real implementation, query device table)
                            val devices = listOf(
                                mapOf(
                                    "device_id" to "device_1",
                                    "display_name" to "Main Device",
                                    "last_seen_ip" to "127.0.0.1",
                                    "last_seen_ts" to System.currentTimeMillis()
                                )
                            )

                            call.respond(mapOf("devices" to devices))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /user/devices/{deviceId} - Get specific device
                    get("/user/devices/{deviceId}") {
                        try {
                            val deviceId = call.parameters["deviceId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            if (deviceId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing deviceId parameter"
                                ))
                                return@get
                            }

                            // Get device info (simplified)
                            val device = mapOf(
                                "device_id" to deviceId,
                                "display_name" to "Device",
                                "last_seen_ip" to "127.0.0.1",
                                "last_seen_ts" to System.currentTimeMillis()
                            )

                            call.respond(device)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /user/devices/{deviceId} - Update device
                    put("/user/devices/{deviceId}") {
                        try {
                            val deviceId = call.parameters["deviceId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            if (deviceId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing deviceId parameter"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val displayName = json["display_name"]?.jsonPrimitive?.content

                            // Update device display name (simplified)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // DELETE /user/devices/{deviceId} - Delete device
                    delete("/user/devices/{deviceId}") {
                        try {
                            val deviceId = call.parameters["deviceId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@delete
                            }

                            if (deviceId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing deviceId parameter"
                                ))
                                return@delete
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val auth = json["auth"]?.jsonObject

                            if (auth == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_PARAM",
                                    "error" to "Missing authentication data",
                                    "flows" to listOf(
                                        mapOf(
                                            "stages" to listOf("m.login.password")
                                        )
                                    ),
                                    "params" to emptyMap<String, Any>(),
                                    "session" to "session_${System.currentTimeMillis()}"
                                ))
                                return@delete
                            }

                            // Validate authentication (simplified)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== ACCOUNT DATA MANAGEMENT =====

                    // GET /user/{userId}/account_data/{type} - Get global account data
                    get("/user/{userId}/account_data/{type}") {
                        try {
                            val userId = call.parameters["userId"]
                            val type = call.parameters["type"]
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null || type == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId or type parameter"
                                ))
                                return@get
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Users can only access their own account data
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot access other users' account data"
                                ))
                                return@get
                            }

                            // Get account data from database
                            val accountData = transaction {
                                AccountData.select {
                                    (AccountData.userId eq userId) and
                                    (AccountData.roomId.isNull()) and
                                    (AccountData.type eq type)
                                }.singleOrNull()
                            }

                            if (accountData != null) {
                                val content = Json.parseToJsonElement(accountData[AccountData.content]).jsonObject
                                call.respond(content)
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Account data not found"
                                ))
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /user/{userId}/account_data/{type} - Set global account data
                    put("/user/{userId}/account_data/{type}") {
                        try {
                            val userId = call.parameters["userId"]
                            val type = call.parameters["type"]
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null || type == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId or type parameter"
                                ))
                                return@put
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            // Users can only modify their own account data
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot modify other users' account data"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val content = Json.parseToJsonElement(request).jsonObject

                            // Store account data
                            transaction {
                                // Delete existing data
                                AccountData.deleteWhere {
                                    (AccountData.userId eq userId) and
                                    (AccountData.roomId.isNull()) and
                                    (AccountData.type eq type)
                                }

                                // Insert new data
                                AccountData.insert {
                                    it[AccountData.userId] = userId
                                    it[AccountData.roomId] = null
                                    it[AccountData.type] = type
                                    it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(content))
                                }
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /user/{userId}/rooms/{roomId}/account_data/{type} - Get room account data
                    get("/user/{userId}/rooms/{roomId}/account_data/{type}") {
                        try {
                            val userId = call.parameters["userId"]
                            val roomId = call.parameters["roomId"]
                            val type = call.parameters["type"]
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null || roomId == null || type == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId, roomId, or type parameter"
                                ))
                                return@get
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Users can only access their own account data
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot access other users' account data"
                                ))
                                return@get
                            }

                            // Get room account data from database
                            val accountData = transaction {
                                AccountData.select {
                                    (AccountData.userId eq userId) and
                                    (AccountData.roomId eq roomId) and
                                    (AccountData.type eq type)
                                }.singleOrNull()
                            }

                            if (accountData != null) {
                                val content = Json.parseToJsonElement(accountData[AccountData.content]).jsonObject
                                call.respond(content)
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Account data not found"
                                ))
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /user/{userId}/rooms/{roomId}/account_data/{type} - Set room account data
                    put("/user/{userId}/rooms/{roomId}/account_data/{type}") {
                        try {
                            val userId = call.parameters["userId"]
                            val roomId = call.parameters["roomId"]
                            val type = call.parameters["type"]
                            val authenticatedUserId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null || roomId == null || type == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId, roomId, or type parameter"
                                ))
                                return@put
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            // Users can only modify their own account data
                            if (authenticatedUserId != userId) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Cannot modify other users' account data"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val content = Json.parseToJsonElement(request).jsonObject

                            // Store room account data
                            transaction {
                                // Delete existing data
                                AccountData.deleteWhere {
                                    (AccountData.userId eq userId) and
                                    (AccountData.roomId eq roomId) and
                                    (AccountData.type eq type)
                                }

                                // Insert new data
                                AccountData.insert {
                                    it[AccountData.userId] = userId
                                    it[AccountData.roomId] = roomId
                                    it[AccountData.type] = type
                                    it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(content))
                                }
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== ROOM OPERATIONS =====

                    // POST /createRoom - Create a new room
                    post("/createRoom") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject

                            // Extract room creation parameters
                            val name = json["name"]?.jsonPrimitive?.content
                            val topic = json["topic"]?.jsonPrimitive?.content
                            val visibility = json["visibility"]?.jsonPrimitive?.content ?: "private"
                            val roomAliasName = json["room_alias_name"]?.jsonPrimitive?.content
                            val preset = json["preset"]?.jsonPrimitive?.content
                            val isDirect = json["is_direct"]?.jsonPrimitive?.boolean ?: false
                            val invite = json["invite"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                            val initialState = json["initial_state"]?.jsonArray ?: JsonArray(emptyList())
                            val powerLevelContentOverride = json["power_level_content_override"]?.jsonObject
                            val creationContent = json["creation_content"]?.jsonObject

                            // Generate room ID
                            val roomId = "!${System.currentTimeMillis()}:localhost"

                            // Create room in database
                            transaction {
                                Rooms.insert {
                                    it[Rooms.roomId] = roomId
                                    it[Rooms.creator] = userId
                                    it[Rooms.name] = name
                                    it[Rooms.topic] = topic
                                    it[Rooms.visibility] = visibility
                                    it[Rooms.roomVersion] = "9"
                                    it[Rooms.isDirect] = isDirect
                                }
                            }

                            // Create initial state events
                            val initialEvents = mutableListOf<Map<String, Any>>()

                            // m.room.create event
                            val createEvent = mapOf(
                                "type" to "m.room.create",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to "\$${System.currentTimeMillis()}_create",
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to (creationContent ?: mapOf(
                                    "creator" to userId,
                                    "room_version" to "9"
                                )),
                                "state_key" to "",
                                "unsigned" to emptyMap<String, Any>()
                            )
                            initialEvents.add(createEvent)

                            // m.room.member event for creator
                            val memberEvent = mapOf(
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to "\$${System.currentTimeMillis()}_member",
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf("membership" to "join"),
                                "state_key" to userId,
                                "unsigned" to emptyMap<String, Any>()
                            )
                            initialEvents.add(memberEvent)

                            // Store initial events
                            for (event in initialEvents) {
                                transaction {
                                    Events.insert {
                                        it[Events.roomId] = roomId
                                        it[Events.eventId] = event["event_id"] as String
                                        it[Events.type] = event["type"] as String
                                        it[Events.sender] = event["sender"] as String
                                        it[Events.stateKey] = event["state_key"] as String?
                                        it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject((event["content"] as Map<String, Any>).mapValues { (_, value) ->
                                        when (value) {
                                            is String -> JsonPrimitive(value)
                                            is Number -> JsonPrimitive(value)
                                            is Boolean -> JsonPrimitive(value)
                                            is Map<*, *> -> JsonObject((value as Map<String, Any>).mapValues { (_, v) ->
                                                when (v) {
                                                    is String -> JsonPrimitive(v)
                                                    is Number -> JsonPrimitive(v)
                                                    is Boolean -> JsonPrimitive(v)
                                                    else -> JsonPrimitive(v.toString())
                                                }
                                            })
                                            is List<*> -> JsonArray(value.map { item ->
                                                when (item) {
                                                    is String -> JsonPrimitive(item)
                                                    is Number -> JsonPrimitive(item)
                                                    is Boolean -> JsonPrimitive(item)
                                                    else -> JsonPrimitive(item.toString())
                                                }
                                            })
                                            else -> JsonPrimitive(value.toString())
                                        }
                                    }))
                                        it[Events.originServerTs] = event["origin_server_ts"] as Long
                                        it[Events.prevEvents] = "[]"
                                        it[Events.authEvents] = "[]"
                                        it[Events.depth] = 1
                                        it[Events.hashes] = "{}"
                                        it[Events.signatures] = "{}"
                                    }
                                }
                            }

                            call.respond(mapOf("room_id" to roomId))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /rooms/{roomId}/join - Join a room
                    post("/rooms/{roomId}/join") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@post
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val thirdPartySigned = json["third_party_signed"]?.jsonObject

                            // Check if room exists
                            val roomExists = transaction {
                                Rooms.select { Rooms.roomId eq roomId }.count() > 0
                            }

                            if (!roomExists) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Room not found"
                                ))
                                return@post
                            }

                            // Create join event
                            val eventId = "\$${System.currentTimeMillis()}_join"
                            val joinEvent = mapOf(
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf("membership" to "join"),
                                "state_key" to userId,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store join event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = "m.room.member"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = userId
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("membership" to JsonPrimitive("join"))))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(mapOf("room_id" to roomId))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /rooms/{roomId}/leave - Leave a room
                    post("/rooms/{roomId}/leave") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@post
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            // Create leave event
                            val eventId = "\$${System.currentTimeMillis()}_leave"
                            val leaveEvent = mapOf(
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf("membership" to "leave"),
                                "state_key" to userId,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store leave event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = "m.room.member"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = userId
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("membership" to JsonPrimitive("leave"))))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /rooms/{roomId}/members - Get room members
                    get("/rooms/{roomId}/members") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get room members
                            val members = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.type eq "m.room.member")
                                }.map { row ->
                                    val content = Json.parseToJsonElement(row[Events.content]).jsonObject
                                    val membership = content["membership"]?.jsonPrimitive?.content
                                    if (membership == "join") {
                                        mapOf(
                                            "type" to "m.room.member",
                                            "room_id" to roomId,
                                            "sender" to row[Events.sender],
                                            "event_id" to row[Events.eventId],
                                            "origin_server_ts" to row[Events.originServerTs],
                                            "content" to content,
                                            "state_key" to row[Events.stateKey],
                                            "unsigned" to emptyMap<String, Any>()
                                        )
                                    } else null
                                }.filterNotNull()
                            }

                            call.respond(mapOf("chunk" to members))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /rooms/{roomId}/state - Get room state
                    get("/rooms/{roomId}/state") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get current room state
                            val stateEvents = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.stateKey.isNotNull())
                                }.map { row ->
                                    mapOf(
                                        "type" to row[Events.type],
                                        "room_id" to roomId,
                                        "sender" to row[Events.sender],
                                        "event_id" to row[Events.eventId],
                                        "origin_server_ts" to row[Events.originServerTs],
                                        "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                        "state_key" to row[Events.stateKey],
                                        "unsigned" to emptyMap<String, Any>()
                                    )
                                }
                            }

                            call.respond(stateEvents)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /rooms/{roomId}/state/{eventType}/{stateKey} - Get specific state event
                    get("/rooms/{roomId}/state/{eventType}/{stateKey}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val eventType = call.parameters["eventType"]
                            val stateKey = call.parameters["stateKey"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null || eventType == null || stateKey == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId, eventType, or stateKey parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get specific state event
                            val stateEvent = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.type eq eventType) and
                                    (Events.stateKey eq stateKey)
                                }.singleOrNull()
                            }

                            if (stateEvent != null) {
                                val event = mapOf(
                                    "type" to stateEvent[Events.type],
                                    "room_id" to roomId,
                                    "sender" to stateEvent[Events.sender],
                                    "event_id" to stateEvent[Events.eventId],
                                    "origin_server_ts" to stateEvent[Events.originServerTs],
                                    "content" to Json.parseToJsonElement(stateEvent[Events.content]).jsonObject,
                                    "state_key" to stateEvent[Events.stateKey],
                                    "unsigned" to emptyMap<String, Any>()
                                )
                                call.respond(event)
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "State event not found"
                                ))
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /rooms/{roomId}/state/{eventType}/{stateKey} - Send state event
                    put("/rooms/{roomId}/state/{eventType}/{stateKey}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val eventType = call.parameters["eventType"]
                            val stateKey = call.parameters["stateKey"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null || eventType == null || stateKey == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId, eventType, or stateKey parameter"
                                ))
                                return@put
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val content = Json.parseToJsonElement(request).jsonObject

                            // Generate event ID
                            val eventId = "\$${System.currentTimeMillis()}_${eventType}_${stateKey}"

                            // Create state event
                            val stateEvent = mapOf(
                                "type" to eventType,
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to content,
                                "state_key" to stateKey,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store state event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = eventType
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = stateKey
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(content))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(stateEvent)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== EVENT MANAGEMENT =====

                    // GET /rooms/{roomId}/messages - Get room messages
                    get("/rooms/{roomId}/messages") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            val from = call.request.queryParameters["from"]
                            val to = call.request.queryParameters["to"]
                            val dir = call.request.queryParameters["dir"] ?: "b"
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                            val filter = call.request.queryParameters["filter"]

                            // Get room messages
                            val messages = transaction {
                                val query = Events.select { Events.roomId eq roomId }

                                // Apply direction and pagination
                                when (dir) {
                                    "b" -> query.orderBy(Events.originServerTs, SortOrder.DESC)
                                    "f" -> query.orderBy(Events.originServerTs, SortOrder.ASC)
                                }

                                query.limit(limit).map { row ->
                                    mapOf(
                                        "type" to row[Events.type],
                                        "room_id" to roomId,
                                        "sender" to row[Events.sender],
                                        "event_id" to row[Events.eventId],
                                        "origin_server_ts" to row[Events.originServerTs],
                                        "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                        "unsigned" to emptyMap<String, Any>()
                                    )
                                }
                            }

                            val start = messages.firstOrNull()?.get("event_id") as String?
                            val end = messages.lastOrNull()?.get("event_id") as String?

                            call.respond(mapOf(
                                "start" to start,
                                "end" to end,
                                "chunk" to messages
                            ))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /rooms/{roomId}/context/{eventId} - Get event context
                    get("/rooms/{roomId}/context/{eventId}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val eventId = call.parameters["eventId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null || eventId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId or eventId parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                            val filter = call.request.queryParameters["filter"]

                            // Get the target event
                            val targetEvent = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.eventId eq eventId)
                                }.singleOrNull()
                            }

                            if (targetEvent == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Event not found"
                                ))
                                return@get
                            }

                            val targetTimestamp = targetEvent[Events.originServerTs]

                            // Get events before the target
                            val eventsBefore = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.originServerTs less targetTimestamp)
                                }.orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(limit / 2)
                                    .map { row ->
                                        mapOf(
                                            "type" to row[Events.type],
                                            "room_id" to roomId,
                                            "sender" to row[Events.sender],
                                            "event_id" to row[Events.eventId],
                                            "origin_server_ts" to row[Events.originServerTs],
                                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                            "unsigned" to emptyMap<String, Any>()
                                        )
                                    }
                            }

                            // Get events after the target
                            val eventsAfter = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.originServerTs greater targetTimestamp)
                                }.orderBy(Events.originServerTs, SortOrder.ASC)
                                    .limit(limit / 2)
                                    .map { row ->
                                        mapOf(
                                            "type" to row[Events.type],
                                            "room_id" to roomId,
                                            "sender" to row[Events.sender],
                                            "event_id" to row[Events.eventId],
                                            "origin_server_ts" to row[Events.originServerTs],
                                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                            "unsigned" to emptyMap<String, Any>()
                                        )
                                    }
                            }

                            val event = mapOf(
                                "type" to targetEvent[Events.type],
                                "room_id" to roomId,
                                "sender" to targetEvent[Events.sender],
                                "event_id" to targetEvent[Events.eventId],
                                "origin_server_ts" to targetEvent[Events.originServerTs],
                                "content" to Json.parseToJsonElement(targetEvent[Events.content]).jsonObject,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            call.respond(mapOf(
                                "start" to eventsBefore.lastOrNull()?.get("event_id"),
                                "end" to eventsAfter.lastOrNull()?.get("event_id"),
                                "events_before" to eventsBefore.reversed(),
                                "event" to event,
                                "events_after" to eventsAfter,
                                "state" to emptyList<Map<String, Any>>()
                            ))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /rooms/{roomId}/redact/{eventId}/{txnId} - Redact event
                    put("/rooms/{roomId}/redact/{eventId}/{txnId}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val eventId = call.parameters["eventId"]
                            val txnId = call.parameters["txnId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null || eventId == null || txnId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId, eventId, or txnId parameter"
                                ))
                                return@put
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val reason = json["reason"]?.jsonPrimitive?.content

                            // Generate redaction event ID
                            val redactionEventId = "\$${System.currentTimeMillis()}_redact"

                            // Create redaction event
                            val redactionEvent = mapOf(
                                "type" to "m.room.redaction",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to redactionEventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf("reason" to reason).filterValues { it != null },
                                "redacts" to eventId,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store redaction event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = redactionEventId
                                    it[Events.type] = "m.room.redaction"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = null
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("reason" to reason).filterValues { it != null }.mapValues { JsonPrimitive(it.value) }))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(mapOf("event_id" to redactionEventId))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /rooms/{roomId}/relations/{eventId} - Get event relations
                    get("/rooms/{roomId}/relations/{eventId}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val eventId = call.parameters["eventId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null || eventId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId or eventId parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            val relType = call.request.queryParameters["rel_type"]
                            val eventType = call.request.queryParameters["event_type"]
                            val from = call.request.queryParameters["from"]
                            val to = call.request.queryParameters["to"]
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
                            val dir = call.request.queryParameters["dir"] ?: "b"

                            // Get related events (simplified - in real implementation, would parse m.relates_to)
                            val relatedEvents = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.type neq "m.room.redaction") // Exclude redactions
                                }.orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(limit)
                                    .map { row ->
                                        mapOf(
                                            "type" to row[Events.type],
                                            "room_id" to roomId,
                                            "sender" to row[Events.sender],
                                            "event_id" to row[Events.eventId],
                                            "origin_server_ts" to row[Events.originServerTs],
                                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                            "unsigned" to emptyMap<String, Any>()
                                        )
                                    }
                            }

                            call.respond(mapOf(
                                "chunk" to relatedEvents,
                                "next_batch" to null,
                                "prev_batch" to null
                            ))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /rooms/{roomId}/relations/{eventId}/{relType} - Get specific relation type
                    get("/rooms/{roomId}/relations/{eventId}/{relType}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val eventId = call.parameters["eventId"]
                            val relType = call.parameters["relType"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null || eventId == null || relType == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId, eventId, or relType parameter"
                                ))
                                return@get
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            val eventType = call.request.queryParameters["event_type"]
                            val from = call.request.queryParameters["from"]
                            val to = call.request.queryParameters["to"]
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
                            val dir = call.request.queryParameters["dir"] ?: "b"

                            // Get events of specific relation type (simplified)
                            val relatedEvents = transaction {
                                Events.select {
                                    (Events.roomId eq roomId) and
                                    (Events.type neq "m.room.redaction")
                                }.orderBy(Events.originServerTs, SortOrder.DESC)
                                    .limit(limit)
                                    .map { row ->
                                        mapOf(
                                            "type" to row[Events.type],
                                            "room_id" to roomId,
                                            "sender" to row[Events.sender],
                                            "event_id" to row[Events.eventId],
                                            "origin_server_ts" to row[Events.originServerTs],
                                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                                            "unsigned" to emptyMap<String, Any>()
                                        )
                                    }
                            }

                            call.respond(mapOf(
                                "chunk" to relatedEvents,
                                "next_batch" to null,
                                "prev_batch" to null
                            ))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== ADDITIONAL ROOM MANAGEMENT =====

                    // POST /rooms/{roomId}/invite - Invite user to room
                    post("/rooms/{roomId}/invite") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@post
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val inviteeUserId = json["user_id"]?.jsonPrimitive?.content

                            if (inviteeUserId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing user_id parameter"
                                ))
                                return@post
                            }

                            // Create invite event
                            val eventId = "\$${System.currentTimeMillis()}_invite"
                            val inviteEvent = mapOf(
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf("membership" to "invite"),
                                "state_key" to inviteeUserId,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store invite event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = "m.room.member"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = inviteeUserId
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("membership" to JsonPrimitive("invite"))))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /rooms/{roomId}/kick - Remove user from room
                    post("/rooms/{roomId}/kick") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@post
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val targetUserId = json["user_id"]?.jsonPrimitive?.content
                            val reason = json["reason"]?.jsonPrimitive?.content

                            if (targetUserId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing user_id parameter"
                                ))
                                return@post
                            }

                            // Create kick event (leave event with reason)
                            val eventId = "\$${System.currentTimeMillis()}_kick"
                            val kickEvent = mapOf(
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf(
                                    "membership" to "leave",
                                    "reason" to reason
                                ).filterValues { it != null },
                                "state_key" to targetUserId,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store kick event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = "m.room.member"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = targetUserId
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(buildMap {
                                        put("membership", JsonPrimitive("leave"))
                                        reason?.let { put("reason", JsonPrimitive(it)) }
                                    }))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /rooms/{roomId}/ban - Ban user from room
                    post("/rooms/{roomId}/ban") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@post
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val targetUserId = json["user_id"]?.jsonPrimitive?.content
                            val reason = json["reason"]?.jsonPrimitive?.content

                            if (targetUserId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing user_id parameter"
                                ))
                                return@post
                            }

                            // Create ban event
                            val eventId = "\$${System.currentTimeMillis()}_ban"
                            val banEvent = mapOf(
                                "type" to "m.room.member",
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to mapOf(
                                    "membership" to "ban",
                                    "reason" to reason
                                ).filterValues { it != null },
                                "state_key" to targetUserId,
                                "unsigned" to emptyMap<String, Any>()
                            )

                            // Store ban event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = "m.room.member"
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = targetUserId
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(buildMap {
                                        put("membership", JsonPrimitive("ban"))
                                        reason?.let { put("reason", JsonPrimitive(it)) }
                                    }))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]"
                                    it[Events.authEvents] = "[]"
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }
                }
            }
        }
    }
}