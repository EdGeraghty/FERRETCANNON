package routes.client_server.client.room

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
import utils.MatrixAuth
import routes.client_server.client.common.*

// Data class to hold the result of room creation
data class RoomCreationResult(
    val createEvent: JsonObject,
    val memberEvent: JsonObject
)

/**
 * Creates a room with all its initial state events
 */
fun createRoomWithStateEvents(
    roomId: String,
    userId: String,
    roomName: String?,
    roomTopic: String?,
    roomAlias: String?,
    preset: String,
    visibility: String,
    joinRule: String,
    initialState: JsonArray?,
    isDirect: Boolean,
    currentTime: Long,
    config: ServerConfig
): RoomCreationResult {
    lateinit var signedCreateEvent: JsonObject
    lateinit var signedMemberEvent: JsonObject

    transaction {
        // Create room entry
        Rooms.insert {
            it[Rooms.roomId] = roomId
            it[Rooms.creator] = userId
            it[Rooms.name] = roomName
            it[Rooms.topic] = roomTopic
            it[Rooms.visibility] = visibility
            it[Rooms.roomVersion] = "12"
            it[Rooms.isDirect] = isDirect
            it[Rooms.currentState] = Json.encodeToString(JsonObject.serializer(), JsonObject(mutableMapOf())) // Initialize with empty JSON object
            it[Rooms.stateGroups] = Json.encodeToString(JsonObject.serializer(), JsonObject(mutableMapOf())) // Initialize with empty JSON object
        }

        // Generate event IDs
        val createEventId = "\$${currentTime}_create"
        val memberEventId = "\$${currentTime}_member"
        val powerLevelsEventId = "\$${currentTime}_power_levels"
        val joinRulesEventId = "\$${currentTime}_join_rules"
        val historyVisibilityEventId = "\$${currentTime}_history_visibility"

        // Create m.room.create event
        val createContent = JsonObject(mutableMapOf(
            "creator" to JsonPrimitive(userId),
            "room_version" to JsonPrimitive("12"),
            "predecessor" to JsonNull
        ))

        val createEventJson = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(createEventId),
            "type" to JsonPrimitive("m.room.create"),
            "sender" to JsonPrimitive(userId),
            "content" to createContent,
            "origin_server_ts" to JsonPrimitive(currentTime),
            "state_key" to JsonPrimitive(""),
            "prev_events" to JsonArray(emptyList()),
            "auth_events" to JsonArray(emptyList()),
            "depth" to JsonPrimitive(1)
        ))

        signedCreateEvent = MatrixAuth.hashAndSignEvent(createEventJson, config.federation.serverName)

        Events.insert {
            it[Events.eventId] = signedCreateEvent["event_id"]?.jsonPrimitive?.content ?: createEventId
            it[Events.roomId] = roomId
            it[Events.type] = "m.room.create"
            it[Events.sender] = userId
            it[Events.content] = Json.encodeToString(JsonObject.serializer(), createContent)
            it[Events.originServerTs] = currentTime
            it[Events.stateKey] = ""
            it[Events.prevEvents] = "[]"
            it[Events.authEvents] = "[]"
            it[Events.depth] = 1
            it[Events.hashes] = signedCreateEvent["hashes"]?.toString() ?: "{}"
            it[Events.signatures] = signedCreateEvent["signatures"]?.toString() ?: "{}"
        }

        // Create m.room.member event for creator
        val memberContent = JsonObject(mutableMapOf(
            "membership" to JsonPrimitive("join"),
            "displayname" to JsonPrimitive(userId.split(":")[0].substring(1)) // Extract localpart
        ))

        val memberEventJson = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(memberEventId),
            "type" to JsonPrimitive("m.room.member"),
            "sender" to JsonPrimitive(userId),
            "content" to memberContent,
            "origin_server_ts" to JsonPrimitive(currentTime),
            "state_key" to JsonPrimitive(userId),
            "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))))),
            "auth_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))))),
            "depth" to JsonPrimitive(2)
        ))

        // Get auth events as list of pairs
        val authEventList = transaction {
            val createEvent = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq "m.room.create")
            }.singleOrNull()

            val powerLevelsEvent = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq "m.room.power_levels")
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .limit(1)
                .singleOrNull()

            listOfNotNull(
                createEvent?.let { JsonArray(listOf(JsonPrimitive(it[Events.eventId]), JsonObject(mutableMapOf()))) },
                powerLevelsEvent?.let { JsonArray(listOf(JsonPrimitive(it[Events.eventId]), JsonObject(mutableMapOf()))) }
            )
        }

        val signedMemberEventJson = JsonObject(memberEventJson.toMutableMap().apply {
            put("auth_events", JsonArray(authEventList))
        })

        signedMemberEvent = MatrixAuth.hashAndSignEvent(signedMemberEventJson, config.federation.serverName)

        Events.insert {
            it[Events.eventId] = signedMemberEvent["event_id"]?.jsonPrimitive?.content ?: memberEventId
            it[Events.roomId] = roomId
            it[Events.type] = "m.room.member"
            it[Events.sender] = userId
            it[Events.content] = Json.encodeToString(JsonObject.serializer(), memberContent)
            it[Events.originServerTs] = currentTime
            it[Events.stateKey] = userId
            it[Events.prevEvents] = "[[\"$createEventId\",{}]]"
            it[Events.authEvents] = "[[\"$createEventId\",{}]]"
            it[Events.depth] = 2
            it[Events.hashes] = signedMemberEvent["hashes"]?.toString() ?: "{}"
            it[Events.signatures] = signedMemberEvent["signatures"]?.toString() ?: "{}"
        }

        // Create m.room.power_levels event
        val powerLevelsContent = JsonObject(mutableMapOf(
            "users" to JsonObject(mutableMapOf(userId to JsonPrimitive(100))),
            "users_default" to JsonPrimitive(0),
            "events" to JsonObject(mutableMapOf<String, JsonElement>()),
            "events_default" to JsonPrimitive(0),
            "state_default" to JsonPrimitive(50),
            "ban" to JsonPrimitive(50),
            "kick" to JsonPrimitive(50),
            "redact" to JsonPrimitive(50),
            "invite" to JsonPrimitive(0)
        ))

        val powerLevelsEventJson = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(powerLevelsEventId),
            "type" to JsonPrimitive("m.room.power_levels"),
            "sender" to JsonPrimitive(userId),
            "content" to powerLevelsContent,
            "origin_server_ts" to JsonPrimitive(currentTime),
            "state_key" to JsonPrimitive(""),
            "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))))),
            "auth_events" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf())))
            )),
            "depth" to JsonPrimitive(3)
        ))

        val signedPowerLevelsEvent = MatrixAuth.hashAndSignEvent(powerLevelsEventJson, config.federation.serverName)

        Events.insert {
            it[Events.eventId] = signedPowerLevelsEvent["event_id"]?.jsonPrimitive?.content ?: powerLevelsEventId
            it[Events.roomId] = roomId
            it[Events.type] = "m.room.power_levels"
            it[Events.sender] = userId
            it[Events.content] = Json.encodeToString(JsonObject.serializer(), powerLevelsContent)
            it[Events.originServerTs] = currentTime
            it[Events.stateKey] = ""
            it[Events.prevEvents] = "[[\"$memberEventId\",{}]]"
            it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}]]"
            it[Events.depth] = 3
            it[Events.hashes] = signedPowerLevelsEvent["hashes"]?.toString() ?: "{}"
            it[Events.signatures] = signedPowerLevelsEvent["signatures"]?.toString() ?: "{}"
        }

        // Create m.room.join_rules event
        val joinRulesContent = JsonObject(mutableMapOf(
            "join_rule" to JsonPrimitive(joinRule)
        ))

        val joinRulesEventJson = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(joinRulesEventId),
            "type" to JsonPrimitive("m.room.join_rules"),
            "sender" to JsonPrimitive(userId),
            "content" to joinRulesContent,
            "origin_server_ts" to JsonPrimitive(currentTime),
            "state_key" to JsonPrimitive(""),
            "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf()))))),
            "auth_events" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))),
                JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf())))
            )),
            "depth" to JsonPrimitive(4)
        ))

        val signedJoinRulesEvent = MatrixAuth.hashAndSignEvent(joinRulesEventJson, config.federation.serverName)

        Events.insert {
            it[Events.eventId] = signedJoinRulesEvent["event_id"]?.jsonPrimitive?.content ?: joinRulesEventId
            it[Events.roomId] = roomId
            it[Events.type] = "m.room.join_rules"
            it[Events.sender] = userId
            it[Events.content] = Json.encodeToString(JsonObject.serializer(), joinRulesContent)
            it[Events.originServerTs] = currentTime
            it[Events.stateKey] = ""
            it[Events.prevEvents] = "[[\"$powerLevelsEventId\",{}]]"
            it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
            it[Events.depth] = 4
            it[Events.hashes] = signedJoinRulesEvent["hashes"]?.toString() ?: "{}"
            it[Events.signatures] = signedJoinRulesEvent["signatures"]?.toString() ?: "{}"
        }

        // Create m.room.history_visibility event
        val historyVisibilityContent = JsonObject(mutableMapOf(
            "history_visibility" to JsonPrimitive("shared")
        ))

        val historyVisibilityEventJson = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive(historyVisibilityEventId),
            "type" to JsonPrimitive("m.room.history_visibility"),
            "sender" to JsonPrimitive(userId),
            "content" to historyVisibilityContent,
            "origin_server_ts" to JsonPrimitive(currentTime),
            "state_key" to JsonPrimitive(""),
            "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(joinRulesEventId), JsonObject(mutableMapOf()))))),
            "auth_events" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))),
                JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf())))
            )),
            "depth" to JsonPrimitive(5)
        ))

        val signedHistoryVisibilityEvent = MatrixAuth.hashAndSignEvent(historyVisibilityEventJson, config.federation.serverName)

        Events.insert {
            it[Events.eventId] = signedHistoryVisibilityEvent["event_id"]?.jsonPrimitive?.content ?: historyVisibilityEventId
            it[Events.roomId] = roomId
            it[Events.type] = "m.room.history_visibility"
            it[Events.sender] = userId
            it[Events.content] = Json.encodeToString(JsonObject.serializer(), historyVisibilityContent)
            it[Events.originServerTs] = currentTime
            it[Events.stateKey] = ""
            it[Events.prevEvents] = "[[\"$joinRulesEventId\",{}]]"
            it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
            it[Events.depth] = 5
            it[Events.hashes] = signedHistoryVisibilityEvent["hashes"]?.toString() ?: "{}"
            it[Events.signatures] = signedHistoryVisibilityEvent["signatures"]?.toString() ?: "{}"
        }

        // Create m.room.canonical_alias event if alias is provided
        var nextDepth = 6
        var lastEventId = historyVisibilityEventId
        
        if (roomAlias != null) {
            val canonicalAliasEventId = "\$${currentTime}_canonical_alias"
            val fullAlias = "#$roomAlias:${config.federation.serverName}"
            
            val canonicalAliasContent = JsonObject(mutableMapOf(
                "alias" to JsonPrimitive(fullAlias)
            ))
            
            val canonicalAliasEventJson = JsonObject(mutableMapOf(
                "event_id" to JsonPrimitive(canonicalAliasEventId),
                "type" to JsonPrimitive("m.room.canonical_alias"),
                "sender" to JsonPrimitive(userId),
                "content" to canonicalAliasContent,
                "origin_server_ts" to JsonPrimitive(currentTime),
                "state_key" to JsonPrimitive(""),
                "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(lastEventId), JsonObject(mutableMapOf()))))),
                "auth_events" to JsonArray(listOf(
                    JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                    JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))),
                    JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf())))
                )),
                "depth" to JsonPrimitive(nextDepth)
            ))
            
            val signedCanonicalAliasEvent = MatrixAuth.hashAndSignEvent(canonicalAliasEventJson, config.federation.serverName)
            
            Events.insert {
                it[Events.eventId] = signedCanonicalAliasEvent["event_id"]?.jsonPrimitive?.content ?: canonicalAliasEventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.canonical_alias"
                it[Events.sender] = userId
                it[Events.content] = Json.encodeToString(JsonObject.serializer(), canonicalAliasContent)
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = ""
                it[Events.prevEvents] = "[[\"$lastEventId\",{}]]"
                it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
                it[Events.depth] = nextDepth
                it[Events.hashes] = signedCanonicalAliasEvent["hashes"]?.toString() ?: "{}"
                it[Events.signatures] = signedCanonicalAliasEvent["signatures"]?.toString() ?: "{}"
            }
            
            lastEventId = canonicalAliasEventId
            nextDepth++
        }

        // Process initial_state events if provided

        if (initialState != null) {
            for ((index, stateEvent) in initialState.withIndex()) {
                val stateEventObj = stateEvent.jsonObject
                val eventType = stateEventObj["type"]?.jsonPrimitive?.content ?: continue
                val stateKey = stateEventObj["state_key"]?.jsonPrimitive?.content ?: ""
                val content = stateEventObj["content"]?.jsonObject ?: JsonObject(mutableMapOf())

                val stateEventId = "\$${currentTime}_initial_${index}"

                val stateEventJson = JsonObject(mutableMapOf(
                    "event_id" to JsonPrimitive(stateEventId),
                    "type" to JsonPrimitive(eventType),
                    "sender" to JsonPrimitive(userId),
                    "content" to content,
                    "origin_server_ts" to JsonPrimitive(currentTime),
                    "state_key" to JsonPrimitive(stateKey),
                    "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(lastEventId), JsonObject(mutableMapOf()))))),
                    "auth_events" to JsonArray(listOf(
                        JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                        JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))),
                        JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf())))
                    )),
                    "depth" to JsonPrimitive(nextDepth)
                ))

                val signedStateEvent = MatrixAuth.hashAndSignEvent(stateEventJson, config.federation.serverName)

                Events.insert {
                    it[Events.eventId] = signedStateEvent["event_id"]?.jsonPrimitive?.content ?: stateEventId
                    it[Events.roomId] = roomId
                    it[Events.type] = eventType
                    it[Events.sender] = userId
                    it[Events.content] = Json.encodeToString(JsonObject.serializer(), content)
                    it[Events.originServerTs] = currentTime
                    it[Events.stateKey] = stateKey
                    it[Events.prevEvents] = "[[\"$lastEventId\",{}]]"
                    it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
                    it[Events.depth] = nextDepth
                    it[Events.hashes] = signedStateEvent["hashes"]?.toString() ?: "{}"
                    it[Events.signatures] = signedStateEvent["signatures"]?.toString() ?: "{}"
                }

                lastEventId = signedStateEvent["event_id"]?.jsonPrimitive?.content ?: stateEventId
                nextDepth++
            }
        }

        // Create m.room.name event if name is specified
        if (roomName != null) {
            val nameEventId = "\$${currentTime}_name"
            val nameContent = JsonObject(mutableMapOf("name" to JsonPrimitive(roomName)))

            val nameEventJson = JsonObject(mutableMapOf(
                "event_id" to JsonPrimitive(nameEventId),
                "type" to JsonPrimitive("m.room.name"),
                "sender" to JsonPrimitive(userId),
                "content" to nameContent,
                "origin_server_ts" to JsonPrimitive(currentTime),
                "state_key" to JsonPrimitive(""),
                "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(lastEventId), JsonObject(mutableMapOf()))))),
                "auth_events" to JsonArray(listOf(
                    JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                    JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))),
                    JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf())))
                )),
                "depth" to JsonPrimitive(nextDepth)
            ))

            val signedNameEvent = MatrixAuth.hashAndSignEvent(nameEventJson, config.federation.serverName)

            Events.insert {
                it[Events.eventId] = signedNameEvent["event_id"]?.jsonPrimitive?.content ?: nameEventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.name"
                it[Events.sender] = userId
                it[Events.content] = Json.encodeToString(JsonObject.serializer(), nameContent)
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = ""
                it[Events.prevEvents] = "[[\"$lastEventId\",{}]]"
                it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
                it[Events.depth] = nextDepth
                it[Events.hashes] = signedNameEvent["hashes"]?.toString() ?: "{}"
                it[Events.signatures] = signedNameEvent["signatures"]?.toString() ?: "{}"
            }

            lastEventId = signedNameEvent["event_id"]?.jsonPrimitive?.content ?: nameEventId
            nextDepth++
        }

        // Create m.room.topic event if topic is specified
        if (roomTopic != null) {
            val topicEventId = "\$${currentTime}_topic"
            val topicContent = JsonObject(mutableMapOf("topic" to JsonPrimitive(roomTopic)))

            val topicEventJson = JsonObject(mutableMapOf(
                "event_id" to JsonPrimitive(topicEventId),
                "type" to JsonPrimitive("m.room.topic"),
                "sender" to JsonPrimitive(userId),
                "content" to topicContent,
                "origin_server_ts" to JsonPrimitive(currentTime),
                "state_key" to JsonPrimitive(""),
                "prev_events" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(lastEventId), JsonObject(mutableMapOf()))))),
                "auth_events" to JsonArray(listOf(
                    JsonArray(listOf(JsonPrimitive(createEventId), JsonObject(mutableMapOf()))),
                    JsonArray(listOf(JsonPrimitive(memberEventId), JsonObject(mutableMapOf()))),
                    JsonArray(listOf(JsonPrimitive(powerLevelsEventId), JsonObject(mutableMapOf())))
                )),
                "depth" to JsonPrimitive(nextDepth)
            ))

            val signedTopicEvent = MatrixAuth.hashAndSignEvent(topicEventJson, config.federation.serverName)

            Events.insert {
                it[Events.eventId] = signedTopicEvent["event_id"]?.jsonPrimitive?.content ?: topicEventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.topic"
                it[Events.sender] = userId
                it[Events.content] = Json.encodeToString(JsonObject.serializer(), topicContent)
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = ""
                it[Events.prevEvents] = "[[\"$lastEventId\",{}]]"
                it[Events.authEvents] = "[[\"$createEventId\",{}], [\"$memberEventId\",{}], [\"$powerLevelsEventId\",{}]]"
                it[Events.depth] = nextDepth
                it[Events.hashes] = signedTopicEvent["hashes"]?.toString() ?: "{}"
                it[Events.signatures] = signedTopicEvent["signatures"]?.toString() ?: "{}"
            }
        }
    }

    return RoomCreationResult(
        createEvent = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive("\$${currentTime}_create"),
            "type" to JsonPrimitive("m.room.create"),
            "sender" to JsonPrimitive(userId),
            "room_id" to JsonPrimitive(roomId),
            "origin_server_ts" to JsonPrimitive(currentTime),
            "content" to JsonObject(mutableMapOf(
                "creator" to JsonPrimitive(userId),
                "room_version" to JsonPrimitive("12")
            )),
            "state_key" to JsonPrimitive(""),
            "hashes" to signedCreateEvent["hashes"]!!,
            "signatures" to signedCreateEvent["signatures"]!!
        )),
        memberEvent = JsonObject(mutableMapOf(
            "event_id" to JsonPrimitive("\$${currentTime}_member"),
            "type" to JsonPrimitive("m.room.member"),
            "sender" to JsonPrimitive(userId),
            "room_id" to JsonPrimitive(roomId),
            "origin_server_ts" to JsonPrimitive(currentTime),
            "content" to JsonObject(mutableMapOf(
                "membership" to JsonPrimitive("join"),
                "displayname" to JsonPrimitive(userId.split(":")[0].substring(1))
            )),
            "state_key" to JsonPrimitive(userId),
            "hashes" to signedMemberEvent["hashes"]!!,
            "signatures" to signedMemberEvent["signatures"]!!
        )
    )
    )
}