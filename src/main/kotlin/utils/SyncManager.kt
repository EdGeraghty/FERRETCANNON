package utils

import models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import java.util.*
import utils.*
import utils.MatrixPagination
import utils.typingMap

import org.slf4j.LoggerFactory

/**
 * Enhanced Sync Implementation
 * Provides full state and incremental sync functionality according to Matrix Client-Server API v1.15
 */
object SyncManager {

    private val logger = LoggerFactory.getLogger("utils.SyncManager")

    private val json = Json { encodeDefaults = true }

    /**
     * Room sync data
     */
    data class RoomSyncData(
        val roomId: String,
        val timeline: List<JsonElement>,
        val state: List<JsonElement>,
        val ephemeral: List<JsonElement>,
        val accountData: List<JsonElement>,
        val summary: JsonElement?,
        val unreadNotifications: JsonElement?
    )

    /**
     * Perform a sync operation for a user
     */
    fun performSync(
        userId: String,
        since: MatrixPagination.SyncToken? = null,
        fullState: Boolean = false,
        useStateAfter: Boolean = false
    ): JsonObject {
        logger.info("performSync called for userId=$userId, since=$since, fullState=$fullState")
        val currentTime = System.currentTimeMillis()

        // Get user's joined rooms
        val joinedRoomIds = getUserJoinedRooms(userId)
        val invitedRoomIds = getUserInvitedRooms(userId)
        val leftRoomIds = getUserLeftRooms(userId)

        logger.info("performSync - userId=$userId, joinedRooms=${joinedRoomIds.size}, invitedRooms=${invitedRoomIds.size}, leftRooms=${leftRoomIds.size}")

        // Determine if this is a full state sync
        val isFullState = fullState || since == null

        // Get room data for each joined room
        val joinRooms = mutableMapOf<String, JsonElement>()
        val inviteRooms = mutableMapOf<String, JsonElement>()
        val leaveRooms = mutableMapOf<String, JsonElement>()

        for (roomId in joinedRoomIds) {
            val roomData = getRoomSyncData(userId, roomId, since, isFullState)
            joinRooms[roomId] = buildJsonObject {
                put("timeline", buildJsonObject {
                    put("events", JsonArray(roomData.timeline))
                    put("limited", false)
                    // prev_batch is optional, omit when null
                })
                put("state", buildJsonObject {
                    put("events", JsonArray(roomData.state))
                })
                put("ephemeral", buildJsonObject {
                    put("events", JsonArray(roomData.ephemeral))
                })
                put("account_data", buildJsonObject {
                    put("events", JsonArray(roomData.accountData))
                })
                put("summary", if (roomData.summary != null) roomData.summary else JsonNull)
                put("unread_notifications", if (roomData.unreadNotifications != null) roomData.unreadNotifications else JsonNull)
            }
        }

        for (roomId in invitedRoomIds) {
            logger.info("Processing invited room: $roomId for user: $userId")
            val strippedState = getStrippedState(roomId, userId)
            logger.info("Got ${strippedState.size} stripped state events for room $roomId")
            inviteRooms[roomId] = buildJsonObject {
                put("invite_state", buildJsonObject {
                    put("events", JsonArray(strippedState))
                })
            }
        }

        for (roomId in leftRoomIds) {
            val roomData = getRoomSyncData(userId, roomId, since, isFullState)
            leaveRooms[roomId] = buildJsonObject {
                put("timeline", buildJsonObject {
                    put("events", JsonArray(roomData.timeline))
                    put("limited", false)
                    // prev_batch is optional, omit when null
                })
                put("state", buildJsonObject {
                    put("events", JsonArray(roomData.state))
                })
            }
        }

        // Get presence events
        val presenceEvents = getPresenceEvents(since)

        // Get account data
        val accountData = getAccountData(userId, since)

        // Get device lists (simplified)
        val deviceLists = mutableMapOf(
            "changed" to emptyList<String>(),
            "left" to emptyList<String>()
        )

        // Get device one-time keys count (simplified)
        val deviceOneTimeKeysCount = mapOf<String, Int>()

        // Get to-device messages (simplified)
        val toDevice = mutableMapOf("events" to emptyList<JsonElement>())

        // Generate next batch token
        val nextBatchToken = MatrixPagination.createSyncToken(
            eventId = "sync_${currentTime}",
            timestamp = currentTime,
            roomId = null
        )

        return buildJsonObject {
            put("next_batch", nextBatchToken)
            put("rooms", buildJsonObject {
                put("join", JsonObject(joinRooms))
                put("invite", JsonObject(inviteRooms))
                put("leave", JsonObject(leaveRooms))
            })
            put("presence", buildJsonObject {
                put("events", JsonArray(presenceEvents.toList()))
            })
            if (accountData.isNotEmpty()) {
                put("account_data", buildJsonObject {
                    put("events", JsonArray(accountData.toList()))
                })
            }
            put("device_lists", buildJsonObject {
                put("changed", JsonArray(deviceLists["changed"]?.map { JsonPrimitive(it) } ?: emptyList()))
                put("left", JsonArray(deviceLists["left"]?.map { JsonPrimitive(it) } ?: emptyList()))
            })
            put("device_one_time_keys_count", buildJsonObject {
                deviceOneTimeKeysCount.forEach { (key, value) ->
                    put(key, value)
                }
            })
            put("to_device", buildJsonObject {
                put("events", JsonArray(toDevice["events"] ?: emptyList()))
            })
            if (useStateAfter) {
                put("state_after", nextBatchToken)
            }
        }
    }

