package models

import org.jetbrains.exposed.sql.Table

object Events : Table("events") {
    val eventId = varchar("event_id", 255).uniqueIndex()
    val roomId = varchar("room_id", 255)
    val type = varchar("type", 255)
    val sender = varchar("sender", 255)
    val content = text("content")
    val authEvents = text("auth_events") // JSON array of event IDs
    val prevEvents = text("prev_events") // JSON array
    val depth = integer("depth")
    val hashes = text("hashes") // JSON
    val signatures = text("signatures") // JSON
    val originServerTs = long("origin_server_ts")
    val stateKey = varchar("state_key", 255).nullable()
    val unsigned = text("unsigned").nullable()
    val softFailed = bool("soft_failed").default(false)
    val outlier = bool("outlier").default(false)
}

object Rooms : Table("rooms") {
    val roomId = varchar("room_id", 255).uniqueIndex()
    val creator = varchar("creator", 255)
    val name = varchar("name", 255).nullable()
    val topic = text("topic").nullable()
    val visibility = varchar("visibility", 50).default("private")
    val roomVersion = varchar("room_version", 50).default("9")
    val isDirect = bool("is_direct").default(false)
    val currentState = text("current_state") // JSON map of state
    val stateGroups = text("state_groups") // JSON map of state groups for resolution
    val published = bool("published").default(false) // Whether room is published in directory
}

object StateGroups : Table("state_groups") {
    val groupId = integer("group_id").uniqueIndex()
    val roomId = varchar("room_id", 255)
    val state = text("state") // JSON map of state for this group
    val events = text("events") // JSON array of event IDs in this group
}

object AccountData : Table("account_data") {
    val userId = varchar("user_id", 255)
    val type = varchar("type", 255)
    val roomId = varchar("room_id", 255).nullable() // null for global account data
    val content = text("content") // JSON content
    val lastModified = long("last_modified").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, type, roomId)
}

object Users : Table("users") {
    val userId = varchar("user_id", 255).uniqueIndex()
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 255).nullable()
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val isGuest = bool("is_guest").default(false)
    val deactivated = bool("deactivated").default(false)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastSeen = long("last_seen").default(System.currentTimeMillis())
}

object AccessTokens : Table("access_tokens") {
    val token = varchar("token", 255).uniqueIndex()
    val userId = varchar("user_id", 255)
    val deviceId = varchar("device_id", 255)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val expiresAt = long("expires_at").nullable() // null = never expires
    val lastUsed = long("last_used").default(System.currentTimeMillis())
    val userAgent = varchar("user_agent", 500).nullable()
    val ipAddress = varchar("ip_address", 45).nullable() // IPv6 compatible

    override val primaryKey = PrimaryKey(token)
}

object Devices : Table("devices") {
    val userId = varchar("user_id", 255)
    val deviceId = varchar("device_id", 255)
    val displayName = varchar("display_name", 255).nullable()
    val lastSeen = long("last_seen").default(System.currentTimeMillis())
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = varchar("user_agent", 500).nullable()
    val deviceType = varchar("device_type", 50).nullable() // mobile, desktop, tablet, etc.
    val os = varchar("os", 100).nullable() // Windows, macOS, Linux, iOS, Android, etc.
    val browser = varchar("browser", 100).nullable() // Chrome, Firefox, Safari, etc.
    val browserVersion = varchar("browser_version", 50).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastLoginAt = long("last_login_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, deviceId)
}

object OAuthAuthorizationCodes : Table("oauth_auth_codes") {
    val code = varchar("code", 255).uniqueIndex()
    val clientId = varchar("client_id", 255)
    val userId = varchar("user_id", 255)
    val redirectUri = varchar("redirect_uri", 500)
    val scope = varchar("scope", 500)
    val state = varchar("state", 255).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val expiresAt = long("expires_at")
    val used = bool("used").default(false)

    override val primaryKey = PrimaryKey(code)
}

object OAuthAccessTokens : Table("oauth_access_tokens") {
    val accessToken = varchar("access_token", 255).uniqueIndex()
    val refreshToken = varchar("refresh_token", 255).uniqueIndex()
    val clientId = varchar("client_id", 255)
    val userId = varchar("user_id", 255)
    val scope = varchar("scope", 500)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val expiresAt = long("expires_at")
    val refreshTokenExpiresAt = long("refresh_token_expires_at").nullable()

    override val primaryKey = PrimaryKey(accessToken)
}

object OAuthStates : Table("oauth_states") {
    val state = varchar("state", 255).uniqueIndex()
    val providerId = varchar("provider_id", 255)
    val redirectUri = varchar("redirect_uri", 500).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(state)
}
