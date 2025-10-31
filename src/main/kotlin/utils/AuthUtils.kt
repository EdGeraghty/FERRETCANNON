package utils

import models.AccessTokens
import models.Devices
import models.Users
import models.ApplicationServices
import models.LoginTokens
import config.ServerConfig
import utils.MatrixAuth
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.SecureRandom
import java.util.*
import utils.OAuthService
import kotlin.concurrent.write

object AuthUtils {
    private val random = SecureRandom()

    /**
     * Verify a password against its hash
     */
    fun verifyPassword(password: String, hash: String): Boolean {
        return try {
            BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Hash a password using BCrypt
     */
    fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
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
     * Generate a device ID with proper format
     */
    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate a media ID for content repository
     */
    fun generateMediaId(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Extract device information from user agent string
     */
    fun parseUserAgent(userAgent: String?): DeviceInfo {
        if (userAgent.isNullOrBlank()) {
            return DeviceInfo("Unknown", "Unknown", "Unknown", "Unknown")
        }

        val ua = userAgent.lowercase()

        // Detect browser
        val browser = when {
            ua.contains("chrome") && !ua.contains("edg") -> "Chrome"
            ua.contains("firefox") -> "Firefox"
            ua.contains("safari") && !ua.contains("chrome") -> "Safari"
            ua.contains("edg") -> "Edge"
            ua.contains("opera") -> "Opera"
            ua.contains("brave") -> "Brave"
            else -> "Unknown"
        }

        // Extract browser version
        val browserVersion = when (browser) {
            "Chrome" -> extractVersion(ua, "chrome/([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+|[0-9]+\\.[0-9]+)")
            "Firefox" -> extractVersion(ua, "firefox/([0-9]+\\.[0-9]+)")
            "Safari" -> extractVersion(ua, "version/([0-9]+\\.[0-9]+)")
            "Edge" -> extractVersion(ua, "edg/([0-9]+\\.[0-9]+)")
            "Opera" -> extractVersion(ua, "opr/([0-9]+\\.[0-9]+)")
            else -> "Unknown"
        }

        // Detect OS
        val os = when {
            ua.contains("windows") -> "Windows"
            ua.contains("mac os x") || ua.contains("macos") -> "macOS"
            ua.contains("linux") -> "Linux"
            ua.contains("android") -> "Android"
            ua.contains("ios") || ua.contains("iphone") || ua.contains("ipad") -> "iOS"
            else -> "Unknown"
        }

        // Detect device type
        val deviceType = when {
            ua.contains("mobile") || ua.contains("android") || ua.contains("iphone") -> "mobile"
            ua.contains("tablet") || ua.contains("ipad") -> "tablet"
            ua.contains("windows") || ua.contains("mac os x") || ua.contains("linux") -> "desktop"
            else -> "unknown"
        }

        return DeviceInfo(browser, browserVersion, os, deviceType)
    }

    /**
     * Extract version from user agent string using regex
     */
    private fun extractVersion(userAgent: String, pattern: String): String {
        val regex = Regex(pattern)
        val match = regex.find(userAgent)
        return match?.groupValues?.get(1) ?: "Unknown"
    }

    /**
     * Data class for device information
     */
    data class DeviceInfo(
        val browser: String,
        val browserVersion: String,
        val os: String,
        val deviceType: String
    )

    /**
     * Validate and get user from access token
     */
    fun validateAccessToken(token: String): Pair<String, String>? { // Returns Pair<userId, deviceId> or null
        return transaction {
            val accessTokenRow = AccessTokens.select { AccessTokens.token eq token }.singleOrNull()
                ?: return@transaction null

            // Check if token is expired
            val expiresAt = accessTokenRow[AccessTokens.expiresAt]
            if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                // Remove expired token
                AccessTokens.deleteWhere { AccessTokens.token eq token }
                return@transaction null
            }

            // Update last used timestamp
            AccessTokens.update({ AccessTokens.token eq token }) {
                it[lastUsed] = System.currentTimeMillis()
            }

            // Update user's last seen
            Users.update({ Users.userId eq accessTokenRow[AccessTokens.userId] }) {
                it[lastSeen] = System.currentTimeMillis()
            }

            // Update device's last seen
            Devices.update({
                (Devices.userId eq accessTokenRow[AccessTokens.userId]) and
                (Devices.deviceId eq accessTokenRow[AccessTokens.deviceId])
            }) {
                it[lastSeen] = System.currentTimeMillis()
            }

            Pair(accessTokenRow[AccessTokens.userId], accessTokenRow[AccessTokens.deviceId])
        }
    }

    /**
     * Create a new user
     */
    fun createUser(
        username: String,
        password: String,
        displayName: String? = null,
        isGuest: Boolean = false,
        isAdmin: Boolean = false,
        serverName: String = "localhost"
    ): String {
        val userId = "@$username:$serverName"
        val passwordHash = if (isGuest) "" else hashPassword(password)

        return transaction {
            // Check if username already exists
            val existingUser = Users.select { Users.username eq username }.singleOrNull()
            if (existingUser != null) {
                throw IllegalArgumentException("Username already exists")
            }

            // Insert new user
            Users.insert {
                it[Users.userId] = userId
                it[Users.username] = username
                it[Users.passwordHash] = passwordHash
                it[Users.displayName] = displayName ?: username
                it[Users.isGuest] = isGuest
                it[Users.isAdmin] = isAdmin
                it[Users.createdAt] = System.currentTimeMillis()
                it[Users.lastSeen] = System.currentTimeMillis()
            }

            userId
        }
    }

    /**
     * Authenticate user with password
     */
    fun authenticateUser(username: String, password: String): String? {
        return try {
            // Handle both full user IDs (@user:domain) and local usernames
            val lookupUsername = if (username.startsWith("@") && username.contains(":")) {
                // Extract localpart from full user ID
                username.substring(1, username.indexOf(":"))
            } else {
                username
            }

            println("AuthUtils.authenticateUser - Input: '$username', Lookup: '$lookupUsername'")

            transaction {
                // Case-insensitive username lookup per Matrix spec
                val userRow = Users.select { 
                    Users.username.lowerCase() eq lookupUsername.lowercase()
                }.singleOrNull()
                    
                if (userRow == null) {
                    println("AuthUtils.authenticateUser - No user found for lookup: '$lookupUsername'")
                    return@transaction null
                }

                if (userRow[Users.deactivated]) {
                    println("AuthUtils.authenticateUser - User deactivated: '${userRow[Users.username]}'")
                    return@transaction null
                }

                if (userRow[Users.isGuest]) {
                    println("AuthUtils.authenticateUser - Guest user: '${userRow[Users.username]}'")
                    return@transaction userRow[Users.userId]
                }

                if (verifyPassword(password, userRow[Users.passwordHash])) {
                    println("AuthUtils.authenticateUser - Password verified for: '${userRow[Users.username]}'")
                    // Update last seen
                    Users.update({ Users.userId eq userRow[Users.userId] }) {
                        it[lastSeen] = System.currentTimeMillis()
                    }
                    return@transaction userRow[Users.userId]
                } else {
                    println("AuthUtils.authenticateUser - Password verification failed for: '${userRow[Users.username]}'")
                }

                null
            }
        } catch (e: Exception) {
            println("Error in authenticateUser: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Create access token for user with enhanced device information
     */
    fun createAccessToken(
        userId: String,
        deviceId: String,
        userAgent: String? = null,
        ipAddress: String? = null
    ): String {
        val token = generateAccessToken()

        // Parse device information from user agent
        val deviceInfo = parseUserAgent(userAgent)

        return transaction {
            // Insert access token
            AccessTokens.insert {
                it[AccessTokens.token] = token
                it[AccessTokens.userId] = userId
                it[AccessTokens.deviceId] = deviceId
                it[AccessTokens.createdAt] = System.currentTimeMillis()
                it[AccessTokens.lastUsed] = System.currentTimeMillis()
                it[AccessTokens.userAgent] = userAgent
                it[AccessTokens.ipAddress] = ipAddress
            }

            // Check if device exists
            val existingDevice = Devices.select {
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }.singleOrNull()

            if (existingDevice == null) {
                // Generate device keys for new device
                val (ed25519Key, curve25519Key) = generateDeviceKeys()
                val keyId = ServerKeys.generateKeyId()

                // Create new device with comprehensive information and keys
                Devices.insert {
                    it[Devices.userId] = userId
                    it[Devices.deviceId] = deviceId
                    it[Devices.lastSeen] = System.currentTimeMillis()
                    it[Devices.ipAddress] = ipAddress
                    it[Devices.userAgent] = userAgent
                    it[Devices.deviceType] = deviceInfo.deviceType
                    it[Devices.os] = deviceInfo.os
                    it[Devices.browser] = deviceInfo.browser
                    it[Devices.browserVersion] = deviceInfo.browserVersion
                    it[Devices.createdAt] = System.currentTimeMillis()
                    it[Devices.lastLoginAt] = System.currentTimeMillis()
                    it[Devices.ed25519Key] = ed25519Key
                    it[Devices.curve25519Key] = curve25519Key
                    it[Devices.ed25519KeyId] = keyId
                    it[Devices.curve25519KeyId] = keyId
                }
            } else {
                // Update existing device with latest information
                Devices.update({
                    (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
                }) {
                    it[lastSeen] = System.currentTimeMillis()
                    it[lastLoginAt] = System.currentTimeMillis()
                    if (userAgent != null) it[Devices.userAgent] = userAgent
                    if (ipAddress != null) it[Devices.ipAddress] = ipAddress
                    it[Devices.deviceType] = deviceInfo.deviceType
                    it[Devices.os] = deviceInfo.os
                    it[Devices.browser] = deviceInfo.browser
                    it[Devices.browserVersion] = deviceInfo.browserVersion
                }
            }

            token
        }
    }

    /**
     * Get user profile
     */
    fun getUserProfile(userId: String): Map<String, Any?>? {
        return transaction {
            val userRow = Users.select { Users.userId eq userId }.singleOrNull()
                ?: return@transaction null

            mutableMapOf(
                "user_id" to userRow[Users.userId],
                "username" to userRow[Users.username],
                "displayname" to userRow[Users.displayName],
                "avatar_url" to userRow[Users.avatarUrl],
                "is_guest" to userRow[Users.isGuest],
                "deactivated" to userRow[Users.deactivated]
            )
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
     * Get user's devices with enhanced information
     */
    fun getUserDevices(userId: String): List<Map<String, Any?>> {
        return transaction {
            Devices.select { Devices.userId eq userId }
                .map { deviceRow ->
                    mutableMapOf(
                        "device_id" to deviceRow[Devices.deviceId],
                        "display_name" to deviceRow[Devices.displayName],
                        "last_seen_ts" to deviceRow[Devices.lastSeen],
                        "last_seen_ip" to deviceRow[Devices.ipAddress],
                        "device_type" to deviceRow[Devices.deviceType],
                        "os" to deviceRow[Devices.os],
                        "browser" to deviceRow[Devices.browser],
                        "browser_version" to deviceRow[Devices.browserVersion],
                        "created_at" to deviceRow[Devices.createdAt],
                        "last_login_at" to deviceRow[Devices.lastLoginAt]
                    )
                }
        }
    }

    /**
     * Get specific device for user with enhanced information
     */
    fun getUserDevice(userId: String, deviceId: String): Map<String, Any?>? {
        return transaction {
            val deviceRow = Devices.select {
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }.singleOrNull() ?: return@transaction null

            mutableMapOf(
                "device_id" to deviceRow[Devices.deviceId],
                "display_name" to deviceRow[Devices.displayName],
                "last_seen_ts" to deviceRow[Devices.lastSeen],
                "last_seen_ip" to deviceRow[Devices.ipAddress],
                "device_type" to deviceRow[Devices.deviceType],
                "os" to deviceRow[Devices.os],
                "browser" to deviceRow[Devices.browser],
                "browser_version" to deviceRow[Devices.browserVersion],
                "created_at" to deviceRow[Devices.createdAt],
                "last_login_at" to deviceRow[Devices.lastLoginAt]
            )
        }
    }

    /**
     * Check if device belongs to user
     */
    fun deviceBelongsToUser(userId: String, deviceId: String): Boolean {
        return transaction {
            Devices.select {
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }.count() > 0
        }
    }

    /**
     * Update device display name
     */
    fun updateDeviceDisplayName(userId: String, deviceId: String, displayName: String?): Boolean {
        return transaction {
            val updated = Devices.update({
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }) {
                it[Devices.displayName] = displayName
            }
            updated > 0 // Return true if device was found and updated
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
     * Delete all access tokens for a user (logout all sessions)
     */
    fun deleteAllAccessTokensForUser(userId: String) {
        transaction {
            AccessTokens.deleteWhere { AccessTokens.userId eq userId }
        }
    }

    /**
     * Delete device and all its access tokens
     */
    fun deleteDevice(userId: String, deviceId: String) {
        transaction {
            // Remove all access tokens for this device
            AccessTokens.deleteWhere {
                (AccessTokens.userId eq userId) and (AccessTokens.deviceId eq deviceId)
            }

            // Remove the device
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
     * Validate application service token (real implementation)
     */
    fun validateApplicationServiceToken(token: String): String? {
        return transaction {
            // Find the application service by AS token
            val asService = ApplicationServices.select { ApplicationServices.asToken eq token }
                .singleOrNull()

            if (asService != null && asService[ApplicationServices.isActive]) {
                // Return the user ID that this AS can act as
                asService[ApplicationServices.userId]
            } else {
                null
            }
        }
    }

    /**
     * Validate login token (real implementation)
     */
    fun validateLoginToken(token: String): String? {
        val currentTime = System.currentTimeMillis()

        return transaction {
            // Find the login token and check if it's not expired
            val loginToken = LoginTokens.select {
                (LoginTokens.token eq token) and (LoginTokens.expiresAt greater currentTime)
            }.singleOrNull()

            if (loginToken != null) {
                // Return the user ID associated with this login token
                loginToken[LoginTokens.userId]
            } else {
                null
            }
        }
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
            return Pair(false, "Password must contain at least one special character (!@#\$%^&*()_+-=[]{}|;:,.<>?/~`)")
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

    /**
     * Clean up expired access tokens
     */
    fun cleanupExpiredTokens() {
        val currentTime = System.currentTimeMillis()
        transaction {
            // Clean up expired access tokens
            AccessTokens.deleteWhere {
                AccessTokens.expiresAt neq null and (AccessTokens.expiresAt less currentTime)
            }

            // Clean up expired OAuth auth codes
            models.OAuthAuthorizationCodes.deleteWhere {
                models.OAuthAuthorizationCodes.expiresAt less currentTime
            }

            // Clean up expired OAuth access tokens
            models.OAuthAccessTokens.deleteWhere {
                models.OAuthAccessTokens.expiresAt less currentTime
            }

            // Clean up expired OAuth states
            models.OAuthStates.deleteWhere {
                models.OAuthStates.expiresAt less currentTime
            }
        }
    }

    /**
     * Get all users (for admin purposes)
     */
    fun getAllUsers(): List<Map<String, Any?>> {
        return transaction {
            Users.selectAll().map { userRow ->
                mutableMapOf(
                    "user_id" to userRow[Users.userId],
                    "username" to userRow[Users.username],
                    "display_name" to userRow[Users.displayName],
                    "is_guest" to userRow[Users.isGuest],
                    "deactivated" to userRow[Users.deactivated],
                    "created_at" to userRow[Users.createdAt],
                    "last_seen" to userRow[Users.lastSeen]
                )
            }
        }
    }

    /**
     * Deactivate user
     */
    fun deactivateUser(userId: String) {
        transaction {
            // Deactivate user
            Users.update({ Users.userId eq userId }) {
                it[Users.deactivated] = true
            }

            // Remove all access tokens for this user
            AccessTokens.deleteWhere { AccessTokens.userId eq userId }
        }
    }

    /**
     * Generate device keys for end-to-end encryption
     */
    fun generateDeviceKeys(): Pair<String, String> {
        // Generate ed25519 key pair
        val ed25519PrivateKey = ServerKeys.generateEd25519KeyPair()

        // Derive public key from private key
        val spec = net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName(net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.ED_25519)
        val publicKeySpec = net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(ed25519PrivateKey.a, spec)
        val ed25519PublicKey = net.i2p.crypto.eddsa.EdDSAPublicKey(publicKeySpec)

        val ed25519PublicKeyEncoded = ServerKeys.encodeEd25519PublicKey(ed25519PublicKey)

        // Generate curve25519 key pair (for demo purposes, we'll use the same key)
        // In a real implementation, you'd generate separate curve25519 keys
        val curve25519PublicKey = ed25519PublicKeyEncoded // Simplified for demo

        return Pair(ed25519PublicKeyEncoded, curve25519PublicKey)
    }

    /**
     * Update device with generated keys
     */
    fun updateDeviceKeys(userId: String, deviceId: String) {
        val (ed25519Key, curve25519Key) = generateDeviceKeys()
        val keyId = ServerKeys.generateKeyId()

        transaction {
            Devices.update({
                (Devices.userId eq userId) and (Devices.deviceId eq deviceId)
            }) {
                it[Devices.ed25519Key] = ed25519Key
                it[Devices.curve25519Key] = curve25519Key
                it[Devices.ed25519KeyId] = keyId
                it[Devices.curve25519KeyId] = keyId
            }
        }
    }

    /**
     * Check if a user is an admin
     */
    fun isUserAdmin(userId: String): Boolean {
        return transaction {
            val user = Users.select { Users.userId eq userId }.singleOrNull()
            user?.get(Users.isAdmin) ?: false
        }
    }

    /**
     * Set admin status for a user
     */
    fun setUserAdminStatus(userId: String, isAdmin: Boolean) {
        transaction {
            Users.update({ Users.userId eq userId }) {
                it[Users.isAdmin] = isAdmin
            }
        }
    }

    /**
     * Get device keys for multiple users
     * Returns Map<userId, Map<deviceId, deviceKeyData>>
     */
    fun getDeviceKeysForUsers(userIds: List<String>, config: ServerConfig): Map<String, Map<String, JsonElement>> {
        return transaction {
            val result = mutableMapOf<String, MutableMap<String, JsonElement>>()

            Devices.select {
                Devices.userId inList userIds
            }.forEach { row ->
                val userId = row[Devices.userId]
                val deviceId = row[Devices.deviceId]
                val ed25519Key = row[Devices.ed25519Key]
                val curve25519Key = row[Devices.curve25519Key]
                val ed25519KeyId = row[Devices.ed25519KeyId]
                val curve25519KeyId = row[Devices.curve25519KeyId]

                if (ed25519Key != null && curve25519Key != null && ed25519KeyId != null && curve25519KeyId != null) {
                    val deviceKeyData = buildJsonObject {
                        put("user_id", userId)
                        put("device_id", deviceId)
                        put("algorithms", JsonArray(listOf(JsonPrimitive("m.olm.v1.curve25519-aes-sha2"), JsonPrimitive("m.megolm.v1.aes-sha2"))))
                        put("keys", buildJsonObject {
                            put("curve25519:$curve25519KeyId", curve25519Key)
                            put("ed25519:$ed25519KeyId", ed25519Key)
                        })
                        put("signatures", buildJsonObject {
                            put(config.federation.serverName, buildJsonObject {
                                put("ed25519:ed25519:0", MatrixAuth.signJson(buildJsonObject {
                                    put("user_id", userId)
                                    put("device_id", deviceId)
                                    put("algorithms", JsonArray(listOf(JsonPrimitive("m.olm.v1.curve25519-aes-sha2"), JsonPrimitive("m.megolm.v1.aes-sha2"))))
                                    put("keys", buildJsonObject {
                                        put("curve25519:$curve25519KeyId", curve25519Key)
                                        put("ed25519:$ed25519KeyId", ed25519Key)
                                    })
                                }))
                            })
                        })
                    }

                    result.computeIfAbsent(userId) { mutableMapOf() }[deviceId] = deviceKeyData
                }
            }

            result
        }
    }
}
