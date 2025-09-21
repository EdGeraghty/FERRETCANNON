package routes.client_server.client

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
import config.ServerConfig

// Add ReadMarkers table definition
object ReadMarkers : Table("read_markers") {
    val roomId = varchar("room_id", 255)
    val userId = varchar("user_id", 255)
    val eventId = varchar("event_id", 255)
    val markerType = varchar("marker_type", 50) // "m.fully_read", "m.read"
    val timestamp = long("timestamp").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(roomId, userId, markerType)
}

fun Route.eventRoutes() {
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

        // Read markers endpoint
        post("/read_markers") {
            try {
                val roomId = call.parameters["roomId"] ?: return@post call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_MISSING_PARAM"),
                        "error" to JsonPrimitive("Missing roomId parameter")
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

                val requestBody = call.receiveText()
                val requestJson = Json.parseToJsonElement(requestBody).jsonObject

                val fullyReadEventId = requestJson["m.fully_read"]?.jsonPrimitive?.content
                val readEventId = requestJson["m.read"]?.jsonPrimitive?.content

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

                // Validate and store read markers
                val ts = Instant.now().epochSecond * 1000

                if (fullyReadEventId != null) {
                    // Check if event exists
                    val eventExists = transaction {
                        Events.select {
                            (Events.eventId eq fullyReadEventId) and (Events.roomId eq roomId)
                        }.count() > 0
                    }

                    if (!eventExists) {
                        return@post call.respondText(
                            JsonObject(mutableMapOf(
                                "errcode" to JsonPrimitive("M_NOT_FOUND"),
                                "error" to JsonPrimitive("Fully read event not found")
                            )).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound
                        )
                    }

                    // Store fully read marker
                    transaction {
                        ReadMarkers.replace {
                            it[ReadMarkers.roomId] = roomId
                            it[ReadMarkers.userId] = userId
                            it[ReadMarkers.eventId] = fullyReadEventId
                            it[ReadMarkers.markerType] = "m.fully_read"
                            it[ReadMarkers.timestamp] = ts
                        }
                    }
                }

                if (readEventId != null) {
                    // Check if event exists
                    val eventExists = transaction {
                        Events.select {
                            (Events.eventId eq readEventId) and (Events.roomId eq roomId)
                        }.count() > 0
                    }

                    if (!eventExists) {
                        return@post call.respondText(
                            JsonObject(mutableMapOf(
                                "errcode" to JsonPrimitive("M_NOT_FOUND"),
                                "error" to JsonPrimitive("Read event not found")
                            )).toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound
                        )
                    }

                    // Store read marker and receipt
                    transaction {
                        ReadMarkers.replace {
                            it[ReadMarkers.roomId] = roomId
                            it[ReadMarkers.userId] = userId
                            it[ReadMarkers.eventId] = readEventId
                            it[ReadMarkers.markerType] = "m.read"
                            it[ReadMarkers.timestamp] = ts
                        }

                        Receipts.replace {
                            it[Receipts.roomId] = roomId
                            it[Receipts.userId] = userId
                            it[Receipts.eventId] = readEventId
                            it[Receipts.receiptType] = "m.read"
                            it[Receipts.timestamp] = ts
                        }
                    }
                }

                // Broadcast read markers to room clients
                val markerData = JsonObject(mutableMapOf(
                    "type" to JsonPrimitive("m.read_marker"),
                    "room_id" to JsonPrimitive(roomId),
                    "content" to JsonObject(mutableMapOf(
                        "user_id" to JsonPrimitive(userId)
                    ).apply {
                        if (fullyReadEventId != null) {
                            this["m.fully_read"] = JsonPrimitive(fullyReadEventId)
                        }
                        if (readEventId != null) {
                            this["m.read"] = JsonPrimitive(readEventId)
                        }
                    })
                ))

                broadcastEDU(roomId, markerData)

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
