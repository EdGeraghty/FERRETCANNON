package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.AuthUtils
import config.ServerConfig
import routes.client_server.client.MATRIX_USER_ID_KEY
import routes.client_server.client.MATRIX_DEVICE_ID_KEY
import models.CrossSigningKeys
import models.DehydratedDevices
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.*

fun Route.keysRoutes() {
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
            call.validateAccessToken() ?: return@post

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

    // GET /room_keys/version - Get room keys version
    get("/room_keys/version") {
        try {
            call.validateAccessToken() ?: return@get

            // Return room keys backup version information
            // In a real implementation, this would return the current backup version
            // For now, return a 404 indicating no backup exists (which is valid per spec)
            call.respond(HttpStatusCode.NotFound, buildJsonObject {
                put("errcode", "M_NOT_FOUND")
                put("error", "No current backup")
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // POST /keys/device_signing/upload - Upload cross-signing keys
    post("/keys/device_signing/upload") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            val masterKey = jsonBody["master_key"]?.jsonObject
            val selfSigningKey = jsonBody["self_signing_key"]?.jsonObject
            val userSigningKey = jsonBody["user_signing_key"]?.jsonObject

            // Validate keys before storing
            if (masterKey != null) {
                val keyId = masterKey["key_id"]?.jsonPrimitive?.content
                val publicKey = masterKey["public_key"]?.jsonPrimitive?.content
                if (keyId == null || publicKey == null) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("errcode", "M_INVALID_PARAM")
                        put("error", "Missing key_id or public_key for master_key")
                    })
                    return@post
                }
            }

            if (selfSigningKey != null) {
                val keyId = selfSigningKey["key_id"]?.jsonPrimitive?.content
                val publicKey = selfSigningKey["public_key"]?.jsonPrimitive?.content
                if (keyId == null || publicKey == null) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("errcode", "M_INVALID_PARAM")
                        put("error", "Missing key_id or public_key for self_signing_key")
                    })
                    return@post
                }
            }

            if (userSigningKey != null) {
                val keyId = userSigningKey["key_id"]?.jsonPrimitive?.content
                val publicKey = userSigningKey["public_key"]?.jsonPrimitive?.content
                if (keyId == null || publicKey == null) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("errcode", "M_INVALID_PARAM")
                        put("error", "Missing key_id or public_key for user_signing_key")
                    })
                    return@post
                }
            }

            // Store the cross-signing keys
            transaction {
                // Store master key
                if (masterKey != null) {
                    val keyId = masterKey["key_id"]?.jsonPrimitive?.content!!
                    val publicKey = masterKey["public_key"]?.jsonPrimitive?.content!!
                    val signatures = masterKey["signatures"]?.toString()

                    CrossSigningKeys.insert {
                        it[CrossSigningKeys.userId] = userId
                        it[CrossSigningKeys.keyType] = "master"
                        it[CrossSigningKeys.keyId] = keyId
                        it[CrossSigningKeys.publicKey] = publicKey
                        it[CrossSigningKeys.signatures] = signatures
                        it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                    }
                }

                // Store self-signing key
                if (selfSigningKey != null) {
                    val keyId = selfSigningKey["key_id"]?.jsonPrimitive?.content!!
                    val publicKey = selfSigningKey["public_key"]?.jsonPrimitive?.content!!
                    val signatures = selfSigningKey["signatures"]?.toString()

                    CrossSigningKeys.insert {
                        it[CrossSigningKeys.userId] = userId
                        it[CrossSigningKeys.keyType] = "self_signing"
                        it[CrossSigningKeys.keyId] = keyId
                        it[CrossSigningKeys.publicKey] = publicKey
                        it[CrossSigningKeys.signatures] = signatures
                        it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                    }
                }

                // Store user-signing key
                if (userSigningKey != null) {
                    val keyId = userSigningKey["key_id"]?.jsonPrimitive?.content!!
                    val publicKey = userSigningKey["public_key"]?.jsonPrimitive?.content!!
                    val signatures = userSigningKey["signatures"]?.toString()

                    CrossSigningKeys.insert {
                        it[CrossSigningKeys.userId] = userId
                        it[CrossSigningKeys.keyType] = "user_signing"
                        it[CrossSigningKeys.keyId] = keyId
                        it[CrossSigningKeys.publicKey] = publicKey
                        it[CrossSigningKeys.signatures] = signatures
                        it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
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

    // GET /unstable/org.matrix.msc3814.v1/dehydrated_device - Get dehydrated device
    get("/unstable/org.matrix.msc3814.v1/dehydrated_device") {
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

    // PUT /unstable/org.matrix.msc3814.v1/dehydrated_device - Create/update dehydrated device
    put("/unstable/org.matrix.msc3814.v1/dehydrated_device") {
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

    // DELETE /unstable/org.matrix.msc3814.v1/dehydrated_device - Delete dehydrated device
    delete("/unstable/org.matrix.msc3814.v1/dehydrated_device") {
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
