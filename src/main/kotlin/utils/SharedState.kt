package utils

import io.ktor.server.websocket.DefaultWebSocketServerSession
import models.Events
import models.Rooms
import models.StateGroups
import models.AccountData
import models.Users
import models.AccessTokens
import models.Devices
import models.CrossSigningKeys
import models.DehydratedDevices
import models.OAuthAuthorizationCodes
import models.OAuthAccessTokens
import models.OAuthStates
import models.Media
import models.Receipts
import models.Presence
import models.PushRules
import models.Pushers
import models.RoomAliases
import models.RegistrationTokens
import models.Filters
import models.ThirdPartyIdentifiers
import models.ApplicationServices
import models.LoginTokens
import models.RoomKeyVersions
import models.RoomKeys
import models.OneTimeKeys
import models.KeySignatures
import models.ServerKeys as ServerKeysTable
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
            val existing = ServerKeysTable.select {
                (ServerKeysTable.serverName eq serverName) and
                (ServerKeysTable.keyId eq keyId)
            }.singleOrNull()

            if (existing != null) {
                ServerKeysTable.update({
                    (ServerKeysTable.serverName eq serverName) and
                    (ServerKeysTable.keyId eq keyId)
                }) {
                    it[ServerKeysTable.publicKey] = publicKey
                    it[ServerKeysTable.keyValidUntilTs] = validUntilTs
                    it[ServerKeysTable.tsAddedTs] = System.currentTimeMillis()
                    it[ServerKeysTable.tsValidUntilTs] = validUntilTs
                }
            } else {
                ServerKeysTable.insert {
                    it[ServerKeysTable.serverName] = serverName
                    it[ServerKeysTable.keyId] = keyId
                    it[ServerKeysTable.publicKey] = publicKey
                    it[ServerKeysTable.keyValidUntilTs] = validUntilTs
                    it[ServerKeysTable.tsAddedTs] = System.currentTimeMillis()
                    it[ServerKeysTable.tsValidUntilTs] = validUntilTs
                }
            }
        }
    }

    fun getServerKey(serverName: String, keyId: String): Map<String, Any?>? {
        return transaction {
            ServerKeysTable.select {
                (ServerKeysTable.serverName eq serverName) and
                (ServerKeysTable.keyId eq keyId)
            }.singleOrNull()?.let { row ->
                mutableMapOf(
                    "server_name" to row[ServerKeysTable.serverName],
                    "key_id" to row[ServerKeysTable.keyId],
                    "public_key" to row[ServerKeysTable.publicKey],
                    "valid_until_ts" to row[ServerKeysTable.keyValidUntilTs]
                )
            }
        }
    }

    fun getServerKeys(serverName: String): List<Map<String, Any?>> {
        return transaction {
            ServerKeysTable.select { ServerKeysTable.serverName eq serverName }
                .map { row ->
                    mutableMapOf(
                        "server_name" to row[ServerKeysTable.serverName],
                        "key_id" to row[ServerKeysTable.keyId],
                        "public_key" to row[ServerKeysTable.publicKey],
                        "valid_until_ts" to row[ServerKeysTable.keyValidUntilTs]
                    )
                }
        }
    }
}// Cross-signing keys operations
object CrossSigningKeysStorage {
    fun storeCrossSigningKey(userId: String, keyType: String, keyData: String) {
        transaction {
            val existing = CrossSigningKeys.select {
                (CrossSigningKeys.userId eq userId) and
                (CrossSigningKeys.keyType eq keyType)
            }.singleOrNull()

            if (existing != null) {
                CrossSigningKeys.update({
                    (CrossSigningKeys.userId eq userId) and
                    (CrossSigningKeys.keyType eq keyType)
                }) {
                    it[CrossSigningKeys.publicKey] = keyData
                    it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                }
            } else {
                CrossSigningKeys.insert {
                    it[CrossSigningKeys.userId] = userId
                    it[CrossSigningKeys.keyType] = keyType
                    it[CrossSigningKeys.publicKey] = keyData
                    it[CrossSigningKeys.createdAt] = System.currentTimeMillis()
                    it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                }
            }
        }
    }

    fun getCrossSigningKey(userId: String, keyType: String): String? {
        return transaction {
            CrossSigningKeys.select {
                (CrossSigningKeys.userId eq userId) and
                (CrossSigningKeys.keyType eq keyType)
            }.singleOrNull()?.get(CrossSigningKeys.publicKey)
        }
    }

    fun getUserCrossSigningKeys(userId: String): Map<String, String> {
        return transaction {
            CrossSigningKeys.select { CrossSigningKeys.userId eq userId }
                .associate { row ->
                    row[CrossSigningKeys.keyType] to row[CrossSigningKeys.publicKey]
                }
        }
    }
}

// One-time keys operations
object OneTimeKeysStorage {
    fun storeOneTimeKey(userId: String, deviceId: String, keyId: String, algorithm: String, keyData: String) {
        transaction {
            OneTimeKeys.insert {
                it[OneTimeKeys.userId] = userId
                it[OneTimeKeys.deviceId] = deviceId
                it[OneTimeKeys.keyId] = keyId
                it[OneTimeKeys.algorithm] = algorithm
                it[OneTimeKeys.keyData] = keyData
                it[OneTimeKeys.isClaimed] = false
                it[OneTimeKeys.uploadedAt] = System.currentTimeMillis()
            }
        }
    }

    fun getOneTimeKey(userId: String, keyId: String): String? {
        return transaction {
            OneTimeKeys.select {
                (OneTimeKeys.userId eq userId) and
                (OneTimeKeys.keyId eq keyId)
            }.singleOrNull()?.get(OneTimeKeys.keyData)
        }
    }

    fun getUserOneTimeKeys(userId: String): Map<String, String> {
        return transaction {
            OneTimeKeys.select { OneTimeKeys.userId eq userId }
                .associate { row ->
                    row[OneTimeKeys.keyId] to row[OneTimeKeys.keyData]
                }
        }
    }

    fun deleteOneTimeKey(userId: String, keyId: String) {
        transaction {
            OneTimeKeys.deleteWhere {
                (OneTimeKeys.userId eq userId) and
                (OneTimeKeys.keyId eq keyId)
            }
        }
    }

    fun claimOneTimeKeys(userId: String, keyIds: List<String>): Map<String, String> {
        return transaction {
            val claimedKeys = mutableMapOf<String, String>()
            for (keyId in keyIds) {
                val keyData = getOneTimeKey(userId, keyId)
                if (keyData != null) {
                    claimedKeys[keyId] = keyData
                    deleteOneTimeKey(userId, keyId)
                }
            }
            claimedKeys
        }
    }

    fun claimOneTimeKeyByAlgorithm(userId: String, algorithm: String): Pair<String, String>? {
        return transaction {
            val availableKeys = OneTimeKeys.select {
                (OneTimeKeys.userId eq userId) and
                (OneTimeKeys.algorithm eq algorithm) and
                (OneTimeKeys.isClaimed eq false)
            }.map { row ->
                row[OneTimeKeys.keyId] to row[OneTimeKeys.keyData]
            }

            if (availableKeys.isNotEmpty()) {
                val (keyId, keyData) = availableKeys.first()
                OneTimeKeys.update({
                    (OneTimeKeys.userId eq userId) and
                    (OneTimeKeys.keyId eq keyId)
                }) {
                    it[OneTimeKeys.isClaimed] = true
                    it[OneTimeKeys.claimedAt] = System.currentTimeMillis()
                }
                Pair(keyId, keyData)
            } else {
                null
            }
        }
    }
}
