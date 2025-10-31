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
import org.jetbrains.exposed.sql.transactions.transaction
import models.Rooms
import models.Events
import utils.AuthUtils
import utils.StateResolver
import utils.MatrixAuth
import routes.server_server.federation.v1.broadcastEDU
import routes.client_server.client.common.*
import routes.client_server.client.room.InviteHandler

fun Route.roomCreationRoutes(config: ServerConfig) {
    // POST /createRoom - Create a new room
    post("/createRoom") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val roomName = jsonBody["name"]?.jsonPrimitive?.content
            val roomTopic = jsonBody["topic"]?.jsonPrimitive?.content
            val roomAlias = jsonBody["room_alias_name"]?.jsonPrimitive?.content
            val preset = jsonBody["preset"]?.jsonPrimitive?.content ?: "private_chat"
            val visibility = jsonBody["visibility"]?.jsonPrimitive?.content ?: "private"
            val initialState = jsonBody["initial_state"]?.jsonArray
            val inviteList = jsonBody["invite"]?.jsonArray
            val isDirect = jsonBody["is_direct"]?.jsonPrimitive?.boolean ?: false

            // Determine join rule based on preset
            val joinRule = when (preset) {
                "public_chat" -> "public"
                else -> "invite"
            }

            // Generate room ID
            val roomId = "!${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().replace("-", "")}:${config.federation.serverName}"

            val currentTime = System.currentTimeMillis()

            // Create room and initial state events
            val roomCreationResult = createRoomWithStateEvents(
                roomId = roomId,
                userId = userId,
                roomName = roomName,
                roomTopic = roomTopic,
                roomAlias = roomAlias,
                preset = preset,
                visibility = visibility,
                joinRule = joinRule,
                initialState = initialState,
                isDirect = isDirect,
                currentTime = currentTime,
                config = config
            )

            // Broadcast room creation events
            runBlocking {
                broadcastEDU(roomId, roomCreationResult.createEvent)
                broadcastEDU(roomId, roomCreationResult.memberEvent)
            }

            // Send invites if specified
            if (inviteList != null) {
                val stateResolver = StateResolver()
                for (inviteUserIdElement in inviteList) {
                    val inviteUserId = inviteUserIdElement.jsonPrimitive.content
                    try {
                        runBlocking {
                            InviteHandler.sendInvite(userId, inviteUserId, roomId, config, stateResolver)
                        }
                    } catch (e: Exception) {
                        // Log error but don't fail room creation
                        println("Failed to send invite to $inviteUserId: ${e.message}")
                    }
                }
            }

            call.respond(mutableMapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}