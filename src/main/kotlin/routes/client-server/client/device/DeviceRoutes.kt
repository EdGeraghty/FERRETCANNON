package routes.client_server.client.device

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.AuthUtils
import routes.client_server.client.common.*

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("routes.client_server.client.device.DeviceRoutes")

fun Route.deviceRoutes() {
    // GET /devices - List user's devices
    get("/devices") {
        try {
            logger.debug("DeviceRoutes - /devices called")
            val accessToken = call.validateAccessToken()
            logger.debug("DeviceRoutes - accessToken: $accessToken")

            if (accessToken == null) {
                logger.debug("DeviceRoutes - accessToken is null, returning unauthorized")
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                logger.debug("DeviceRoutes - userId is null")
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Get devices from AuthUtils
            val devices = AuthUtils.getUserDevices(userId)
            logger.debug("DeviceRoutes - returning devices response")
            // Convert to proper JSON
            val devicesJson = buildJsonArray {
                devices.forEach { device ->
                    addJsonObject {
                        device.forEach { (key, value) ->
                            when (value) {
                                is String -> put(key, value)
                                is Long -> put(key, value)
                                is Int -> put(key, value)
                                is Boolean -> put(key, value)
                                null -> put(key, JsonNull)
                                else -> put(key, value.toString())
                            }
                        }
                    }
                }
            }
            call.respond(mapOf("devices" to devicesJson))        } catch (e: Exception) {
            logger.error("DeviceRoutes - Exception in /devices: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /devices/{deviceId} - Get device information
    get("/devices/{deviceId}") {
        try {
            val accessToken = call.validateAccessToken()
            val deviceId = call.parameters["deviceId"]

            if (accessToken == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing deviceId parameter"
                ))
                return@get
            }

            // Get device info from AuthUtils
            val device = AuthUtils.getUserDevice(userId, deviceId)
            if (device != null) {
                // Convert to proper JSON
                val deviceJson = buildJsonObject {
                    device.forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Long -> put(key, value)
                            is Int -> put(key, value)
                            is Boolean -> put(key, value)
                            null -> put(key, JsonNull)
                            else -> put(key, value.toString())
                        }
                    }
                }
                call.respond(deviceJson)
            } else {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Device not found"
                ))
            }        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /devices/{deviceId} - Update device information
    put("/devices/{deviceId}") {
        try {
            val accessToken = call.validateAccessToken()
            val deviceId = call.parameters["deviceId"]

            if (accessToken == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing deviceId parameter"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val displayName = jsonBody["display_name"]?.jsonPrimitive?.content

            // Update device in AuthUtils
            AuthUtils.updateDeviceDisplayName(userId, deviceId, displayName)
            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // DELETE /devices/{deviceId} - Delete device
    delete("/devices/{deviceId}") {
        try {
            val accessToken = call.validateAccessToken()
            val deviceId = call.parameters["deviceId"]

            if (accessToken == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@delete
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@delete
            }

            // Get current device ID
            val currentDeviceId = call.attributes.getOrNull(MATRIX_DEVICE_ID_KEY)
            if (currentDeviceId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@delete
            }

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing deviceId parameter"
                ))
                return@delete
            }

            // Check if trying to delete a different device
            val isDeletingOtherDevice = deviceId != currentDeviceId

            if (isDeletingOtherDevice) {
                // Parse request body for auth
                val requestBody = try {
                    call.receiveText()
                } catch (e: Exception) {
                    // No body provided
                    ""
                }
                val jsonBody = if (requestBody.isNotBlank()) Json.parseToJsonElement(requestBody).jsonObject else buildJsonObject {}
                val auth = jsonBody["auth"]?.jsonObject

                if (auth == null) {
                    // No auth provided - return UIA challenge
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_device_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                        put("errcode", "M_AUTHENTICATION")
                        put("error", "Authentication required to delete device")
                    })
                    return@delete
                }

                // Validate auth
                val authType = auth["type"]?.jsonPrimitive?.content
                if (authType != "m.login.password") {
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        put("errcode", "M_UNKNOWN")
                        put("error", "Unsupported authentication type")
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_device_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                    })
                    return@delete
                }

                // Validate password
                val authUser = auth["identifier"]?.jsonObject?.get("user")?.jsonPrimitive?.content
                val authPassword = auth["password"]?.jsonPrimitive?.content

                if (authUser.isNullOrBlank() || authPassword.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        put("errcode", "M_MISSING_PARAM")
                        put("error", "Missing username or password")
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_device_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                    })
                    return@delete
                }

                // Authenticate user
                val authenticatedUserId = AuthUtils.authenticateUser(authUser, authPassword)
                if (authenticatedUserId != userId) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_FORBIDDEN")
                        put("error", "Invalid username or password")
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_device_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                    })
                    return@delete
                }
            }

            // Delete device from AuthUtils
            AuthUtils.deleteDevice(userId, deviceId)
            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /delete_devices - Delete multiple devices
    post("/delete_devices") {
        try {
            val accessToken = call.validateAccessToken()

            if (accessToken == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Get current device ID
            val currentDeviceId = call.attributes.getOrNull(MATRIX_DEVICE_ID_KEY)
            if (currentDeviceId == null) {
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val devicesArray = jsonBody["devices"]?.jsonArray

            if (devicesArray == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing devices parameter"
                ))
                return@post
            }

            val deviceIds = devicesArray.mapNotNull { it.jsonPrimitive.content }

            // Check if any devices are not the current device
            val hasOtherDevices = deviceIds.any { it != currentDeviceId }

            if (hasOtherDevices) {
                // Parse request body for auth
                val auth = jsonBody["auth"]?.jsonObject

                if (auth == null) {
                    // No auth provided - return UIA challenge
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_devices_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                        put("errcode", "M_AUTHENTICATION")
                        put("error", "Authentication required to delete devices")
                    })
                    return@post
                }

                // Validate auth
                val authType = auth["type"]?.jsonPrimitive?.content
                if (authType != "m.login.password") {
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        put("errcode", "M_UNKNOWN")
                        put("error", "Unsupported authentication type")
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_devices_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                    })
                    return@post
                }

                // Validate password
                val authUser = auth["identifier"]?.jsonObject?.get("user")?.jsonPrimitive?.content
                val authPassword = auth["password"]?.jsonPrimitive?.content

                if (authUser.isNullOrBlank() || authPassword.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        put("errcode", "M_MISSING_PARAM")
                        put("error", "Missing username or password")
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_devices_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                    })
                    return@post
                }

                // Authenticate user
                val authenticatedUserId = AuthUtils.authenticateUser(authUser, authPassword)
                if (authenticatedUserId != userId) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_FORBIDDEN")
                        put("error", "Invalid username or password")
                        putJsonArray("flows") {
                            addJsonObject {
                                putJsonArray("stages") {
                                    add("m.login.password")
                                }
                            }
                        }
                        put("session", "delete_devices_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                    })
                    return@post
                }
            }

            // Delete devices from AuthUtils
            deviceIds.forEach { deviceId ->
                AuthUtils.deleteDevice(userId, deviceId)
            }

            call.respondText("{}", ContentType.Application.Json)

        } catch (e: Exception) {
            logger.error("DeviceRoutes - Exception in /delete_devices: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
