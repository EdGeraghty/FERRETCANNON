package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import utils.MatrixAuth
import utils.ServerKeys

fun Route.federationV1ServerKeys() {
    get("/server/{serverName}") {
        val serverName = call.parameters["serverName"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Get server keys for the requested server
            val serverKeys = ServerKeys.getServerKeys(serverName)

            // Return the server keys
            call.respond(serverKeys)
        } catch (e: Exception) {
            println("Server keys error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
}
