package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import models.CrossSigningKeys
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*

fun Route.crossSigningRoutes(config: ServerConfig) {
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