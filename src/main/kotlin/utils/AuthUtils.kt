package utils

import models.AccessTokens
import models.Devices
import models.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.*
import utils.OAuthService

object AuthUtils {
    private val random = SecureRandom()

    /**
     * Hash a password using BCrypt
     */
    fun hashPassword(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt(12))
    }

    /**
     * Verify a password against its hash
     */
    fun verifyPassword(password: String, hash: String): Boolean {
        return try {
            BCrypt.checkpw(password, hash)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate a secure random access token
     */
    fun generateAccessToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate a device ID
     */
    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Validate and get user from access token
     */
    fun validateAccessToken(token: String): Pair<String, String>? { // Returns Pair<userId, deviceId> or null
        return transaction {
            AccessTokens.select { AccessTokens.token eq token }
                .singleOrNull()
                ?.let { row ->
                    val userId = row[AccessTokens.userId]
                    val deviceId = row[AccessTokens.deviceId]

                    // Update last used timestamp
                    AccessTokens.update({ AccessTokens.token eq token }) {
                        it[AccessTokens.lastUsed] = System.currentTimeMillis()
                    }

                    // Update user's last seen
                    Users.update({ Users.userId eq userId }) {
                        it[Users.lastSeen] = System.currentTimeMillis()
                    }

                    // Update device's last seen
                    Devices.update({ (Devices.userId eq userId) and (Devices.deviceId eq deviceId) }) {
                        it[Devices.lastSeen] = System.currentTimeMillis()
                    }

                    Pair(userId, deviceId)
                }
        }
    }

    /**
     * Create a new user
     */
    fun createUser(
        username: String,
        password: String,
        displayName: String? = null,
        isGuest: Boolean = false
    ): String {
        val userId = "@$username:localhost"
        val passwordHash = if (isGuest) "" else hashPassword(password)

        transaction {
            Users.insert {
                it[Users.userId] = userId
                it[Users.username] = username
                it[Users.passwordHash] = passwordHash
                it[Users.displayName] = displayName ?: username
                it[Users.isGuest] = isGuest
            }
        }

        return userId
    }

    /**
     * Authenticate user with password
     */
    fun authenticateUser(username: String, password: String): String? {
        return transaction {
            Users.select { (Users.username eq username) and (Users.deactivated eq false) }
                .singleOrNull()
                ?.let { row ->
                    val userId = row[Users.userId]
                    val passwordHash = row[Users.passwordHash]

                    if (verifyPassword(password, passwordHash)) {
                        userId
                    } else {
                        null
                    }
                }
        }
    }

    /**
     * Create access token for user
     */
    fun createAccessToken(
        userId: String,
        deviceId: String,
        userAgent: String? = null,
        ipAddress: String? = null
    ): String {
        val token = generateAccessToken()

        transaction {
            AccessTokens.insert {
                it[AccessTokens.token] = token
                it[AccessTokens.userId] = userId
                it[AccessTokens.deviceId] = deviceId
                it[AccessTokens.userAgent] = userAgent
                it[AccessTokens.ipAddress] = ipAddress
            }

            // Ensure device exists
            val deviceExists = Devices.select {
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }.count() > 0

            if (!deviceExists) {
                Devices.insert {
                    it[Devices.userId] = userId
                    it[Devices.deviceId] = deviceId
                    it[Devices.userAgent] = userAgent
                    it[Devices.ipAddress] = ipAddress
                }
            } else {
                Devices.update({ (Devices.userId eq userId) and (Devices.deviceId eq deviceId) }) {
                    it[Devices.lastSeen] = System.currentTimeMillis()
                    if (userAgent != null) it[Devices.userAgent] = userAgent
                    if (ipAddress != null) it[Devices.ipAddress] = ipAddress
                }
            }
        }

        return token
    }

    /**
     * Get user profile
     */
    fun getUserProfile(userId: String): Map<String, Any?>? {
        return transaction {
            Users.select { Users.userId eq userId }
                .singleOrNull()
                ?.let { row ->
                    mapOf(
                        "user_id" to row[Users.userId],
                        "username" to row[Users.username],
                        "displayname" to row[Users.displayName],
                        "avatar_url" to row[Users.avatarUrl],
                        "is_guest" to row[Users.isGuest],
                        "deactivated" to row[Users.deactivated]
                    )
                }
        }
    }

    /**
     * Update user profile
     */
    fun updateUserProfile(userId: String, displayName: String? = null, avatarUrl: String? = null) {
        transaction {
            Users.update({ Users.userId eq userId }) {
                if (displayName != null) it[Users.displayName] = displayName
                if (avatarUrl != null) it[Users.avatarUrl] = avatarUrl
            }
        }
    }

    /**
     * Check if username is available
     */
    fun isUsernameAvailable(username: String): Boolean {
        return transaction {
            Users.select { Users.username eq username }.count() == 0L
        }
    }

    /**
     * Get user's devices
     */
    fun getUserDevices(userId: String): List<Map<String, Any?>> {
        return transaction {
            Devices.select { Devices.userId eq userId }
                .map { row ->
                    mapOf(
                        "device_id" to row[Devices.deviceId],
                        "display_name" to row[Devices.displayName],
                        "last_seen_ts" to row[Devices.lastSeen],
                        "last_seen_ip" to row[Devices.ipAddress]
                    )
                }
        }
    }

    /**
     * Update device display name
     */
    fun updateDeviceDisplayName(userId: String, deviceId: String, displayName: String?) {
        transaction {
            Devices.update({ (Devices.userId eq userId) and (Devices.deviceId eq deviceId) }) {
                it[Devices.displayName] = displayName
            }
        }
    }

    /**
     * Delete access token (logout)
     */
    fun deleteAccessToken(token: String) {
        transaction {
            AccessTokens.deleteWhere { AccessTokens.token eq token }
        }
    }

    /**
     * Delete device and all its access tokens
     */
    fun deleteDevice(userId: String, deviceId: String) {
        transaction {
            // Delete all access tokens for this device
            AccessTokens.deleteWhere {
                (AccessTokens.userId eq userId) and (AccessTokens.deviceId eq deviceId)
            }

            // Delete the device
            Devices.deleteWhere {
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }
        }
    }

    /**
     * Validate OAuth 2.0 token using OAuth service
     */
    fun validateOAuthToken(token: String): String? {
        val tokenValidation = OAuthService.validateAccessToken(token)
        return tokenValidation?.first // Return userId if valid
    }

    /**
     * Validate application service token (placeholder)
     */
    fun validateApplicationServiceToken(token: String): String? {
        // TODO: Implement real application service token validation
        // For now, return a mock user ID for demo purposes
        return if (token.startsWith("as_")) "@appservice_user:localhost" else null
    }

    /**
     * Validate login token (placeholder)
     */
    fun validateLoginToken(token: String): String? {
        // TODO: Implement real login token validation
        // For now, return a mock user ID for demo purposes
        return if (token.startsWith("login_")) "@login_user:localhost" else null
    }

    /**
     * Validates password strength requirements
     * @param password The password to validate
     * @return Pair of (isValid, errorMessage) where errorMessage is null if valid
     */
    fun validatePasswordStrength(password: String): Pair<Boolean, String?> {
        // Minimum length requirement
        if (password.length < 8) {
            return Pair(false, "Password must be at least 8 characters long")
        }

        // Maximum length to prevent DoS attacks
        if (password.length > 128) {
            return Pair(false, "Password must be no more than 128 characters long")
        }

        // Check for at least one uppercase letter
        if (!password.any { it.isUpperCase() }) {
            return Pair(false, "Password must contain at least one uppercase letter")
        }

        // Check for at least one lowercase letter
        if (!password.any { it.isLowerCase() }) {
            return Pair(false, "Password must contain at least one lowercase letter")
        }

        // Check for at least one digit
        if (!password.any { it.isDigit() }) {
            return Pair(false, "Password must contain at least one number")
        }

        // Check for at least one special character
        val specialChars = "!@#\$%^&*()_+-=[]{}|;:,.<>?/~`"
        if (!password.any { specialChars.contains(it) }) {
            return Pair(false, "Password must contain at least one special character (!@#\$%^&*()_+-=[]{}|;:,.<>?/~`}")
        }

        // Check for common weak passwords
        val commonPasswords = listOf(
            "password", "123456", "123456789", "qwerty", "abc123",
            "password123", "admin", "letmein", "welcome", "monkey",
            "1234567890", "iloveyou", "princess", "rockyou", "1234567",
            "12345678", "password1", "123123", "football", "baseball"
        )

        if (commonPasswords.contains(password.lowercase())) {
            return Pair(false, "Password is too common. Please choose a more unique password")
        }

        // Check for repeated characters (more than 3 in a row)
        if (Regex("(.)\\1{3,}").containsMatchIn(password)) {
            return Pair(false, "Password cannot contain more than 3 repeated characters in a row")
        }

        // Check for sequential characters (like 123, abc, etc.)
        val sequentialPatterns = listOf(
            "012", "123", "234", "345", "456", "567", "678", "789", "890",
            "abc", "bcd", "cde", "def", "efg", "fgh", "ghi", "hij", "ijk",
            "jkl", "klm", "lmn", "mno", "nop", "opq", "pqr", "qrs", "rst",
            "stu", "tuv", "uvw", "vwx", "wxy", "xyz"
        )

        if (sequentialPatterns.any { password.lowercase().contains(it) }) {
            return Pair(false, "Password cannot contain sequential characters")
        }

        return Pair(true, null)
    }
}
