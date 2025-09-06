package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig

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
                "user_id" to "@$user:localhost",
                "access_token" to "mock_access_token_${System.currentTimeMillis()}",
                "device_id" to "mock_device",
                "home_server" to "localhost"
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
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val username = jsonBody["username"]?.jsonPrimitive?.content
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val auth = jsonBody["auth"]

            if (username == null || password == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Missing username or password"
                ))
                return@post
            }

            // TODO: Implement actual registration
            // For now, return a mock response
            call.respond(mapOf(
                "user_id" to "@$username:localhost",
                "access_token" to "mock_access_token_${System.currentTimeMillis()}",
                "device_id" to "mock_device",
                "home_server" to "localhost"
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
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

        // TODO: Check actual availability in database
        // For now, assume available
        call.respond(mapOf(
            "available" to true
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
