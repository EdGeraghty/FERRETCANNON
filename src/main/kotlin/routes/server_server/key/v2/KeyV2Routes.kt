package routes.server_server.key.v2

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import utils.ServerKeysStorage
import utils.MatrixAuth
import utils.ServerKeys
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("routes.server_server.key.v2.KeyV2Routes")

fun Application.keyV2Routes() {

    routing {
        route("/_matrix") {
            route("/key") {
                route("/v2") {
                    get("/server") {
                        logger.trace("GET /_matrix/key/v2/server request")
                        val serverName = utils.ServerNameResolver.getServerName() // Dynamic server name resolution

                        logger.debug("Serving server keys for server: $serverName")

                        // Get the server keys response with proper signing
                        val serverKeysResponse = ServerKeys.getServerKeys(serverName)

                        call.respond(serverKeysResponse)
                    }
                    post("/query") {
                        // Key query endpoint is public - no authentication required per Matrix spec
                        logger.trace("POST /_matrix/key/v2/query request")
                        try {
                            val body = call.receiveText()
                            logger.debug("Key query request body length: ${body.length}")
                            val requestBody = Json.parseToJsonElement(body).jsonObject
                            val serverKeys = requestBody["server_keys"]?.jsonObject ?: JsonObject(mutableMapOf())

                            val response = buildJsonObject {
                                // Process server keys
                                putJsonObject("server_keys") {
                                    for (serverName in serverKeys.keys) {
                                        logger.debug("Processing key query for server: $serverName")
                                        val requestedKeysJson = serverKeys[serverName]
                                        val globalServerKeys = ServerKeysStorage.getServerKeys(serverName).associate { keyData ->
                                            // Convert the map to the expected format
                                            val keyId = keyData["key_id"] as? String ?: ""
                                            keyId to keyData
                                        }

                                        if (requestedKeysJson is JsonNull) {
                                            // Return all keys for this server
                                            logger.trace("Returning all keys for server: $serverName")
                                            if (globalServerKeys.isNotEmpty()) {
                                                putJsonObject(serverName) {
                                                    globalServerKeys.forEach { (keyId, keyInfo) ->
                                                        put(keyId, Json.encodeToJsonElement(keyInfo))
                                                    }
                                                }
                                            }
                                        } else if (requestedKeysJson is JsonObject) {
                                            val keyList = requestedKeysJson["key_ids"]?.jsonArray ?: JsonArray(emptyList())
                                            logger.trace("Returning specific keys for server $serverName: ${keyList.map { it.jsonPrimitive.content }}")
                                            // Return only requested keys
                                            val serverKeyData = mutableMapOf<String, Map<String, Any?>>()
                                            for (keyElement in keyList) {
                                                val keyId = keyElement.jsonPrimitive.content
                                                val keyInfo = globalServerKeys[keyId]
                                                if (keyInfo != null) {
                                                    serverKeyData[keyId] = keyInfo
                                                }
                                            }
                                            if (serverKeyData.isNotEmpty()) {
                                                putJsonObject(serverName) {
                                                    serverKeyData.forEach { (keyId, keyInfo) ->
                                                        put(keyId, Json.encodeToJsonElement(keyInfo))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            logger.debug("Key query response built successfully")
                            call.respond(response)
                        } catch (e: Exception) {
                            logger.error("Key query error", e)
                            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("errcode", "M_BAD_JSON")
                                put("error", "Invalid JSON")
                            })
                        }
                    }
                    get("/query/{serverName}") {
                        val serverName = call.parameters["serverName"]
                        if (serverName == null) {
                            logger.warn("GET /_matrix/key/v2/query/{serverName} - missing serverName parameter")
                            return@get call.respond(HttpStatusCode.BadRequest)
                        }

                        logger.trace("GET /_matrix/key/v2/query/$serverName request")

                        // Key query endpoint is public - no authentication required per Matrix spec
                        try {
                            // Get server's keys from database
                            val serverKeyData = ServerKeysStorage.getServerKeys(serverName)
                            if (serverKeyData.isNotEmpty()) {
                                // Convert to the expected response format
                                val response = buildJsonObject {
                                    put("server_name", serverName)
                                    put("valid_until_ts", System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L)) // 1 year
                                    putJsonObject("verify_keys") {
                                        serverKeyData.forEach { keyData ->
                                            val keyId = keyData["key_id"] as? String ?: ""
                                            val publicKey = keyData["public_key"] as? String ?: ""
                                            putJsonObject(keyId) {
                                                put("key", publicKey)
                                            }
                                        }
                                    }
                                    putJsonObject("signatures") {
                                        putJsonObject(serverName) {
                                            // Add signature if available
                                        }
                                    }
                                }
                                call.respond(response)
                            } else {
                                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                                    put("errcode", "M_NOT_FOUND")
                                    put("error", "Server keys not found")
                                })
                            }
                        } catch (e: Exception) {
                            logger.error("Query server keys error for $serverName", e)
                            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                put("errcode", "M_UNKNOWN")
                                put("error", e.message)
                            })
                        }
                    }
                }
            }
        }
    }
}

suspend fun fetchRemoteServerKeys(serverName: String, client: HttpClient): Map<String, Any?>? {
    return try {
        logger.debug("Fetching server keys from remote server: $serverName")
        val response = client.get("https://$serverName/_matrix/key/v2/server")
        
        // Check if the response is successful
        if (!response.status.isSuccess()) {
            logger.warn("Failed to fetch keys from $serverName: HTTP ${response.status}")
            return null
        }
        
        val json = response.body<String>()
        val data = Json.parseToJsonElement(json).jsonObject

        // Validate the response has required fields
        val serverNameResponse = data["server_name"]?.jsonPrimitive?.content
        val verifyKeys = data["verify_keys"]?.jsonObject
        val validUntilTs = data["valid_until_ts"]?.jsonPrimitive?.long

        if (serverNameResponse == null || verifyKeys == null || validUntilTs == null) {
            logger.warn("Invalid server key response from $serverName - missing required fields")
            return null
        }

        // Cache the keys
        val keyData = mutableMapOf<String, Any?>()
        keyData["server_name"] = serverNameResponse
        keyData["verify_keys"] = verifyKeys
        keyData["valid_until_ts"] = validUntilTs
        keyData["signatures"] = data["signatures"]?.jsonObject ?: mapOf<String, Any>()

        logger.debug("Successfully parsed server keys for $serverName")
        keyData
    } catch (e: Exception) {
        logger.error("Failed to fetch keys from $serverName", e)
        null
    }
}