    /**
     * Get rooms that the user has joined
     */
    private fun getUserJoinedRooms(userId: String): List<String> {
    return getUserMembershipMap(userId).filterValues { it == "join" }.keys.toList()
    }

    /**
     * Get rooms that the user has been invited to
     */
    private fun getUserInvitedRooms(userId: String): List<String> {
        val invitedRooms = getUserMembershipMap(userId).filterValues { it == "invite" }.keys.toList()
        logger.info("getUserInvitedRooms for $userId: $invitedRooms")
        return invitedRooms
    }

    /**
     * Get rooms that the user has left
     */
    private fun getUserLeftRooms(userId: String): List<String> {
        return getUserMembershipMap(userId).filterValues { it == "leave" }.keys.toList()
    }

    /**
     * Compute the latest membership for the user in each room.
     * Returns a map of roomId -> membership ("join"|"invite"|"leave"|other)
     */
    private fun getUserMembershipMap(userId: String): Map<String, String> {
        val membershipMap = transaction {
            // Select all member events for this user ordered by time desc so the first occurrence by room is the latest
            val rows = Events.select {
                (Events.type eq "m.room.member") and
                (Events.stateKey eq userId)
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .map { it }

            logger.debug("Query executed: SELECT * FROM events WHERE type='m.room.member' AND state_key='$userId' ORDER BY origin_server_ts DESC")
            logger.debug("Found ${rows.size} member events for $userId")
            rows.forEach { row ->
                val roomId = row[Events.roomId]
                val content = row[Events.content]
                val eventId = row[Events.eventId]
                val sender = row[Events.sender]
                val stateKey = row[Events.stateKey]
                logger.debug("Member event: eventId=$eventId, roomId=$roomId, sender=$sender, stateKey=$stateKey, content=$content")
            }

            // Group by roomId and pick the first (latest) event per room
            val latestByRoom = rows.groupBy { it[Events.roomId] }
                .mapValues { entry -> entry.value.first() }

            // Extract the membership string from the content JSON
            latestByRoom.mapValues { (_, row) ->
                try {
                    val content = Json.parseToJsonElement(row[Events.content]).jsonObject
                    content["membership"]?.jsonPrimitive?.content ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
        }
        logger.debug("getUserMembershipMap for $userId: $membershipMap")
        return membershipMap
    }

    /**
     * Get stripped state for a room (for invites)
     * Per Matrix spec, this should include the invite event itself plus invite_room_state context
     */
    private fun getStrippedState(roomId: String, userId: String): List<JsonElement> {
        logger.debug("getStrippedState called for roomId=$roomId, userId=$userId")
        return transaction {
            val strippedEvents = mutableListOf<JsonElement>()

            // First, add the actual invite event for this user
            val inviteEvent = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq "m.room.member") and
                (Events.stateKey eq userId)
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .map { row ->
                    try {
                        val content = Json.parseToJsonElement(row[Events.content]).jsonObject
                        val membership = content["membership"]?.jsonPrimitive?.content
                        logger.debug("Found member event for $userId in $roomId with membership=$membership")
                        if (membership == "invite") {
                            buildJsonObject {
                                put("type", row[Events.type])
                                put("sender", row[Events.sender])
                                put("origin_server_ts", row[Events.originServerTs])
                                put("content", content)
                                row[Events.stateKey]?.let { put("state_key", it) }
                            }
                        } else null
                    } catch (e: Exception) {
                        logger.debug("Error parsing member event content: $e")
                        null
                    }
                }
                .filterNotNull()
                .firstOrNull()

            if (inviteEvent != null) {
                logger.debug("Added invite event to stripped state")
                strippedEvents.add(inviteEvent)
            } else {
                logger.debug("No invite event found for $userId in $roomId")
            }

            // Then get ALL outlier events for this room (these are the invite_room_state events)
            val outlierEvents = Events.select {
                (Events.roomId eq roomId) and
                (Events.outlier eq true)
            }.map { row ->
                buildJsonObject {
                    put("type", row[Events.type])
                    put("sender", row[Events.sender])
                    put("origin_server_ts", row[Events.originServerTs])
                    put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                    row[Events.stateKey]?.let { put("state_key", it) }
                }
            }
            logger.debug("Found ${outlierEvents.size} outlier events")
            strippedEvents.addAll(outlierEvents)

            // If no outliers exist, fall back to basic state (shouldn't happen with proper federation)
            if (strippedEvents.isEmpty()) {
                logger.debug("No stripped events found, using fallback")
                // Always include m.room.create
                val createEvent = Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.create")
                }.singleOrNull()
                if (createEvent != null) {
                    strippedEvents.add(buildJsonObject {
                        put("type", createEvent[Events.type])
                        put("sender", createEvent[Events.sender])
                        put("origin_server_ts", createEvent[Events.originServerTs])
                        put("content", Json.parseToJsonElement(createEvent[Events.content]).jsonObject)
                        put("state_key", createEvent[Events.stateKey])
                    })
                }

                // Include other state events
                val otherEvents = Events.select {
                    (Events.roomId eq roomId) and
                    (Events.stateKey.isNotNull()) and
                    (Events.type inList listOf("m.room.name", "m.room.topic", "m.room.avatar", "m.room.join_rules", "m.room.canonical_alias", "m.room.member"))
                }.map { row ->
                    buildJsonObject {
                        put("type", row[Events.type])
                        put("sender", row[Events.sender])
                        put("origin_server_ts", row[Events.originServerTs])
                        put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                        put("state_key", row[Events.stateKey])
                    }
                }
                strippedEvents.addAll(otherEvents)
            }

            logger.debug("Returning ${strippedEvents.size} stripped events")
            strippedEvents
        }
    }

    /**
     * Get sync data for a specific room
     */
    private fun getRoomSyncData(
        userId: String,
        roomId: String,
        since: MatrixPagination.SyncToken?,
        isFullState: Boolean
    ): RoomSyncData {
        val timeline = mutableListOf<JsonElement>()
        val state = mutableListOf<JsonElement>()
        val ephemeral = mutableListOf<JsonElement>()
        val accountData = mutableListOf<JsonElement>()

        // Get timeline events (recent messages)
        val timelineEvents = transaction {
            val query = Events.select { Events.roomId eq roomId }

            // For incremental sync, only get events since the token
            if (!isFullState && since != null) {
                query.andWhere { Events.originServerTs greater since.timestamp }
            }

            query.orderBy(Events.originServerTs, SortOrder.DESC)
                .limit(50) // Limit to recent events
                .map { row ->
                    buildJsonObject {
                        put("event_id", row[Events.eventId])
                        put("type", row[Events.type])
                        put("sender", row[Events.sender])
                        put("origin_server_ts", row[Events.originServerTs])
                        put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                        put("unsigned", buildJsonObject { })
                    }
                }
        }
        timeline.addAll(timelineEvents)

        // Get current state events - always include full state for joined rooms
        val stateEvents = transaction {
            Events.select {
                (Events.roomId eq roomId) and
                (Events.stateKey.isNotNull())
            }.orderBy(Events.originServerTs, SortOrder.DESC)
                .distinctBy { it[Events.type] to it[Events.stateKey] } // Get latest for each state key
                .map { row ->
                    buildJsonObject {
                        put("event_id", row[Events.eventId])
                        put("type", row[Events.type])
                        put("sender", row[Events.sender])
                        put("origin_server_ts", row[Events.originServerTs])
                        put("content", Json.parseToJsonElement(row[Events.content]).jsonObject)
                        put("state_key", row[Events.stateKey])
                        put("unsigned", buildJsonObject { })
                    }
                }
        }
        state.addAll(stateEvents)

        // Get ephemeral events (typing notifications)
        val typingUsers = typingMap[roomId] ?: mutableMapOf()
        if (typingUsers.isNotEmpty()) {
            ephemeral.add(buildJsonObject {
                put("type", "m.typing")
                put("content", buildJsonObject {
                    put("user_ids", JsonArray(typingUsers.keys.map { JsonPrimitive(it) }))
                })
            })
        }

        // Get room account data
        val roomAccountData = transaction {
            AccountData.select {
                (AccountData.userId eq userId) and
                (AccountData.roomId eq roomId)
            }.map { row ->
                buildJsonObject {
                    put("type", row[AccountData.type])
                    put("content", Json.parseToJsonElement(row[AccountData.content]).jsonObject)
                }
            }
        }
        accountData.addAll(roomAccountData)

        // Get room summary
        val summary = getRoomSummary(roomId)

        // Get unread notifications (simplified)
        val unreadNotifications = buildJsonObject {
            put("notification_count", 0)
            put("highlight_count", 0)
        }

        return RoomSyncData(
            roomId = roomId,
            timeline = timeline,
            state = state,
            ephemeral = ephemeral,
            accountData = accountData,
            summary = summary,
            unreadNotifications = unreadNotifications
        )
    }

    /**
     * Get room summary information
     */
    private fun getRoomSummary(roomId: String): JsonElement? {
        return transaction {
            val room = Rooms.select { Rooms.roomId eq roomId }.singleOrNull()
            if (room != null) {
                val summary = mutableMapOf<String, JsonElement>()

                room[Rooms.name]?.let { summary["name"] = JsonPrimitive(it) }
                room[Rooms.topic]?.let { summary["topic"] = JsonPrimitive(it) }

                // Get member count
                val memberCount = Events.select {
                    (Events.roomId eq roomId) and
                    (Events.type eq "m.room.member") and
                    (Events.content.like("%\"membership\":\"join\"%"))
                }.count()

                summary["joined_member_count"] = JsonPrimitive(memberCount.toInt())

                // Get room aliases
                val aliases = RoomAliases.select { RoomAliases.roomId eq roomId }
                    .map { it[RoomAliases.alias] }

                if (aliases.isNotEmpty()) {
                    summary["aliases"] = JsonArray(aliases.map { JsonPrimitive(it) })
                }

                JsonObject(summary)
            } else null
        }
    }

    /**
     * Get presence events
     */
    private fun getPresenceEvents(since: MatrixPagination.SyncToken?): Collection<JsonElement> {
        return transaction {
            val query = Presence.selectAll()

            // For incremental sync, only get changes since the token
            if (since != null) {
                query.andWhere { Presence.lastSyncTs.isNull() or (Presence.lastSyncTs less since.timestamp) }
            }

            query.map { row ->
                val contentMap = mutableMapOf<String, JsonElement>(
                    "presence" to JsonPrimitive(row[Presence.presence]),
                    "last_active_ago" to JsonPrimitive(row[Presence.lastActiveAgo]),
                    "currently_active" to JsonPrimitive(row[Presence.currentlyActive])
                )

                row[Presence.statusMsg]?.let {
                    contentMap["status_msg"] = JsonPrimitive(it)
                }

                buildJsonObject {
                    put("type", "m.presence")
                    put("sender", row[Presence.userId])
                    put("content", JsonObject(contentMap))
                }
            }
        }
    }

    /**
     * Get account data events
     */
    private fun getAccountData(userId: String, since: MatrixPagination.SyncToken?): Collection<JsonElement> {
        return transaction {
            val query = AccountData.select {
                (AccountData.userId eq userId) and
                (AccountData.roomId.isNull())
            }

            // For incremental sync, only get changes since the token
            if (since != null) {
                query.andWhere { AccountData.lastModified greater since.timestamp }
            }

            query.map { row ->
                buildJsonObject {
                    put("type", row[AccountData.type])
                    put("content", Json.parseToJsonElement(row[AccountData.content]).jsonObject)
                }
            }
        }
    }

    /**
     * Update user's last sync timestamp
     */
    fun updateUserSyncTimestamp(_userId: String, timestamp: Long = System.currentTimeMillis()) {
        transaction {
            Presence.update({ Presence.userId eq _userId }) {
                it[lastSyncTs] = timestamp
            }
        }
    }
}
