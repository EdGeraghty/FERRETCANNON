package utils

import io.ktor.server.websocket.DefaultWebSocketServerSession
import models.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

// Connected clients for broadcasting
val connectedClients = mutableMapOf<String, MutableList<DefaultWebSocketServerSession>>() // roomId to list of sessions

// In-memory storage for EDUs (keeping typing as it's short-lived)
val typingMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (userId to timestamp)

// Direct-to-device message storage (temporary storage for messages to be delivered)
val directToDeviceMessages = mutableMapOf<String, MutableList<Map<String, Any?>>>() // userId -> list of messages

// ===== DATABASE-BACKED STORAGE =====

// Receipts operations
object ReceiptsStorage {
    fun addReceipt(roomId: String, userId: String, eventId: String, receiptType: String = "m.read", threadId: String? = null) {
        transaction {
            // Remove existing receipt for this user/room/type combination
            Receipts.deleteWhere {
                (Receipts.roomId eq roomId) and
                (Receipts.userId eq userId) and
                (Receipts.receiptType eq receiptType)
            }

            // Add new receipt
            Receipts.insert {
                it[Receipts.roomId] = roomId
                it[Receipts.userId] = userId
                it[Receipts.eventId] = eventId
                it[Receipts.receiptType] = receiptType
                it[Receipts.threadId] = threadId
                it[Receipts.timestamp] = System.currentTimeMillis()
            }
        }
    }

    fun getReceiptsForRoom(roomId: String): Map<String, Map<String, Any?>> {
        return transaction {
            Receipts.select { Receipts.roomId eq roomId }
                .associate { row ->
                    val userId = row[Receipts.userId]
                    val _receiptType = row[Receipts.receiptType]
                    val eventId = row[Receipts.eventId]
                    val timestamp = row[Receipts.timestamp]

                    userId to mutableMapOf(
                        "event_id" to eventId,
                        "ts" to timestamp,
                        "thread_id" to row[Receipts.threadId]
                    )
                }
        }
    }

    fun getUserReceipt(userId: String, roomId: String, receiptType: String = "m.read"): Map<String, Any?>? {
        return transaction {
            Receipts.select {
                (Receipts.userId eq userId) and
                (Receipts.roomId eq roomId) and
                (Receipts.receiptType eq receiptType)
            }.singleOrNull()?.let { row ->
                mutableMapOf(
                    "event_id" to row[Receipts.eventId],
                    "ts" to row[Receipts.timestamp],
                    "thread_id" to row[Receipts.threadId]
                )
            }
        }
    }
}

// Presence operations
object PresenceStorage {
    fun updatePresence(userId: String, presence: String, statusMsg: String? = null, lastActiveAgo: Long = 0) {
        transaction {
            val existing = Presence.select { Presence.userId eq userId }.singleOrNull()

            if (existing != null) {
                Presence.update({ Presence.userId eq userId }) {
                    it[Presence.presence] = presence
                    it[Presence.statusMsg] = statusMsg
                    it[Presence.lastActiveAgo] = lastActiveAgo
                    it[Presence.currentlyActive] = presence == "online"
                    it[Presence.lastUserSyncTs] = System.currentTimeMillis()
                }
            } else {
                Presence.insert {
                    it[Presence.userId] = userId
                    it[Presence.presence] = presence
                    it[Presence.statusMsg] = statusMsg
                    it[Presence.lastActiveAgo] = lastActiveAgo
                    it[Presence.currentlyActive] = presence == "online"
                    it[Presence.lastUserSyncTs] = System.currentTimeMillis()
                    it[Presence.lastSyncTs] = null
                }
            }
        }
    }

    fun getPresence(userId: String): Map<String, Any?>? {
        return transaction {
            Presence.select { Presence.userId eq userId }
                .singleOrNull()?.let { row ->
                    mutableMapOf(
                        "presence" to row[Presence.presence],
                        "status_msg" to row[Presence.statusMsg],
                        "last_active_ago" to row[Presence.lastActiveAgo],
                        "currently_active" to row[Presence.currentlyActive]
                    )
                }
        }
    }

    fun getAllPresence(): Map<String, Map<String, Any?>> {
        return transaction {
            Presence.selectAll().associate { row ->
                val userId = row[Presence.userId]
                userId to mutableMapOf(
                    "presence" to row[Presence.presence],
                    "status_msg" to row[Presence.statusMsg],
                    "last_active_ago" to row[Presence.lastActiveAgo],
                    "currently_active" to row[Presence.currentlyActive]
                )
            }
        }
    }
}

