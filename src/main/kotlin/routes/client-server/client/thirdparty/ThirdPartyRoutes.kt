package routes.client_server.client.thirdparty

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.util.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.http.content.*
import io.ktor.http.content.PartData
import models.Events
import models.Rooms
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import kotlinx.serialization.json.*
import routes.server_server.federation.v1.broadcastEDU
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import models.AccountData
import io.ktor.websocket.Frame
import utils.MediaStorage
import models.Users
import models.AccessTokens
import at.favre.lib.crypto.bcrypt.BCrypt
import utils.AuthUtils
import utils.connectedClients
import utils.typingMap
import utils.ServerKeys
import utils.OAuthService
import utils.OAuthConfig
import config.ServerConfig
import utils.MatrixPagination
import io.ktor.util.AttributeKey
import routes.client_server.client.common.*
import org.slf4j.LoggerFactory

// Define the attribute key locally
val LOCAL_MATRIX_USER_ID_KEY = AttributeKey<String>("MatrixUserId")

private val logger = LoggerFactory.getLogger("routes.client_server.client.thirdparty.ThirdPartyRoutes")

fun Route.thirdPartyRoutes() {
    // GET /thirdparty/protocols - Get third-party protocols
    get("/thirdparty/protocols") {
        try {
            logger.debug("ThirdPartyRoutes - /thirdparty/protocols called")
            val accessToken = call.validateAccessToken()
            logger.debug("ThirdPartyRoutes - accessToken: $accessToken")

            if (accessToken == null) {
                logger.debug("ThirdPartyRoutes - accessToken is null, returning unauthorized")
                // Response already sent by validateAccessToken
                return@get
            }

            // Return supported third-party protocols (simplified)
            logger.debug("ThirdPartyRoutes - returning protocols response")
            call.respondText("""{"protocols":{}}""", ContentType.Application.Json)

        } catch (e: Exception) {
            println("ERROR: ThirdPartyRoutes - Exception in /thirdparty/protocols: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/protocol/{protocol} - Get protocol information
    get("/thirdparty/protocol/{protocol}") {
        try {
            val protocol = call.parameters["protocol"]

            if (protocol == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing protocol parameter"
                ))
                return@get
            }

            // Return protocol information (simplified)
            call.respond(mutableMapOf(
                "protocol" to protocol,
                "instances" to emptyList<Map<String, Any>>()
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/location/{protocol} - Get location information
    get("/thirdparty/location/{protocol}") {
        try {
            val protocol = call.parameters["protocol"]

            if (protocol == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing protocol parameter"
                ))
                return@get
            }

            // Return location information (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/user/{protocol} - Get user information
    get("/thirdparty/user/{protocol}") {
        try {
            val protocol = call.parameters["protocol"]

            if (protocol == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing protocol parameter"
                ))
                return@get
            }

            // Return user information (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/location - Search locations
    get("/thirdparty/location") {
        try {
            // Search locations (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/user - Search users
    get("/thirdparty/user") {
        try {
            // Search users (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
