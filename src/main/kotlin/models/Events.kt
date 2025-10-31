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
    val roomVersion = varchar("room_version", 50).default("12")
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
    val isAdmin = bool("is_admin").default(false)
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
    // Device keys for end-to-end encryption
    val ed25519Key = varchar("ed25519_key", 44).nullable() // Base64 encoded ed25519 public key
    val curve25519Key = varchar("curve25519_key", 44).nullable() // Base64 encoded curve25519 public key
    val ed25519KeyId = varchar("ed25519_key_id", 50).nullable() // Key ID for ed25519 key (e.g. ed25519:YOLO420-1759713455)
    val curve25519KeyId = varchar("curve25519_key_id", 50).nullable() // Key ID for curve25519 key

    override val primaryKey = PrimaryKey(userId, deviceId)
}

object CrossSigningKeys : Table("cross_signing_keys") {
    val userId = varchar("user_id", 255)
    val keyType = varchar("key_type", 50) // "master", "self_signing", "user_signing"
    val keyId = varchar("key_id", 255) // Key ID like "ed25519:ABCDEF"
    val publicKey = text("public_key") // Base64 encoded public key
    val privateKey = text("private_key").nullable() // Encrypted private key (optional)
    val signatures = text("signatures").nullable() // JSON object of signatures
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastModified = long("last_modified").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, keyType)
}

object DehydratedDevices : Table("dehydrated_devices") {
    val userId = varchar("user_id", 255).uniqueIndex()
    val deviceId = varchar("device_id", 255)
    val deviceData = text("device_data") // JSON device data
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastModified = long("last_modified").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId)
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

object OAuthClients : Table("oauth_clients") {
    val clientId = varchar("client_id", 255).uniqueIndex()
    val clientSecret = varchar("client_secret", 255)
    val redirectUris = text("redirect_uris") // JSON array of redirect URIs
    val clientName = varchar("client_name", 255).nullable()
    val clientUri = varchar("client_uri", 500).nullable()
    val logoUri = varchar("logo_uri", 500).nullable()
    val contacts = text("contacts").nullable() // JSON array of contacts
    val tosUri = varchar("tos_uri", 500).nullable()
    val policyUri = varchar("policy_uri", 500).nullable()
    val softwareId = varchar("software_id", 255).nullable()
    val softwareVersion = varchar("software_version", 50).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(clientId)
}

object Media : Table("media") {
    val mediaId = varchar("media_id", 255).uniqueIndex()
    val userId = varchar("user_id", 255)
    val filename = varchar("filename", 500)
    val contentType = varchar("content_type", 255)
    val size = long("size")
    val uploadTime = long("upload_time").default(System.currentTimeMillis())
    val thumbnailMediaId = varchar("thumbnail_media_id", 255).nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val duration = integer("duration").nullable() // for audio/video
    val hash = varchar("hash", 128).nullable() // SHA-256 hash

    override val primaryKey = PrimaryKey(mediaId)
}

object Receipts : Table("receipts") {
    val roomId = varchar("room_id", 255)
    val userId = varchar("user_id", 255)
    val eventId = varchar("event_id", 255)
    val receiptType = varchar("receipt_type", 50) // "m.read", "m.read.private", etc.
    val threadId = varchar("thread_id", 255).nullable() // for threaded receipts
    val timestamp = long("timestamp").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(roomId, userId, receiptType)
}

object Presence : Table("presence") {
    val userId = varchar("user_id", 255).uniqueIndex()
    val presence = varchar("presence", 50).default("offline") // "online", "offline", "unavailable", "busy"
    val statusMsg = varchar("status_msg", 500).nullable()
    val lastActiveAgo = long("last_active_ago").default(0)
    val currentlyActive = bool("currently_active").default(false)
    val lastUserSyncTs = long("last_user_sync_ts").nullable()
    val lastSyncTs = long("last_sync_ts").nullable()

    override val primaryKey = PrimaryKey(userId)
}

object PushRules : Table("push_rules") {
    val userId = varchar("user_id", 255)
    val scope = varchar("scope", 50).default("global") // "global", "device"
    val kind = varchar("kind", 50) // "override", "underride", "sender", "room", "content"
    val ruleId = varchar("rule_id", 255)
    val default = bool("default").default(false)
    val enabled = bool("enabled").default(true)
    val conditions = text("conditions").nullable() // JSON array of conditions
    val actions = text("actions").nullable() // JSON array of actions
    val priorityClass = integer("priority_class").default(0)
    val priorityIndex = integer("priority_index").default(0)

