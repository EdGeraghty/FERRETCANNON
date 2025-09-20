package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.AuthUtils
import config.ServerConfig

fun Route.keysRoutes(_config: ServerConfig) {
    // POST /keys/query - Query device keys for users
    post("/keys/query") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val deviceKeys = jsonBody["device_keys"]?.jsonObject ?: buildJsonObject { }

            // Process device keys query
            val result = mutableMapOf<String, JsonElement>()

            for (queryUserId in deviceKeys.keys) {
                val requestedDevices = deviceKeys[queryUserId]

                if (requestedDevices is JsonNull || (requestedDevices is JsonObject && requestedDevices.isEmpty())) {
                    // Return all devices for this user
                    val userDeviceKeys = AuthUtils.getDeviceKeysForUsers(listOf(queryUserId))
                    if (userDeviceKeys.containsKey(queryUserId)) {
                        result[queryUserId] = JsonObject(userDeviceKeys[queryUserId]!!)
                    }
                } else if (requestedDevices is JsonObject) {
                    val deviceIds = requestedDevices["device_ids"]?.jsonArray ?: JsonArray(emptyList())
                    if (deviceIds.isNotEmpty()) {
                        // Return only requested devices
                        val userDeviceKeys = AuthUtils.getDeviceKeysForUsers(listOf(queryUserId))
                        val filteredDevices = mutableMapOf<String, JsonElement>()

                        deviceIds.forEach { deviceIdElement ->
                            val deviceId = deviceIdElement.jsonPrimitive.content
                            val userDevices = userDeviceKeys[queryUserId]
                            if (userDevices != null && userDevices.containsKey(deviceId)) {
                                filteredDevices[deviceId] = userDevices[deviceId]!!
                            }
                        }

                        if (filteredDevices.isNotEmpty()) {
                            result[queryUserId] = JsonObject(filteredDevices)
                        }
                    }
                }
            }

            // Return the device keys
            val response = buildJsonObject {
                put("device_keys", Json.encodeToJsonElement(result))
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // POST /keys/claim - Claim one-time keys
    post("/keys/claim") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val oneTimeKeys = jsonBody["one_time_keys"]?.jsonObject ?: buildJsonObject { }

            // Process one-time key claims
            val result = mutableMapOf<String, JsonElement>()

            for (claimUserId in oneTimeKeys.keys) {
                val userRequestedKeys = oneTimeKeys[claimUserId]?.jsonObject ?: buildJsonObject { }
                val userClaimedKeys = mutableMapOf<String, JsonElement>()

                for (keyId in userRequestedKeys.keys) {
                    // Parse the key ID (format: algorithm:key_id)
                    val parts = keyId.split(":", limit = 2)
                    if (parts.size == 2) {
                        val _algorithm = parts[0]
                        val _keyIdValue = parts[1]

                        // For now, return a placeholder response
                        // In a full implementation, this would claim actual one-time keys
                        val keyData = buildJsonObject {
                            put("key", "placeholder_key_data")
                            put("signatures", buildJsonObject { })
                        }
                        userClaimedKeys[keyId] = keyData
                    }
                }

                if (userClaimedKeys.isNotEmpty()) {
                    result[claimUserId] = JsonObject(userClaimedKeys)
                }
            }

            // Return the claimed keys
            val response = buildJsonObject {
                put("one_time_keys", Json.encodeToJsonElement(result))
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // POST /keys/upload - Upload device and one-time keys
    post("/keys/upload") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val deviceId = call.attributes.getOrNull(MATRIX_DEVICE_ID_KEY)

            if (userId == null || deviceId == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            val deviceKeys = jsonBody["device_keys"]?.jsonObject
            val oneTimeKeys = jsonBody["one_time_keys"]?.jsonObject

            // Process device keys upload
            if (deviceKeys != null) {
                AuthUtils.updateDeviceKeys(userId, deviceId)
            }

            // Process one-time keys upload
            if (oneTimeKeys != null) {
                // In a full implementation, this would store the one-time keys
                // For now, just acknowledge the upload
            }

            // Return success response with key counts
            val response = buildJsonObject {
                putJsonObject("one_time_key_counts") {
                    put("curve25519", 0)  // Placeholder
                    put("signed_curve25519", 0)  // Placeholder
                }
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
}
