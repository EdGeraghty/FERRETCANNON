package routes.client_server.client.room

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import models.Rooms
import models.Events
import utils.StateResolver
import utils.MatrixAuth
import routes.client_server.client.common.*

/**
 * Room membership routes - thin routing layer that delegates to handlers
 * Complies with Matrix Specification v1.16
 */
fun Route.roomMembershipRoutes(config: ServerConfig) {
    val stateResolver = StateResolver()

    // POST /rooms/{roomId}/invite - Invite a user to a room
    post("/rooms/{roomId}/invite") {
        println("Invite endpoint called")
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

            // Parse request body
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

            // Delegate to invite handler
            val result = InviteHandler.sendInvite(userId, inviteeUserId, roomId, config, stateResolver)
            
            if (result.success) {
                call.respondText("{}", ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to result.errorCode,
                    "error" to result.errorMessage
                ))
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
    
    post("/rooms/{roomId}/join") {
        try {
            val userId = call.validateAccessToken() ?: return@post
            val roomId = call.parameters["roomId"]
            val serverNames = call.request.queryParameters.getAll("server_name") ?: emptyList()

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            // Check current membership
            println("JOIN: Checking membership for user $userId in room $roomId")
            val membershipInfo = LocalMembershipHandler.getCurrentMembership(roomId, userId)
            
            if (membershipInfo.membership == "join") {
                println("JOIN: User already joined")
                call.respond(mutableMapOf("room_id" to roomId))
                return@post
            }
            
            // Determine if federation is needed
            val effectiveServerNames = if (membershipInfo.membership == "invite" && membershipInfo.sender != null) {
                // Have invite with sender - use inviter's server
                val inviterServer = membershipInfo.sender.substringAfter(":")
                println("JOIN: User has invite from ${membershipInfo.sender} - will use make_join via $inviterServer")
                listOf(inviterServer)
            } else if (serverNames.isNotEmpty()) {
                // Explicit server_name parameters provided
                println("JOIN: Using provided server_name parameters: $serverNames")
                serverNames
            } else {
                // No invite and no server_name - try to extract from room ID or check for outlier events
                println("JOIN: No invite or server_name - checking for outlier events to determine origin server")
                val originServer = transaction {
                    // Check for any m.room.create outlier event that might have the origin server
                    Events.select { 
                        (Events.roomId eq roomId) and 
                        (Events.type eq "m.room.create") and
                        (Events.outlier eq true)
                    }
                    .map { it[Events.sender].substringAfter(":") }
                    .firstOrNull()
                }
                
                if (originServer != null) {
                    println("JOIN: Found origin server from outlier m.room.create event: $originServer")
                    listOf(originServer)
                } else {
                    println("JOIN: No origin server found - join will fail")
                    emptyList()
                }
            }
            
            // Check if room exists locally
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }
            
            // Use federation if needed
            if (!roomExists || effectiveServerNames.isNotEmpty()) {
                if (effectiveServerNames.isNotEmpty()) {
                    println("JOIN: Attempting federation join via: $effectiveServerNames")
                    val result = FederationJoinHandler.performFederationJoin(
                        userId, roomId, effectiveServerNames, config
                    )
                    
                    if (result.success) {
                        call.respond(mutableMapOf("room_id" to roomId))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                            "errcode" to result.errorCode,
                            "error" to result.errorMessage
                        ))
                    }
                    return@post
                }
                
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Room not found"
                ))
                return@post
            }

            // Local join
            val result = LocalMembershipHandler.createLocalJoin(userId, roomId, config, stateResolver)
            
            if (result.success) {
                call.respond(mutableMapOf("room_id" to roomId))
            } else {
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to result.errorCode,
                    "error" to result.errorMessage
                ))
            }

        } catch (e: Exception) {
            println("JOIN: Exception caught: ${e.message}")
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
            
            call.respondText("""{"deleted":$deletedCount,"room_id":"$roomId","user_id":"$targetUserId"}""", ContentType.Application.Json)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}