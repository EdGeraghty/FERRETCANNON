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
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes

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

                            // Add ephemeral events if any
                            if (ephemeral.isNotEmpty()) {
                                response["rooms"] = mapOf(
                                    "join" to ephemeral,
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
                }
            }
        }
    }
}

suspend fun broadcastEDU(roomId: String?, edu: JsonObject) {
    val message = Json.encodeToString(JsonObject.serializer(), edu)
    if (roomId != null) {
        // Send to clients in room
        connectedClients[roomId]?.forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                // Client disconnected
            }
        }
    } else {
        // Send to all clients (for presence)
        connectedClients.values.flatten().forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                // Client disconnected
            }
        }
    }
}
