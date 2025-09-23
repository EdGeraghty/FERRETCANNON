package routes.client_server.client.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.AccountData
import models.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import routes.client_server.client.common.*

fun Route.profileDisplayRoutes() {
    // GET /profile/{userId} - Get user profile
    get("/profile/{userId}") {
        try {
            val userId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId parameter"
                ))
                return@get
            }

            // Get user profile from database
            val profile = transaction {
                Users.select { Users.userId eq userId }.singleOrNull()
            }

            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "User not found"
                ))
                return@get
            }

            val response = mutableMapOf<String, String>()

            // Get display name from account data
            val displayName = transaction {
                AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()?.let { row ->
                    Json.parseToJsonElement(row[AccountData.content]).jsonObject["displayname"]?.jsonPrimitive?.content
                }
            }

            if (displayName != null) {
                response["displayname"] = displayName
            }

            // Get avatar URL from account data
            val avatarUrl = transaction {
                AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()?.let { row ->
                    Json.parseToJsonElement(row[AccountData.content]).jsonObject["avatar_url"]?.jsonPrimitive?.content
                }
            }

            if (avatarUrl != null) {
                response["avatar_url"] = avatarUrl
            }

            // Get timezone from account data
            val timezone = transaction {
                AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()?.let { row ->
                    Json.parseToJsonElement(row[AccountData.content]).jsonObject["timezone"]?.jsonPrimitive?.content
                }
            }

            if (timezone != null) {
                response["timezone"] = timezone
            }

            // Get custom profile fields from account data (m.profile_fields type)
            val customFields = transaction {
                AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.profile_fields") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()?.let { row ->
                    try {
                        Json.parseToJsonElement(row[AccountData.content]).jsonObject
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            if (customFields != null) {
                for ((key, value) in customFields) {
                    if (value is JsonPrimitive && value.isString) {
                        response[key] = value.content
                    }
                }
            }

            call.respond(response)

        } catch (e: Exception) {
            println("ERROR: Exception in GET /profile/{userId}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /profile/{userId}/displayname - Set display name
    put("/profile/{userId}/displayname") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val profileUserId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (profileUserId == null || userId != profileUserId) {
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own profile"
                ))
                return@put
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@put
            }
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val displayName = jsonBody["displayname"]?.jsonPrimitive?.content

            // Store display name in account data
            transaction {
                val existing = AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()

                val currentContent = if (existing != null) {
                    Json.parseToJsonElement(existing[AccountData.content]).jsonObject.toMutableMap()
                } else {
                    mutableMapOf()
                }

                run {
                    if (displayName != null) {
                        currentContent["displayname"] = JsonPrimitive(displayName)
                    } else {
                        currentContent.remove("displayname")
                    }
                }

                if (existing != null) {
                    AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.direct") and (AccountData.roomId.isNull()) }) {
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                } else {
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = "m.direct"
                        it[AccountData.roomId] = null
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                }
            }

            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /profile/{userId}/displayname: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}