package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.util.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.http.content.*
import io.ktor.http.content.PartData
import models.Events
import models.Rooms
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import kotlinx.serialization.json.*
import utils.users
import utils.accessTokens
import routes.server_server.federation.v1.broadcastEDU
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import models.AccountData
import io.ktor.websocket.Frame
import utils.MediaStorage
import models.Users
import models.AccessTokens
import at.favre.lib.crypto.bcrypt.BCrypt
import utils.AuthUtils
import utils.connectedClients
import utils.typingMap
import utils.ServerKeys
import utils.OAuthService
import utils.OAuthConfig
import config.ServerConfig
import utils.MatrixPagination

fun Route.eventRoutes(_config: ServerConfig) {
    // GET /rooms/{roomId}/context/{eventId} - Get event context
    get("/rooms/{roomId}/context/{eventId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val eventId = call.parameters["eventId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (roomId == null || eventId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId or eventId parameter"
                ))
                return@get
            }

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@get
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

            // Get the target event
            val targetEvent = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.eventId eq eventId)
                }.singleOrNull()?.let { row ->
                    mapOf(
                        "event_id" to row[Events.eventId],
                        "type" to row[Events.type],
                        "sender" to row[Events.sender],
                        "origin_server_ts" to row[Events.originServerTs],
                        "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                        "room_id" to row[Events.roomId]
                    )
                }
            }

            if (targetEvent == null) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Event not found"
                ))
                return@get
            }

            // Get events before the target event
            val eventsBefore = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.originServerTs less targetEvent["origin_server_ts"] as Long)
                }.orderBy(Events.originServerTs, SortOrder.DESC)
                    .limit(limit / 2)
                    .map { row ->
                        mapOf(
                            "event_id" to row[Events.eventId],
                            "type" to row[Events.type],
                            "sender" to row[Events.sender],
                            "origin_server_ts" to row[Events.originServerTs],
                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                            "room_id" to row[Events.roomId]
                        )
                    }
                    .reversed()
            }

            // Get events after the target event
            val eventsAfter = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.originServerTs greater targetEvent["origin_server_ts"] as Long)
                }.orderBy(Events.originServerTs, SortOrder.ASC)
                    .limit(limit / 2)
                    .map { row ->
                        mapOf(
                            "event_id" to row[Events.eventId],
                            "type" to row[Events.type],
                            "sender" to row[Events.sender],
                            "origin_server_ts" to row[Events.originServerTs],
                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                            "room_id" to row[Events.roomId]
                        )
                    }
            }

            // Get current room state
            val state = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.stateKey.isNotNull())
                }.groupBy { Pair(it[Events.type], it[Events.stateKey]) }
                    .map { (_, events) ->
                        events.maxByOrNull { it[Events.originServerTs] }
                    }
                    .filterNotNull()
                    .map { row ->
                        mapOf(
                            "event_id" to row[Events.eventId],
                            "type" to row[Events.type],
                            "sender" to row[Events.sender],
                            "state_key" to row[Events.stateKey],
                            "origin_server_ts" to row[Events.originServerTs],
                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                            "room_id" to row[Events.roomId]
                        )
                    }
            }

            call.respond(mapOf(
                "start" to (if (eventsBefore.isNotEmpty()) MatrixPagination.createSyncToken(
                    eventId = eventsBefore.first()["event_id"] as String,
                    timestamp = eventsBefore.first()["origin_server_ts"] as Long,
                    roomId = roomId
                ) else ""),
                "end" to (if (eventsAfter.isNotEmpty()) MatrixPagination.createSyncToken(
                    eventId = eventsAfter.last()["event_id"] as String,
                    timestamp = eventsAfter.last()["origin_server_ts"] as Long,
                    roomId = roomId
                ) else ""),
                "events_before" to eventsBefore,
                "event" to targetEvent,
                "events_after" to eventsAfter,
                "state" to state
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /rooms/{roomId}/receipt/{receiptType}/{eventId} - Send receipt
    post("/rooms/{roomId}/receipt/{receiptType}/{eventId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val receiptType = call.parameters["receiptType"]
            val eventId = call.parameters["eventId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            if (roomId == null || receiptType == null || eventId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId, receiptType, or eventId parameter"
                ))
                return@post
            }

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@post
            }

            // Parse request body - handle empty body gracefully
            val requestBody = call.receiveText().trim()
            val ts = if (requestBody.isNotEmpty()) {
                try {
                    val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
                    jsonBody["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    // If JSON parsing fails, use current timestamp
                    System.currentTimeMillis()
                }
            } else {
                System.currentTimeMillis()
            }

            // Store receipt in account data
            val receiptKey = "m.receipt"
            val receiptData = JsonObject(mapOf(
                roomId to JsonObject(mapOf(
                    receiptType to JsonObject(mapOf(
                        eventId to JsonObject(mapOf(
                            "ts" to JsonPrimitive(ts),
                            "thread_id" to JsonPrimitive(eventId) // Simplified
                        ))
                    ))
                ))
            ))

            transaction {
                val existing = AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq receiptKey) and
                    (AccountData.roomId.isNull())
                }.singleOrNull()

                if (existing != null) {
                    val currentContent = Json.parseToJsonElement(existing[AccountData.content]).jsonObject.toMutableMap()

                    // Merge receipt data
                    val roomReceipts = (currentContent[roomId] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    val typeReceipts = (roomReceipts[receiptType] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    typeReceipts[eventId] = JsonObject(mapOf(
                        "ts" to JsonPrimitive(ts),
                        "thread_id" to JsonPrimitive(eventId)
                    ))
                    roomReceipts[receiptType] = JsonObject(typeReceipts)
                    currentContent[roomId] = JsonObject(roomReceipts)

                    AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq receiptKey) and (AccountData.roomId.isNull()) }) {
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                } else {
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = receiptKey
                        it[AccountData.roomId] = null
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), receiptData)
                    }
                }
            }

            // Broadcast receipt as EDU
            val receiptEvent = JsonObject(mapOf(
                "type" to JsonPrimitive("m.receipt"),
                "content" to receiptData,
                "sender" to JsonPrimitive(userId)
            ))

            runBlocking {
                broadcastEDU(roomId, receiptEvent)
            }

            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            when (e) {
                is kotlinx.serialization.SerializationException -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "errcode" to "M_BAD_JSON",
                        "error" to "Invalid JSON"
                    ))
                }
                else -> {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "errcode" to "M_UNKNOWN",
                        "error" to "Internal server error"
                    ))
                }
            }
        }
    }

    // POST /rooms/{roomId}/read_markers - Set read markers
    post("/rooms/{roomId}/read_markers") {
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

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val fullyRead = jsonBody["m.fully_read"]?.jsonPrimitive?.content
            val read = jsonBody["m.read"]?.jsonPrimitive?.content

            // Store read markers in account data
            val readMarkersKey = "m.read"
            val readMarkersData = JsonObject(mapOf(
                roomId to JsonObject(buildMap {
                    if (fullyRead != null) put("m.fully_read", JsonPrimitive(fullyRead))
                    if (read != null) put("m.read", JsonPrimitive(read))
                })
            ))

            transaction {
                val existing = AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq readMarkersKey) and
                    (AccountData.roomId.isNull())
                }.singleOrNull()

                if (existing != null) {
                    val currentContent = Json.parseToJsonElement(existing[AccountData.content]).jsonObject.toMutableMap()

                    // Merge read markers data
                    val roomMarkers = (currentContent[roomId] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    if (fullyRead != null) roomMarkers["m.fully_read"] = JsonPrimitive(fullyRead)
                    if (read != null) roomMarkers["m.read"] = JsonPrimitive(read)
                    currentContent[roomId] = JsonObject(roomMarkers)

                    AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq readMarkersKey) and (AccountData.roomId.isNull()) }) {
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                } else {
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = readMarkersKey
                        it[AccountData.roomId] = null
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), readMarkersData)
                    }
                }
            }

            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            when (e) {
                is kotlinx.serialization.SerializationException -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "errcode" to "M_BAD_JSON",
                        "error" to "Invalid JSON"
                    ))
                }
                else -> {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "errcode" to "M_UNKNOWN",
                        "error" to "Internal server error"
                    ))
                }
            }
        }
    }

    // PUT /rooms/{roomId}/redact/{eventId}/{txnId} - Redact event
    put("/rooms/{roomId}/redact/{eventId}/{txnId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val eventId = call.parameters["eventId"]
            val txnId = call.parameters["txnId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (roomId == null || eventId == null || txnId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId, eventId, or txnId parameter"
                ))
                return@put
            }

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@put
            }

            // Check if event exists
            val targetEvent = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.eventId eq eventId)
                }.singleOrNull()
            }

            if (targetEvent == null) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Event not found"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val reason = jsonBody["reason"]?.jsonPrimitive?.content

            // Generate redaction event ID
            val redactionEventId = "\$${System.currentTimeMillis()}_${txnId}"

            // Get current room state for prev_events
            val latestEvent = transaction {
                Events.select { Events.roomId eq roomId }
                    .orderBy(Events.originServerTs, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
            }

            val prevEvents = if (latestEvent != null) {
                "[[\"${latestEvent[Events.eventId]}\",{}]]"
            } else {
                "[]"
            }

            val depth = if (latestEvent != null) {
                latestEvent[Events.depth] + 1
            } else {
                1
            }

            // Create redaction event content
            val redactionContent = JsonObject(buildMap {
                put("reason", JsonPrimitive(reason ?: ""))
            })

            // Store redaction event
            transaction {
                Events.insert {
                    it[Events.roomId] = roomId
                    it[Events.eventId] = redactionEventId
                    it[Events.type] = "m.room.redaction"
                    it[Events.sender] = userId
                    it[Events.stateKey] = null
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), redactionContent)
                    it[Events.originServerTs] = System.currentTimeMillis()
                    it[Events.prevEvents] = prevEvents
                    it[Events.authEvents] = "[]" // Simplified
                    it[Events.depth] = depth
                    it[Events.hashes] = "{}"
                    it[Events.signatures] = "{}"
                }
            }

            // Update original event content to indicate redaction
            transaction {
                Events.update({ (Events.roomId eq roomId) and (Events.eventId eq eventId) }) {
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                        "body" to JsonPrimitive("Message deleted"),
                        "msgtype" to JsonPrimitive("m.room.message")
                    )))
                }
            }

            // Broadcast redaction event
            val redactionEventJson = JsonObject(mapOf(
                "event_id" to JsonPrimitive(redactionEventId),
                "type" to JsonPrimitive("m.room.redaction"),
                "sender" to JsonPrimitive(userId),
                "room_id" to JsonPrimitive(roomId),
                "origin_server_ts" to JsonPrimitive(System.currentTimeMillis()),
                "content" to redactionContent,
                "redacts" to JsonPrimitive(eventId)
            ))

            runBlocking {
                broadcastEDU(roomId, redactionEventJson)
            }

            call.respond(mapOf(
                "event_id" to redactionEventId
            ))

        } catch (e: Exception) {
            when (e) {
                is kotlinx.serialization.SerializationException -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "errcode" to "M_BAD_JSON",
                        "error" to "Invalid JSON"
                    ))
                }
                else -> {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "errcode" to "M_UNKNOWN",
                        "error" to "Internal server error"
                    ))
                }
            }
        }
    }

    // GET /rooms/{roomId}/relations/{eventId} - Get event relations
    get("/rooms/{roomId}/relations/{eventId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val eventId = call.parameters["eventId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (roomId == null || eventId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId or eventId parameter"
                ))
                return@get
            }

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@get
            }

            val relType = call.request.queryParameters["rel_type"]
            val eventType = call.request.queryParameters["event_type"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
            val _from = call.request.queryParameters["from"]
            val _to = call.request.queryParameters["to"]
            val dir = call.request.queryParameters["dir"] ?: "b"

            // Get related events
            val relatedEvents = transaction {
                val query = Events.select { Events.roomId eq roomId }

                // Filter by relation type if specified
                if (relType != null) {
                    query.andWhere { Events.type eq "m.relates_to" } // Simplified - would need proper relation handling
                }

                if (eventType != null) {
                    query.andWhere { Events.type eq eventType }
                }

                query.orderBy(Events.originServerTs, if (dir == "b") SortOrder.DESC else SortOrder.ASC)
                    .limit(limit)
                    .map { row ->
                        mapOf(
                            "event_id" to row[Events.eventId],
                            "type" to row[Events.type],
                            "sender" to row[Events.sender],
                            "origin_server_ts" to row[Events.originServerTs],
                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                            "room_id" to row[Events.roomId]
                        )
                    }
            }

            call.respond(mapOf(
                "chunk" to relatedEvents
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /rooms/{roomId}/relations/{eventId}/{relType} - Get specific relation type
    get("/rooms/{roomId}/relations/{eventId}/{relType}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val roomId = call.parameters["roomId"]
            val eventId = call.parameters["eventId"]
            val relType = call.parameters["relType"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (roomId == null || eventId == null || relType == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId, eventId, or relType parameter"
                ))
                return@get
            }

            // Check if user is joined to the room
            val currentMembership = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId)
                }.mapNotNull { row ->
                    Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
                }.firstOrNull()
            }

            if (currentMembership != "join") {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "User is not joined to this room"
                ))
                return@get
            }

            val eventType = call.request.queryParameters["event_type"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
            val _from = call.request.queryParameters["from"]
            val _to = call.request.queryParameters["to"]
            val dir = call.request.queryParameters["dir"] ?: "b"

            // Get related events of specific type
            val relatedEvents = transaction {
                val query = Events.select { Events.roomId eq roomId }

                // Filter by relation type
                if (relType == "m.annotation") {
                    query.andWhere { Events.type eq "m.reaction" } // Reactions
                } else if (relType == "m.replace") {
                    query.andWhere { Events.type eq "m.room.message" } // Edits (simplified)
                } else if (relType == "m.thread") {
                    query.andWhere { Events.type eq "m.room.message" } // Thread replies (simplified)
                }

                if (eventType != null) {
                    query.andWhere { Events.type eq eventType }
                }

                query.orderBy(Events.originServerTs, if (dir == "b") SortOrder.DESC else SortOrder.ASC)
                    .limit(limit)
                    .map { row ->
                        mapOf(
                            "event_id" to row[Events.eventId],
                            "type" to row[Events.type],
                            "sender" to row[Events.sender],
                            "origin_server_ts" to row[Events.originServerTs],
                            "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                            "room_id" to row[Events.roomId]
                        )
                    }
            }

            call.respond(mapOf(
                "chunk" to relatedEvents
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
