package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.OAuthService
import utils.OAuthConfig
import utils.ServerNameResolver
import utils.AuthUtils
import models.Users
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.runBlocking

fun Route.oauthCallbackRoutes(config: ServerConfig) {
    // OAuth callback endpoints for different providers
    route("/callback") {
        get("/{provider}") {
            try {
                val providerId = call.parameters["provider"] ?: return@get
                val code = call.request.queryParameters["code"]
                val state = call.request.queryParameters["state"]
                val error = call.request.queryParameters["error"]

                // Handle OAuth provider errors
                if (error != null) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "oauth_error")
                        put("error_description", error)
                    })
                    return@get
                }

                if (code.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "invalid_request")
                        put("error_description", "Missing authorization code")
                    })
                    return@get
                }

                if (state.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "invalid_request")
                        put("error_description", "Missing state parameter")
                    })
                    return@get
                }

                // Validate state parameter
                val storedProviderId = OAuthService.validateState(state)
                if (storedProviderId == null || storedProviderId != providerId) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "invalid_state")
                        put("error_description", "Invalid or expired state parameter")
                    })
                    return@get
                }

                // Get OAuth provider
                val provider = OAuthConfig.getProvider(providerId)
                if (provider == null) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "invalid_request")
                        put("error_description", "Unsupported OAuth provider")
                    })
                    return@get
                }

                // Exchange code for tokens
                val tokenResult = runBlocking {
                    OAuthService.exchangeCodeForToken(provider, code, provider.redirectUri)
                }

                if (tokenResult.isFailure) {
                    call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                        put("error", "token_exchange_failed")
                        put("error_description", tokenResult.exceptionOrNull()?.message ?: "Unknown error")
                    })
                    return@get
                }

                val tokenResponse = tokenResult.getOrNull()!!

                // Get user info
                val userInfoResult = runBlocking {
                    OAuthService.getUserInfo(provider, tokenResponse.access_token)
                }

                if (userInfoResult.isFailure) {
                    call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                        put("error", "userinfo_failed")
                        put("error_description", userInfoResult.exceptionOrNull()?.message ?: "Unknown error")
                    })
                    return@get
                }

                val userInfo = userInfoResult.getOrNull()!!

                // Create or update user account
                val userId = transaction {
                    // For OAuth, we'll use the OAuth user ID as a unique identifier
                    // Since we don't have email in the Users table, we'll create a unique username
                    val oauthUserId = userInfo.id ?: userInfo.sub ?: "oauth_${System.currentTimeMillis()}"
                    val username = userInfo.login ?: "oauth_${oauthUserId.hashCode().toString().replace("-", "")}"

                    val existingUser = Users.select { Users.username eq username }.singleOrNull()

                    if (existingUser != null) {
                        // Update existing user
                        val existingUserId = existingUser[Users.userId]
                        Users.update({ Users.userId eq existingUserId }) {
                            it[Users.displayName] = userInfo.name ?: userInfo.login ?: "OAuth User"
                        }
                        existingUserId
                    } else {
                        // Create new user
                        val newUserId = "@$username:${config.federation.serverName}"
                        val securePassword = AuthUtils.generateAccessToken() // Generate secure password

                        AuthUtils.createUser(
                            username = username,
                            password = securePassword,
                            displayName = userInfo.name ?: userInfo.login ?: "OAuth User",
                            serverName = config.federation.serverName
                        )

                        newUserId
                    }
                }

                // Generate Matrix access token
                val matrixAccessToken = AuthUtils.createAccessToken(
                    userId = userId,
                    deviceId = "oauth_device_${System.currentTimeMillis()}",
                    userAgent = call.request.headers["User-Agent"],
                    ipAddress = call.request.headers["X-Forwarded-For"] ?: call.request.headers["X-Real-IP"]
                )

                // Store OAuth tokens
                OAuthService.storeAccessToken(
                    accessToken = tokenResponse.access_token,
                    refreshToken = tokenResponse.refresh_token,
                    clientId = provider.clientId,
                    userId = userId,
                    scope = tokenResponse.scope ?: "openid profile",
                    expiresIn = tokenResponse.expires_in
                )

                // Redirect to client with Matrix access token
                val redirectUri = "${ServerNameResolver.getServerBaseUrl()}/#/login?loginToken=$matrixAccessToken"
                call.respondRedirect(redirectUri)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("error", "server_error")
                    put("error_description", "Internal server error")
                })
            }
        }
    }
}