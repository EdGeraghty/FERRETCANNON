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
import utils.MatrixAuth

fun Route.federationV1ThirdParty() {
    put("/3pid/onbind") {
        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@put
        }

        try {
            val bindRequest = Json.parseToJsonElement(body).jsonObject

            // Validate the request
            val medium = bindRequest["medium"]?.jsonPrimitive?.content
            val address = bindRequest["address"]?.jsonPrimitive?.content
            val mxid = bindRequest["mxid"]?.jsonPrimitive?.content

            if (medium == null || address == null || mxid == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing required fields")
                })
                return@put
            }

            // In a real implementation, this would:
            // 1. Validate that the third-party invite was actually sent
            // 2. Check that the MXID matches the invite
            // 3. Mark the invite as redeemed
            // 4. Possibly send a membership event

            // For now, just acknowledge the binding
            println("Third-party invite bound: $medium:$address -> $mxid")

            call.respond(buildJsonObject {
                put("success", true)
            })
        } catch (e: Exception) {
            println("3PID onbind error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
    put("/exchange_third_party_invite/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@put
        }

        try {
            val exchangeRequest = Json.parseToJsonElement(body).jsonObject

            // Validate the request
            val medium = exchangeRequest["medium"]?.jsonPrimitive?.content
            val address = exchangeRequest["address"]?.jsonPrimitive?.content
            val sender = exchangeRequest["sender"]?.jsonPrimitive?.content

            if (medium == null || address == null || sender == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing required fields")
                })
                return@put
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
                return@put
            }

            // In a real implementation, this would:
            // 1. Validate the third-party invite token
            // 2. Check that the sender has permission to invite
            // 3. Create a membership event with invite status
            // 4. Return the invite event

            // For now, create a basic invite event
            val inviteEvent = buildJsonObject {
                put("event_id", "\$${System.currentTimeMillis()}_invite")
                put("type", "m.room.member")
                put("room_id", roomId)
                put("sender", sender)
                putJsonObject("content") {
                    put("membership", "invite")
                    putJsonObject("third_party_invite") {
                        put("display_name", address)
                        putJsonObject("signed") {
                            put("mxid", "@$address:$medium")
                            put("token", "placeholder_token")
                            put("signatures", JsonObject(mutableMapOf()))
                        }
                    }
                }
                put("state_key", "@$address:$medium")
                put("origin_server_ts", System.currentTimeMillis())
                put("origin", "localhost")
            }

            call.respond(inviteEvent)
        } catch (e: Exception) {
            println("Exchange third party invite error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
}
