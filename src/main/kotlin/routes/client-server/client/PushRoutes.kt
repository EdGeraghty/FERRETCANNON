package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.AccountData

fun Route.pushRoutes(config: ServerConfig) {
    // GET /pushrules - Get push rules
    get("/pushrules") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // TODO: Retrieve push rules from database
            // For now, return empty rules
            val response = buildJsonObject {
                put("global", buildJsonObject { })
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /pushrules/ - Get push rules (with trailing slash)
    get("/pushrules/") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // TODO: Retrieve push rules from database
            // For now, return empty rules
            val response = buildJsonObject {
                put("global", buildJsonObject { })
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /pushrules/{scope}/{kind}/{ruleId} - Set push rule
    put("/pushrules/{scope}/{kind}/{ruleId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val scope = call.parameters["scope"]
            val kind = call.parameters["kind"]
            val ruleId = call.parameters["ruleId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (scope == null || kind == null || ruleId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            // TODO: Store push rule in account data
            // For now, just acknowledge
            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // DELETE /pushrules/{scope}/{kind}/{ruleId} - Delete push rule
    delete("/pushrules/{scope}/{kind}/{ruleId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val scope = call.parameters["scope"]
            val kind = call.parameters["kind"]
            val ruleId = call.parameters["ruleId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@delete
            }

            if (scope == null || kind == null || ruleId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@delete
            }

            // TODO: Delete push rule from account data
            // For now, just acknowledge
            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
