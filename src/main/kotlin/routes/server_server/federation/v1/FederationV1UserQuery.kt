package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import models.Rooms
import utils.StateResolver
import utils.MatrixAuth

fun Route.federationV1UserQuery() {
    get("/query/directory") {
        val roomAlias = call.request.queryParameters["room_alias"]

        if (roomAlias == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing room_alias parameter"))
            return@get
        }

        try {
            // Look up room by alias
            val roomId = findRoomByAlias(roomAlias)

            if (roomId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room alias not found"))
                return@get
            }

            // Get room information
            val roomInfo = getRoomInfo(roomId)
            if (roomInfo == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                return@get
            }

            val servers = listOf("localhost") // In a real implementation, this would include all servers that know about the room

            call.respond(mapOf(
                "room_id" to roomId,
                "servers" to servers
            ))
        } catch (e: Exception) {
            println("Query directory error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
    get("/query/profile") {
        val userId = call.request.queryParameters["user_id"]
        val field = call.request.queryParameters["field"]

        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing user_id parameter"))
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            // Get user profile information
            val profile = getUserProfile(userId, field)
            if (profile != null) {
                call.respond(profile)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "User not found"))
            }
        } catch (e: Exception) {
            println("Query profile error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
    get("/query/displayname") {
        val userId = call.request.queryParameters["user_id"]

        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing user_id parameter"))
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            // Get user display name specifically
            val profile = getUserProfile(userId, "displayname")
            if (profile != null && profile.containsKey("displayname")) {
                call.respond(mapOf("displayname" to profile["displayname"]))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "User display name not found"))
            }
        } catch (e: Exception) {
            println("Query displayname error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
    get("/query/avatar_url") {
        val userId = call.request.queryParameters["user_id"]

        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing user_id parameter"))
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            // Get user avatar URL specifically
            val profile = getUserProfile(userId, "avatar_url")
            if (profile != null && profile.containsKey("avatar_url")) {
                call.respond(mapOf("avatar_url" to profile["avatar_url"]))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "User avatar URL not found"))
            }
        } catch (e: Exception) {
            println("Query avatar_url error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
    get("/query") {
        // Generic /query endpoint - returns information about available query types
        call.respond(HttpStatusCode.BadRequest, mapOf(
            "errcode" to "M_INVALID_PARAM",
            "error" to "Query type required. Available query types: directory, profile, displayname, avatar_url"
        ))
    }
    get("/query/{queryType}") {
        val queryType = call.parameters["queryType"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            when (queryType) {
                "directory" -> {
                    // This is handled by the specific directory endpoint above
                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Use /query/directory endpoint"))
                }
                "profile" -> {
                    // This is handled by the specific profile endpoint above
                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Use /query/profile endpoint"))
                }
                else -> {
                    // Unknown query type
                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Unknown query type: $queryType"))
                }
            }
        } catch (e: Exception) {
            println("Query error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
}