// Server keys operations
object ServerKeysStorage {
    fun storeServerKey(serverName: String, keyId: String, publicKey: String, validUntilTs: Long) {
        transaction {
            val existing = models.ServerKeys.select {
                (models.ServerKeys.serverName eq serverName) and
                (models.ServerKeys.keyId eq keyId)
            }.singleOrNull()

            if (existing != null) {
                models.ServerKeys.update({
                    (models.ServerKeys.serverName eq serverName) and
                    (models.ServerKeys.keyId eq keyId)
                }) {
                    it[models.ServerKeys.publicKey] = publicKey
                    it[models.ServerKeys.keyValidUntilTs] = validUntilTs
                    it[models.ServerKeys.tsAddedTs] = System.currentTimeMillis()
                    it[models.ServerKeys.tsValidUntilTs] = validUntilTs
                }
            } else {
                models.ServerKeys.insert {
                    it[models.ServerKeys.serverName] = serverName
                    it[models.ServerKeys.keyId] = keyId
                    it[models.ServerKeys.publicKey] = publicKey
                    it[models.ServerKeys.keyValidUntilTs] = validUntilTs
                    it[models.ServerKeys.tsAddedTs] = System.currentTimeMillis()
                    it[models.ServerKeys.tsValidUntilTs] = validUntilTs
                }
            }
        }
    }

    fun getServerKey(serverName: String, keyId: String): Map<String, Any?>? {
        return transaction {
            models.ServerKeys.select {
                (models.ServerKeys.serverName eq serverName) and
                (models.ServerKeys.keyId eq keyId)
            }.singleOrNull()?.let { row ->
                mutableMapOf(
                    "server_name" to row[models.ServerKeys.serverName],
                    "key_id" to row[models.ServerKeys.keyId],
                    "public_key" to row[models.ServerKeys.publicKey],
                    "valid_until_ts" to row[models.ServerKeys.keyValidUntilTs]
                )
            }
        }
    }

    fun getServerKeys(serverName: String): List<Map<String, Any?>> {
        return transaction {
            models.ServerKeys.select { models.ServerKeys.serverName eq serverName }
                .map { row ->
                    mutableMapOf(
                        "server_name" to row[models.ServerKeys.serverName],
                        "key_id" to row[models.ServerKeys.keyId],
                        "public_key" to row[models.ServerKeys.publicKey],
                        "valid_until_ts" to row[models.ServerKeys.keyValidUntilTs]
                    )
                }
        }
    }
}

// ===== LEGACY IN-MEMORY STORAGE (keeping for compatibility) =====

// In-memory storage for EDUs (deprecated - use database operations above)
val presenceMap = mutableMapOf<String, Map<String, Any?>>() // userId to presence data (DEPRECATED)
val receiptsMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (eventId to ts) (DEPRECATED)

// ===== IN-MEMORY USER STORAGE =====

// User data structure
data class User(
    val userId: String,
    val username: String,
    var passwordHash: String,
    var displayName: String? = null,
    var avatarUrl: String? = null,
    val isGuest: Boolean = false,
    var deactivated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis()
)

// Access token data structure
data class AccessToken(
    val token: String,
    val userId: String,
    val deviceId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    var lastUsed: Long = System.currentTimeMillis(),
    val userAgent: String? = null,
    val ipAddress: String? = null
)

// Device data structure
data class Device(
    val userId: String,
    val deviceId: String,
    var displayName: String? = null,
    var lastSeen: Long = System.currentTimeMillis(),
    var ipAddress: String? = null,
    var userAgent: String? = null,
    var deviceType: String? = null,
    var os: String? = null,
    var browser: String? = null,
    var browserVersion: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var lastLoginAt: Long = System.currentTimeMillis()
)

// OAuth authorization code data structure
data class OAuthAuthorizationCode(
    val code: String,
    val clientId: String,
    val userId: String,
    val redirectUri: String,
    val scope: String,
    val state: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    var used: Boolean = false
)

// OAuth access token data structure
data class OAuthAccessToken(
    val accessToken: String,
    val refreshToken: String,
    val clientId: String,
    val userId: String,
    val scope: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val refreshTokenExpiresAt: Long? = null
)

// OAuth state data structure
data class OAuthState(
    val state: String,
    val providerId: String,
    val redirectUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long
)

// In-memory user storage
val users = mutableMapOf<String, User>() // userId to User
val accessTokens = mutableMapOf<String, AccessToken>() // token to AccessToken
val devices = mutableMapOf<Pair<String, String>, Device>() // (userId, deviceId) to Device
val oauthAuthCodes = mutableMapOf<String, OAuthAuthorizationCode>() // code to OAuthAuthorizationCode
val oauthAccessTokens = mutableMapOf<String, OAuthAccessToken>() // accessToken to OAuthAccessToken
val oauthStates = mutableMapOf<String, OAuthState>() // state to OAuthState

// Username to userId mapping for quick lookup
val usernameToUserId = mutableMapOf<String, String>() // username to userId

// Guest user tracking
val guestUsers = mutableSetOf<String>() // Set of guest user IDs

// Device management storage (keeping existing for compatibility)
val deviceKeys = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>() // userId -> deviceId -> device info
val oneTimeKeys = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>() // userId -> keyId -> key data
val crossSigningKeys = mutableMapOf<String, Map<String, Any?>>() // userId -> cross-signing key data
val deviceListStreamIds = mutableMapOf<String, Long>() // userId -> stream_id for device list updates

// Server key storage for federation (deprecated - use ServerKeysStorage above)
val serverKeys = mutableMapOf<String, Map<String, Any?>>() // serverName -> server key data (DEPRECATED)
