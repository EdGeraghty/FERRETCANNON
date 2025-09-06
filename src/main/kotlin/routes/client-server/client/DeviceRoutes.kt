package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.AuthUtils

fun Route.deviceRoutes(config: ServerConfig) {
    // GET /devices - List user's devices
    get("/devices") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Get devices from AuthUtils
            val devices = AuthUtils.getUserDevices(userId)
            call.respond(mapOf("devices" to devices))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /devices/{deviceId} - Get device information
    get("/devices/{deviceId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val deviceId = call.parameters["deviceId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing deviceId parameter"
                ))
                return@get
            }

            // Get device info from AuthUtils
            val device = AuthUtils.getUserDevice(userId, deviceId)
            if (device != null) {
                call.respond(device)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Device not found"
                ))
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /devices/{deviceId} - Update device information
    put("/devices/{deviceId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val deviceId = call.parameters["deviceId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
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
            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // DELETE /devices/{deviceId} - Delete device
    delete("/devices/{deviceId}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val deviceId = call.parameters["deviceId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@delete
            }

            if (deviceId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing deviceId parameter"
                ))
                return@delete
            }

            // Delete device from AuthUtils
            AuthUtils.deleteDevice(userId, deviceId)
            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
