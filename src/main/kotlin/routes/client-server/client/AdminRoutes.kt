package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Users
import models.Rooms

fun Route.adminRoutes(config: ServerConfig) {
    // GET /server_version - Get server version information
    get("/server_version") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // TODO: Check if user is admin
            // For now, allow all authenticated users

            call.respond(mapOf(
                "server_version" to "FerretCannon 1.0",
                "python_version" to "Kotlin/JVM"
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /admin/serverinfo - Get server information
    get("/admin/serverinfo") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // TODO: Check if user is admin
            // For now, allow all authenticated users

            val userCount = transaction { Users.selectAll().count() }
            val roomCount = transaction { Rooms.selectAll().count() }

            call.respond(mapOf(
                "user_count" to userCount,
                "room_count" to roomCount,
                "server_name" to config.federation.serverName
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /admin/users/{userId} - Get user information
    get("/admin/users/{userId}") {
        try {
            val requestingUserId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val targetUserId = call.parameters["userId"]

            if (requestingUserId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (targetUserId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId parameter"
                ))
                return@get
            }

            // TODO: Check if requesting user is admin
            // For now, allow all authenticated users

            val user = transaction {
                Users.select { Users.userId eq targetUserId }
                    .singleOrNull()
                    ?.let { row ->
                        mapOf(
                            "user_id" to row[Users.userId],
                            "display_name" to row[Users.displayName],
                            "avatar_url" to row[Users.avatarUrl]
                        )
                    }
            }

            if (user != null) {
                call.respond(user)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "User not found"
                ))
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
