package routes.client_server.client.auth

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.OAuthConfig
import utils.OAuthService
import routes.client_server.client.common.*

fun Route.oauthAuthorizationRoutes() {
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

            // Validate client_id exists and is enabled
            val client = OAuthConfig.getClient(clientId)
            if (client == null || !client.enabled) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("error", "invalid_client")
                    put("error_description", "Invalid client_id")
                })
                return@get
            }

            // Validate redirect_uri is registered for this client
            if (!OAuthConfig.validateRedirectUri(clientId, redirectUri)) {
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
}