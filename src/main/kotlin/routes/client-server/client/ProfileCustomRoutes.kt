package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.AccountData
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull

fun Route.profileCustomRoutes() {
    // PUT /profile/{userId} - Update user profile (custom fields)
    put("/profile/{userId}") {
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

            // Extract standard fields
            val displayName = jsonBody["displayname"]?.jsonPrimitive?.content
            val avatarUrl = jsonBody["avatar_url"]?.jsonPrimitive?.content
            val timezone = jsonBody["timezone"]?.jsonPrimitive?.content

            // Handle custom profile fields
            val customFields = jsonBody.filterKeys { it !in setOf("displayname", "avatar_url", "timezone") }
            if (customFields.isNotEmpty()) {
                transaction {
                    val existing = AccountData.select {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq "m.profile_fields") and
                        (AccountData.roomId.isNull())
                    }.singleOrNull()

                    val currentContent = if (existing != null) {
                        Json.parseToJsonElement(existing[AccountData.content]).jsonObject.toMutableMap()
                    } else {
                        mutableMapOf()
                    }

                    // Update custom fields
                    for ((key, value) in customFields) {
                        if (value is JsonPrimitive && value.isString) {
                            currentContent[key] = value
                        } else if (value is JsonNull) {
                            currentContent.remove(key)
                        } else {
                            // Ignore other value types for custom fields
                        }
                    }

                    if (existing != null) {
                        AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.profile_fields") and (AccountData.roomId.isNull()) }) {
                            it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                        }
                    } else if (currentContent.isNotEmpty()) {
                        AccountData.insert {
                            it[AccountData.userId] = userId
                            it[AccountData.type] = "m.profile_fields"
                            it[AccountData.roomId] = null
                            it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                        }
                    } else {
                        // No custom fields to store
                    }
                }
            }

            // Handle standard fields
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

                // Update standard fields
                when {
                    displayName != null -> currentContent["displayname"] = JsonPrimitive(displayName)
                    jsonBody.containsKey("displayname") -> currentContent.remove("displayname")
                    else -> Unit
                }

                when {
                    avatarUrl != null -> currentContent["avatar_url"] = JsonPrimitive(avatarUrl)
                    jsonBody.containsKey("avatar_url") -> currentContent.remove("avatar_url")
                    else -> Unit
                }

                when {
                    timezone != null -> currentContent["timezone"] = JsonPrimitive(timezone)
                    jsonBody.containsKey("timezone") -> currentContent.remove("timezone")
                    else -> Unit
                }

                run {
                    if (existing != null) {
                        AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.direct") and (AccountData.roomId.isNull()) }) {
                            it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                        }
                    } else if (currentContent.isNotEmpty()) {
                        AccountData.insert {
                            it[AccountData.userId] = userId
                            it[AccountData.type] = "m.direct"
                            it[AccountData.roomId] = null
                            it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                        }
                    } else {
                        // No changes needed
                    }
                }
            }

            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /profile/{userId}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}