package routes.client_server.client

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
import utils.users
import utils.accessTokens
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
import routes.client_server.client.MATRIX_USER_ID_KEY

fun Route.thirdPartyRoutes(_config: ServerConfig) {
    // GET /thirdparty/protocols - Get third-party protocols
    get("/thirdparty/protocols") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Return supported third-party protocols (simplified)
            call.respond(mapOf(
                "protocols" to mapOf<String, Any>() // Empty for now
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/protocol/{protocol} - Get protocol information
    get("/thirdparty/protocol/{protocol}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val protocol = call.parameters["protocol"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (protocol == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing protocol parameter"
                ))
                return@get
            }

            // Return protocol information (simplified)
            call.respond(mapOf(
                "protocol" to protocol,
                "instances" to emptyList<Map<String, Any>>()
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/location/{protocol} - Get location information
    get("/thirdparty/location/{protocol}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val protocol = call.parameters["protocol"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (protocol == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing protocol parameter"
                ))
                return@get
            }

            // Return location information (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/user/{protocol} - Get user information
    get("/thirdparty/user/{protocol}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val protocol = call.parameters["protocol"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (protocol == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing protocol parameter"
                ))
                return@get
            }

            // Return user information (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/location - Search locations
    get("/thirdparty/location") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val _search = call.request.queryParameters["search"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Search locations (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thirdparty/user - Search users
    get("/thirdparty/user") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val _search = call.request.queryParameters["search"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Search users (simplified)
            call.respond(emptyList<Map<String, Any>>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
