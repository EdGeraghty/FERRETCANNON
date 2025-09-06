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
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            // Get server keys for the requested server
            val serverKeys = ServerKeys.getServerKeys(serverName)
            if (serverKeys == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Server keys not found"))
                return@get
            }

            // Return the server keys
            call.respond(serverKeys)
        } catch (e: Exception) {
            println("Server keys error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
}
