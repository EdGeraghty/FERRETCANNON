package routes.client_server.client.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.AccountData
import models.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import routes.client_server.client.common.*

fun Route.profileAvatarRoutes() {
    // GET /profile/{userId}/avatar_url - Get avatar URL only
    get("/profile/{userId}/avatar_url") {
        try {
            val userId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing userId parameter")
                })
                return@get
            }

            // Get user profile from database
            val profile = transaction {
                Users.select { Users.userId eq userId }.singleOrNull()
            }

            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "User not found")
                })
                return@get
            }

            // Return avatar URL if set
            val avatarUrl = profile[Users.avatarUrl]
            call.respond(buildJsonObject {
                if (avatarUrl != null) {
                    put("avatar_url", avatarUrl)
                }
            })

        } catch (e: Exception) {
            println("ERROR: Exception in GET /profile/{userId}/avatar_url: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // PUT /profile/{userId}/avatar_url - Set avatar URL
    put("/profile/{userId}/avatar_url") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val profileUserId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (profileUserId == null || userId != profileUserId) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own profile"
                ))
                return@put
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@put
            }
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val avatarUrl = jsonBody["avatar_url"]?.jsonPrimitive?.content

            // Store avatar URL in Users table
            transaction {
                Users.update({ Users.userId eq userId }) {
                    if (avatarUrl != null) {
                        it[Users.avatarUrl] = avatarUrl
                    } else {
                        it[Users.avatarUrl] = null
                    }
                }
            }

            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /profile/{userId}/avatar_url: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}