
package config

import kotlinx.serialization.Serializable
import utils.ServerNameResolver

@Serializable
data class ServerConfig(
    val server: ServerSettings = ServerSettings(),
    val database: DatabaseSettings = DatabaseSettings(),
    val federation: FederationSettings = FederationSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val media: MediaSettings = MediaSettings(),
    val development: DevelopmentSettings = DevelopmentSettings(),
    val voip: VoIPSettings = VoIPSettings(),
    val ssl: SSLSettings = SSLSettings()
)

@Serializable
data class ServerSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val maxRequestSize: Long = 1024 * 1024, // 1MB
    val corsAllowedOrigins: List<String> = listOf("*") // In production, specify actual origins
)

@Serializable
data class DatabaseSettings(
    val url: String = "jdbc:sqlite:ferretcannon.db",
    val driver: String = "org.sqlite.JDBC",
    val maxConnections: Int = 10,
    val connectionTimeout: Long = 30000 // 30 seconds
)

@Serializable
data class FederationSettings(
    val serverName: String = ServerNameResolver.getServerName(),
    val federationPort: Int = 8080,
    val enableFederation: Boolean = true,
    val allowedServers: List<String> = listOf("*"), // In production, specify allowed servers
    val keyValidityPeriod: Long = 86400000 // 24 hours in milliseconds
)

@Serializable
data class SecuritySettings(
    val jwtSecret: String = "change-this-in-production-to-a-secure-random-key",
    val tokenExpirationHours: Int = 24,
    val bcryptRounds: Int = 12,
    val rateLimitRequests: Int = 300,
    val rateLimitPeriodMinutes: Int = 1,
    val enableRateLimiting: Boolean = false,
    val disableRegistration: Boolean = false
)

@Serializable
data class MediaSettings(
    val maxUploadSize: Long = 10 * 1024 * 1024, // 10MB
    val maxImageSize: Long = 10 * 1024 * 1024, // 10MB
    val maxAvatarSize: Long = 1024 * 1024, // 1MB
    val maxThumbnailSize: Long = 1024 * 1024, // 1MB
    val thumbnailSizes: List<ThumbnailSize> = listOf(
        ThumbnailSize(32, 32, "crop"),
        ThumbnailSize(96, 96, "crop"),
        ThumbnailSize(320, 240, "scale"),
        ThumbnailSize(640, 480, "scale"),
        ThumbnailSize(800, 600, "scale")
    )
)

@Serializable
data class ThumbnailSize(
    val width: Int,
    val height: Int,
    val method: String
)

@Serializable
data class DevelopmentSettings(
    val createTestUser: Boolean = true,
    val testUsername: String = "testuser",
    val testPassword: String = "TestPass123!",
    val testDisplayName: String = "Test User",
    val enableDebugLogging: Boolean = true,
    val enableWebSocketDebug: Boolean = false,
    val isDebug: Boolean = true
)

@Serializable
data class VoIPSettings(
    val turnServers: List<TurnServer> = emptyList(),
    val stunServers: List<StunServer> = emptyList()
)

@Serializable
data class SSLSettings(
    val mode: SSLMode = SSLMode.SNAKEOIL,
    val certificatePath: String? = null,
    val privateKeyPath: String? = null,
    val certificateChainPath: String? = null
)

@Serializable
enum class SSLMode {
    DISABLED,    // No SSL (for upstream SSL terminating proxies)
    SNAKEOIL,    // Self-signed certificates for development/testing
    CUSTOM       // Custom certificates from files
}

@Serializable
data class TurnServer(
    val uri: String,
    val username: String? = null,
    val password: String? = null
)

@Serializable
data class StunServer(
    val uri: String
)
