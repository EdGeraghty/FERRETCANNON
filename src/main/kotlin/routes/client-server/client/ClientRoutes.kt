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
import models.DehydratedDevices
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

// Import modular route functions
import routes.client_server.client.authRoutes
import routes.client_server.client.userRoutes
import routes.client_server.client.deviceRoutes
import routes.client_server.client.roomRoutes
import routes.client_server.client.eventRoutes
import routes.client_server.client.contentRoutes
import routes.client_server.client.pushRoutes
import routes.client_server.client.adminRoutes
import routes.client_server.client.thirdPartyRoutes
import routes.client_server.client.oauthRoutes
import routes.client_server.client.syncRoutes
import routes.client_server.client.keysRoutes

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

// Helper function to get authenticated user information
fun ApplicationCall.getAuthenticatedUser(): Triple<String, String, String>? {
    val userId = attributes.getOrNull(MATRIX_USER_ID_KEY)
    val deviceId = attributes.getOrNull(MATRIX_DEVICE_ID_KEY)
    val token = attributes.getOrNull(MATRIX_TOKEN_KEY)

    return if (userId != null && deviceId != null && token != null) {
        Triple(userId, deviceId, token)
    } else null
}

fun Application.clientRoutes(config: ServerConfig) {
    // Request size limiting - simplified version
    intercept(ApplicationCallPipeline.Call) {
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > config.server.maxRequestSize) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_TOO_LARGE")
                put("error", "Request too large")
            })
            finish()
        }
    }

    // Enhanced authentication middleware for Matrix access tokens
    intercept(ApplicationCallPipeline.Call) {
        try {
            // Extract access token from multiple sources as per Matrix spec
            val accessToken = call.request.queryParameters["access_token"] ?:
                             call.request.headers["Authorization"]?.removePrefix("Bearer ") ?:
                             call.request.headers["Authorization"]?.removePrefix("Bearer")?.trim()

            println("DEBUG: Authentication middleware - accessToken: '$accessToken'")

            if (accessToken != null) {
                // Validate token using new authentication system
                val result = AuthUtils.validateAccessToken(accessToken)

                if (result != null) {
                    val (userId, deviceId) = result
                    // Store authenticated user information with static AttributeKeys
                    call.attributes.put(MATRIX_USER_KEY, UserIdPrincipal(userId))
                    call.attributes.put(MATRIX_TOKEN_KEY, accessToken)
                    call.attributes.put(MATRIX_USER_ID_KEY, userId)
                    call.attributes.put(MATRIX_DEVICE_ID_KEY, deviceId)
                    println("DEBUG: Authentication successful for user: $userId")
                } else {
                    // Invalid token - will be handled by individual endpoints
                    call.attributes.put(MATRIX_INVALID_TOKEN_KEY, accessToken)
                    println("DEBUG: Invalid token")
                }
            } else {
                // No token provided - will be handled by individual endpoints
                call.attributes.put(MATRIX_NO_TOKEN_KEY, true)
                println("DEBUG: No token provided")
            }
        } catch (e: Exception) {
            // Handle authentication errors gracefully
            call.attributes.put(MATRIX_INVALID_TOKEN_KEY, "auth_error")
            println("DEBUG: Authentication error: ${e.message}")
        }
    }

    routing {
        // Helper function to broadcast events to room clients
        suspend fun broadcastEvent(roomId: String, event: Map<String, Any>) {
            val message = Json.encodeToString(JsonObject.serializer(), JsonObject(event.mapValues { JsonPrimitive(it.value.toString()) }))
            connectedClients[roomId]?.forEach { session ->
                try {
                    session.send(Frame.Text(message))
                } catch (e: Exception) {
                    // Client disconnected
                }
            }
        }

        // OAuth 2.0 endpoints at root level (not under /_matrix/client)
        route("/oauth2") {
            oauthRoutes(config)
        }

        route("/_matrix") {
            route("/client") {
                // Server versions endpoint (for backward compatibility)
                get("/versions") {
                    call.respondText("""
                        {
                            "versions": [
                                "r0.0.1",
                                "r0.1.0",
                                "r0.2.0",
                                "r0.3.0",
                                "r0.4.0",
                                "r0.5.0",
                                "r0.6.0",
                                "v1.1",
                                "v1.2",
                                "v1.3",
                                "v1.4",
                                "v1.5",
                                "v1.6",
                                "v1.7",
                                "v1.8",
                                "v1.9",
                                "v1.10",
                                "v1.11",
                                "v1.12",
                                "v1.13",
                                "v1.14",
                                "v1.15"
                            ],
                            "unstable_features": {}
                        }
                    """.trimIndent(), ContentType.Application.Json)
                }

                route("/v3") {
                    // Server versions endpoint
                    get("/versions") {
                        call.respondText("""
                            {
                                "versions": [
                                    "r0.0.1",
                                    "r0.1.0",
                                    "r0.2.0",
                                    "r0.3.0",
                                    "r0.4.0",
                                    "r0.5.0",
                                    "r0.6.0",
                                    "v1.1",
                                    "v1.2",
                                    "v1.3",
                                    "v1.4",
                                    "v1.5",
                                    "v1.6",
                                    "v1.7",
                                    "v1.8",
                                    "v1.9",
                                    "v1.10",
                                    "v1.11",
                                    "v1.12",
                                    "v1.13",
                                    "v1.14",
                                    "v1.15"
                                ],
                                "unstable_features": {}
                            }
                        """.trimIndent(), ContentType.Application.Json)
                    }

                    // Include all the modular route files
                    authRoutes(config)
                    userRoutes()
                    deviceRoutes()
                    roomRoutes(config)
                    eventRoutes()
                    pushRoutes()
                    adminRoutes(config)
                    thirdPartyRoutes()
                    // oauthRoutes(config) - moved to root level
                    syncRoutes()
                    keysRoutes()

                    // VoIP endpoints
                    route("/voip") {
                        // GET /voip/turnServer - Get TURN server credentials for VoIP
                        get("/turnServer") {
                            try {
                                call.validateAccessToken() ?: return@get

                                // Return TURN server information
                                // In a real implementation, this would return actual TURN server credentials
                                // For now, return an empty list indicating no TURN servers available
                                call.respond(buildJsonObject {
                                    put("username", "")
                                    put("password", "")
                                    put("uris", buildJsonArray { })
                                    put("ttl", 86400)
                                })

                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Internal server error")
                                })
                            }
                        }
                    }
                }

                route("/unstable") {
                    // MSC2965 Authentication Metadata
                    route("/org.matrix.msc2965") {
                        authRoutes(config)
                    }

                    // MSC3814 Device Dehydration
                    route("/org.matrix.msc3814.v1") {
                        // GET /dehydrated_device - Get dehydrated device information
                        get("/dehydrated_device") {
                            try {
                                val userId = call.validateAccessToken() ?: return@get

                                // Return dehydrated device information
                                // MSC3814: Dehydrated devices for cross-device message continuity
                                val dehydratedDevice = transaction {
                                    DehydratedDevices.select {
                                        DehydratedDevices.userId eq userId
                                    }.singleOrNull()
                                }

                                if (dehydratedDevice != null) {
                                    call.respond(buildJsonObject {
                                        put("device_id", dehydratedDevice[DehydratedDevices.deviceId])
                                        put("device_data", Json.parseToJsonElement(dehydratedDevice[DehydratedDevices.deviceData]))
                                    })
                                } else {
                                    call.respond(HttpStatusCode.NotFound, buildJsonObject {
                                        put("errcode", "M_NOT_FOUND")
                                        put("error", "No dehydrated device found")
                                    })
                                }

                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Internal server error")
                                })
                            }
                        }

                        // PUT /dehydrated_device - Create/update dehydrated device
                        put("/dehydrated_device") {
                            try {
                                val userId = call.validateAccessToken() ?: return@put

                                // Parse request body
                                val requestBody = call.receiveText()
                                val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

                                val deviceId = jsonBody["device_id"]?.jsonPrimitive?.content
                                val deviceData = jsonBody["device_data"]?.toString()

                                if (deviceId == null || deviceData == null) {
                                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                        put("errcode", "M_INVALID_PARAM")
                                        put("error", "Missing device_id or device_data")
                                    })
                                    return@put
                                }

                                // Store the dehydrated device
                                transaction {
                                    DehydratedDevices.insert {
                                        it[DehydratedDevices.userId] = userId
                                        it[DehydratedDevices.deviceId] = deviceId
                                        it[DehydratedDevices.deviceData] = deviceData
                                        it[DehydratedDevices.lastModified] = System.currentTimeMillis()
                                    }
                                }

                                // Return success response
                                call.respond(buildJsonObject { })

                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Internal server error")
                                })
                            }
                        }

                        // DELETE /dehydrated_device - Delete dehydrated device
                        delete("/dehydrated_device") {
                            try {
                                val userId = call.validateAccessToken() ?: return@delete

                                // Delete the dehydrated device
                                transaction {
                                    DehydratedDevices.deleteWhere {
                                        DehydratedDevices.userId eq userId
                                    }
                                }

                                // Return success response
                                call.respond(buildJsonObject { })

                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                    put("errcode", "M_UNKNOWN")
                                    put("error", "Internal server error")
                                })
                            }
                        }
                    }
                }
            }

            // Content Repository endpoints under /_matrix/media/v3/
            route("/media") {
                route("/v3") {
                    contentRoutes(config)
                }
            }
        }
    }
}
