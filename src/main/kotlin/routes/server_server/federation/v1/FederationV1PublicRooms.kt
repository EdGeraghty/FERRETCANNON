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

fun Route.federationV1PublicRooms() {
    get("/publicRooms") {
        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val since = call.request.queryParameters["since"]

            // Get published rooms with pagination
            val publishedRooms = transaction {
                val query = Rooms.select { Rooms.published eq true }
                    .orderBy(Rooms.roomId)
                    .limit(limit)

                if (since != null) {
                    // Simple pagination by room_id (in a real implementation, you'd use proper pagination tokens)
                    query.andWhere { Rooms.roomId greater since }
                }

                query.map { roomRow ->
                    val roomId = roomRow[Rooms.roomId]
                    val currentState = stateResolver.getResolvedState(roomId)

                    // Extract room information from state
                    val name = currentState["m.room.name:"]?.get("name")?.jsonPrimitive?.content
                    val topic = currentState["m.room.topic:"]?.get("topic")?.jsonPrimitive?.content
                    val canonicalAlias = currentState["m.room.canonical_alias:"]?.get("alias")?.jsonPrimitive?.content
                    val avatarUrl = currentState["m.room.avatar:"]?.get("url")?.jsonPrimitive?.content

                    // Count joined members
                    val joinedMembers = currentState.entries.count { (key, value) ->
                        key.startsWith("m.room.member:") &&
                        value["membership"]?.jsonPrimitive?.content == "join"
                    }

                    // Check room settings
                    val joinRules = currentState["m.room.join_rules:"]?.get("join_rule")?.jsonPrimitive?.content ?: "invite"
                    val worldReadable = currentState["m.room.history_visibility:"]?.get("history_visibility")?.jsonPrimitive?.content == "world_readable"
                    val guestCanJoin = joinRules == "public"

                    mapOf<String, Any?>(
                        "room_id" to roomId,
                        "name" to name,
                        "topic" to topic,
                        "canonical_alias" to canonicalAlias,
                        "num_joined_members" to joinedMembers,
                        "world_readable" to worldReadable,
                        "guest_can_join" to guestCanJoin,
                        "avatar_url" to avatarUrl
                    ).filterValues { it != null }
                }
            }

            call.respond(mapOf(
                "chunk" to publishedRooms,
                "total_room_count_estimate" to publishedRooms.size
            ))
        } catch (e: Exception) {
            println("Public rooms error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
    post("/publicRooms") {
        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@post
        }

        try {
            val requestBody = Json.parseToJsonElement(body).jsonObject
            val roomId = requestBody["room_id"]?.jsonPrimitive?.content
            val visibility = requestBody["visibility"]?.jsonPrimitive?.content ?: "public"

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing room_id"))
                return@post
            }

            if (visibility != "public") {
                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Only public visibility is supported"))
                return@post
            }

            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Room not found"))
                return@post
            }

            // Update room's published status
            transaction {
                Rooms.update({ Rooms.roomId eq roomId }) {
                    it[published] = true
                }
            }

            call.respond(mapOf("success" to true))
        } catch (e: Exception) {
            println("Publish room error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
        }
    }
}
