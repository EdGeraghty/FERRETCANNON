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
import utils.AuthUtils

fun Route.adminRoutes(config: ServerConfig) {
    // GET /server_version - Get server version information
    get("/server_version") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Check if user is admin
            if (!AuthUtils.isUserAdmin(userId)) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "You are not authorized to access this endpoint"
                ))
                return@get
            }

            call.respond(mutableMapOf(
                "server_version" to "FerretCannon 1.0",
                "python_version" to "Kotlin/JVM"
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /admin/serverinfo - Get server information
    get("/admin/serverinfo") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Check if user is admin
            if (!AuthUtils.isUserAdmin(userId)) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "You are not authorized to access this endpoint"
                ))
                return@get
            }

            val userCount = transaction { Users.selectAll().count() }
            val roomCount = transaction { Rooms.selectAll().count() }

            call.respond(mutableMapOf(
                "user_count" to userCount,
                "room_count" to roomCount,
                "server_name" to config.federation.serverName
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /admin/users/{userId} - Get user information
    get("/admin/users/{userId}") {
        try {
            val requestingUserId = call.validateAccessToken() ?: return@get
            val targetUserId = call.parameters["userId"]

            if (targetUserId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId parameter"
                ))
                return@get
            }

            // Check if requesting user is admin
            if (!AuthUtils.isUserAdmin(requestingUserId)) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "You are not authorized to access this endpoint"
                ))
                return@get
            }

            val user = transaction {
                Users.select { Users.userId eq targetUserId }
                    .singleOrNull()
                    ?.let { row ->
                        mutableMapOf(
                            "user_id" to row[Users.userId],
                            "display_name" to row[Users.displayName],
                            "avatar_url" to row[Users.avatarUrl]
                        )
                    }
            }

            if (user != null) {
                call.respond(user)
            } else {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "User not found"
                ))
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
