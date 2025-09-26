package routes.client_server.client.common

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
import models.DehydratedDevices
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
import routes.client_server.client.UserIdPrincipal

// Attribute keys for authentication
val MATRIX_USER_KEY = AttributeKey<UserIdPrincipal>("MatrixUser")
val MATRIX_TOKEN_KEY = AttributeKey<String>("MatrixToken")
val MATRIX_USER_ID_KEY = AttributeKey<String>("MatrixUserId")
val MATRIX_DEVICE_ID_KEY = AttributeKey<String>("MatrixDeviceId")
val MATRIX_INVALID_TOKEN_KEY = AttributeKey<String>("MatrixInvalidToken")
val MATRIX_NO_TOKEN_KEY = AttributeKey<Boolean>("MatrixNoToken")

// Helper function for token validation and error responses
suspend fun ApplicationCall.validateAccessToken(): String? {
    val accessToken = attributes.getOrNull(MATRIX_TOKEN_KEY)
    val invalidToken = attributes.getOrNull(MATRIX_INVALID_TOKEN_KEY)
    val noToken = attributes.getOrNull(MATRIX_NO_TOKEN_KEY)

    return when {
        noToken == true -> {
            respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_MISSING_TOKEN")
                put("error", "Missing access token")
            })
            null
        }
        invalidToken != null -> {
            respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNKNOWN_TOKEN")
                put("error", "Unrecognised access token")
            })
            null
        }
        accessToken != null -> {
            // Return the user ID, not the access token
            attributes.getOrNull(MATRIX_USER_ID_KEY)
        }
        else -> {
            respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_MISSING_TOKEN")
                put("error", "Missing access token")
            })
            null
        }
    }
}