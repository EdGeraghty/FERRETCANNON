package utils

import io.ktor.server.websocket.DefaultWebSocketServerSession

// Connected clients for broadcasting
val connectedClients = mutableMapOf<String, MutableList<DefaultWebSocketServerSession>>() // roomId to list of sessions

// In-memory storage for EDUs
val presenceMap = mutableMapOf<String, Map<String, Any?>>() // userId to presence data
val receiptsMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (eventId to ts)
val typingMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (userId to timestamp)

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

// Server key storage for federation
val serverKeys = mutableMapOf<String, Map<String, Any?>>() // serverName -> server key data
