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
                                        call.respond(HttpStatus
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

                    // Third-party networks endpoints
                    route("/thirdparty") {
                        // GET /_matrix/client/v3/thirdparty/protocols - Get available third-party protocols
                        get("/protocols") {
                            try {
                                val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                                if (userId == null) {
                                    call.respond(HttpStatusCode.Unauthorized, mapOf(
                                        "errcode" to "M_MISSING_TOKEN",
                                        "error" to "Missing access token"
                                    ))
                                    return@get
                                }

                                // Return supported third-party protocols (simplified example)
                                val protocols = mapOf(
                                    "irc" to mapOf(
                                        "user_fields" to listOf("username", "irc_server"),
                                        "location_fields" to listOf("channel"),
                                        "icon" to "mxc://example.com/irc-icon",
                                        "field_types" to mapOf(
                                            "username" to mapOf(
                                                "regexp" to "[^@:/]+",
                                                "placeholder" to "username"
                                            ),
                                            "irc_server" to mapOf(
                                                "regexp" to "([a-zA-Z0-9]+\\.)*[a-zA-Z0-9]+",
                                                "placeholder" to "irc.example.com"
                                            )
                                        ),
                                        "instances" to listOf(
                                            mapOf(
                                                "desc" to "Example IRC network",
                                                "icon" to "mxc://example.com/irc-network-icon",
                                                "fields" to mapOf(
                                                    "network" to "example.com"
                                                )
                                            )
                                        )
                                    )
                                )

                                call.respond(protocols)

                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "errcode" to "M_UNKNOWN",
                                    "error" to "Internal server error"
                                ))
                            }
                        }

                        // GET /_matrix/client/v3/thirdparty/protocol/{protocol} - Get protocol metadata
                        get("/protocol/{protocol}") {
                            try {
                                val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))
                                val protocol = call.parameters["protocol"]

                                if (userId == null) {
                                    call.respond(HttpStatusCode.Unauthorized, mapOf(
                                        "errcode" to "M_MISSING_TOKEN",
                                        "error" to "Missing access token"
                                    ))
                                    return@get
                                }

                                if (protocol == null) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_INVALID_PARAM",
                                        "error" to "Missing protocol parameter"
                                    ))
                                    return@get
                                }

                                // Simplified protocol response
                                call.respond(mapOf(
                                    "user_fields" to listOf("username"),
                                    "location_fields" to listOf("channel")
                                ))

                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "errcode" to "M_UNKNOWN",
                                    "error" to "Internal server error"
                                ))
                            }
                        }
                    }

                    // PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId} - Send message to room
                    put("/rooms/{roomId}/send/{eventType}/{txnId}") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))
                            val roomId = call.parameters["roomId"]
                            val eventType = call.parameters["eventType"]
                            val txnId = call.parameters["txnId"]

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@put
                            }

                            if (roomId == null || eventType == null || txnId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId, eventType, or txnId parameter"
                                ))
                                return@put
                            }

                            // Parse request body
                            val requestBody = call.receiveText()
                            val content = Json.parseToJsonElement(requestBody).jsonObject

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
                                return@put
                            }

                            // Validate event type and content
                            when (eventType) {
                                "m.room.message" -> {
                                    // Validate m.room.message content
                                    val msgtype = content["msgtype"]?.jsonPrimitive?.content
                                    val body = content["body"]?.jsonPrimitive?.content

                                    if (msgtype == null || body == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_INVALID_PARAM",
                                            "error" to "Missing msgtype or body for m.room.message"
                                        ))
                                        return@put
                                    }

                                    // Support for m.sticker messages
                                    if (msgtype == "m.sticker") {
                                        val url = content["url"]?.jsonPrimitive?.content
                                        val info = content["info"]?.jsonObject

                                        if (url == null || info == null) {
                                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                                "errcode" to "M_INVALID_PARAM",
                                                "error" to "Missing url or info for m.sticker"
                                            ))
                                            return@put
                                        }
                                    }
                                }
                                "m.reaction" -> {
                                    // Validate m.reaction content (annotations/reactions)
                                    val relatesTo = content["m.relates_to"]?.jsonObject
                                    val eventId = relatesTo?.get("event_id")?.jsonPrimitive?.content
                                    val relType = relatesTo?.get("rel_type")?.jsonPrimitive?.content
                                    val key = relatesTo?.get("key")?.jsonPrimitive?.content

                                    if (relatesTo == null || eventId == null || relType != "m.annotation" || key == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_INVALID_PARAM",
                                            "error" to "Invalid m.relates_to structure for m.reaction"
                                        ))
                                        return@put
                                    }

                                    // Verify the target event exists
                                    val targetEventExists = transaction {
                                        Events.select {
                                            (Events.roomId eq roomId) and
                                            (Events.eventId eq eventId)
                                        }.count() > 0
                                    }

                                    if (!targetEventExists) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_INVALID_PARAM",
                                            "error" to "Target event not found"
                                        ))
                                        return@put
                                    }
                                }
                                else -> {
                                    // Check for m.replace relation type (Event Replacements)
                                    val relatesTo = content["m.relates_to"]?.jsonObject
                                    if (relatesTo != null) {
                                        val relType = relatesTo["rel_type"]?.jsonPrimitive?.content
                                        val eventId = relatesTo["event_id"]?.jsonPrimitive?.content

                                        if (relType == "m.replace" && eventId != null) {
                                            // Validate Event Replacement
                                            val targetEventExists = transaction {
                                                Events.select {
                                                    (Events.roomId eq roomId) and
                                                    (Events.eventId eq eventId)
                                                }.count() > 0
                                            }

                                            if (!targetEventExists) {
                                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                                    "errcode" to "M_INVALID_PARAM",
                                                    "error" to "Target event for replacement not found"
                                                ))
                                                return@put
                                            }

                                            // Verify the target event is owned by the sender
                                            val targetEvent = transaction {
                                                Events.select {
                                                    (Events.roomId eq roomId) and
                                                    (Events.eventId eq eventId)
                                                }.singleOrNull()
                                            }

                                            if (targetEvent != null && targetEvent[Events.sender] != userId) {
                                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                                    "errcode" to "M_FORBIDDEN",
                                                    "error" to "Cannot replace events sent by other users"
                                                ))
                                                return@put
                                            }
                                        } else if (relType == "m.thread" && eventId != null) {
                                            // Validate Threading
                                            val targetEventExists = transaction {
                                                Events.select {
                                                    (Events.roomId eq roomId) and
                                                    (Events.eventId eq eventId)
                                                }.count() > 0
                                            }

                                            if (!targetEventExists) {
                                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                                    "errcode" to "M_INVALID_PARAM",
                                                    "error" to "Target event for thread not found"
                                                ))
                                                return@put
                                            }

                                            // Additional threading validation can be added here
                                            val isReply = relatesTo["is_falling_back"]?.jsonPrimitive?.boolean == true
                                            val threadId = if (isReply) {
                                                relatesTo["event_id"]?.jsonPrimitive?.content
                                            } else {
                                                // For new threads, the event_id becomes the thread root
                                                eventId
                                            }
                                        }
                                    }
                                }
                                "m.room.redaction" -> {
                                    // Redaction events are handled by the redact endpoint
                                    call.respond(HttpStatusCode.MethodNotAllowed, mapOf(
                                        "errcode" to "M_CANNOT_SEND",
                                        "error" to "Use redact endpoint for redaction events"
                                    ))
                                    return@put
                                }
                                else -> {
                                    // Check for Moderation Policy Lists (m.policy.rule.* events)
                                    if (eventType.startsWith("m.policy.rule.")) {
                                        // Validate moderation policy content
                                        val entity = content["entity"]?.jsonPrimitive?.content
                                        val reason = content["reason"]?.jsonPrimitive?.content

                                        if (entity == null) {
                                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                                "errcode" to "M_INVALID_PARAM",
                                                "error" to "Missing entity for policy rule"
                                            ))
                                            return@put
                                        }

                                        // Check if user has permission to set policy rules
                                        val powerLevelsEvent = transaction {
                                            Events.select {
                                                (Events.roomId eq roomId) and
                                                (Events.type eq "m.room.power_levels") and
                                                (Events.stateKey eq "")
                                            }.singleOrNull()
                                        }

                                        if (powerLevelsEvent != null) {
                                            val powerLevels = Json.parseToJsonElement(powerLevelsEvent[Events.content]).jsonObject
                                            val userPower = powerLevels["users"]?.jsonObject?.get(userId)?.jsonPrimitive?.int ?: 0
                                            val banPower = powerLevels["ban"]?.jsonPrimitive?.int ?: 50

                                            if (userPower < banPower) {
                                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                                    "errcode" to "M_FORBIDDEN",
                                                    "error" to "Insufficient power level to set policy rules"
                                                ))
                                                return@put
                                            }
                                        }
                                    }
                                    // Allow other event types
                                }
                            }

                            // Generate event ID
                            val eventId = "\$${System.currentTimeMillis()}_${txnId}"

                            // Create the event
                            val event = mapOf(
                                "type" to eventType,
                                "room_id" to roomId,
                                "sender" to userId,
                                "event_id" to eventId,
                                "origin_server_ts" to System.currentTimeMillis(),
                                "content" to content,
                                "unsigned" to mapOf(
                                    "transaction_id" to txnId
                                )
                            )

                            // Store the event
                            transaction {
                                Events.insert {
                                    it[Events.roomId] = roomId
                                    it[Events.eventId] = eventId
                                    it[Events.type] = eventType
                                    it[Events.sender] = userId
                                    it[Events.stateKey] = null // Not a state event
                                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(content))
                                    it[Events.originServerTs] = System.currentTimeMillis()
                                    it[Events.prevEvents] = "[]" // Simplified
                                    it[Events.authEvents] = "[]" // Simplified
                                    it[Events.depth] = 1
                                    it[Events.hashes] = "{}"
                                    it[Events.signatures] = "{}"
                                }
                            }

                            // Broadcast the event to room clients
                            runBlocking {
                                broadcastEvent(roomId, event)
                            }

                            call.respond(mapOf(
                                "event_id" to eventId
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
                }
            }
        }
    }
}