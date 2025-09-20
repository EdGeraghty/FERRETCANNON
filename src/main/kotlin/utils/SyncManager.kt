package utils

import models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.*
import java.util.*
import utils.*
import utils.MatrixPagination
import utils.typingMap

/**
 * Enhanced Sync Implementation
 * Provides full state and incremental sync functionality according to Matrix Client-Server API v1.15
 */
object SyncManager {

    private val json = Json { encodeDefaults = true }

    /**
     * Sync response data class
     */
    data class SyncResponse(
        val nextBatch: String,
        val rooms: JsonElement,
        val presence: JsonElement,
        val accountData: JsonElement?,
        val deviceLists: JsonElement?,
        val deviceOneTimeKeysCount: JsonElement?,
        val toDevice: JsonElement?
    )

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
        timeout: Long = 30000,
        filter: String? = null,
        setPresence: String? = null
    ): SyncResponse {
        val currentTime = System.currentTimeMillis()

        // Get user's joined rooms
        val joinedRoomIds = getUserJoinedRooms(userId)

        // Determine if this is a full state sync
        val isFullState = fullState || since == null

        // Get room data for each joined room
        val joinRooms = mutableMapOf<String, JsonElement>()
        val inviteRooms = emptyMap<String, JsonElement>() // Empty for now
        val leaveRooms = emptyMap<String, JsonElement>() // Empty for now

        for (roomId in joinedRoomIds) {
            val roomData = getRoomSyncData(userId, roomId, since, isFullState)
            joinRooms[roomId] = Json.encodeToJsonElement(mapOf(
                "timeline" to mapOf("events" to roomData.timeline, "limited" to false, "prev_batch" to null),
                "state" to mapOf("events" to roomData.state),
                "ephemeral" to mapOf("events" to roomData.ephemeral),
                "account_data" to mapOf("events" to roomData.accountData),
                "summary" to (roomData.summary ?: JsonNull),
                "unread_notifications" to roomData.unreadNotifications
            ))
        }

        // Get presence events
        val presenceEvents = getPresenceEvents(userId, since)

        // Get account data
        val accountData = getAccountData(userId, since)

        // Get device lists (simplified)
        val deviceLists = mapOf(
            "changed" to emptyList<String>(),
            "left" to emptyList<String>()
        )

        // Get device one-time keys count (simplified)
        val deviceOneTimeKeysCount = emptyMap<String, Int>()

        // Get to-device messages (simplified)
        val toDevice = mapOf("events" to emptyList<JsonElement>())

        // Generate next batch token
        val nextBatchToken = MatrixPagination.createSyncToken(
            eventId = "sync_${currentTime}",
            timestamp = currentTime,
            roomId = null
        )

        return SyncResponse(
            nextBatch = nextBatchToken,
            rooms = Json.encodeToJsonElement(mapOf(
                "join" to joinRooms,
                "invite" to inviteRooms,
                "leave" to leaveRooms
            )),
            presence = Json.encodeToJsonElement(mapOf("events" to presenceEvents)),
            accountData = if (accountData.isNotEmpty()) Json.encodeToJsonElement(mapOf("events" to accountData)) else null,
            deviceLists = Json.encodeToJsonElement(deviceLists),
            deviceOneTimeKeysCount = Json.encodeToJsonElement(deviceOneTimeKeysCount),
            toDevice = Json.encodeToJsonElement(toDevice)
        )
    }

    /**
     * Get rooms that the user has joined
     */
    private fun getUserJoinedRooms(userId: String): List<String> {
        return transaction {
            Events.select {
                (Events.type eq "m.room.member") and
                (Events.stateKey eq userId) and
                (Events.content.like("%\"membership\":\"join\"%"))
            }.map { it[Events.roomId] }.distinct()
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
                    Json.encodeToJsonElement(mapOf(
                        "event_id" to row[Events.eventId],
                        "type" to row[Events.type],
                        "sender" to row[Events.sender],
                        "origin_server_ts" to row[Events.originServerTs],
                        "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                        "unsigned" to emptyMap<String, JsonElement>()
                    ))
                }
        }
        timeline.addAll(timelineEvents)

        // Get current state events
        if (isFullState) {
            val stateEvents = transaction {
                Events.select {
                    (Events.roomId eq roomId) and
                    (Events.stateKey.isNotNull())
                }.map { row ->
                    Json.encodeToJsonElement(mapOf(
                        "event_id" to row[Events.eventId],
                        "type" to row[Events.type],
                        "sender" to row[Events.sender],
                        "origin_server_ts" to row[Events.originServerTs],
                        "content" to Json.parseToJsonElement(row[Events.content]).jsonObject,
                        "state_key" to row[Events.stateKey],
                        "unsigned" to emptyMap<String, JsonElement>()
                    ))
                }
            }
            state.addAll(stateEvents)
        }

        // Get ephemeral events (typing notifications)
        val typingUsers = typingMap[roomId] ?: emptyMap()
        if (typingUsers.isNotEmpty()) {
            ephemeral.add(Json.encodeToJsonElement(mapOf(
                "type" to "m.typing",
                "content" to mapOf("user_ids" to typingUsers.keys.toList())
            )))
        }

        // Get room account data
        val roomAccountData = transaction {
            AccountData.select {
                (AccountData.userId eq userId) and
                (AccountData.roomId eq roomId)
            }.map { row ->
                Json.encodeToJsonElement(mapOf(
                    "type" to row[AccountData.type],
                    "content" to Json.parseToJsonElement(row[AccountData.content]).jsonObject
                ))
            }
        }
        accountData.addAll(roomAccountData)

        // Get room summary
        val summary = getRoomSummary(roomId)

        // Get unread notifications (simplified)
        val unreadNotifications = Json.encodeToJsonElement(mapOf(
            "notification_count" to 0,
            "highlight_count" to 0
        ))

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
    private fun getPresenceEvents(userId: String, since: MatrixPagination.SyncToken?): Collection<JsonElement> {
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

                val presence = mapOf(
                    "type" to JsonPrimitive("m.presence"),
                    "sender" to JsonPrimitive(row[Presence.userId]),
                    "content" to JsonObject(contentMap)
                )

                JsonObject(presence)
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
                Json.encodeToJsonElement(mapOf(
                    "type" to row[AccountData.type],
                    "content" to Json.parseToJsonElement(row[AccountData.content]).jsonObject
                ))
            }
        }
    }

    /**
     * Update user's last sync timestamp
     */
    fun updateUserSyncTimestamp(userId: String, timestamp: Long = System.currentTimeMillis()) {
        transaction {
            Presence.update({ Presence.userId eq userId }) {
                it[lastSyncTs] = timestamp
            }
        }
    }
}
