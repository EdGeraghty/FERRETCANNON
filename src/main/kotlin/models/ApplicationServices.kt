package models

import org.jetbrains.exposed.sql.Table
import java.time.LocalDateTime

/**
 * Application Services table for Matrix Application Service support
 * Application Services allow external services to act on behalf of users
 */
object ApplicationServices : Table("application_services") {
    val id = varchar("id", 255) // Unique identifier for the application service
    val hsToken = varchar("hs_token", 255).uniqueIndex() // Homeserver token for AS authentication
    val asToken = varchar("as_token", 255).uniqueIndex() // Application service token
    val userId = varchar("user_id", 255) // User ID that this AS can act as
    val senderLocalpart = varchar("sender_localpart", 255) // Localpart for user creation
    val namespaces = text("namespaces") // JSON array of namespaces this AS can manage
    val url = varchar("url", 500).nullable() // URL for AS push notifications
    val protocols = text("protocols").nullable() // JSON array of supported protocols
    val rateLimited = bool("rate_limited").default(true) // Whether this AS is rate limited
    val isActive = bool("is_active").default(true) // Whether this AS is active
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(id)
}

/**
 * Login Tokens table for temporary login tokens
 */
object LoginTokens : Table("login_tokens") {
    val token = varchar("token", 255).uniqueIndex()
    val userId = varchar("user_id", 255)
    val deviceId = varchar("device_id", 255).nullable()
    val expiresAt = long("expires_at")
    val createdAt = long("created_at").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(token)
}
