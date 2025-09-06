package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.AuthUtils

fun Route.authRoutes(config: ServerConfig) {
    // GET /login - Get available login flows
    get("/login") {
        call.respond(mapOf(
            "flows" to listOf(
                mapOf(
                    "type" to "m.login.password"
                )
            )
        ))
    }

    // POST /login - User login
    post("/login") {
        try {
            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val user = jsonBody["user"]?.jsonPrimitive?.content
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val type = jsonBody["type"]?.jsonPrimitive?.content ?: "m.login.password"

            if (user == null || password == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Missing user or password"
                ))
                return@post
            }

            // TODO: Implement actual authentication
            // For now, return a mock response
            call.respond(mapOf(
                "user_id" to "@$user:${config.federation.serverName}",
                "access_token" to "mock_access_token_${System.currentTimeMillis()}",
                "device_id" to "mock_device",
                "home_server" to config.federation.serverName
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /register - User registration
    post("/register") {
        try {
            // Parse request body
            val requestBody = call.receiveText()
            
            // Handle empty request body (initial registration request)
            if (requestBody.isBlank()) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_PARAM",
                    "error" to "Missing parameters",
                    "flows" to listOf(
                        mapOf(
                            "stages" to listOf("m.login.password")
                        )
                    )
                ))
                return@post
            }
            
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val username = jsonBody["username"]?.jsonPrimitive?.content
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val auth = jsonBody["auth"]

            // If no username/password provided, this might be an initial request
            if (username == null || password == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_PARAM", 
                    "error" to "Missing username or password",
                    "flows" to listOf(
                        mapOf(
                            "stages" to listOf("m.login.password")
                        )
                    )
                ))
                return@post
            }

            // Validate username format
            if (!username.matches(Regex("^[a-zA-Z0-9._-]+$")) || username.length < 1 || username.length > 255) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_USERNAME",
                    "error" to "Invalid username format"
                ))
                return@post
            }

            // Check if username is available
            if (!AuthUtils.isUsernameAvailable(username)) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_USER_IN_USE",
                    "error" to "Username already taken"
                ))
                return@post
            }

            // Validate password strength
            val (isValidPassword, passwordError) = AuthUtils.validatePasswordStrength(password)
            if (!isValidPassword) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_WEAK_PASSWORD",
                    "error" to passwordError
                ))
                return@post
            }

            // Create the user
            val userId = AuthUtils.createUser(username, password, serverName = config.federation.serverName)

            // Generate device ID and create access token
            val deviceId = AuthUtils.generateDeviceId()
            val accessToken = AuthUtils.createAccessToken(userId, deviceId)

            call.respond(mapOf(
                "user_id" to userId,
                "access_token" to accessToken,
                "device_id" to deviceId,
                "home_server" to config.federation.serverName
            ))

        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "errcode" to "M_USER_IN_USE",
                "error" to "Username already exists"
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // GET /capabilities - Server capabilities
    get("/capabilities") {
        call.respond(mapOf(
            "capabilities" to mapOf(
                "m.change_password" to mapOf("enabled" to true),
                "m.room_versions" to mapOf(
                    "default" to "9",
                    "available" to mapOf("9" to "stable")
                ),
                "m.set_displayname" to mapOf("enabled" to true),
                "m.set_avatar_url" to mapOf("enabled" to true),
                "m.3pid_changes" to mapOf("enabled" to true)
            )
        ))
    }

    // GET /register/available - Check username availability
    get("/register/available") {
        val username = call.request.queryParameters["username"]

        if (username == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "errcode" to "M_INVALID_PARAM",
                "error" to "Missing username parameter"
            ))
            return@get
        }

        // Check actual availability in database
        val available = AuthUtils.isUsernameAvailable(username)
        call.respond(mapOf(
            "available" to available
        ))
    }

    // GET /register - Query supported registration methods
    get("/register") {
        call.respond(mapOf(
            "flows" to listOf(
                mapOf(
                    "stages" to listOf("m.login.password")
                )
            )
        ))
    }
}
