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
import utils.AuthUtils
import config.ServerConfig
import utils.OneTimeKeysStorage
import utils.CrossSigningKeysStorage

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
            // Get user's devices from database
            val config = ServerConfig() // Use default config
            val allDeviceKeys = AuthUtils.getDeviceKeysForUsers(listOf(userId), config)
            val userDeviceKeys = allDeviceKeys[userId] ?: mutableMapOf()

            // Convert devices to the expected format
            val devices = userDeviceKeys.map { (deviceId, deviceKeyJson) ->
                val deviceKeyObj = deviceKeyJson as? JsonObject ?: JsonObject(mutableMapOf())
                val keys = deviceKeyObj["keys"] as? JsonObject ?: JsonObject(mutableMapOf())
                buildJsonObject {
                    put("device_id", deviceId)
                    put("device_display_name", JsonNull) // Not available in current format
                    put("keys", keys)
                }
            }

            // Get cross-signing keys
            val userKeys = CrossSigningKeysStorage.getUserCrossSigningKeys(userId)
            val masterKeyJson = userKeys["master"]
            val selfSigningKeyJson = userKeys["self_signing"]

            val response = buildJsonObject {
                put("devices", Json.encodeToJsonElement(devices))
                put("stream_id", 0L) // Placeholder
                put("user_id", userId)
                if (masterKeyJson != null) {
                    put("master_key", Json.parseToJsonElement(masterKeyJson))
                }
                if (selfSigningKeyJson != null) {
                    put("self_signing_key", Json.parseToJsonElement(selfSigningKeyJson))
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

                    val keyId = deviceKeyId

                    // Try to claim the specific key
                    val claimedKeyData = OneTimeKeysStorage.getOneTimeKey(userId, keyId)
                    if (claimedKeyData != null) {
                        // Parse the stored JSON
                        val keyJson = Json.parseToJsonElement(claimedKeyData).jsonObject
                        userClaimedKeys[keyId] = buildJsonObject {
                            put("key", keyJson["key"] ?: JsonNull)
                            put("signatures", keyJson["signatures"] ?: JsonNull)
                        }.jsonObject

                        // Delete the claimed key
                        OneTimeKeysStorage.deleteOneTimeKey(userId, keyId)
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
            val deviceKeysResponse = mutableMapOf<String, MutableMap<String, JsonElement>>()
            val userIds = deviceKeys.keys.toList()
            val config = ServerConfig() // Use default config
            val allDeviceKeys = AuthUtils.getDeviceKeysForUsers(userIds, config)

            for (userId in deviceKeys.keys) {
                val requestedDevicesJson = deviceKeys[userId]
                val userDeviceKeys = allDeviceKeys[userId] ?: mutableMapOf()

                if (requestedDevicesJson is JsonNull) {
                    // Return all devices for this user
                    deviceKeysResponse[userId] = userDeviceKeys.toMutableMap()
                } else if (requestedDevicesJson is JsonObject) {
                    val deviceList = requestedDevicesJson["device_ids"]?.jsonArray ?: JsonArray(emptyList())
                    val filteredKeys = mutableMapOf<String, JsonElement>()
                    for (deviceElement in deviceList) {
                        val deviceId = deviceElement.jsonPrimitive.content
                        userDeviceKeys[deviceId]?.let { filteredKeys[deviceId] = it }
                    }
                    if (filteredKeys.isNotEmpty()) {
                        deviceKeysResponse[userId] = filteredKeys
                    }
                }
            }

            val response = buildJsonObject {
                if (deviceKeysResponse.isNotEmpty()) {
                    put("device_keys", Json.encodeToJsonElement(deviceKeysResponse))
                }

                // Add cross-signing keys if available
                val masterKeys = mutableMapOf<String, JsonElement>()
                val selfSigningKeys = mutableMapOf<String, JsonElement>()

                for (userId in deviceKeys.keys) {
                    val userKeys = CrossSigningKeysStorage.getUserCrossSigningKeys(userId)
                    userKeys["master"]?.let { masterKeyJson ->
                        val parsed = Json.parseToJsonElement(masterKeyJson)
                        masterKeys[userId] = parsed
                    }
                    userKeys["self_signing"]?.let { selfSigningKeyJson ->
                        val parsed = Json.parseToJsonElement(selfSigningKeyJson)
                        selfSigningKeys[userId] = parsed
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
