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