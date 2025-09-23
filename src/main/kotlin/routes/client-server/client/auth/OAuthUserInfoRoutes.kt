package routes.client_server.client.auth

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.OAuthService
import models.Users
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import routes.client_server.client.common.*

fun Route.oauthUserInfoRoutes() {
    // GET /oauth2/userinfo - OAuth userinfo endpoint
    get("/userinfo") {
        try {
            // Get Authorization header
            val authHeader = call.request.headers["Authorization"]
            if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("error", "invalid_token")
                    put("error_description", "Missing or invalid access token")
                })
                return@get
            }

            val accessToken = authHeader.substringAfter("Bearer ").trim()

            // Validate access token
            val tokenData = OAuthService.validateAccessToken(accessToken)
            if (tokenData == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("error", "invalid_token")
                    put("error_description", "Invalid or expired access token")
                })
                return@get
            }

            val (userId, _, _) = tokenData

            // Get user information from database
            val userInfo = transaction {
                val user = Users.select { Users.userId eq userId }.singleOrNull()
                if (user != null) {
                    buildJsonObject {
                        put("sub", userId)
                        put("name", user[Users.displayName] ?: userId)
                        put("preferred_username", user[Users.username])
                        // Note: email field omitted as it's not stored in current schema
                    }
                } else {
                    null
                }
            }

            if (userInfo == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("error", "user_not_found")
                    put("error_description", "User not found")
                })
                return@get
            }

            call.respond(userInfo)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }
}