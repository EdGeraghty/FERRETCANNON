package routes.client_server.client.event

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.*
import utils.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import routes.server_server.federation.v1.broadcastEDU
import java.time.Instant
import routes.client_server.client.common.*

fun Route.eventRedactionRoutes() {
    route("/_matrix/client/v3/rooms/{roomId}") {
        // Redaction endpoint
        put("/redact/{eventId}/{txnId}") {
            try {
                val roomId = call.parameters["roomId"] ?: return@put call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing roomId parameter")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

                val eventId = call.parameters["eventId"] ?: return@put call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing eventId parameter")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

                val txnId = call.parameters["txnId"] ?: return@put call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing txnId parameter")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

                val accessToken = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                    ?: call.request.queryParameters["access_token"]
                    ?: return@put call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_MISSING_TOKEN"),
                            "error" to JsonPrimitive("Missing access token")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )

                val tokenResult = AuthUtils.validateAccessToken(accessToken)
                if (tokenResult == null) {
                    return@put call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_UNKNOWN_TOKEN"),
                            "error" to JsonPrimitive("Invalid access token")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )
                }
                val (userId, _) = tokenResult

                val requestBody = call.receiveText()
                val requestJson = Json.parseToJsonElement(requestBody).jsonObject
                val reason = requestJson["reason"]?.jsonPrimitive?.content

                // Check if user is in the room
                val isMember = transaction {
                    Events.select {
                        (Events.roomId eq roomId) and (Events.type eq "m.room.member") and (Events.stateKey eq userId)
                    }.mapNotNull { row ->
                        Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                    }.firstOrNull() == "join"
                }

                if (!isMember) {
                    return@put call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_FORBIDDEN"),
                            "error" to JsonPrimitive("User is not a member of this room")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                }

                // Check if event exists and get its details
                val eventDetails = transaction {
                    Events.select {
                        (Events.eventId eq eventId) and (Events.roomId eq roomId)
                    }.singleOrNull()
                }

                if (eventDetails == null) {
                    return@put call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_NOT_FOUND"),
                            "error" to JsonPrimitive("Event not found")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                }

                // Check if user can redact this event (must be sender or have appropriate power level)
                val eventSender = eventDetails[Events.sender]
                val canRedact = eventSender == userId // Simplified: only sender can redact

                if (!canRedact) {
                    return@put call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_FORBIDDEN"),
                            "error" to JsonPrimitive("Insufficient power level to redact event")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                }

                // Create redaction event
                val redactionEventId = "\$${System.currentTimeMillis()}_${txnId}_redact"
                val ts = Instant.now().epochSecond * 1000

                val redactionEvent = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive(redactionEventId),
                    "type" to JsonPrimitive("m.room.redaction"),
                    "sender" to JsonPrimitive(userId),
                    "room_id" to JsonPrimitive(roomId),
                    "origin_server_ts" to JsonPrimitive(ts),
                    "content" to JsonObject(mutableMapOf(
                        "reason" to JsonPrimitive(reason ?: "")
                    )),
                    "redacts" to JsonPrimitive(eventId),
                    "unsigned" to JsonObject(mutableMapOf())
                ))

                // Store redaction event
                transaction {
                    Events.insert {
                        it[Events.eventId] = redactionEventId
                        it[Events.roomId] = roomId
                        it[Events.type] = "m.room.redaction"
                        it[Events.sender] = userId
                        it[Events.originServerTs] = ts
                        it[Events.content] = redactionEvent["content"].toString()
                        it[Events.stateKey] = null
                        it[Events.prevEvents] = JsonArray(listOf(JsonPrimitive(eventId))).toString()
                        it[Events.depth] = eventDetails[Events.depth] + 1
                        it[Events.authEvents] = JsonArray(emptyList()).toString()
                        it[Events.unsigned] = JsonObject(mutableMapOf()).toString()
                        it[Events.hashes] = JsonObject(mutableMapOf()).toString()
                        it[Events.signatures] = JsonObject(mutableMapOf()).toString()
                    }
                }

                // Broadcast redaction event
                broadcastEDU(roomId, redactionEvent)

                call.respondText(
                    JsonObject(mutableMapOf(
                        "event_id" to JsonPrimitive(redactionEventId)
                    )).toString(),
                    ContentType.Application.Json
                )

            } catch (e: Exception) {
                call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_UNKNOWN"),
                        "error" to JsonPrimitive("Internal server error")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }
    }
}