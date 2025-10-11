package routes.client_server.client.directory

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import routes.client_server.client.common.*
import routes.server_server.federation.v1.findRoomByAlias
import routes.server_server.federation.v1.getRoomInfo

/**
 * Room directory routes for room alias resolution
 * Matrix Spec: https://spec.matrix.org/v1.16/client-server-api/#room-directory
 */
fun Route.roomDirectoryRoutes() {
    // GET /directory/room/{roomAlias} - Get room ID from alias
    get("/directory/room/{roomAlias}") {
        try {
            call.validateAccessToken() ?: return@get
            
            val roomAlias = call.parameters["roomAlias"]
            
            if (roomAlias == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing room alias")
                })
                return@get
            }
            
            // Look up room by alias
            val roomId = findRoomByAlias(roomAlias)
            
            if (roomId == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room alias not found")
                })
                return@get
            }
            
            // Build response with room_id and servers
            call.respond(buildJsonObject {
                put("room_id", roomId)
                put("servers", buildJsonArray {
                    // Extract server from room alias
                    val aliasServer = roomAlias.substringAfter(":", "")
                    if (aliasServer.isNotEmpty()) {
                        add(aliasServer)
                    }
                })
            })
            
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
}
