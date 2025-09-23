package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.AuthUtils
import config.ServerConfig
import routes.client_server.client.MATRIX_DEVICE_ID_KEY
import models.OneTimeKeys
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.*

fun Route.deviceKeysRoutes(config: ServerConfig) {
    // POST /keys/query - Query device keys for users
    post("/keys/query") {
        try {
            call.validateAccessToken() ?: return@post

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
                    val userDeviceKeys = AuthUtils.getDeviceKeysForUsers(listOf(queryUserId), config)
                    if (userDeviceKeys.containsKey(queryUserId)) {
                        result[queryUserId] = JsonObject(userDeviceKeys[queryUserId]!!)
                    }
                } else if (requestedDevices is JsonObject) {
                    val deviceIds = requestedDevices["device_ids"]?.jsonArray ?: JsonArray(emptyList())
                    if (deviceIds.isNotEmpty()) {
                        // Return only requested devices
                        val userDeviceKeys = AuthUtils.getDeviceKeysForUsers(listOf(queryUserId), config)
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
            val requestingUserId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val oneTimeKeys = jsonBody["one_time_keys"]?.jsonObject ?: buildJsonObject { }

            // Process one-time key claims
            val result = mutableMapOf<String, JsonElement>()

            for (targetUserId in oneTimeKeys.keys) {
                val userRequestedKeys = oneTimeKeys[targetUserId]?.jsonObject ?: buildJsonObject { }
                val userClaimedKeys = mutableMapOf<String, JsonElement>()

                for (keyId in userRequestedKeys.keys) {
                    // Parse the key ID (format: algorithm:key_id)
                    val parts = keyId.split(":", limit = 2)
                    if (parts.size == 2) {
                        val algorithm = parts[0]

                        // Find an available one-time key for this user and algorithm
                        val availableKey = transaction {
                            OneTimeKeys.select {
                                (OneTimeKeys.userId eq targetUserId) and
                                (OneTimeKeys.algorithm eq algorithm) and
                                (OneTimeKeys.isClaimed eq false)
                            }.firstOrNull()
                        }

                        if (availableKey != null) {
                            // Mark the key as claimed
                            transaction {
                                OneTimeKeys.update({
                                    (OneTimeKeys.userId eq targetUserId) and
                                    (OneTimeKeys.deviceId eq availableKey[OneTimeKeys.deviceId]) and
                                    (OneTimeKeys.keyId eq availableKey[OneTimeKeys.keyId])
                                }) {
                                    it[OneTimeKeys.isClaimed] = true
                                    it[OneTimeKeys.claimedBy] = requestingUserId
                                    it[OneTimeKeys.claimedAt] = System.currentTimeMillis()
                                }
                            }

                            // Return the key data
                            val keyData = Json.parseToJsonElement(availableKey[OneTimeKeys.keyData])
                            userClaimedKeys[keyId] = keyData
                        }
                        // If no key available, don't include it in the response (per spec)
                    }
                }

                if (userClaimedKeys.isNotEmpty()) {
                    result[targetUserId] = JsonObject(userClaimedKeys)
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
            val userId = call.validateAccessToken() ?: return@post
            val deviceId = call.attributes.getOrNull(MATRIX_DEVICE_ID_KEY)

            if (deviceId == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing device ID")
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
                // Store the one-time keys
                transaction {
                    for (keyId in oneTimeKeys.keys) {
                        val keyData = oneTimeKeys[keyId]?.toString() ?: continue

                        // Parse algorithm from key ID
                        val parts = keyId.split(":", limit = 2)
                        if (parts.size == 2) {
                            val algorithm = parts[0]

                            OneTimeKeys.replace {
                                it[OneTimeKeys.userId] = userId
                                it[OneTimeKeys.deviceId] = deviceId
                                it[OneTimeKeys.keyId] = keyId
                                it[OneTimeKeys.keyData] = keyData
                                it[OneTimeKeys.algorithm] = algorithm
                                it[OneTimeKeys.isClaimed] = false
                                it[OneTimeKeys.uploadedAt] = System.currentTimeMillis()
                            }
                        }
                    }
                }
            }

            // Count available one-time keys
            val keyCounts = transaction {
                OneTimeKeys.select {
                    (OneTimeKeys.userId eq userId) and
                    (OneTimeKeys.deviceId eq deviceId) and
                    (OneTimeKeys.isClaimed eq false)
                }.groupBy { it[OneTimeKeys.algorithm] }
                .mapValues { it.value.size }
            }

            // Return success response with key counts
            val response = buildJsonObject {
                putJsonObject("one_time_key_counts") {
                    keyCounts.forEach { (algorithm, count) ->
                        put(algorithm, count)
                    }
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