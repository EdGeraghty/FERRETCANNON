package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.OAuthService

fun Route.oauthRoutes(_config: ServerConfig) {
    // GET /oauth2/authorize - OAuth authorization endpoint
    get("/authorize") {
        try {
            val responseType = call.request.queryParameters["response_type"]
            val _clientId = call.request.queryParameters["client_id"]
            val redirectUri = call.request.queryParameters["redirect_uri"]
            val _scope = call.request.queryParameters["scope"]
            val state = call.request.queryParameters["state"]

            if (responseType != "code") {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "invalid_request",
                    "error_description" to "Unsupported response type"
                ))
                return@get
            }

            // TODO: Validate client_id and redirect_uri
            // TODO: Show authorization page to user
            // For now, redirect with authorization code
            val authCode = "mock_auth_code_${System.currentTimeMillis()}"
            val redirectUrl = "$redirectUri?code=$authCode&state=$state"

            call.respondRedirect(redirectUrl)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "server_error",
                "error_description" to "Internal server error"
            ))
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
            val _redirectUri = params["redirect_uri"]
            val _clientId = params["client_id"]
            val _clientSecret = params["client_secret"]

            if (grantType != "authorization_code") {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "unsupported_grant_type",
                    "error_description" to "Unsupported grant type"
                ))
                return@post
            }

            if (code == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "invalid_request",
                    "error_description" to "Missing authorization code"
                ))
                return@post
            }

            // TODO: Validate authorization code
            // TODO: Generate access token
            val accessToken = "mock_access_token_${System.currentTimeMillis()}"
            val refreshToken = "mock_refresh_token_${System.currentTimeMillis()}"

            call.respond(mapOf(
                "access_token" to accessToken,
                "token_type" to "Bearer",
                "expires_in" to 3600,
                "refresh_token" to refreshToken
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "server_error",
                "error_description" to "Internal server error"
            ))
        }
    }

    // GET /oauth2/userinfo - OAuth userinfo endpoint
    get("/userinfo") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "error" to "invalid_token",
                    "error_description" to "Missing or invalid access token"
                ))
                return@get
            }

            // TODO: Get user information from database
            call.respond(mapOf(
                "sub" to userId,
                "name" to userId,
                "preferred_username" to userId
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "server_error",
                "error_description" to "Internal server error"
            ))
        }
    }

    // POST /oauth2/revoke - OAuth token revocation endpoint
    post("/revoke") {
        try {
            val requestBody = call.receiveText()
            val params = parseQueryString(requestBody)
            val token = params["token"]

            if (token == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "invalid_request",
                    "error_description" to "Missing token"
                ))
                return@post
            }

            // TODO: Revoke the token
            call.respond(HttpStatusCode.OK, emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "server_error",
                "error_description" to "Internal server error"
            ))
        }
    }

    // POST /oauth2/introspect - OAuth token introspection endpoint
    post("/introspect") {
        try {
            val requestBody = call.receiveText()
            val params = parseQueryString(requestBody)
            val token = params["token"]

            if (token == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "invalid_request",
                    "error_description" to "Missing token"
                ))
                return@post
            }

            // TODO: Check if token is valid and return introspection response
            val isActive = token.startsWith("mock_access_token_")

            call.respond(mapOf(
                "active" to isActive,
                "token_type" to if (isActive) "Bearer" else null,
                "exp" to if (isActive) (System.currentTimeMillis() / 1000 + 3600) else null
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "server_error",
                "error_description" to "Internal server error"
            ))
        }
    }

    // GET /oauth2/jwks - JWKS endpoint for public keys
    get("/jwks") {
        try {
            // TODO: Return actual JWKS
            call.respond(mapOf(
                "keys" to emptyList<Map<String, Any>>()
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "error" to "server_error",
                "error_description" to "Internal server error"
            ))
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
