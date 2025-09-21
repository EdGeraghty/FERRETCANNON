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
import models.RoomKeyVersions
import models.RoomKeys
import models.OneTimeKeys
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

    // GET /room_keys/version - Get room keys version
    get("/room_keys/version") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Get the current backup version for this user
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion != null) {
                val version = backupVersion[RoomKeyVersions.version]
                call.respond(buildJsonObject {
                    put("version", version)
                })
            } else {
                // No backup exists
                call.respond(HttpStatusCode.NotFound)
            }

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // PUT /room_keys/keys - Upload room keys
    put("/room_keys/keys") {
        try {
            val userId = call.validateAccessToken() ?: return@put

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            val version = jsonBody["version"]?.jsonPrimitive?.content
            val rooms = jsonBody["rooms"]?.jsonObject

            if (version == null || rooms == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing version or rooms")
                })
                return@put
            }

            // Store the backup version
            transaction {
                RoomKeyVersions.replace {
                    it[RoomKeyVersions.userId] = userId
                    it[RoomKeyVersions.version] = version
                    it[RoomKeyVersions.lastModified] = System.currentTimeMillis()
                }

                // Store the room keys
                for (roomId in rooms.keys) {
                    val roomSessions = rooms[roomId]?.jsonObject ?: continue
                    val sessions = roomSessions["sessions"]?.jsonObject ?: continue

                    for (sessionId in sessions.keys) {
                        val sessionData = sessions[sessionId]?.jsonObject?.toString() ?: continue

                        RoomKeys.replace {
                            it[RoomKeys.userId] = userId
                            it[RoomKeys.version] = version
                            it[RoomKeys.roomId] = roomId
                            it[RoomKeys.sessionId] = sessionId
                            it[RoomKeys.keyData] = sessionData
                            it[RoomKeys.lastModified] = System.currentTimeMillis()
                        }
                    }
                }
            }

            // Return success
            call.respond(buildJsonObject {
                put("count", rooms.size)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /room_keys/keys - Get room keys
    get("/room_keys/keys") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@get
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Get all room keys for this version
            val roomKeys = transaction {
                RoomKeys.select { (RoomKeys.userId eq userId) and (RoomKeys.version eq version) }
                    .groupBy { it[RoomKeys.roomId] }
                    .mapValues { (_, keys) ->
                        buildJsonObject {
                            putJsonObject("sessions") {
                                keys.forEach { key ->
                                    put(key[RoomKeys.sessionId], Json.parseToJsonElement(key[RoomKeys.keyData]))
                                }
                            }
                        }
                    }
            }

            call.respond(buildJsonObject {
                put("rooms", Json.encodeToJsonElement(roomKeys))
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /room_keys/keys/{roomId} - Get keys for room
    get("/room_keys/keys/{roomId}") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val roomId = call.parameters["roomId"] ?: return@get

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@get
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Get room keys for this room
            val roomKeys = transaction {
                RoomKeys.select { (RoomKeys.userId eq userId) and (RoomKeys.version eq version) and (RoomKeys.roomId eq roomId) }
                    .associate { key ->
                        key[RoomKeys.sessionId] to Json.parseToJsonElement(key[RoomKeys.keyData])
                    }
            }

            if (roomKeys.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No keys found for room")
                })
                return@get
            }

            call.respond(buildJsonObject {
                putJsonObject("sessions") {
                    roomKeys.forEach { (sessionId, data) ->
                        put(sessionId, data)
                    }
                }
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // PUT /room_keys/keys/{roomId}/{sessionId} - Upload key for session
    put("/room_keys/keys/{roomId}/{sessionId}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"] ?: return@put
            val sessionId = call.parameters["sessionId"] ?: return@put

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@put
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Parse request body (the key data)
            val requestBody = call.receiveText()
            val keyData = Json.parseToJsonElement(requestBody)

            // Store the key
            transaction {
                RoomKeys.replace {
                    it[RoomKeys.userId] = userId
                    it[RoomKeys.version] = version
                    it[RoomKeys.roomId] = roomId
                    it[RoomKeys.sessionId] = sessionId
                    it[RoomKeys.keyData] = keyData.toString()
                    it[RoomKeys.lastModified] = System.currentTimeMillis()
                }
            }

            // Return success
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /room_keys/keys/{roomId}/{sessionId} - Get key for session
    get("/room_keys/keys/{roomId}/{sessionId}") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val roomId = call.parameters["roomId"] ?: return@get
            val sessionId = call.parameters["sessionId"] ?: return@get

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@get
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Get the key
            val key = transaction {
                RoomKeys.select { (RoomKeys.userId eq userId) and (RoomKeys.version eq version) and (RoomKeys.roomId eq roomId) and (RoomKeys.sessionId eq sessionId) }
                    .singleOrNull()
            }

            if (key == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Key not found")
                })
                return@get
            }

            call.respond(Json.parseToJsonElement(key[RoomKeys.keyData]))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // DELETE /room_keys/keys/{roomId}/{sessionId} - Delete key
    delete("/room_keys/keys/{roomId}/{sessionId}") {
        try {
            val userId = call.validateAccessToken() ?: return@delete
            val roomId = call.parameters["roomId"] ?: return@delete
            val sessionId = call.parameters["sessionId"] ?: return@delete

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@delete
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Delete the key
            transaction {
                RoomKeys.deleteWhere { (RoomKeys.userId eq userId) and (RoomKeys.version eq version) and (RoomKeys.roomId eq roomId) and (RoomKeys.sessionId eq sessionId) }
            }

            // Return success
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // DELETE /room_keys/keys/{roomId} - Delete all keys for room
    delete("/room_keys/keys/{roomId}") {
        try {
            val userId = call.validateAccessToken() ?: return@delete
            val roomId = call.parameters["roomId"] ?: return@delete

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@delete
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Delete all keys for the room
            transaction {
                RoomKeys.deleteWhere { (RoomKeys.userId eq userId) and (RoomKeys.version eq version) and (RoomKeys.roomId eq roomId) }
            }

            // Return success
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // DELETE /room_keys/keys - Delete all keys
    delete("/room_keys/keys") {
        try {
            val userId = call.validateAccessToken() ?: return@delete

            // Get the current backup version
            val backupVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }.singleOrNull()
            }

            if (backupVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No backup found")
                })
                return@delete
            }

            val version = backupVersion[RoomKeyVersions.version]

            // Delete all keys for this version
            transaction {
                RoomKeys.deleteWhere { (RoomKeys.userId eq userId) and (RoomKeys.version eq version) }
            }

            // Return success
            call.respond(buildJsonObject { })

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
}
