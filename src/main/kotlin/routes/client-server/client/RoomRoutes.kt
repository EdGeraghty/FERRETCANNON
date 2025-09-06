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
import models.Rooms
import models.Events
import utils.AuthUtils
import routes.server_server.federation.v1.broadcastEDU

fun Route.roomRoutes(config: ServerConfig) {
    // PUT /rooms/{roomId}/send/{eventType}/{txnId} - Send event to room
    put("/rooms/{roomId}/send/{eventType}/{txnId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val eventType = call.parameters["eventType"]
            val txnId = call.parameters["txnId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (roomId == null || eventType == null || txnId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            // TODO: Validate room membership and permissions
            // TODO: Store event in database
            // TODO: Broadcast event to room members

            // For now, return a mock event ID
            call.respond(mapOf(
                "event_id" to "\$mock_event_${System.currentTimeMillis()}"
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /createRoom - Create a new room
    post("/createRoom") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val roomName = jsonBody["name"]?.jsonPrimitive?.content
            val roomTopic = jsonBody["topic"]?.jsonPrimitive?.content

            // Generate room ID
            val roomId = "!room_${System.currentTimeMillis()}:localhost"

            // TODO: Store room in database
            // TODO: Add creator as room member

            call.respond(mapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /rooms/{roomId}/join - Join a room
    post("/rooms/{roomId}/join") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            // TODO: Add user to room membership
            // TODO: Send join event

            call.respond(mapOf(
                "room_id" to roomId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /rooms/{roomId}/leave - Leave a room
    post("/rooms/{roomId}/leave") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            if (roomId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId parameter"
                ))
                return@post
            }

            // TODO: Remove user from room membership
            // TODO: Send leave event

            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
