package routes.client_server.client.auth

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.OAuthService
import models.Users
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import utils.OAuthConfig
import utils.OAuthProvider
import utils.ServerNameResolver
import org.jetbrains.exposed.sql.transactions.transaction
import models.OAuthAuthorizationCodes
import models.OAuthAccessTokens
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.update
import kotlinx.coroutines.runBlocking
import routes.client_server.client.common.*

fun Route.oauthRoutes(_config: ServerConfig) {
    // GET /oauth2/authorize - OAuth authorization endpoint
    get("/authorize") {
        try {
            val responseType = call.request.queryParameters["response_type"]
            val clientId = call.request.queryParameters["client_id"]
            val redirectUri = call.request.queryParameters["redirect_uri"]
            val state = call.request.queryParameters["state"]
            val providerId = call.request.queryParameters["provider"] ?: "google"

            // Validate required parameters
            if (responseType != "code") {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "Unsupported response type")
                })
                return@get
            }

            if (clientId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "Missing client_id")
                })
                return@get
            }

            if (redirectUri.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "Missing redirect_uri")
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

            // Validate client_id matches provider
            if (clientId != provider.clientId) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_client")
                    put("error_description", "Invalid client_id")
                })
                return@get
            }

            // Validate redirect_uri matches provider
            if (redirectUri != provider.redirectUri) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "Invalid redirect_uri")
                })
                return@get
            }

            // Generate state for CSRF protection if not provided
            val finalState = state ?: OAuthConfig.generateState()
            if (state == null) {
                OAuthService.storeState(finalState, providerId, redirectUri)
            }

            // Build authorization URL
            val authUrl = OAuthService.buildAuthorizationUrl(provider, finalState)

            // Redirect to OAuth provider
            call.respondRedirect(authUrl, permanent = false)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }

    // POST /oauth2/token - OAuth token endpoint
    post("/token") {
        try {
            // Parse request body
            val requestBody = call.receiveText()
            val params = parseQueryString(requestBody)
            val grantType = params["grant_type"]
            val code = params["code"]
            val clientId = params["client_id"]
            val clientSecret = params["client_secret"]
            val refreshToken = params["refresh_token"]

            when (grantType) {
                "authorization_code" -> {
                    if (code.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("error", "invalid_request")
                            put("error_description", "Missing authorization code")
                        })
                        return@post
                    }

                    if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("error", "invalid_client")
                            put("error_description", "Missing client credentials")
                        })
                        return@post
                    }

                    // Validate authorization code
                    val codeData = OAuthService.validateAuthorizationCode(code)
                    if (codeData == null) {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("error", "invalid_grant")
                            put("error_description", "Invalid authorization code")
                        })
                        return@post
                    }

                    val (userId, codeClientId, scope) = codeData

                    // Validate client credentials
                    if (clientId != codeClientId) {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("error", "invalid_client")
                            put("error_description", "Invalid client credentials")
                        })
                        return@post
                    }

                    // Generate tokens
                    val accessToken = OAuthConfig.generateAccessToken()
                    val refreshTokenValue = OAuthConfig.generateRefreshToken()

                    // Store tokens
                    OAuthService.storeAccessToken(
                        accessToken = accessToken,
                        refreshToken = refreshTokenValue,
                        clientId = clientId,
                        userId = userId,
                        scope = scope,
                        expiresIn = 3600L // 1 hour
                    )

                    call.respond(buildJsonObject {
                        put("access_token", accessToken)
                        put("token_type", "Bearer")
                        put("expires_in", 3600)
                        put("refresh_token", refreshTokenValue)
                        put("scope", scope)
                    })
                }

                "refresh_token" -> {
                    if (refreshToken.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("error", "invalid_request")
                            put("error_description", "Missing refresh token")
                        })
                        return@post
                    }

                    // Find refresh token in database
                    val tokenData = transaction {
                        OAuthAccessTokens.select {
                            OAuthAccessTokens.refreshToken eq refreshToken
                        }.singleOrNull()
                    }

                    if (tokenData == null) {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("error", "invalid_grant")
                            put("error_description", "Invalid refresh token")
                        })
                        return@post
                    }

                    // Generate new tokens
                    val newAccessToken = OAuthConfig.generateAccessToken()
                    val newRefreshToken = OAuthConfig.generateRefreshToken()

                    // Update tokens in database
                    transaction {
                        OAuthAccessTokens.update({ OAuthAccessTokens.refreshToken eq refreshToken }) {
                            it[OAuthAccessTokens.accessToken] = newAccessToken
                            it[OAuthAccessTokens.refreshToken] = newRefreshToken
                            it[OAuthAccessTokens.expiresAt] = System.currentTimeMillis() + (3600 * 1000)
                        }
                    }

                    call.respond(buildJsonObject {
                        put("access_token", newAccessToken)
                        put("token_type", "Bearer")
                        put("expires_in", 3600)
                        put("refresh_token", newRefreshToken)
                    })
                }

                else -> {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "unsupported_grant_type")
                        put("error_description", "Unsupported grant type")
                    })
                }
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }

    // GET /oauth2/userinfo - OAuth userinfo endpoint
    get("/userinfo") {
        try {
            // Get Authorization header
            val authHeader = call.request.headers["Authorization"]
            if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("error", "invalid_token")
                    put("error_description", "Missing or invalid access token")
                })
                return@get
            }

            val accessToken = authHeader.substringAfter("Bearer ").trim()

            // Validate access token
            val tokenData = OAuthService.validateAccessToken(accessToken)
            if (tokenData == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("error", "invalid_token")
                    put("error_description", "Invalid or expired access token")
                })
                return@get
            }

            val (userId, _, _) = tokenData

            // Get user information from database
            val userInfo = transaction {
                val user = models.Users.select { models.Users.userId eq userId }.singleOrNull()
                if (user != null) {
                    buildJsonObject {
                        put("sub", userId)
                        put("name", user[models.Users.displayName] ?: userId)
                        put("preferred_username", user[models.Users.username])
                        // Note: email field omitted as it's not stored in current schema
                    }
                } else {
                    null
                }
            }

            if (userInfo == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("error", "user_not_found")
                    put("error_description", "User not found")
                })
                return@get
            }

            call.respond(userInfo)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }

    // POST /oauth2/revoke - OAuth token revocation endpoint
    post("/revoke") {
        try {
            val requestBody = call.receiveText()
            val params = parseQueryString(requestBody)
            val token = params["token"]

            if (token.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "Missing token")
                })
                return@post
            }

            // Revoke the token
            OAuthService.revokeToken(token)

            // Return success (no content)
            call.respond(HttpStatusCode.OK, buildJsonObject {})

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }

    // POST /oauth2/introspect - OAuth token introspection endpoint
    post("/introspect") {
        try {
            val requestBody = call.receiveText()
            val params = parseQueryString(requestBody)
            val token = params["token"]

            if (token.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_request")
                    put("error_description", "Missing token")
                })
                return@post
            }

            // Check if token is valid
            val tokenData = OAuthService.validateAccessToken(token)

            val response = if (tokenData != null) {
                val (userId, clientId, scope) = tokenData
                buildJsonObject {
                    put("active", true)
                    put("client_id", clientId)
                    put("username", userId)
                    put("scope", scope)
                    put("token_type", "Bearer")
                    put("exp", (System.currentTimeMillis() / 1000) + 3600) // Expires in 1 hour
                    put("iat", System.currentTimeMillis() / 1000)
                }
            } else {
                buildJsonObject {
                    put("active", false)
                }
            }

            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }

    // GET /oauth2/jwks - JWKS endpoint for public keys
    get("/jwks") {
        try {
            // For now, return empty keys array
            // In a production implementation, this should return actual RSA public keys
            // used for signing JWT tokens
            call.respond(buildJsonObject {
                put("keys", buildJsonArray { })
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", "server_error")
                put("error_description", "Internal server error")
            })
        }
    }

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
                    
                    val existingUser = models.Users.select { models.Users.username eq username }.singleOrNull()

                    if (existingUser != null) {
                        // Update existing user
                        val existingUserId = existingUser[models.Users.userId]
                        models.Users.update({ models.Users.userId eq existingUserId }) {
                            it[models.Users.displayName] = userInfo.name ?: userInfo.login ?: "OAuth User"
                        }
                        existingUserId
                    } else {
                        // Create new user
                        val newUserId = "@$username:${_config.federation.serverName}"
                        val securePassword = utils.AuthUtils.generateAccessToken() // Generate secure password

                        utils.AuthUtils.createUser(
                            username = username,
                            password = securePassword,
                            displayName = userInfo.name ?: userInfo.login ?: "OAuth User",
                            serverName = _config.federation.serverName
                        )

                        newUserId
                    }
                }

                // Generate Matrix access token
                val matrixAccessToken = utils.AuthUtils.createAccessToken(
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

// Helper function to parse query string from POST body
private fun parseQueryString(body: String): Map<String, String> {
    return body.split("&").associate {
        val (key, value) = it.split("=", limit = 2)
        key to value
    }
}
