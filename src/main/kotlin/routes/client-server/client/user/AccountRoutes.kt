package routes.client_server.client.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.ThirdPartyIdentifiers
import models.Users
import models.AccessTokens
import models.Pushers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import routes.client_server.client.common.*
import utils.AuthUtils
import org.slf4j.LoggerFactory

fun Route.accountRoutes() {
    val logger = LoggerFactory.getLogger("AccountRoutes")
    
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

    // POST /account/password - Change password
    post("/account/password") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            
            val newPassword = jsonBody["new_password"]?.jsonPrimitive?.content
            val logoutDevices = jsonBody["logout_devices"]?.jsonPrimitive?.booleanOrNull ?: true
            
            // Auth object for validating current password/session
            val auth = jsonBody["auth"]?.jsonObject
            
            if (newPassword.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_MISSING_PARAM")
                    put("error", "Missing new_password parameter")
                })
                return@post
            }
            
            // If auth is provided, validate the current password
            if (auth != null) {
                val authType = auth["type"]?.jsonPrimitive?.content
                if (authType == "m.login.password") {
                    val currentPassword = auth["password"]?.jsonPrimitive?.content
                    val authUserId = auth["user"]?.jsonPrimitive?.content
                    
                    if (currentPassword.isNullOrBlank()) {
                        call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                            put("errcode", "M_FORBIDDEN")
                            put("error", "Invalid authentication")
                        })
                        return@post
                    }
                    
                    // Verify current password
                    val authenticated = AuthUtils.authenticateUser(userId, currentPassword)
                    if (authenticated == null) {
                        call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                            put("errcode", "M_FORBIDDEN")
                            put("error", "Invalid current password")
                        })
                        return@post
                    }
                }
            }
            
            // Update password
            val passwordHash = AuthUtils.hashPassword(newPassword)
            transaction {
                Users.update({ Users.userId eq userId }) {
                    it[Users.passwordHash] = passwordHash
                }
            }
            
            logger.info("Password changed for user: $userId")
            
            // Get current access token
            val currentToken = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            
            // Logout other devices if requested
            if (logoutDevices) {
                transaction {
                    val deletedCount = AccessTokens.deleteWhere {
                        (AccessTokens.userId eq userId) and (AccessTokens.token neq (currentToken ?: ""))
                    }
                    logger.info("Logged out $deletedCount other devices for user: $userId")
                }
                
                // Also delete pushers for other access tokens
                transaction {
                    // Get device ID for current token
                    val currentDeviceId = currentToken?.let { token ->
                        AccessTokens.select { AccessTokens.token eq token }
                            .singleOrNull()?.get(AccessTokens.deviceId)
                    }
                    
                    // Delete pushers for all other devices
                    if (currentDeviceId != null) {
                        val devices = AccessTokens.select { AccessTokens.userId eq userId }
                            .map { it[AccessTokens.deviceId] }
                            .filter { it != currentDeviceId }
                        
                        devices.forEach { deviceId ->
                            Pushers.deleteWhere { Pushers.userId eq userId }
                        }
                    }
                }
            }
            
            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            logger.error("Exception in POST /account/password: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }

    // POST /account/deactivate - Deactivate account
    post("/account/deactivate") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            
            // Auth object for validating password/session
            val auth = jsonBody["auth"]?.jsonObject
            val idServer = jsonBody["id_server"]?.jsonPrimitive?.content
            
            // Validate authentication
            if (auth != null) {
                val authType = auth["type"]?.jsonPrimitive?.content
                if (authType == "m.login.password") {
                    val password = auth["password"]?.jsonPrimitive?.content
                    
                    if (password.isNullOrBlank()) {
                        call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                            put("errcode", "M_FORBIDDEN")
                            put("error", "Invalid authentication")
                            put("completed", JsonArray(emptyList()))
                            putJsonObject("flows") {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                            putJsonObject("params") {
                            }
                        })
                        return@post
                    }
                    
                    // Verify password
                    val authenticated = AuthUtils.authenticateUser(userId, password)
                    if (authenticated == null) {
                        call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                            put("errcode", "M_FORBIDDEN")
                            put("error", "Invalid password")
                        })
                        return@post
                    }
                }
            } else {
                // No auth provided, return auth flow requirements
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_FORBIDDEN")
                    put("error", "Missing authentication")
                    put("completed", JsonArray(emptyList()))
                    putJsonArray("flows") {
                        addJsonObject {
                            putJsonArray("stages") {
                                add("m.login.password")
                            }
                        }
                    }
                    putJsonObject("params") {
                    }
                })
                return@post
            }
            
            // Deactivate the account
            transaction {
                Users.update({ Users.userId eq userId }) {
                    it[Users.deactivated] = true
                }
                
                // Delete all access tokens
                AccessTokens.deleteWhere { AccessTokens.userId eq userId }
                
                // Delete all pushers
                Pushers.deleteWhere { Pushers.userId eq userId }
            }
            
            logger.info("Account deactivated for user: $userId")
            
            call.respond(buildJsonObject {
                put("id_server_unbind_result", "success")
            })

        } catch (e: Exception) {
            logger.error("Exception in POST /account/deactivate: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
}