package utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.OAuthAccessTokens
import models.OAuthAuthorizationCodes
import models.OAuthStates
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import java.time.Instant

/**
 * OAuth 2.0 Service for provider integration
 */
object OAuthService {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val token_type: String,
        val expires_in: Long? = null,
        val refresh_token: String? = null,
        val scope: String? = null
    )

    @Serializable
    data class UserInfo(
        val sub: String? = null,
        val id: String? = null,
        val email: String? = null,
        val name: String? = null,
        val given_name: String? = null,
        val family_name: String? = null,
        val picture: String? = null,
        val login: String? = null, // GitHub
        val avatar_url: String? = null // GitHub
    )

    /**
     * Build authorization URL for OAuth provider
     */
    fun buildAuthorizationUrl(provider: OAuthProvider, state: String, additionalParams: Map<String, String> = emptyMap()): String {
        val params = mutableMapOf(
            "client_id" to provider.clientId,
            "redirect_uri" to provider.redirectUri,
            "response_type" to "code",
            "scope" to provider.scope,
            "state" to state
        )
        params.putAll(additionalParams)

        val queryString = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "${provider.authorizationUrl}?$queryString"
    }

    /**
     * Exchange authorization code for access token
     */
    suspend fun exchangeCodeForToken(provider: OAuthProvider, code: String, redirectUri: String): Result<TokenResponse> {
        return try {
            val response = httpClient.post(provider.tokenUrl) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    listOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirectUri,
                        "client_id" to provider.clientId,
                        "client_secret" to provider.clientSecret
                    ).formUrlEncode()
                )
            }

            if (response.status.isSuccess()) {
                val tokenResponse = response.body<TokenResponse>()
                Result.success(tokenResponse)
            } else {
                Result.failure(Exception("Token exchange failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get user information from OAuth provider
     */
    suspend fun getUserInfo(provider: OAuthProvider, accessToken: String): Result<UserInfo> {
        return try {
            val response = httpClient.get(provider.userInfoUrl) {
                bearerAuth(accessToken)
            }

            if (response.status.isSuccess()) {
                val userInfo = response.body<UserInfo>()
                Result.success(userInfo)
            } else {
                Result.failure(Exception("User info request failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Store OAuth authorization code
     */
    fun storeAuthorizationCode(
        code: String,
        clientId: String,
        userId: String,
        redirectUri: String,
        scope: String,
        state: String?
    ) {
        transaction {
            OAuthAuthorizationCodes.insert {
                it[OAuthAuthorizationCodes.code] = code
                it[OAuthAuthorizationCodes.clientId] = clientId
                it[OAuthAuthorizationCodes.userId] = userId
                it[OAuthAuthorizationCodes.redirectUri] = redirectUri
                it[OAuthAuthorizationCodes.scope] = scope
                it[OAuthAuthorizationCodes.state] = state
                it[OAuthAuthorizationCodes.expiresAt] = System.currentTimeMillis() + (10 * 60 * 1000) // 10 minutes
            }
        }
    }

    /**
     * Validate and consume authorization code
     */
    fun validateAuthorizationCode(code: String): Triple<String, String, String>? { // Returns (userId, clientId, scope) or null
        return transaction {
            val authCode = OAuthAuthorizationCodes.select {
                (OAuthAuthorizationCodes.code eq code) and
                (OAuthAuthorizationCodes.used eq false) and
                (OAuthAuthorizationCodes.expiresAt greaterEq System.currentTimeMillis())
            }.singleOrNull()

            if (authCode != null) {
                // Mark code as used
                OAuthAuthorizationCodes.update({ OAuthAuthorizationCodes.code eq code }) {
                    it[OAuthAuthorizationCodes.used] = true
                }

                Triple(
                    authCode[OAuthAuthorizationCodes.userId],
                    authCode[OAuthAuthorizationCodes.clientId],
                    authCode[OAuthAuthorizationCodes.scope]
                )
            } else null
        }
    }

    /**
     * Store OAuth access token
     */
    fun storeAccessToken(
        accessToken: String,
        refreshToken: String?,
        clientId: String,
        userId: String,
        scope: String,
        expiresIn: Long?
    ) {
        val expiresAt = expiresIn?.let { System.currentTimeMillis() + (it * 1000) }
        val refreshTokenExpiresAt = expiresIn?.let { System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000) } // 30 days

        transaction {
            OAuthAccessTokens.insert {
                it[OAuthAccessTokens.accessToken] = accessToken
                it[OAuthAccessTokens.refreshToken] = refreshToken ?: ""
                it[OAuthAccessTokens.clientId] = clientId
                it[OAuthAccessTokens.userId] = userId
                it[OAuthAccessTokens.scope] = scope
                it[OAuthAccessTokens.expiresAt] = expiresAt ?: (System.currentTimeMillis() + (3600 * 1000)) // 1 hour default
                it[OAuthAccessTokens.refreshTokenExpiresAt] = refreshTokenExpiresAt
            }
        }
    }

    /**
     * Validate OAuth access token
     */
    fun validateAccessToken(token: String): Triple<String, String, String>? { // Returns (userId, clientId, scope) or null
        return transaction {
            val accessToken = OAuthAccessTokens.select {
                (OAuthAccessTokens.accessToken eq token) and
                (OAuthAccessTokens.expiresAt greaterEq System.currentTimeMillis())
            }.singleOrNull()

            if (accessToken != null) {
                Triple(
                    accessToken[OAuthAccessTokens.userId],
                    accessToken[OAuthAccessTokens.clientId],
                    accessToken[OAuthAccessTokens.scope]
                )
            } else null
        }
    }

    /**
     * Store OAuth state for CSRF protection
     */
    fun storeState(state: String, providerId: String, redirectUri: String?) {
        transaction {
            OAuthStates.insert {
                it[OAuthStates.state] = state
                it[OAuthStates.providerId] = providerId
                it[OAuthStates.redirectUri] = redirectUri
                it[OAuthStates.expiresAt] = System.currentTimeMillis() + (10 * 60 * 1000) // 10 minutes
            }
        }
    }

    /**
     * Validate OAuth state
     */
    fun validateState(state: String): String? { // Returns providerId or null
        return transaction {
            val stateRecord = OAuthStates.select {
                (OAuthStates.state eq state) and
                (OAuthStates.expiresAt greaterEq System.currentTimeMillis())
            }.singleOrNull()

            if (stateRecord != null) {
                // Clean up used state
                transaction {
                    OAuthStates.deleteWhere { OAuthStates.state eq state }
                }
                stateRecord[OAuthStates.providerId]
            } else null
        }
    }

    /**
     * Refresh OAuth access token
     */
    suspend fun refreshAccessToken(provider: OAuthProvider, refreshToken: String): Result<TokenResponse> {
        return try {
            val response = httpClient.post(provider.tokenUrl) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    listOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                        "client_id" to provider.clientId,
                        "client_secret" to provider.clientSecret
                    ).formUrlEncode()
                )
            }

            if (response.status.isSuccess()) {
                val tokenResponse = response.body<TokenResponse>()
                Result.success(tokenResponse)
            } else {
                Result.failure(Exception("Token refresh failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Revoke OAuth token
     */
    fun revokeToken(token: String) {
        transaction {
            OAuthAccessTokens.deleteWhere {
                (OAuthAccessTokens.accessToken eq token) or
                (OAuthAccessTokens.refreshToken eq token)
            }
        }
    }

    /**
     * Clean up expired tokens and codes
     */
    fun cleanupExpiredTokens() {
        val now = System.currentTimeMillis()
        transaction {
            OAuthAuthorizationCodes.deleteWhere { OAuthAuthorizationCodes.expiresAt lessEq now }
            OAuthAccessTokens.deleteWhere { OAuthAccessTokens.expiresAt lessEq now }
            OAuthStates.deleteWhere { OAuthStates.expiresAt lessEq now }
        }
    }
}
