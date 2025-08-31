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
import io.ktor.server.request.*
import io.ktor.http.content.*
import io.ktor.http.content.PartData
import models.Events
import models.Rooms
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import kotlinx.serialization.json.*
import utils.AuthUtils
import utils.typingMap
import utils.connectedClients
import utils.ServerKeys
import routes.server_server.federation.v1.broadcastEDU
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import models.AccountData
import io.ktor.websocket.Frame
import utils.MediaStorage
import models.Users
import models.AccessTokens
import org.mindrot.jbcrypt.BCrypt
import utils.OAuthService
import utils.OAuthConfig
import utils.OAuthProvider

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
                // Use new database-backed authentication
                val result = AuthUtils.validateAccessToken(tokenCredential.token)
                if (result != null) {
                    UserIdPrincipal(result.first)
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
            // Validate token using new authentication system
            val result = AuthUtils.validateAccessToken(accessToken)

            if (result != null) {
                val (userId, deviceId) = result
                // Store authenticated user information
                call.attributes.put(AttributeKey("matrix-user"), UserIdPrincipal(userId))
                call.attributes.put(AttributeKey("matrix-token"), accessToken)
                call.attributes.put(AttributeKey("matrix-user-id"), userId)
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

                            // Extract login parameters
                            val userId = json["user"]?.jsonPrimitive?.content
                            val password = json["password"]?.jsonPrimitive?.content
                            val type = json["type"]?.jsonPrimitive?.content ?: "m.login.password"
                            val deviceId = json["device_id"]?.jsonPrimitive?.content

                            if (userId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_MISSING_PARAM",
                                    "error" to "Missing 'user' parameter"
                                ))
                                return@post
                            }

                            // Authenticate based on type
                            val authenticatedUserId = when (type) {
                                "m.login.password" -> {
                                    if (password == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "errcode" to "M_MISSING_PARAM",
                                            "error" to "Missing 'password' parameter"
                                        ))
                                        return@post
                                    }
                                    AuthUtils.authenticateUser(userId, password)
                                }
                                else -> {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_UNKNOWN",
                                        "error" to "Unsupported login type: $type"
                                    ))
                                    return@post
                                }
                            }

                            if (authenticatedUserId == null) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Invalid username or password"
                                ))
                                return@post
                            }

                            // Generate device ID if not provided
                            val finalDeviceId = deviceId ?: AuthUtils.generateDeviceId()

                            // Extract device information from request headers
                            val userAgent = call.request.headers["User-Agent"]
                            val ipAddress = call.request.headers["X-Forwarded-For"]
                                ?: call.request.headers["X-Real-IP"]
                                ?: call.request.local.remoteHost

                            // Create access token with device information
                            val accessToken = AuthUtils.createAccessToken(
                                authenticatedUserId,
                                finalDeviceId,
                                userAgent,
                                ipAddress
                            )

                            // Return login response
                            call.respond(mapOf(
                                "user_id" to authenticatedUserId,
                                "access_token" to accessToken,
                                "home_server" to "localhost:8080",
                                "device_id" to finalDeviceId
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
                                        val oauth2Token = auth["token"]?.jsonPrimitive?.content
                                        if (oauth2Token == null) {
                                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                                "errcode" to "M_INVALID_PARAM",
                                                "error" to "Missing OAuth token in authentication data"
                                            ))
                                            return@post
                                        }

                                        // Validate OAuth 2.0 token
                                        val tokenValidation = OAuthService.validateAccessToken(oauth2Token)
                                        if (tokenValidation == null) {
                                            call.respond(HttpStatusCode.Forbidden, mapOf(
                                                "errcode" to "M_FORBIDDEN",
                                                "error" to "Invalid OAuth 2.0 token"
                                            ))
                                            return@post
                                        }

                                        val (oauthUserId, clientId, scope) = tokenValidation

                                        // Use OAuth user ID for registration
                                        val finalUsername = json["username"]?.jsonPrimitive?.content ?: oauthUserId.substringAfter("@").substringBefore(":")
                                        val finalUserId = "@$finalUsername:localhost"

                                        // Check if user already exists
                                        if (AuthUtils.isUsernameAvailable(finalUsername)) {
                                            // Create user with OAuth credentials
                                            AuthUtils.createUser(finalUsername, "", finalUsername, false)
                                        }
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
                                val guestUserPair = AuthUtils.validateAccessToken(guestAccessToken)
                                val guestUserId = guestUserPair?.first
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

                            // Check if username is available using AuthUtils
                            if (!AuthUtils.isUsernameAvailable(finalUsername)) {
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

                            // Validate password strength for non-guest users
                            if (!isGuest && password != null) {
                                val (isValid, errorMessage) = AuthUtils.validatePasswordStrength(password)
                                if (!isValid) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf(
                                        "errcode" to "M_WEAK_PASSWORD",
                                        "error" to errorMessage
                                    ))
                                    return@post
                                }
                            }

                            // Generate access token (unless inhibited)
                            val accessToken = if (!inhibitLogin) {
                                val finalDeviceId = deviceId ?: AuthUtils.generateDeviceId()

                                // Extract device information from request headers
                                val userAgent = call.request.headers["User-Agent"]
                                val ipAddress = call.request.headers["X-Forwarded-For"]
                                    ?: call.request.headers["X-Real-IP"]
                                    ?: call.request.local.remoteHost

                                // Create user first
                                val userId = AuthUtils.createUser(finalUsername, password ?: "", finalUsername, isGuest)
                                // Create access token for the user with device information
                                AuthUtils.createAccessToken(userId, finalDeviceId, userAgent, ipAddress)
                            } else {
                                // For inhibited login, still create the user but don't create access token
                                AuthUtils.createUser(finalUsername, password ?: "", finalUsername, isGuest)
                                null
                            }

                            val finalDeviceId = deviceId ?: "device_${System.currentTimeMillis()}"

                            // Store user session in database if login is not inhibited
                            if (accessToken != null) {
                                val userId = "@$finalUsername:localhost"
                                // Access token is already stored by AuthUtils.createAccessToken()
                            }

                            // Prepare response
                            val userId = "@$finalUsername:localhost"
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

                    // ===== ACCOUNT MANAGEMENT =====

                    // POST /account/password - Change password
                    post("/account/password") {
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
                            val auth = json["auth"]?.jsonObject
                            val newPassword = json["new_password"]?.jsonPrimitive?.content

                            if (newPassword == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing new_password parameter"
                                ))
                                return@post
                            }

                            // Validate new password strength
                            val passwordValidation = AuthUtils.validatePasswordStrength(newPassword)
                            if (!passwordValidation.first) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to passwordValidation.second
                                ))
                                return@post
                            }

                            // Handle User-Interactive Authentication (UIA)
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
                                return@post
                            }

                            // Validate current password authentication
                            val authType = auth["type"]?.jsonPrimitive?.content
                            val authUser = auth["user"]?.jsonPrimitive?.content ?: userId
                            val authPassword = auth["password"]?.jsonPrimitive?.content

                            if (authType != "m.login.password" || authPassword == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Invalid authentication data"
                                ))
                                return@post
                            }

                            // Verify current password
                            val user = transaction {
                                Users.select { Users.userId eq userId }.singleOrNull()
                            }

                            if (user == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "User not found"
                                ))
                                return@post
                            }

                            val currentPasswordHash = user[Users.passwordHash]
                            if (!BCrypt.checkpw(authPassword, currentPasswordHash)) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Invalid password"
                                ))
                                return@post
                            }

                            // Update password in database
                            val newPasswordHash = AuthUtils.hashPassword(newPassword)
                            transaction {
                                Users.update({ Users.userId eq userId }) {
                                    it[Users.passwordHash] = newPasswordHash
                                }
                            }

                            // Invalidate all existing access tokens for security
                            transaction {
                                AccessTokens.deleteWhere { AccessTokens.userId eq userId }
                            }

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
                                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))

                            val devices = AuthUtils.getUserDevices(userId)
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

                            val device = AuthUtils.getUserDevice(userId, deviceId)
                                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Device not found"
                                ))

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

                            if (!AuthUtils.deviceBelongsToUser(userId, deviceId)) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Device not found"
                                ))
                                return@put
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val displayName = json["display_name"]?.jsonPrimitive?.content

                            AuthUtils.updateDeviceDisplayName(userId, deviceId, displayName)
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

                            if (!AuthUtils.deviceBelongsToUser(userId, deviceId)) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Device not found"
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

                            val authType = auth["type"]?.jsonPrimitive?.content
                            if (authType != "m.login.password") {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_UNKNOWN",
                                    "error" to "Unsupported authentication type"
                                ))
                                return@delete
                            }

                            AuthUtils.deleteDevice(userId, deviceId)
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

                    // ===== CONTENT REPOSITORY =====

                    // POST /upload - Upload media
                    post("/upload") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            val multipart = call.receiveMultipart()
                            var filename: String? = null
                            var contentType: String? = null
                            var content: ByteArray? = null

                            multipart.forEachPart { part ->
                                when (part) {
                                    is PartData.FileItem -> {
                                        filename = part.originalFileName
                                        contentType = part.contentType?.toString()
                                        content = part.streamProvider().use { it.readBytes() }
                                    }
                                    is PartData.FormItem -> {
                                        // Handle form fields if needed
                                    }
                                    is PartData.BinaryChannelItem -> {
                                        // Handle binary channel items if needed
                                    }
                                    else -> {
                                        // Handle other part types
                                    }
                                }
                                part.dispose()
                            }

                            if (content == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "No file provided"
                                ))
                                return@post
                            }

                            // Generate media ID
                            val mediaId = "media_${System.currentTimeMillis()}_${content.hashCode()}"

                            // Store media
                            val success = MediaStorage.storeMedia(mediaId, content!!, contentType ?: "application/octet-stream", filename)
                            if (!success) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "errcode" to "M_UNKNOWN",
                                    "error" to "Failed to store media"
                                ))
                                return@post
                            }

                            // Return MXC URI
                            val mxcUri = "mxc://localhost/$mediaId"
                            call.respond(mapOf("content_uri" to mxcUri))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /download/{serverName}/{mediaId} - Download media
                    get("/download/{serverName}/{mediaId}") {
                        try {
                            val serverName = call.parameters["serverName"]
                            val mediaId = call.parameters["mediaId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (serverName == null || mediaId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing serverName or mediaId parameter"
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

                            // Check if media exists
                            if (!MediaStorage.mediaExists(mediaId)) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Media not found"
                                ))
                                return@get
                            }

                            // Get media content
                            val (content, contentType) = MediaStorage.getMedia(mediaId)
                            if (content == null || contentType == null) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "errcode" to "M_UNKNOWN",
                                    "error" to "Failed to retrieve media"
                                ))
                                return@get
                            }

                            // Get metadata
                            val metadata = MediaStorage.getMediaMetadata(mediaId)

                            // Return multipart response as per spec
                            val boundary = "boundary_${System.currentTimeMillis()}"

                            // Create metadata part
                            val metadataMap = mapOf(
                                "content_uri" to "mxc://$serverName/$mediaId",
                                "content_type" to contentType,
                                "content_length" to content.size
                            )

                            val metadataJson = Json.encodeToString(JsonObject.serializer(), JsonObject(metadataMap.mapValues { JsonPrimitive(it.value.toString()) }))

                            // Create the multipart response
                            val multipartContent = """
--$boundary
Content-Type: application/json

$metadataJson
--$boundary
Content-Type: $contentType

${String(content, Charsets.UTF_8)}
--$boundary--
                            """.trimIndent()

                            call.respondText(
                                contentType = ContentType.MultiPart.Mixed.withParameter("boundary", boundary),
                                text = multipartContent
                            )

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /thumbnail/{serverName}/{mediaId} - Get media thumbnail
                    get("/thumbnail/{serverName}/{mediaId}") {
                        try {
                            val serverName = call.parameters["serverName"]
                            val mediaId = call.parameters["mediaId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (serverName == null || mediaId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing serverName or mediaId parameter"
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

                            // Parse query parameters
                            val width = call.request.queryParameters["width"]?.toIntOrNull()
                            val height = call.request.queryParameters["height"]?.toIntOrNull()
                            val method = call.request.queryParameters["method"] ?: "scale"
                            val animated = call.request.queryParameters["animated"]?.toBoolean() ?: false
                            val timeoutMs = call.request.queryParameters["timeout_ms"]?.toLongOrNull() ?: 20000L

                            // Validate required parameters
                            if (width == null || height == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing width or height parameter"
                                ))
                                return@get
                            }

                            // Validate method parameter
                            if (method !in setOf("crop", "scale")) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Invalid method parameter"
                                ))
                                return@get
                            }

                            // Validate dimensions
                            if (width <= 0 || height <= 0 || width > 1000 || height > 1000) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Invalid dimensions"
                                ))
                                return@get
                            }

                            // Check if media exists
                            if (!MediaStorage.mediaExists(mediaId)) {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Media not found"
                                ))
                                return@get
                            }

                            // Generate thumbnail
                            val thumbnailData = MediaStorage.generateThumbnail(mediaId, width, height, method)
                            if (thumbnailData == null) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "errcode" to "M_UNKNOWN",
                                    "error" to "Failed to generate thumbnail"
                                ))
                                return@get
                            }

                            // Return multipart response as per spec
                            val boundary = "boundary_${System.currentTimeMillis()}"

                            // Create metadata part
                            val metadata = mapOf(
                                "content_uri" to "mxc://$serverName/$mediaId",
                                "content_type" to "image/jpeg",
                                "content_length" to thumbnailData.size,
                                "width" to width,
                                "height" to height,
                                "method" to method,
                                "animated" to animated
                            )

                            val metadataJson = Json.encodeToString(JsonObject.serializer(), JsonObject(metadata.mapValues { JsonPrimitive(it.value.toString()) }))

                            // Create the multipart response
                            val multipartContent = """
--$boundary
Content-Type: application/json

$metadataJson
--$boundary
Content-Type: image/jpeg

${String(thumbnailData, Charsets.UTF_8)}
--$boundary--
                            """.trimIndent()

                            call.respondText(
                                contentType = ContentType.MultiPart.Mixed.withParameter("boundary", boundary),
                                text = multipartContent
                            )

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /config - Get upload configuration
                    get("/config") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Return upload configuration
                            val config = mapOf(
                                "upload_size" to mapOf(
                                    "max_upload_size" to 10485760L, // 10MB
                                    "max_image_size" to 10485760L,
                                    "max_avatar_size" to 1048576L, // 1MB
                                    "max_thumbnail_size" to 1048576L
                                ),
                                "thumbnail_sizes" to listOf(
                                    mapOf("width" to 32, "height" to 32, "method" to "crop"),
                                    mapOf("width" to 96, "height" to 96, "method" to "crop"),
                                    mapOf("width" to 320, "height" to 240, "method" to "scale"),
                                    mapOf("width" to 640, "height" to 480, "method" to "scale"),
                                    mapOf("width" to 800, "height" to 600, "method" to "scale")
                                )
                            )

                            call.respond(config)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== PUSH NOTIFICATIONS =====

                    // GET /pushrules/ - Get push rules
                    get("/pushrules/") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get user's push rules (simplified - in real implementation, query push rules table)
                            val pushRules = mapOf(
                                "global" to mapOf(
                                    "override" to emptyList<Map<String, Any>>(),
                                    "underride" to emptyList<Map<String, Any>>(),
                                    "sender" to emptyList<Map<String, Any>>(),
                                    "room" to emptyList<Map<String, Any>>(),
                                    "content" to listOf(
                                        mapOf(
                                            "rule_id" to "content",
                                            "default" to true,
                                            "enabled" to true,
                                            "conditions" to listOf(
                                                mapOf(
                                                    "kind" to "event_match",
                                                    "key" to "content.body",
                                                    "pattern" to "*"
                                                )
                                            ),
                                            "actions" to listOf(
                                                mapOf(
                                                    "set_tweak" to "highlight",
                                                    "value" to false
                                                ),
                                                mapOf(
                                                    "set_tweak" to "sound",
                                                    "value" to "default"
                                                ),
                                                "notify"
                                            )
                                        )
                                    )
                                )
                            )

                            call.respond(pushRules)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /pushrules/global/{kind}/{ruleId} - Get specific push rule
                    get("/pushrules/global/{kind}/{ruleId}") {
                        try {
                            val kind = call.parameters["kind"]
                            val ruleId = call.parameters["ruleId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (kind == null || ruleId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing kind or ruleId parameter"
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

                            // Get specific push rule (simplified)
                            if (kind == "content" && ruleId == "content") {
                                val pushRule = mapOf(
                                    "rule_id" to "content",
                                    "default" to true,
                                    "enabled" to true,
                                    "conditions" to listOf(
                                        mapOf(
                                            "kind" to "event_match",
                                            "key" to "content.body",
                                            "pattern" to "*"
                                        )
                                    ),
                                    "actions" to listOf(
                                        mapOf(
                                            "set_tweak" to "highlight",
                                            "value" to false
                                        ),
                                        mapOf(
                                            "set_tweak" to "sound",
                                            "value" to "default"
                                        ),
                                        "notify"
                                    )
                                )
                                call.respond(pushRule)
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Push rule not found"
                                ))
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // PUT /pushrules/global/{kind}/{ruleId} - Set push rule
                    put("/pushrules/global/{kind}/{ruleId}") {
                        try {
                            val kind = call.parameters["kind"]
                            val ruleId = call.parameters["ruleId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (kind == null || ruleId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing kind or ruleId parameter"
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
                            val pushRule = Json.parseToJsonElement(request).jsonObject

                            // Validate push rule structure
                            val conditions = pushRule["conditions"]?.jsonArray
                            val actions = pushRule["actions"]?.jsonArray

                            if (conditions == null || actions == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing conditions or actions"
                                ))
                                return@put
                            }

                            // Store push rule (simplified - acknowledge request)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // DELETE /pushrules/global/{kind}/{ruleId} - Delete push rule
                    delete("/pushrules/global/{kind}/{ruleId}") {
                        try {
                            val kind = call.parameters["kind"]
                            val ruleId = call.parameters["ruleId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (kind == null || ruleId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing kind or ruleId parameter"
                                ))
                                return@delete
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@delete
                            }

                            // Delete push rule (simplified - acknowledge request)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /admin/server_version - Get server version
                    get("/admin/server_version") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Check if user is admin (simplified - check if user ID contains 'admin')
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@get
                            }

                            val serverVersion = mapOf(
                                "server_version" to "1.0.0",
                                "python_version" to "Kotlin/JVM"
                            )

                            call.respond(serverVersion)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /admin/crypto_test - Test cryptographic compliance with Matrix specification
                    get("/admin/crypto_test") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@get
                            }

                            // Run cryptographic test vectors
                            val testResults = ServerKeys.testWithSpecificationSeed()

                            val response = mapOf(
                                "test_results" to testResults,
                                "all_passed" to testResults.values.all { it },
                                "matrix_specification_version" to "v1.15",
                                "implementation_status" to if (testResults.values.all { it }) "COMPLIANT" else "NON_COMPLIANT"
                            )

                            call.respond(response)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error: ${e.message}"
                            ))
                        }
                    }

                    // POST /admin/whois/{userId} - Get user information
                    post("/admin/whois/{userId}") {
                        try {
                            val targetUserId = call.parameters["userId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (targetUserId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId parameter"
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

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@post
                            }

                            // Get user information (simplified)
                            val userInfo = mapOf(
                                "user_id" to targetUserId,
                                "devices" to mapOf(
                                    "device_1" to mapOf(
                                        "sessions" to listOf(
                                            mapOf(
                                                "connections" to listOf(
                                                    mapOf(
                                                        "ip" to "127.0.0.1",
                                                        "last_seen" to System.currentTimeMillis(),
                                                        "user_agent" to "FERRETCANNON/1.0.0"
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )

                            call.respond(userInfo)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /admin/server_notice/{userId} - Send server notice
                    post("/admin/server_notice/{userId}") {
                        try {
                            val targetUserId = call.parameters["userId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (targetUserId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId parameter"
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

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val content = json["content"]?.jsonObject

                            if (content == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing content"
                                ))
                                return@post
                            }

                            // Send server notice (simplified - acknowledge request)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /admin/registration_tokens - Get registration tokens
                    get("/admin/registration_tokens") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@get
                            }

                            // Get registration tokens (simplified)
                            val tokens = listOf(
                                mapOf(
                                    "token" to "abc123",
                                    "uses_allowed" to null,
                                    "pending" to 0,
                                    "completed" to 1,
                                    "expiry_time" to null
                                )
                            )

                            call.respond(mapOf("registration_tokens" to tokens))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /admin/registration_tokens - Create registration token
                    post("/admin/registration_tokens") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@post
                            }

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@post
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val usesAllowed = json["uses_allowed"]?.jsonPrimitive?.int
                            val expiryTime = json["expiry_time"]?.jsonPrimitive?.long

                            // Generate token
                            val token = "token_${System.currentTimeMillis()}"

                            val tokenInfo = mapOf(
                                "token" to token,
                                "uses_allowed" to usesAllowed,
                                "pending" to 0,
                                "completed" to 0,
                                "expiry_time" to expiryTime
                            )

                            call.respond(tokenInfo)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // DELETE /admin/registration_tokens/{token} - Delete registration token
                    delete("/admin/registration_tokens/{token}") {
                        try {
                            val token = call.parameters["token"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (token == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing token parameter"
                                ))
                                return@delete
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@delete
                            }

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@delete
                            }

                            // Delete token (simplified - acknowledge request)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // POST /admin/deactivate/{userId} - Deactivate user
                    post("/admin/deactivate/{userId}") {
                        try {
                            val targetUserId = call.parameters["userId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (targetUserId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing userId parameter"
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

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@post
                            }

                            // Deactivate user (simplified - acknowledge request)
                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /admin/rooms/{roomId} - Get room information
                    get("/admin/rooms/{roomId}") {
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

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@get
                            }

                            // Get room information (simplified)
                            val roomInfo = mapOf(
                                "room_id" to roomId,
                                "name" to "Test Room",
                                "topic" to "Test topic",
                                "canonical_alias" to "#test:localhost",
                                "joined_members" to 1,
                                "joined_local_members" to 1,
                                "version" to "9",
                                "creator" to "@test:localhost",
                                "encryption" to null,
                                "federatable" to true,
                                "public" to false,
                                "join_rules" to "invite",
                                "guest_access" to "forbidden",
                                "history_visibility" to "shared",
                                "state_events" to 5
                            )

                            call.respond(roomInfo)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // DELETE /admin/rooms/{roomId} - Delete room
                    delete("/admin/rooms/{roomId}") {
                        try {
                            val roomId = call.parameters["roomId"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (roomId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing roomId parameter"
                                ))
                                return@delete
                            }

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@delete
                            }

                            // Check if user is admin
                            if (!userId.contains("admin")) {
                                call.respond(HttpStatusCode.Forbidden, mapOf(
                                    "errcode" to "M_FORBIDDEN",
                                    "error" to "Insufficient permissions"
                                ))
                                return@delete
                            }

                            val request = call.receiveText()
                            val json = Json.parseToJsonElement(request).jsonObject
                            val block = json["block"]?.jsonPrimitive?.boolean ?: false
                            val purge = json["purge"]?.jsonPrimitive?.boolean ?: true

                            // Delete room (simplified - acknowledge request)
                            call.respond(mapOf(
                                "delete_id" to "delete_${System.currentTimeMillis()}",
                                "kicked_users" to emptyList<String>(),
                                "failed_to_kick_users" to emptyList<String>(),
                                "local_aliases" to emptyList<String>()
                            ))

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== THIRD-PARTY NETWORKS =====

                    // GET /thirdparty/protocols - Get available protocols
                    get("/thirdparty/protocols") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get available protocols (simplified)
                            val protocols = mapOf(
                                "irc" to mapOf(
                                    "user_fields" to listOf("username", "irc_server"),
                                    "location_fields" to listOf("irc_server", "irc_channel"),
                                    "icon" to "mxc://localhost/irc_icon",
                                    "field_types" to mapOf(
                                        "username" to mapOf("regexp" to "[^@]+", "placeholder" to "username"),
                                        "irc_server" to mapOf("regexp" to "irc\\..*", "placeholder" to "irc.example.com"),
                                        "irc_channel" to mapOf("regexp" to "#.*", "placeholder" to "#channel")
                                    ),
                                    "instances" to listOf(
                                        mapOf(
                                            "desc" to "Example IRC network",
                                            "icon" to "mxc://localhost/irc_icon",
                                            "fields" to mapOf("irc_server" to "irc.example.com"),
                                            "network_id" to "example"
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

                    // GET /thirdparty/protocol/{protocol} - Get protocol metadata
                    get("/thirdparty/protocol/{protocol}") {
                        try {
                            val protocol = call.parameters["protocol"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (protocol == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing protocol parameter"
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

                            // Get protocol metadata (simplified)
                            if (protocol == "irc") {
                                val protocolInfo = mapOf(
                                    "user_fields" to listOf("username", "irc_server"),
                                    "location_fields" to listOf("irc_server", "irc_channel"),
                                    "icon" to "mxc://localhost/irc_icon",
                                    "field_types" to mapOf(
                                        "username" to mapOf("regexp" to "[^@]+", "placeholder" to "username"),
                                        "irc_server" to mapOf("regexp" to "irc\\..*", "placeholder" to "irc.example.com"),
                                        "irc_channel" to mapOf("regexp" to "#.*", "placeholder" to "#channel")
                                    ),
                                    "instances" to listOf(
                                        mapOf(
                                            "desc" to "Example IRC network",
                                            "icon" to "mxc://localhost/irc_icon",
                                            "fields" to mapOf("irc_server" to "irc.example.com"),
                                            "network_id" to "example"
                                        )
                                    )
                                )
                                call.respond(protocolInfo)
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf(
                                    "errcode" to "M_NOT_FOUND",
                                    "error" to "Protocol not found"
                                ))
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /thirdparty/user/{protocol} - Get user by protocol
                    get("/thirdparty/user/{protocol}") {
                        try {
                            val protocol = call.parameters["protocol"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (protocol == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing protocol parameter"
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

                            val fields = call.request.queryParameters.entries().associate { it.key to it.value.first() }

                            // Get user by protocol (simplified)
                            val users = listOf(
                                mapOf(
                                    "userid" to "@irc_user:localhost",
                                    "protocol" to protocol,
                                    "fields" to fields
                                )
                            )

                            call.respond(users)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /thirdparty/location/{protocol} - Get location by protocol
                    get("/thirdparty/location/{protocol}") {
                        try {
                            val protocol = call.parameters["protocol"]
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (protocol == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "errcode" to "M_INVALID_PARAM",
                                    "error" to "Missing protocol parameter"
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

                            val fields = call.request.queryParameters.entries().associate { it.key to it.value.first() }

                            // Get location by protocol (simplified)
                            val locations = listOf(
                                mapOf(
                                    "alias" to "#irc_channel:localhost",
                                    "protocol" to protocol,
                                    "fields" to fields
                                )
                            )

                            call.respond(locations)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // GET /thirdparty/location - Get all locations
                    get("/thirdparty/location") {
                        try {
                            val userId = call.attributes.getOrNull(AttributeKey<String>("matrix-user-id"))

                            if (userId == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "errcode" to "M_MISSING_TOKEN",
                                    "error" to "Missing access token"
                                ))
                                return@get
                            }

                            // Get all locations (simplified)
                            val locations = listOf(
                                mapOf(
                                    "alias" to "#irc_channel:localhost",
                                    "protocol" to "irc",
                                    "fields" to mapOf("irc_server" to "irc.example.com", "irc_channel" to "#channel")
                                )
                            )

                            call.respond(locations)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "errcode" to "M_UNKNOWN",
                                "error" to "Internal server error"
                            ))
                        }
                    }

                    // ===== OAUTH 2.0 API =====

                    // GET /oauth2/authorize - OAuth 2.0 authorization
                    get("/oauth2/authorize") {
                        try {
                            val responseType = call.request.queryParameters["response_type"]
                            val clientId = call.request.queryParameters["client_id"]
                            val redirectUri = call.request.queryParameters["redirect_uri"]
                            val scope = call.request.queryParameters["scope"]
                            val state = call.request.queryParameters["state"]
                            val providerId = call.request.queryParameters["provider"]

                            // Validate required parameters
                            if (responseType != "code") {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "unsupported_response_type",
                                    "error_description" to "Only 'code' response type is supported"
                                ))
                                return@get
                            }

                            if (clientId == null || redirectUri == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Missing client_id or redirect_uri"
                                ))
                                return@get
                            }

                            // Get OAuth provider
                            val provider = if (providerId != null) {
                                OAuthConfig.getProvider(providerId)
                            } else {
                                OAuthConfig.getDefaultProvider()
                            }

                            if (provider == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Invalid or unsupported OAuth provider"
                                ))
                                return@get
                            }

                            // Generate state for CSRF protection
                            val oauthState = OAuthConfig.generateState()
                            OAuthService.storeState(oauthState, provider.id, redirectUri)

                            // Build authorization URL
                            val authUrl = OAuthService.buildAuthorizationUrl(
                                provider = provider,
                                state = oauthState,
                                additionalParams = mapOf(
                                    "scope" to (scope ?: provider.scope),
                                    "client_id" to clientId,
                                    "redirect_uri" to redirectUri
                                )
                            )

                            call.respondRedirect(authUrl)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "error" to "server_error",
                                "error_description" to "Internal server error"
                            ))
                        }
                    }

                    // POST /oauth2/token - OAuth 2.0 token exchange
                    post("/oauth2/token") {
                        try {
                            val grantType = call.receiveParameters()["grant_type"]
                            val code = call.receiveParameters()["code"]
                            val redirectUri = call.receiveParameters()["redirect_uri"]
                            val clientId = call.receiveParameters()["client_id"]
                            val clientSecret = call.receiveParameters()["client_secret"]
                            val refreshToken = call.receiveParameters()["refresh_token"]

                            // Validate grant type
                            if (grantType != "authorization_code" && grantType != "refresh_token") {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "unsupported_grant_type",
                                    "error_description" to "Only 'authorization_code' and 'refresh_token' grant types are supported"
                                ))
                                return@post
                            }

                            when (grantType) {
                                "authorization_code" -> {
                                    if (code == null || redirectUri == null || clientId == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "error" to "invalid_request",
                                            "error_description" to "Missing required parameters"
                                        ))
                                        return@post
                                    }

                                    // Validate authorization code
                                    val codeValidation = OAuthService.validateAuthorizationCode(code)
                                    if (codeValidation == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "error" to "invalid_grant",
                                            "error_description" to "Invalid or expired authorization code"
                                        ))
                                        return@post
                                    }

                                    val (userId, storedClientId, scope) = codeValidation

                                    // Validate client ID matches
                                    if (clientId != storedClientId) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "error" to "invalid_client",
                                            "error_description" to "Client ID mismatch"
                                        ))
                                        return@post
                                    }

                                    // Get OAuth provider for this user (simplified - assume default provider)
                                    val provider = OAuthConfig.getDefaultProvider()
                                    if (provider == null) {
                                        call.respond(HttpStatusCode.InternalServerError, mapOf(
                                            "error" to "server_error",
                                            "error_description" to "OAuth provider not configured"
                                        ))
                                        return@post
                                    }

                                    // Exchange code for tokens (simplified - generate tokens directly)
                                    val accessToken = OAuthConfig.generateAccessToken()
                                    val refreshTokenValue = OAuthConfig.generateRefreshToken()

                                    // Store tokens
                                    OAuthService.storeAccessToken(
                                        accessToken = accessToken,
                                        refreshToken = refreshTokenValue,
                                        clientId = clientId,
                                        userId = userId,
                                        scope = scope,
                                        expiresIn = 3600L // 1 hour
                                    )

                                    call.respond(mapOf(
                                        "access_token" to accessToken,
                                        "token_type" to "Bearer",
                                        "expires_in" to 3600,
                                        "refresh_token" to refreshTokenValue,
                                        "scope" to scope
                                    ))
                                }

                                "refresh_token" -> {
                                    if (refreshToken == null || clientId == null) {
                                        call.respond(HttpStatusCode.BadRequest, mapOf(
                                            "error" to "invalid_request",
                                            "error_description" to "Missing refresh_token or client_id"
                                        ))
                                        return@post
                                    }

                                    // Get provider and refresh token
                                    val provider = OAuthConfig.getDefaultProvider()
                                    if (provider == null) {
                                        call.respond(HttpStatusCode.InternalServerError, mapOf(
                                            "error" to "server_error",
                                            "error_description" to "OAuth provider not configured"
                                        ))
                                        return@post
                                    }

                                    // Generate new tokens
                                    val newAccessToken = OAuthConfig.generateAccessToken()
                                    val newRefreshToken = OAuthConfig.generateRefreshToken()

                                    // Store new tokens (simplified - in real implementation, update existing)
                                    OAuthService.storeAccessToken(
                                        accessToken = newAccessToken,
                                        refreshToken = newRefreshToken,
                                        clientId = clientId,
                                        userId = "@test:localhost", // Simplified
                                        scope = "openid profile",
                                        expiresIn = 3600L
                                    )

                                    call.respond(mapOf(
                                        "access_token" to newAccessToken,
                                        "token_type" to "Bearer",
                                        "expires_in" to 3600,
                                        "refresh_token" to newRefreshToken,
                                        "scope" to "openid profile"
                                    ))
                                }
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "error" to "server_error",
                                "error_description" to "Internal server error"
                            ))
                        }
                    }

                    // POST /oauth2/userinfo - OAuth 2.0 user info
                    post("/oauth2/userinfo") {
                        try {
                            val authHeader = call.request.headers["Authorization"]
                            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "error" to "invalid_token",
                                    "error_description" to "Missing or invalid access token"
                                ))
                                return@post
                            }

                            val accessToken = authHeader.substringAfter("Bearer ")

                            // Validate access token
                            val tokenValidation = OAuthService.validateAccessToken(accessToken)
                            if (tokenValidation == null) {
                                call.respond(HttpStatusCode.Unauthorized, mapOf(
                                    "error" to "invalid_token",
                                    "error_description" to "Invalid or expired access token"
                                ))
                                return@post
                            }

                            val (userId, clientId, scope) = tokenValidation

                            // Get user profile information (simplified)
                            val userInfo = mapOf(
                                "sub" to userId,
                                "name" to userId.substringAfter("@").substringBefore(":"),
                                "preferred_username" to userId.substringAfter("@").substringBefore(":"),
                                "profile" to "https://localhost/profile/$userId",
                                "picture" to "https://localhost/avatar/$userId"
                            )

                            call.respond(userInfo)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "error" to "server_error",
                                "error_description" to "Internal server error"
                            ))
                        }
                    }

                    // POST /oauth2/revoke - OAuth 2.0 token revocation
                    post("/oauth2/revoke") {
                        try {
                            val token = call.receiveParameters()["token"]
                            val tokenTypeHint = call.receiveParameters()["token_type_hint"]

                            if (token == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Missing token parameter"
                                ))
                                return@post
                            }

                            // Revoke the token
                            OAuthService.revokeToken(token)

                            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "error" to "server_error",
                                "error_description" to "Internal server error"
                            ))
                        }
                    }

                    // POST /oauth2/introspect - OAuth 2.0 token introspection
                    post("/oauth2/introspect") {
                        try {
                            val token = call.receiveParameters()["token"]
                            val tokenTypeHint = call.receiveParameters()["token_type_hint"]

                            if (token == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Missing token parameter"
                                ))
                                return@post
                            }

                            // Validate access token
                            val tokenValidation = OAuthService.validateAccessToken(token)
                            if (tokenValidation != null) {
                                val (userId, clientId, scope) = tokenValidation
                                call.respond(mapOf(
                                    "active" to true,
                                    "client_id" to clientId,
                                    "username" to userId,
                                    "scope" to scope,
                                    "token_type" to "Bearer",
                                    "exp" to (System.currentTimeMillis() / 1000 + 3600),
                                    "iat" to (System.currentTimeMillis() / 1000),
                                    "sub" to userId
                                ))
                            } else {
                                call.respond(mapOf("active" to false))
                            }

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "error" to "server_error",
                                "error_description" to "Internal server error"
                            ))
                        }
                    }

                    // ===== OAUTH CALLBACK ENDPOINTS =====

                    // GET /oauth2/callback/{provider} - OAuth provider callback
                    get("/oauth2/callback/{provider}") {
                        try {
                            val providerId = call.parameters["provider"]
                            val code = call.request.queryParameters["code"]
                            val state = call.request.queryParameters["state"]
                            val error = call.request.queryParameters["error"]
                            val errorDescription = call.request.queryParameters["error_description"]

                            if (providerId == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Missing provider parameter"
                                ))
                                return@get
                            }

                            // Handle OAuth errors
                            if (error != null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to error,
                                    "error_description" to (errorDescription ?: "OAuth provider error")
                                ))
                                return@get
                            }

                            if (code == null || state == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Missing authorization code or state"
                                ))
                                return@get
                            }

                            // Validate state for CSRF protection
                            val storedProviderId = OAuthService.validateState(state)
                            if (storedProviderId == null || storedProviderId != providerId) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_state",
                                    "error_description" to "Invalid or expired state parameter"
                                ))
                                return@get
                            }

                            // Get OAuth provider
                            val provider = OAuthConfig.getProvider(providerId)
                            if (provider == null) {
                                call.respond(HttpStatusCode.BadRequest, mapOf(
                                    "error" to "invalid_request",
                                    "error_description" to "Invalid OAuth provider"
                                ))
                                return@get
                            }

                            // Exchange authorization code for access token
                            val tokenResult = OAuthService.exchangeCodeForToken(
                                provider = provider,
                                code = code,
                                redirectUri = provider.redirectUri
                            )

                            if (tokenResult.isFailure) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "error" to "server_error",
                                    "error_description" to "Failed to exchange authorization code for token"
                                ))
                                return@get
                            }

                            val tokenResponse = tokenResult.getOrThrow()

                            // Get user information from OAuth provider
                            val userInfoResult = OAuthService.getUserInfo(provider, tokenResponse.access_token)
                            if (userInfoResult.isFailure) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "error" to "server_error",
                                    "error_description" to "Failed to get user information from OAuth provider"
                                ))
                                return@get
                            }

                            val userInfo = userInfoResult.getOrThrow()

                            // Create or update Matrix user account
                            val matrixUserId = "@${userInfo.email?.substringBefore("@") ?: userInfo.id ?: "oauth_user"}:localhost"

                            // Check if user exists, create if not
                            val existingUser = transaction {
                                Users.select { Users.userId eq matrixUserId }.singleOrNull()
                            }

                            if (existingUser == null) {
                                // Create new user
                                AuthUtils.createUser(
                                    username = userInfo.email?.substringBefore("@") ?: userInfo.id ?: "oauth_user",
                                    password = "", // OAuth users don't have passwords
                                    displayName = userInfo.name ?: userInfo.email ?: "OAuth User",
                                    isGuest = false
                                )
                            }

                            // Generate authorization code for Matrix client
                            val matrixAuthCode = OAuthConfig.generateAuthorizationCode()
                            OAuthService.storeAuthorizationCode(
                                code = matrixAuthCode,
                                clientId = "matrix_client", // Simplified
                                userId = matrixUserId,
                                redirectUri = "http://localhost:3000", // Simplified
                                scope = tokenResponse.scope ?: "openid profile",
                                state = state
                            )

                            // Redirect back to Matrix client with authorization code
                            val redirectUrl = "http://localhost:3000?code=$matrixAuthCode&state=$state"
                            call.respondRedirect(redirectUrl)

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf(
                                "error" to "server_error",
                                "error_description" to "Internal server error"
                            ))
                        }
                    }

                    // GET /.well-known/oauth-authorization-server - OAuth discovery
                    get("/.well-known/oauth-authorization-server") {
                        call.respond(mapOf(
                            "issuer" to "https://localhost:8080",
                            "authorization_endpoint" to "https://localhost:8080/_matrix/client/v3/oauth2/authorize",
                            "token_endpoint" to "https://localhost:8080/_matrix/client/v3/oauth2/token",
                            "userinfo_endpoint" to "https://localhost:8080/_matrix/client/v3/oauth2/userinfo",
                            "revocation_endpoint" to "https://localhost:8080/_matrix/client/v3/oauth2/revoke",
                            "introspection_endpoint" to "https://localhost:8080/_matrix/client/v3/oauth2/introspect",
                            "jwks_uri" to "https://localhost:8080/_matrix/client/v3/oauth2/jwks",
                            "scopes_supported" to listOf("openid", "profile", "email"),
                            "response_types_supported" to listOf("code"),
                            "grant_types_supported" to listOf("authorization_code", "refresh_token"),
                            "token_endpoint_auth_methods_supported" to listOf("client_secret_post"),
                            "code_challenge_methods_supported" to listOf("S256", "plain")
                        ))
                    }

                    // GET /oauth2/jwks - JSON Web Key Set (simplified)
                    get("/oauth2/jwks") {
                        call.respond(mapOf(
                            "keys" to listOf(
                                mapOf(
                                    "kty" to "RSA",
                                    "use" to "sig",
                                    "kid" to "rsa1",
                                    "n" to "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtmUAmh9K8X1GYTAJwTDFb",
                                    "e" to "AQAB"
                                )
                            )
                        ))
                    }
                }
            }
        }
    }
}