package routes.client_server.client.room

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import config.ServerConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import models.Rooms
import models.Events
import utils.StateResolver
import utils.MatrixAuth
import routes.server_server.federation.v1.broadcastEDU
import routes.client_server.client.common.*
import routes.client_server.client.room.LocalMembershipHandler
import routes.client_server.client.room.FederationJoinHandler
import org.slf4j.LoggerFactory

private val roomMembershipLogger = LoggerFactory.getLogger("routes.client_server.client.room.RoomMembershipRoutes")

fun Route.roomMembershipRoutes(config: ServerConfig) {
    val stateResolver = StateResolver()

    // Local helper to process joins so we can support both /rooms/{roomId}/join and /join/{roomId}
    suspend fun ApplicationCall.processJoin(roomParamName: String) {
        println("JOIN: ===== JOIN ENDPOINT CALLED =====")
        try {
            val userId = this.validateAccessToken() ?: return
            val roomId = this.parameters[roomParamName]

            if (roomId == null) {
                this.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return
            }

            println("JOIN: userId=$userId, roomId=$roomId")

            // Parse request body for server_name (accept array or single string), and also accept query param
            val requestBody = this.receiveText()
            println("JOIN: Raw request body: '$requestBody'")
            val jsonBody = if (requestBody.isNotBlank()) {
                Json.parseToJsonElement(requestBody).jsonObject
            } else {
                JsonObject(emptyMap())
            }

            val serverNamesFromBody: List<String> = jsonBody["server_name"]?.let { elem ->
                try {
                    when (elem) {
                        is JsonArray -> elem.mapNotNull { it.jsonPrimitive.contentOrNull }
                        is JsonPrimitive -> elem.jsonPrimitive.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                        else -> emptyList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()

            val serverNamesFromQuery: List<String> = this.request.queryParameters.getAll("server_name")
                ?.flatMap { it.split(",") }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: emptyList()

            val serverNames = (serverNamesFromBody + serverNamesFromQuery).distinct()

            println("JOIN: Parsed serverNames: $serverNames (body: $serverNamesFromBody, query: $serverNamesFromQuery)")

            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (serverNames.isNotEmpty()) {
                println("JOIN: Federation join requested via servers: $serverNames")
                val result = FederationJoinHandler.performFederationJoin(
                    userId, roomId, serverNames, config
                )

                if (result.success) {
                    this.respond(mutableMapOf("room_id" to roomId))
                    roomMembershipLogger.info("JOIN: Responded 200 for federation join room_id={}", roomId)
                } else {
                    this.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                        "errcode" to result.errorCode,
                        "error" to result.errorMessage
                    ))
                    roomMembershipLogger.info("JOIN: Responded 500 for federation join room_id={} err={}", roomId, result.errorMessage)
                }
                return
            }

            if (roomExists) {
                println("JOIN: Local join for existing room")
                val result = LocalMembershipHandler.createLocalJoin(userId, roomId, config, stateResolver)

                if (result.success) {
                    this.respond(mutableMapOf("room_id" to roomId))
                    roomMembershipLogger.info("JOIN: Responded 200 for local join room_id={}", roomId)
                } else {
                    this.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                        "errcode" to result.errorCode,
                        "error" to result.errorMessage
                    ))
                    roomMembershipLogger.info("JOIN: Responded 500 for local join room_id={} err={}", roomId, result.errorMessage)
                }
                return
            }

            this.respond(HttpStatusCode.BadRequest, mutableMapOf(
                "errcode" to "M_MISSING_PARAM",
                "error" to "server_name parameter is required when joining a room not known to this server"
            ))
            roomMembershipLogger.info("JOIN: Responded 400 missing server_name for room_id={}", roomId ?: "(null)")

        } catch (e: Exception) {
            println("JOIN: Exception caught: ${e.message}")
            e.printStackTrace()
            this.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // Existing route (spec-compliant)
    post("/rooms/{roomId}/join") {
        call.processJoin("roomId")
    }

    // Support shorthand endpoint used by some clients: POST /join/{roomId}
    post("/join/{roomId}") {
        call.processJoin("roomId")
    }

    // POST /rooms/{roomId}/invite - Invite a user to a room
    post("/rooms/{roomId}/invite") {
        try {
            val userId = call.validateAccessToken() ?: return@post
            val roomId = call.parameters["roomId"]

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val inviteeUserId = jsonBody["user_id"]?.jsonPrimitive?.content

            if (inviteeUserId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing user_id parameter"
                ))
                return@post
            }

            println("INVITE: userId=$userId, roomId=$roomId, invitee=$inviteeUserId")

            val result = runBlocking {
                InviteHandler.sendInvite(userId, inviteeUserId, roomId, config, stateResolver)
            }

            if (result.success) {
                call.respondText("{}", ContentType.Application.Json)
                roomMembershipLogger.info("INVITE: Successfully invited user {} to room {}", inviteeUserId, roomId)
            } else {
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to result.errorCode,
                    "error" to result.errorMessage
                ))
                roomMembershipLogger.error("INVITE: Failed to invite user {} to room {}: {}", inviteeUserId, roomId, result.errorMessage)
            }

        } catch (e: Exception) {
            println("INVITE: Exception caught: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // POST /rooms/{roomId}/leave - Leave a room
    post("/rooms/{roomId}/leave") {
        try {
            val userId = call.validateAccessToken() ?: return@post
            val roomId = call.parameters["roomId"]

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            // Check current membership - we might have invite events even without the room existing locally
            val membershipInfo = LocalMembershipHandler.getCurrentMembership(roomId, userId)
            val currentMembership = membershipInfo.membership

            // If no membership exists, assume already left (idempotent)
            if (currentMembership != "join" && currentMembership != "invite") {
                println("LEAVE: User has no membership in room $roomId - assuming already left")
                call.respondText("{}", ContentType.Application.Json)
                return@post
            }

            // For federated invites (invited but room doesn't exist locally), just delete the invite event
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists && currentMembership == "invite") {
                println("LEAVE: Rejecting federated invite - creating and sending leave event")

                // Get the invite event to find the origin server
                val inviteEvent = transaction {
                    Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.member") and
                        (Events.stateKey eq userId)
                    }.singleOrNull()
                }

                if (inviteEvent != null) {
                    // Create a leave event to send to the remote server
                    val currentTime = System.currentTimeMillis()
                    val sender = inviteEvent[Events.sender]
                    val remoteServer = sender.substringAfter(":")

                    val leaveContent = JsonObject(mutableMapOf("membership" to JsonPrimitive("leave")))

                    val leaveEvent = buildJsonObject {
                        put("type", "m.room.member")
                        put("room_id", roomId)
                        put("sender", userId)
                        put("content", leaveContent)
                        put("origin_server_ts", currentTime)
                        put("state_key", userId)
                        put("prev_events", Json.parseToJsonElement("[]"))
                        put("auth_events", Json.parseToJsonElement("[]"))
                        put("depth", 1)
                        put("hashes", Json.parseToJsonElement("{}"))
                        put("signatures", Json.parseToJsonElement("{}"))
                        put("origin", config.federation.serverName)
                    }

                    // Hash and sign the event
                    val signedLeaveEvent = MatrixAuth.hashAndSignEvent(leaveEvent, config.federation.serverName)

                    // Send the leave event to the remote server via federation
                    MatrixAuth.sendFederationLeave(remoteServer, roomId, signedLeaveEvent, config)
                }

                // Delete the local invite event
                val deletedCount = transaction {
                    Events.deleteWhere {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.member") and
                        (Events.stateKey eq userId)
                    }
                }
                println("LEAVE: Deleted $deletedCount invite event(s)")
                call.respondText("{}", ContentType.Application.Json)
                return@post
            }

            // For local rooms or joined state, create a proper leave event
            val result = LocalMembershipHandler.createLocalLeave(roomId, userId, stateResolver)

            if (result.success) {
                call.respondText("{}", ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to result.errorCode,
                    "error" to result.errorMessage
                ))
            }

        } catch (e: Exception) {
            println("LEAVE: Exception caught: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }

    // DEBUG: Force delete membership events
    delete("/rooms/{roomId}/membership/{userId}") {
        try {
            val accessUserId = call.validateAccessToken() ?: return@delete
            val roomId = call.parameters["roomId"]
            val targetUserId = call.parameters["userId"]

            if (roomId == null || targetUserId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId or userId parameter"
                ))
                return@delete
            }

            // Check permissions: user can only delete their own membership unless they're an admin
            if (accessUserId != targetUserId) {
                // Check if the user is a room admin
                val isAdmin = transaction {
                    Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.power_levels")
                    }.orderBy(Events.originServerTs, SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()?.let { powerLevelEvent ->
                            val content = Json.parseToJsonElement(powerLevelEvent[Events.content]).jsonObject
                            val users = content["users"]?.jsonObject
                            val userLevel = users?.get(accessUserId)?.jsonPrimitive?.intOrNull ?: 0
                            userLevel >= 50 // Default admin level
                        } ?: false
                }

                if (!isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                        "errcode" to "M_FORBIDDEN",
                        "error" to "You don't have permission to delete another user's membership"
                    ))
                    return@delete
                }
            }

            println("DEBUG: Deleting membership events for user=$targetUserId in room=$roomId")

            val deletedCount = transaction {
                Events.deleteWhere {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq targetUserId)
                }
            }

            println("DEBUG: Deleted $deletedCount membership events")

            call.respondText("{" + "\"deleted\":$deletedCount,\"room_id\":\"$roomId\",\"user_id\":\"$targetUserId\"}" , ContentType.Application.Json)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}