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

                    buildJsonObject {
                        put("room_id", roomId)
                        if (name != null) put("name", name)
                        if (topic != null) put("topic", topic)
                        if (canonicalAlias != null) put("canonical_alias", canonicalAlias)
                        put("num_joined_members", joinedMembers)
                        put("world_readable", worldReadable)
                        put("guest_can_join", guestCanJoin)
                        if (avatarUrl != null) put("avatar_url", avatarUrl)
                    }
                }
            }

            call.respond(buildJsonObject {
                put("chunk", JsonArray(publishedRooms.map { Json.encodeToJsonElement(it) }))
                put("total_room_count_estimate", publishedRooms.size)
            })
        } catch (e: Exception) {
            println("Public rooms error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
    post("/publicRooms") {
        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@post
        }

        try {
            val requestBody = Json.parseToJsonElement(body).jsonObject
            val roomId = requestBody["room_id"]?.jsonPrimitive?.content
            val visibility = requestBody["visibility"]?.jsonPrimitive?.content ?: "public"

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing room_id")
                })
                return@post
            }

            if (visibility != "public") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Only public visibility is supported")
                })
                return@post
            }

            // Check if room exists
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
                return@post
            }

            // Update room's published status
            transaction {
                Rooms.update({ Rooms.roomId eq roomId }) {
                    it[published] = true
                }
            }

            call.respond(buildJsonObject {
                put("success", true)
            })
        } catch (e: Exception) {
            println("Publish room error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
}
