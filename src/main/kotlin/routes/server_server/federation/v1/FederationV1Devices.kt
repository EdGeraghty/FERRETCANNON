package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.AccessTokens
import utils.MatrixAuth
import utils.deviceKeys
import utils.crossSigningKeys
import utils.deviceListStreamIds

fun Route.federationV1Devices() {
    get("/openid/userinfo") {
        val accessToken = call.request.queryParameters["access_token"]
        if (accessToken == null) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNKNOWN_TOKEN")
                put("error", "Access token required")
            })
            return@get
        }

        // Find user by access token
        val userId = transaction {
            AccessTokens.select { AccessTokens.token eq accessToken }
                .singleOrNull()?.get(AccessTokens.userId)
        }
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNKNOWN_TOKEN")
                put("error", "Access token unknown or expired")
            })
            return@get
        }

        call.respond(buildJsonObject {
            put("sub", userId)
        })
    }
    get("/user/devices/{userId}") {
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Get user's devices
            val userDevices = deviceKeys[userId] ?: mutableMapOf()

            // Convert devices to the expected format
            val devices = userDevices.map { (deviceId, deviceInfo) ->
                buildJsonObject {
                    put("device_id", deviceId)
                    put("device_display_name", Json.encodeToJsonElement(deviceInfo["device_display_name"]))
                    put("keys", Json.encodeToJsonElement(deviceInfo["keys"]))
                }
            }

            // Get cross-signing keys
            val masterKey = crossSigningKeys["${userId}_master"]
            val selfSigningKey = crossSigningKeys["${userId}_self_signing"]

            // Get stream ID for device list
            val streamId = deviceListStreamIds.getOrPut(userId) { 0L }

            val response = buildJsonObject {
                put("devices", Json.encodeToJsonElement(devices))
                put("stream_id", streamId)
                put("user_id", userId)
                if (masterKey != null) {
                    put("master_key", Json.encodeToJsonElement(masterKey))
                }
                if (selfSigningKey != null) {
                    put("self_signing_key", Json.encodeToJsonElement(selfSigningKey))
                }
            }

            call.respond(response)
        } catch (e: Exception) {
            println("Get devices error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
    post("/user/keys/claim") {
        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@post
        }

        try {
            val requestBody = Json.parseToJsonElement(body).jsonObject
            val oneTimeKeys = requestBody["one_time_keys"]?.jsonObject ?: JsonObject(mutableMapOf())

            val claimedKeys = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()

            // Process each user's requested keys
            for (userId in oneTimeKeys.keys) {
                val userRequestedKeys = oneTimeKeys[userId]?.jsonObject ?: JsonObject(mutableMapOf())
                val userClaimedKeys = mutableMapOf<String, Map<String, Any?>>()

                for (deviceKeyId in userRequestedKeys.keys) {
                    // Parse the key ID (format: algorithm:key_id)
                    val parts = deviceKeyId.split(":", limit = 2)
                    if (parts.size != 2) continue

                    val algorithm = parts[0]
                    val _keyId = parts[1]

                    // Find available one-time key for this user and algorithm
                    val globalUserOneTimeKeys = utils.oneTimeKeys[userId] ?: mapOf<String, Map<String, Any?>>()
                    val availableKey = globalUserOneTimeKeys.entries.find { (key, _) ->
                        key.startsWith("$algorithm:")
                    }

                    if (availableKey != null) {
                        val (foundKeyId, keyData) = availableKey
                        userClaimedKeys[foundKeyId] = buildJsonObject {
                            put("key", Json.encodeToJsonElement(keyData["key"]))
                            put("signatures", Json.encodeToJsonElement(keyData["signatures"]))
                        }.jsonObject

                        // Remove the claimed key from available keys
                        val userKeysMap = utils.oneTimeKeys.getOrPut(userId) { mutableMapOf<String, Map<String, Any?>>() }
                        userKeysMap.remove(foundKeyId)
                    }
                }

                if (userClaimedKeys.isNotEmpty()) {
                    claimedKeys[userId] = userClaimedKeys
                }
            }

            call.respond(buildJsonObject {
                put("one_time_keys", Json.encodeToJsonElement(claimedKeys))
            })
        } catch (e: Exception) {
            println("Claim keys error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
    post("/user/keys/query") {
        // Authenticate the request
        val body = call.receiveText()
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@post
        }

        try {
            val requestBody = Json.parseToJsonElement(body).jsonObject
            val deviceKeys = requestBody["device_keys"]?.jsonObject ?: JsonObject(mutableMapOf())

            // Process device keys
            val deviceKeysResponse = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
            for (userId in deviceKeys.keys) {
                val requestedDevicesJson = deviceKeys[userId]
                val globalUserDevices = utils.deviceKeys[userId] ?: mapOf<String, Map<String, Any?>>()
                val userDeviceKeys = mutableMapOf<String, Map<String, Any?>>()

                if (requestedDevicesJson is JsonNull) {
                    // Return all devices for this user
                    for (deviceId in globalUserDevices.keys) {
                        val deviceInfo = globalUserDevices[deviceId]
                        if (deviceInfo != null) {
                            userDeviceKeys[deviceId] = deviceInfo
                        }
                    }
                } else if (requestedDevicesJson is JsonObject) {
                    val deviceList = requestedDevicesJson["device_ids"]?.jsonArray ?: JsonArray(emptyList())
                    // Return only requested devices
                    for (deviceElement in deviceList) {
                        val deviceId = deviceElement.jsonPrimitive.content
                        val deviceInfo = globalUserDevices[deviceId]
                        if (deviceInfo != null) {
                            userDeviceKeys[deviceId] = deviceInfo
                        }
                    }
                }

                if (userDeviceKeys.isNotEmpty()) {
                    deviceKeysResponse[userId] = userDeviceKeys
                }
            }

            val response = buildJsonObject {
                if (deviceKeysResponse.isNotEmpty()) {
                    put("device_keys", Json.encodeToJsonElement(deviceKeysResponse))
                }

                // Add cross-signing keys if available
                val masterKeys = mutableMapOf<String, Map<String, Any?>>()
                val selfSigningKeys = mutableMapOf<String, Map<String, Any?>>()

                for (userId in deviceKeys.keys) {
                    val masterKey = crossSigningKeys["${userId}_master"]
                    val selfSigningKey = crossSigningKeys["${userId}_self_signing"]

                    if (masterKey != null) {
                        masterKeys[userId] = masterKey
                    }
                    if (selfSigningKey != null) {
                        selfSigningKeys[userId] = selfSigningKey
                    }
                }

                if (masterKeys.isNotEmpty()) {
                    put("master_keys", Json.encodeToJsonElement(masterKeys))
                }
                if (selfSigningKeys.isNotEmpty()) {
                    put("self_signing_keys", Json.encodeToJsonElement(selfSigningKeys))
                }
            }

            call.respond(response)
        } catch (e: Exception) {
            println("Query keys error: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_BAD_JSON")
                put("error", "Invalid JSON")
            })
        }
    }
}
