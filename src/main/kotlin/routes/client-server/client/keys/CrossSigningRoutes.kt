package routes.client_server.client.keys

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.CrossSigningKeys
import models.KeySignatures
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import routes.client_server.client.common.*
import org.slf4j.LoggerFactory
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

private val logger = LoggerFactory.getLogger("routes.CrossSigningRoutes")

fun Route.crossSigningRoutes() {
    // GET /keys/device_signing - Get cross-signing keys
    get("/keys/device_signing") {
        try {
            logger.debug("CrossSigningRoutes - GET /keys/device_signing called")
            val userId = call.validateAccessToken() ?: return@get
            logger.debug("CrossSigningRoutes - userId: $userId")

            // Retrieve the user's cross-signing keys
            val crossSigningKeys = transaction {
                CrossSigningKeys.select { CrossSigningKeys.userId eq userId }
                    .associate { row ->
                        val keyType = row[CrossSigningKeys.keyType]
                        val keyId = row[CrossSigningKeys.keyId]
                        val publicKey = row[CrossSigningKeys.publicKey]
                        val signatures = row[CrossSigningKeys.signatures]?.let { Json.parseToJsonElement(it) }

                        keyType to buildJsonObject {
                            put("key_id", keyId)
                            put("public_key", publicKey)
                            if (signatures != null) {
                                put("signatures", signatures)
                            }
                        }
                    }
            }

            // Build response
            val response = buildJsonObject {
                crossSigningKeys["master"]?.let { put("master_key", it) }
                crossSigningKeys["self_signing"]?.let { put("self_signing_key", it) }
                crossSigningKeys["user_signing"]?.let { put("user_signing_key", it) }
            }

            logger.debug("CrossSigningRoutes - returning keys: ${response.keys}")
            call.respond(response)

        } catch (e: Exception) {
            logger.error("CrossSigningRoutes - GET error", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // POST /keys/device_signing/upload - Upload cross-signing keys
    post("/keys/device_signing/upload") {
        try {
            logger.debug("CrossSigningRoutes - POST /keys/device_signing/upload called")
            val userId = call.validateAccessToken() ?: return@post
            logger.debug("CrossSigningRoutes - userId: $userId")

            // Parse request body
            val requestBody = call.receiveText()
            logger.debug("CrossSigningRoutes - request body length: ${requestBody.length}")
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            val masterKey = jsonBody["master_key"]?.jsonObject
            val selfSigningKey = jsonBody["self_signing_key"]?.jsonObject
            val userSigningKey = jsonBody["user_signing_key"]?.jsonObject

            logger.debug("CrossSigningRoutes - masterKey: ${masterKey != null}, selfSigning: ${selfSigningKey != null}, userSigning: ${userSigningKey != null}")

            // Validate keys before storing
            if (masterKey != null) {
                val keyId = masterKey["key_id"]?.jsonPrimitive?.content
                val publicKey = masterKey["public_key"]?.jsonPrimitive?.content
                if (keyId == null || publicKey == null) {
                    logger.warn("CrossSigningRoutes - missing key_id or public_key for master_key")
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
                    logger.warn("CrossSigningRoutes - missing key_id or public_key for self_signing_key")
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
                    logger.warn("CrossSigningRoutes - missing key_id or public_key for user_signing_key")
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("errcode", "M_INVALID_PARAM")
                        put("error", "Missing key_id or public_key for user_signing_key")
                    })
                    return@post
                }
            }

            // Store the cross-signing keys
            transaction {
                logger.debug("CrossSigningRoutes - storing keys in transaction")
                // Store master key
                if (masterKey != null) {
                    val keyId = masterKey["key_id"]?.jsonPrimitive?.content!!
                    val publicKey = masterKey["public_key"]?.jsonPrimitive?.content!!
                    val signatures = masterKey["signatures"]?.toString()

                    CrossSigningKeys.replace {
                        it[CrossSigningKeys.userId] = userId
                        it[CrossSigningKeys.keyType] = "master"
                        it[CrossSigningKeys.keyId] = keyId
                        it[CrossSigningKeys.publicKey] = publicKey
                        it[CrossSigningKeys.signatures] = signatures
                        it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                    }
                    logger.debug("CrossSigningRoutes - stored master key")
                }

                // Store self-signing key
                if (selfSigningKey != null) {
                    val keyId = selfSigningKey["key_id"]?.jsonPrimitive?.content!!
                    val publicKey = selfSigningKey["public_key"]?.jsonPrimitive?.content!!
                    val signatures = selfSigningKey["signatures"]?.toString()

                    CrossSigningKeys.replace {
                        it[CrossSigningKeys.userId] = userId
                        it[CrossSigningKeys.keyType] = "self_signing"
                        it[CrossSigningKeys.keyId] = keyId
                        it[CrossSigningKeys.publicKey] = publicKey
                        it[CrossSigningKeys.signatures] = signatures
                        it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                    }
                    logger.debug("CrossSigningRoutes - stored self_signing key")
                }

                // Store user-signing key
                if (userSigningKey != null) {
                    val keyId = userSigningKey["key_id"]?.jsonPrimitive?.content!!
                    val publicKey = userSigningKey["public_key"]?.jsonPrimitive?.content!!
                    val signatures = userSigningKey["signatures"]?.toString()

                    CrossSigningKeys.replace {
                        it[CrossSigningKeys.userId] = userId
                        it[CrossSigningKeys.keyType] = "user_signing"
                        it[CrossSigningKeys.keyId] = keyId
                        it[CrossSigningKeys.publicKey] = publicKey
                        it[CrossSigningKeys.signatures] = signatures
                        it[CrossSigningKeys.lastModified] = System.currentTimeMillis()
                    }
                    logger.debug("CrossSigningRoutes - stored user_signing key")
                }
            }

            logger.debug("CrossSigningRoutes - upload successful")
            // Return success response
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            logger.error("CrossSigningRoutes - POST upload error", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // POST /keys/signatures/upload - Upload signatures for cross-signing keys and device keys
    post("/keys/signatures/upload") {
        try {
            logger.debug("CrossSigningRoutes - POST /keys/signatures/upload called")
            val signerUserId = call.validateAccessToken() ?: return@post
            logger.debug("CrossSigningRoutes - signerUserId: $signerUserId")

            // Parse request body
            val requestBody = call.receiveText()
            logger.debug("CrossSigningRoutes - signatures request body length: ${requestBody.length}")
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            logger.debug("CrossSigningRoutes - target users: ${jsonBody.keys}")

            // Process signatures for each target user
            transaction {
                logger.debug("CrossSigningRoutes - storing signatures in transaction")
                for (targetUserId in jsonBody.keys) {
                    val userSignatures = jsonBody[targetUserId]?.jsonObject ?: continue

                    // Device key signatures
                    val deviceKeys = userSignatures["device_keys"]?.jsonObject
                    if (deviceKeys != null) {
                        for (deviceId in deviceKeys.keys) {
                            val deviceSignatures = deviceKeys[deviceId]?.jsonObject?.get("signatures")?.toString()
                            if (deviceSignatures != null) {
                                KeySignatures.replace {
                                    it[KeySignatures.signerUserId] = signerUserId
                                    it[KeySignatures.targetUserId] = targetUserId
                                    it[KeySignatures.targetKeyId] = deviceId
                                    it[KeySignatures.targetKeyType] = "device"
                                    it[KeySignatures.signatures] = deviceSignatures
                                }
                                logger.debug("CrossSigningRoutes - stored device signature for $targetUserId:$deviceId")
                            }
                        }
                    }

                    // Cross-signing key signatures
                    val crossSigningKeys = userSignatures["cross_signing_keys"]?.jsonObject
                    if (crossSigningKeys != null) {
                        for (keyType in crossSigningKeys.keys) {
                            val keySignatures = crossSigningKeys[keyType]?.jsonObject?.get("signatures")?.toString()
                            if (keySignatures != null) {
                                KeySignatures.replace {
                                    it[KeySignatures.signerUserId] = signerUserId
                                    it[KeySignatures.targetUserId] = targetUserId
                                    it[KeySignatures.targetKeyId] = keyType
                                    it[KeySignatures.targetKeyType] = "cross_signing"
                                    it[KeySignatures.signatures] = keySignatures
                                }
                                logger.debug("CrossSigningRoutes - stored cross_signing signature for $targetUserId:$keyType")
                            }
                        }
                    }
                }
            }

            logger.debug("CrossSigningRoutes - signatures upload successful")
            // Return success response
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            logger.error("CrossSigningRoutes - POST signatures error", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /keys/signatures/upload - Get signatures
    get("/keys/signatures/upload") {
        try {
            logger.debug("CrossSigningRoutes - GET /keys/signatures/upload called")
            val userId = call.validateAccessToken() ?: return@get
            logger.debug("CrossSigningRoutes - userId: $userId")

            // Retrieve signatures uploaded by this user
            val signatures = transaction {
                KeySignatures.select { KeySignatures.signerUserId eq userId }
                    .associate { row ->
                        val targetUserId = row[KeySignatures.targetUserId]
                        val targetKeyId = row[KeySignatures.targetKeyId]
                        val targetKeyType = row[KeySignatures.targetKeyType]
                        val sigs = Json.parseToJsonElement(row[KeySignatures.signatures])

                        mapOf(
                            "targetUserId" to targetUserId,
                            "targetKeyId" to targetKeyId,
                            "targetKeyType" to targetKeyType
                        ) to sigs
                    }
            }

            // Build response structure
            val response = buildJsonObject {
                signatures.keys.groupBy { it["targetUserId"] }.forEach { (targetUserId, userSigs) ->
                    putJsonObject(targetUserId as String) {
                        userSigs.groupBy { it["targetKeyType"] }.forEach { (keyType, typeSigs) ->
                            if (keyType == "device") {
                                putJsonObject("device_keys") {
                                    typeSigs.forEach { map ->
                                        put(map["targetKeyId"] as String, buildJsonObject {
                                            put("signatures", signatures[map]!!)
                                        })
                                    }
                                }
                            } else if (keyType == "cross_signing") {
                                putJsonObject("cross_signing_keys") {
                                    typeSigs.forEach { map ->
                                        put(map["targetKeyId"] as String, buildJsonObject {
                                            put("signatures", signatures[map]!!)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            logger.debug("CrossSigningRoutes - returning signatures")
            call.respond(response)

        } catch (e: Exception) {
            logger.error("CrossSigningRoutes - GET signatures error", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // DELETE /keys/device_signing/{keyId} - Delete a cross-signing key
    delete("/keys/device_signing/{keyId}") {
        try {
            logger.debug("CrossSigningRoutes - DELETE /keys/device_signing/{keyId} called")
            val userId = call.validateAccessToken() ?: return@delete
            val keyId = call.parameters["keyId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing keyId")
            })
            logger.debug("CrossSigningRoutes - userId: $userId, keyId: $keyId")

            // Delete the specified cross-signing key
            val deletedCount = transaction {
                CrossSigningKeys.deleteWhere {
                    (CrossSigningKeys.userId eq userId) and (CrossSigningKeys.keyId eq keyId)
                }
            }

            if (deletedCount == 0) {
                logger.warn("CrossSigningRoutes - key not found for deletion: $keyId")
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Key not found")
                })
                return@delete
            }

            logger.debug("CrossSigningRoutes - deleted key: $keyId")
            // Return success response
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            logger.error("CrossSigningRoutes - DELETE error", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
}