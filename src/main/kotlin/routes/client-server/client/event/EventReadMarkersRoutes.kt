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

// Add ReadMarkers table definition
object ReadMarkers : Table("read_markers") {
    val roomId = varchar("room_id", 255)
    val userId = varchar("user_id", 255)
    val eventId = varchar("event_id", 255)
    val markerType = varchar("marker_type", 50) // "m.fully_read", "m.read"
    val timestamp = long("timestamp").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(roomId, userId, markerType)
}

fun Route.eventReadMarkersRoutes() {
    route("/_matrix/client/v3/rooms/{roomId}") {
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
    }
}