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

fun Route.federationV1Spaces() {
    get("/hierarchy/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val maxDepth = call.request.queryParameters["max_depth"]?.toIntOrNull() ?: 3
            val from = call.request.queryParameters["from"]
            val suggestedOnly = call.request.queryParameters["suggested_only"]?.toBoolean() ?: false

            // Check if room exists and is a space
            val roomExists = transaction {
                Rooms.select { Rooms.roomId eq roomId }.count() > 0
            }

            if (!roomExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
                return@get
            }

            val currentState = stateResolver.getResolvedState(roomId)
            val createEvent = currentState["m.room.create:"]

            // Check if this is actually a space
            val roomType = createEvent?.get("type")?.jsonPrimitive?.content
            if (roomType != "m.space") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Room is not a space")
                })
                return@get
            }

            // Get child rooms from space state
            val children = mutableListOf<Map<String, Any?>>()
            var nextToken: String? = null

            for ((stateKey, stateEvent) in currentState) {
                if (stateKey.startsWith("m.space.child:")) {
                    val childRoomId = stateKey.substringAfter("m.space.child:")
                    val content = stateEvent

                    // Check if this child should be included
                    val suggested = content["suggested"]?.jsonPrimitive?.boolean ?: false
                    if (suggestedOnly && !suggested) continue

                    // Get child room information
                    val childInfo = getRoomInfo(childRoomId)
                    if (childInfo != null) {
                        children.add(childInfo)

                        // Simple pagination (in a real implementation, you'd use proper tokens)
                        if (children.size >= limit) {
                            nextToken = childRoomId
                            break
                        }
                    }
                }
            }

            val response = buildJsonObject {
                put("rooms", Json.encodeToJsonElement(children))
                if (nextToken != null) {
                    put("next_batch", nextToken)
                }
            }

            call.respond(response)
        } catch (e: Exception) {
            println("Hierarchy error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
}
