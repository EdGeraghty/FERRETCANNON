package utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.*

/**
 * OAuth 2.0 Provider Configuration
 */
@Serializable
data class OAuthProvider(
    val id: String,
    val name: String,
    val clientId: String,
    val clientSecret: String,
    val authorizationUrl: String,
    val tokenUrl: String,
    val userInfoUrl: String,
    val scope: String,
    val redirectUri: String,
    val enabled: Boolean = true
)

/**
 * OAuth 2.0 Configuration Manager
 */
object OAuthConfig {
    private val random = SecureRandom()
    private val providers = mutableMapOf<String, OAuthProvider>()

    init {
        loadProviders()
    }

    /**
     * Load OAuth providers from configuration
     */
    private fun loadProviders() {
        // Load from environment variables or config file
        // For now, we'll configure common providers

        // Google OAuth Provider
        val googleClientId = System.getenv("GOOGLE_OAUTH_CLIENT_ID") ?: "demo_google_client_id"
        val googleClientSecret = System.getenv("GOOGLE_OAUTH_CLIENT_SECRET") ?: "demo_google_client_secret"

        if (googleClientId != "demo_google_client_id") {
            providers["google"] = OAuthProvider(
                id = "google",
                name = "Google",
                clientId = googleClientId,
                clientSecret = googleClientSecret,
                authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenUrl = "https://oauth2.googleapis.com/token",
                userInfoUrl = "https://www.googleapis.com/oauth2/v2/userinfo",
                scope = "openid profile email",
                redirectUri = "${ServerNameResolver.getServerBaseUrl()}/_matrix/client/v3/oauth2/callback/google"
            )
        }

        // GitHub OAuth Provider
        val githubClientId = System.getenv("GITHUB_OAUTH_CLIENT_ID") ?: "demo_github_client_id"
        val githubClientSecret = System.getenv("GITHUB_OAUTH_CLIENT_SECRET") ?: "demo_github_client_secret"

        if (githubClientId != "demo_github_client_id") {
            providers["github"] = OAuthProvider(
                id = "github",
                name = "GitHub",
                clientId = githubClientId,
                clientSecret = githubClientSecret,
                authorizationUrl = "https://github.com/login/oauth/authorize",
                tokenUrl = "https://github.com/login/oauth/access_token",
                userInfoUrl = "https://api.github.com/user",
                scope = "read:user user:email",
                redirectUri = "${ServerNameResolver.getServerBaseUrl()}/_matrix/client/v3/oauth2/callback/github"
            )
        }

        // Microsoft OAuth Provider
        val microsoftClientId = System.getenv("MICROSOFT_OAUTH_CLIENT_ID") ?: "demo_microsoft_client_id"
        val microsoftClientSecret = System.getenv("MICROSOFT_OAUTH_CLIENT_SECRET") ?: "demo_microsoft_client_secret"

        if (microsoftClientId != "demo_microsoft_client_id") {
            providers["microsoft"] = OAuthProvider(
                id = "microsoft",
                name = "Microsoft",
                clientId = microsoftClientId,
                clientSecret = microsoftClientSecret,
                authorizationUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                userInfoUrl = "https://graph.microsoft.com/v1.0/me",
                scope = "openid profile email",
                redirectUri = "${ServerNameResolver.getServerBaseUrl()}/_matrix/client/v3/oauth2/callback/microsoft"
            )
        }
    }

    /**
     * Get OAuth provider by ID
     */
    fun getProvider(providerId: String): OAuthProvider? {
        return providers[providerId]
    }

    /**
     * Get all enabled providers
     */
    fun getEnabledProviders(): Map<String, OAuthProvider> {
        return providers.filter { it.value.enabled }
    }

    /**
     * Generate OAuth state parameter for CSRF protection
     */
    fun generateState(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Get default OAuth provider (first enabled provider)
     */
    fun getDefaultProvider(): OAuthProvider? {
        return providers.values.firstOrNull { it.enabled }
    }

    /**
     * Generate authorization code
     */
    fun generateAuthorizationCode(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate access token
     */
    fun generateAccessToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate refresh token
     */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
