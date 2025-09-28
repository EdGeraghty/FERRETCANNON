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
import routes.client_server.client.auth.authRoutes
import routes.client_server.client.auth.oauthAuthorizationRoutes
import routes.client_server.client.auth.oauthCallbackRoutes
import routes.client_server.client.auth.oauthJWKSroutes
import routes.client_server.client.auth.oauthRoutes
import routes.client_server.client.auth.oauthTokenRoutes
import routes.client_server.client.auth.oauthUserInfoRoutes
import routes.client_server.client.user.accountDataRoutes
import routes.client_server.client.user.accountRoutes
import routes.client_server.client.user.profileAvatarRoutes
import routes.client_server.client.user.profileCustomRoutes
import routes.client_server.client.user.profileDisplayRoutes
import routes.client_server.client.user.profileTimezoneRoutes
import routes.client_server.client.user.userDirectoryRoutes
import routes.client_server.client.user.userRoutes
import routes.client_server.client.device.dehydratedDeviceRoutes
import routes.client_server.client.device.deviceKeysRoutes
import routes.client_server.client.device.deviceRoutes
import routes.client_server.client.room.roomCreationRoutes
import routes.client_server.client.room.roomKeysRoutes
import routes.client_server.client.room.roomMembershipRoutes
import routes.client_server.client.room.roomRoutes
import routes.client_server.client.event.eventReadMarkersRoutes
import routes.client_server.client.event.eventReceiptRoutes
import routes.client_server.client.event.eventRedactionRoutes
import routes.client_server.client.push.pushersRoutes
import routes.client_server.client.push.pushRulesRoutes
import routes.client_server.client.keys.crossSigningRoutes
import routes.client_server.client.keys.keysRoutes
import routes.client_server.client.content.contentRoutes
import routes.client_server.client.sync.syncRoutes
import routes.client_server.client.admin.adminRoutes
import routes.client_server.client.thirdparty.thirdPartyRoutes
import routes.client_server.client.filter.filterRoutes
import routes.client_server.client.common.*

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("routes.client_server.client.ClientRoutes")

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

            logger.debug("Authentication middleware - accessToken: '$accessToken'")

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
                    logger.debug("Authentication successful for user: $userId")
                } else {
                    // Invalid token - will be handled by individual endpoints
                    call.attributes.put(MATRIX_INVALID_TOKEN_KEY, accessToken)
                    logger.info("Invalid token")
                }
            } else {
                // No token provided - will be handled by individual endpoints
                call.attributes.put(MATRIX_NO_TOKEN_KEY, true)
                logger.debug("No token provided")
            }
        } catch (e: Exception) {
            // Handle authentication errors gracefully
            call.attributes.put(MATRIX_INVALID_TOKEN_KEY, "auth_error")
            logger.info("Authentication error: ${e.message}")
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
            oauthAuthorizationRoutes()
            oauthTokenRoutes()
            oauthUserInfoRoutes()
            oauthJWKSroutes()
            oauthCallbackRoutes(config)
        }

        route("/_matrix") {
            route("/client") {
                // Server versions endpoint (for backward compatibility)
                get("/versions") {
                    val buildVersion = try {
                        java.io.File("/app/version.txt").readText().trim()
                    } catch (e: Exception) {
                        "Ferret Cannon Build Unknown"
                    }
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
                                "v1.15",
                                "v1.16"
                            ],
                            "unstable_features": {},
                            "server_version": "$buildVersion"
                        }
                    """.trimIndent(), ContentType.Application.Json)
                }

                route("/v3") {
                    // Server versions endpoint
                    get("/versions") {
                        val buildVersion = try {
                            java.io.File("/app/version.txt").readText().trim()
                        } catch (e: Exception) {
                            "Ferret Cannon Build Unknown"
                        }
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
                                    "v1.15",
                                    "v1.16"
                                ],
                                "unstable_features": {},
                                "server_version": "$buildVersion"
                            }
                        """.trimIndent(), ContentType.Application.Json)
                    }

                    // Capabilities endpoint
                    get("/capabilities") {
                        try {
                            call.validateAccessToken() ?: return@get

                            call.respond(buildJsonObject {
                                put("capabilities", buildJsonObject {
                                    put("m.room_versions", buildJsonObject {
                                        put("default", "12")
                                        put("available", buildJsonObject {
                                            put("12", "stable")
                                        })
                                    })
                                    put("m.change_password", buildJsonObject {
                                        put("enabled", true)
                                    })
                                    put("m.set_displayname", buildJsonObject {
                                        put("enabled", true)
                                    })
                                    put("m.set_avatar_url", buildJsonObject {
                                        put("enabled", true)
                                    })
                                    put("m.3pid_changes", buildJsonObject {
                                        put("enabled", false)
                                    })
                                    put("m.cross_signing", buildJsonObject {
                                        put("enabled", true)
                                    })
                                    put("m.room_keys_backup", buildJsonObject {
                                        put("enabled", true)
                                    })
                                })
                            })

                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                put("errcode", "M_UNKNOWN")
                                put("error", "Internal server error")
                            })
                        }
                    }

                    // Include all the modular route files
                    authRoutes(config)
                    profileDisplayRoutes()
                    profileAvatarRoutes()
                    profileTimezoneRoutes()
                    profileCustomRoutes()
                    accountDataRoutes()
                    filterRoutes()
                    accountRoutes()
                    userDirectoryRoutes()
                    dehydratedDeviceRoutes()
                    userRoutes()
                    deviceRoutes()
                    roomCreationRoutes(config)
                    roomMembershipRoutes(config)
                    roomRoutes()
                    eventReceiptRoutes()
                    eventReadMarkersRoutes()
                    eventRedactionRoutes()
                    pushRulesRoutes()
                    pushersRoutes()
                    adminRoutes(config)
                    thirdPartyRoutes()
                    // oauthRoutes(config) - moved to root level
                    syncRoutes()
                    keysRoutes(config)
                    deviceKeysRoutes(config)
                    roomKeysRoutes()
                    dehydratedDeviceRoutes()
                    crossSigningRoutes()

                    // VoIP endpoints
                    route("/voip") {
                        // GET /voip/turnServer - Get TURN server credentials for VoIP
                        get("/turnServer") {
                            try {
                                call.validateAccessToken() ?: return@get

                                // Return TURN server information from config
                                val turnServers = config.voip.turnServers
                                val stunServers = config.voip.stunServers

                                val uris = buildJsonArray {
                                    turnServers.forEach { server ->
                                        add(server.uri)
                                    }
                                    stunServers.forEach { server ->
                                        add(server.uri)
                                    }
                                }

                                // Use the first TURN server for credentials if available
                                val turnServer = turnServers.firstOrNull()

                                call.respond(buildJsonObject {
                                    put("username", turnServer?.username ?: "")
                                    put("password", turnServer?.password ?: "")
                                    put("uris", uris)
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
                                    val existing = DehydratedDevices.select { DehydratedDevices.userId eq userId }.singleOrNull()
                                    if (existing != null) {
                                        // Update existing dehydrated device
                                        DehydratedDevices.update({ DehydratedDevices.userId eq userId }) {
                                            it[DehydratedDevices.deviceId] = deviceId
                                            it[DehydratedDevices.deviceData] = deviceData
                                            it[DehydratedDevices.lastModified] = System.currentTimeMillis()
                                        }
                                    } else {
                                        // Insert new dehydrated device
                                        DehydratedDevices.insert {
                                            it[DehydratedDevices.userId] = userId
                                            it[DehydratedDevices.deviceId] = deviceId
                                            it[DehydratedDevices.deviceData] = deviceData
                                            it[DehydratedDevices.lastModified] = System.currentTimeMillis()
                                        }
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
