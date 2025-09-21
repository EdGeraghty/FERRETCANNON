package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.AuthUtils

fun Route.deviceRoutes(_config: ServerConfig) {
    // GET /devices - List user's devices
    get("/devices") {
        try {
            println("DEBUG: DeviceRoutes - /devices called")
            val accessToken = call.validateAccessToken()
            println("DEBUG: DeviceRoutes - accessToken: $accessToken")

            if (accessToken == null) {
                println("DEBUG: DeviceRoutes - accessToken is null, returning unauthorized")
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                println("DEBUG: DeviceRoutes - userId is null")
                call.respond(HttpStatusCode.Unauthorized, mutableMapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Get devices from AuthUtils
            val devices = AuthUtils.getUserDevices(userId)
            println("DEBUG: DeviceRoutes - returning devices response")
            // Use manual JSON construction to avoid serialization issues
            val devicesJson = devices.joinToString(",", "[", "]") { device ->
                device.entries.joinToString(",", "{", "}") { 
                    "\"${it.key}\":\"${it.value}\""
                }
            }
            call.respondText("""{"devices":$devicesJson}""", ContentType.Application.Json)

        } catch (e: Exception) {
            println("ERROR: DeviceRoutes - Exception in /devices: ${e.message}")
            e.printStackTrace()
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
                // Convert Map to JSON manually since Kotlinx serialization has issues with Map types
                val jsonString = device.entries.joinToString(",", "{", "}") { 
                    "\"${it.key}\":\"${it.value}\""
                }
                call.respondText(jsonString, ContentType.Application.Json)
            } else {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Device not found"
                ))
            }

        } catch (e: Exception) {
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

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing deviceId parameter"
                ))
                return@delete
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
}
