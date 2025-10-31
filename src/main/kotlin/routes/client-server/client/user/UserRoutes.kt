package routes.client_server.client.user

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
import io.ktor.http.content.*
import io.ktor.http.content.PartData
import models.Events
import models.Rooms
import models.Filters
import models.DehydratedDevices
import models.ThirdPartyIdentifiers
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
import routes.client_server.client.common.*

fun Route.userRoutes() {
    // GET /joined_rooms - Get joined rooms
    get("/joined_rooms") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Get all rooms where the user has membership "join"
            val joinedRoomIds = transaction {
                Events.select {
                    (Events.type eq "m.room.member") and
                    (Events.stateKey eq userId) and
                    (Events.content like "%\"membership\":\"join\"%")
                }.mapNotNull { row ->
                    row[Events.roomId]
                }.distinct()
            }

            call.respond(buildJsonObject {
                putJsonArray("joined_rooms") {
                    joinedRoomIds.forEach { roomId ->
                        add(JsonPrimitive(roomId))
                    }
                }
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}
