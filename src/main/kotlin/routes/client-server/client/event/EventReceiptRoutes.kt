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

fun Route.eventReceiptRoutes() {
    route("/_matrix/client/v3/rooms/{roomId}") {
        // Receipt endpoint
        post("/receipt/{receiptType}/{eventId}") {
            try {
                val roomId = call.parameters["roomId"] ?: return@post call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing roomId parameter")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

                val receiptType = call.parameters["receiptType"] ?: return@post call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing receiptType parameter")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

                val eventId = call.parameters["eventId"] ?: return@post call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing eventId parameter")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

                val accessToken = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                    ?: call.request.queryParameters["access_token"]
                    ?: return@post call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_MISSING_TOKEN"),
                            "error" to JsonPrimitive("Missing access token")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )

                val tokenResult = AuthUtils.validateAccessToken(accessToken)
                if (tokenResult == null) {
                    return@post call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_UNKNOWN_TOKEN"),
                            "error" to JsonPrimitive("Invalid access token")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )
                }
                val (userId, _) = tokenResult

                // Validate receipt type
                if (receiptType != "m.read") {
                    return@post call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_INVALID_PARAM"),
                            "error" to JsonPrimitive("Invalid receipt type")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }

                // Check if user is in the room
                val isMember = transaction {
                    Events.select {
                        (Events.roomId eq roomId) and (Events.type eq "m.room.member") and (Events.stateKey eq userId)
                    }.mapNotNull { row ->
                        Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                    }.firstOrNull() == "join"
                }

                if (!isMember) {
                    return@post call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_FORBIDDEN"),
                            "error" to JsonPrimitive("User is not a member of this room")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                }

                // Check if event exists in the room
                val eventExists = transaction {
                    Events.select {
                        (Events.eventId eq eventId) and (Events.roomId eq roomId)
                    }.count() > 0
                }

                if (!eventExists) {
                    return@post call.respondText(
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_NOT_FOUND"),
                            "error" to JsonPrimitive("Event not found")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                }

                // Store receipt
                val ts = Instant.now().epochSecond * 1000
                transaction {
                    Receipts.replace {
                        it[Receipts.roomId] = roomId
                        it[Receipts.userId] = userId
                        it[Receipts.eventId] = eventId
                        it[Receipts.receiptType] = receiptType
                        it[Receipts.timestamp] = ts
                    }
                }

                // Broadcast receipt to room clients
                val receiptData = JsonObject(mutableMapOf(
                    "type" to JsonPrimitive("m.receipt"),
                    "room_id" to JsonPrimitive(roomId),
                    "content" to JsonObject(mutableMapOf(
                        eventId to JsonObject(mutableMapOf(
                            receiptType to JsonObject(mutableMapOf(
                                userId to JsonObject(mutableMapOf(
                                    "ts" to JsonPrimitive(ts)
                                ))
                            ))
                        ))
                    ))
                ))

                broadcastEDU(roomId, receiptData)

                call.respondText("{}", ContentType.Application.Json)

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