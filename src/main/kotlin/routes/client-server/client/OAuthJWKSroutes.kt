package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig

fun Route.oauthJWKSroutes(config: ServerConfig) {
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
}