    override val primaryKey = PrimaryKey(userId, scope, kind, ruleId)
}

object Pushers : Table("pushers") {
    val userId = varchar("user_id", 255)
    val pushkey = varchar("pushkey", 255) // Unique identifier for the pusher
    val kind = varchar("kind", 50) // "http"
    val appId = varchar("app_id", 255) // Application identifier
    val appDisplayName = varchar("app_display_name", 255).nullable()
    val deviceDisplayName = varchar("device_display_name", 255).nullable()
    val profileTag = varchar("profile_tag", 255).nullable()
    val lang = varchar("lang", 10).default("en")
    val data = text("data") // JSON object with push data (url, format, etc.)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastSeen = long("last_seen").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, pushkey)
}

object RoomAliases : Table("room_aliases") {
    val roomId = varchar("room_id", 255)
    val alias = varchar("alias", 255).uniqueIndex()
    val servers = text("servers") // JSON array of servers that know about this alias
    val createdAt = long("created_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(alias)
}

object RegistrationTokens : Table("registration_tokens") {
    val token = varchar("token", 255).uniqueIndex()
    val usesAllowed = integer("uses_allowed").nullable() // null = unlimited
    val pending = integer("pending").default(0)
    val completed = integer("completed").default(0)
    val expiryTime = long("expiry_time").nullable() // null = never expires
    val createdAt = long("created_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(token)
}

object ServerKeys : Table("server_keys") {
    val serverName = varchar("server_name", 255)
    val keyId = varchar("key_id", 255)
    val publicKey = text("public_key")
    val privateKey = text("private_key").nullable()
    val keyValidUntilTs = long("key_valid_until_ts")
    val tsAddedTs = long("ts_added_ts").default(System.currentTimeMillis())
    val tsValidUntilTs = long("ts_valid_until_ts")

    override val primaryKey = PrimaryKey(serverName, keyId)
}

object Filters : Table("filters") {
    val filterId = varchar("filter_id", 255).uniqueIndex()
    val userId = varchar("user_id", 255)
    val filterJson = text("filter_json") // JSON representation of the filter
    val createdAt = long("created_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(filterId)
}

object ThirdPartyIdentifiers : Table("third_party_identifiers") {
    val userId = varchar("user_id", 255)
    val medium = varchar("medium", 50) // "email", "msisdn" (phone)
    val address = varchar("address", 255) // email address or phone number
    val validatedAt = long("validated_at").default(System.currentTimeMillis())
    val addedAt = long("added_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, medium, address)
}

object RoomKeyVersions : Table("room_key_versions") {
    val userId = varchar("user_id", 255).uniqueIndex()
    val version = varchar("version", 255)
    val algorithm = varchar("algorithm", 255)
    val authData = text("auth_data") // JSON auth data
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val lastModified = long("last_modified").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId)
}

object RoomKeys : Table("room_keys") {
    val userId = varchar("user_id", 255)
    val version = varchar("version", 255)
    val roomId = varchar("room_id", 255)
    val sessionId = varchar("session_id", 255)
    val keyData = text("key_data") // JSON key data
    val lastModified = long("last_modified").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, version, roomId, sessionId)
}

object OneTimeKeys : Table("one_time_keys") {
    val userId = varchar("user_id", 255)
    val deviceId = varchar("device_id", 255)
    val keyId = varchar("key_id", 255) // e.g., "curve25519:AAAAA"
    val keyData = text("key_data") // JSON key data
    val algorithm = varchar("algorithm", 50) // e.g., "curve25519", "signed_curve25519"
    val isClaimed = bool("is_claimed").default(false)
    val claimedBy = varchar("claimed_by", 255).nullable() // userId who claimed it
    val claimedAt = long("claimed_at").nullable()
    val uploadedAt = long("uploaded_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, deviceId, keyId)
}

object KeySignatures : Table("key_signatures") {
    val signerUserId = varchar("signer_user_id", 255)
    val targetUserId = varchar("target_user_id", 255)
    val targetKeyId = varchar("target_key_id", 255) // device_id or key_id
    val targetKeyType = varchar("target_key_type", 50) // "device" or "cross_signing"
    val signatures = text("signatures") // JSON object of signatures
    val uploadedAt = long("uploaded_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(signerUserId, targetUserId, targetKeyId, targetKeyType)
}
