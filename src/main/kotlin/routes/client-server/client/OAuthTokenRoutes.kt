package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.OAuthService
import utils.OAuthConfig
import models.OAuthAccessTokens
import models.Users
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.select

fun Route.oauthTokenRoutes(config: ServerConfig) {
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
}