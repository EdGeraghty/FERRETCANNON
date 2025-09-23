package routes.client_server.client.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.ThirdPartyIdentifiers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import routes.client_server.client.common.*

fun Route.accountRoutes() {
    // GET /account/whoami - Get information about the authenticated user
    get("/account/whoami") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            call.respond(buildJsonObject {
                put("user_id", userId)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /account/3pid - Get third-party identifiers
    get("/account/3pid") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Query third-party identifiers for this user
            val tpids = transaction {
                ThirdPartyIdentifiers.select { ThirdPartyIdentifiers.userId eq userId }
                    .map { row ->
                        buildJsonObject {
                            put("medium", row[ThirdPartyIdentifiers.medium])
                            put("address", row[ThirdPartyIdentifiers.address])
                            put("validated_at", row[ThirdPartyIdentifiers.validatedAt])
                            put("added_at", row[ThirdPartyIdentifiers.addedAt])
                        }
                    }
            }

            call.respond(JsonArray(tpids))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
}