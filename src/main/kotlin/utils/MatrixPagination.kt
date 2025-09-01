package utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

/**
 * Matrix Pagination Token Utility
 * Implements proper Matrix pagination tokens as per Matrix Client-Server API v1.15
 */
object MatrixPagination {

    private val json = Json { encodeDefaults = true }

    @Serializable
    data class SyncToken(
        val eventId: String,
        val timestamp: Long,
        val roomId: String? = null
    )

    @Serializable
    data class MessageToken(
        val eventId: String,
        val timestamp: Long,
        val roomId: String
    )

    @Serializable
    data class ContextToken(
        val eventId: String,
        val timestamp: Long,
        val roomId: String
    )

    /**
     * Create a sync pagination token
     */
    fun createSyncToken(eventId: String, timestamp: Long, roomId: String? = null): String {
        val token = SyncToken(eventId, timestamp, roomId)
        val jsonString = json.encodeToString(token)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonString.toByteArray())
    }

    /**
     * Parse a sync pagination token
     */
    fun parseSyncToken(token: String): SyncToken? {
        return try {
            val jsonString = String(Base64.getUrlDecoder().decode(token))
            json.decodeFromString<SyncToken>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a message pagination token
     */
    fun createMessageToken(eventId: String, timestamp: Long, roomId: String): String {
        val token = MessageToken(eventId, timestamp, roomId)
        val jsonString = json.encodeToString(token)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonString.toByteArray())
    }

    /**
     * Parse a message pagination token
     */
    fun parseMessageToken(token: String): MessageToken? {
        return try {
            val jsonString = String(Base64.getUrlDecoder().decode(token))
            json.decodeFromString<MessageToken>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a context pagination token
     */
    fun createContextToken(eventId: String, timestamp: Long, roomId: String): String {
        val token = ContextToken(eventId, timestamp, roomId)
        val jsonString = json.encodeToString(token)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonString.toByteArray())
    }

    /**
     * Parse a context pagination token
     */
    fun parseContextToken(token: String): ContextToken? {
        return try {
            val jsonString = String(Base64.getUrlDecoder().decode(token))
            json.decodeFromString<ContextToken>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validate if a token is properly formatted
     */
    fun isValidToken(token: String): Boolean {
        return try {
            Base64.getUrlDecoder().decode(token)
            true
        } catch (e: Exception) {
            false
        }
    }
}
