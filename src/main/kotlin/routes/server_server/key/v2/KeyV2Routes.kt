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
import utils.ServerKeys
import utils.MatrixAuth
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("routes.server_server.key.v2.KeyV2Routes")

fun Application.keyV2Routes() {
    val client = HttpClient(CIO)

    routing {
        route("/_matrix") {
            route("/key") {
                route("/v2") {
                    get("/server") {
                        logger.trace("GET /_matrix/key/v2/server request")
                        val serverName = utils.ServerNameResolver.getServerName() // Dynamic server name resolution
                        val validUntilTs = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours

                        // Get the actual server key
                        val publicKeyBase64 = ServerKeys.getPublicKey()
                        val keyId = ServerKeys.getKeyId()

                        logger.debug("Serving server keys for server: $serverName, keyId: $keyId")
                        logger.trace("Server key response: server_name=$serverName, valid_until_ts=$validUntilTs")

                        // Create response manually to avoid serialization issues
                        val responseJson = kotlinx.serialization.json.JsonObject(mapOf(
                            "server_name" to kotlinx.serialization.json.JsonPrimitive(serverName),
                            "signatures" to kotlinx.serialization.json.JsonObject(mapOf(
                                serverName to kotlinx.serialization.json.JsonObject(mapOf(
                                    keyId to kotlinx.serialization.json.JsonPrimitive(ServerKeys.sign("$serverName valid_until_ts:$validUntilTs".toByteArray()))
                                ))
                            )),
                            "valid_until_ts" to kotlinx.serialization.json.JsonPrimitive(validUntilTs),
                            "verify_keys" to kotlinx.serialization.json.JsonObject(mapOf(
                                keyId to kotlinx.serialization.json.JsonObject(mapOf(
                                    "key" to kotlinx.serialization.json.JsonPrimitive(publicKeyBase64)
                                ))
                            ))
                        ))

                        call.respondText(
                            kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), responseJson),
                            io.ktor.http.ContentType.Application.Json
                        )
                    }
                    post("/query") {
                        // Key query endpoint is public - no authentication required per Matrix spec
                        logger.trace("POST /_matrix/key/v2/query request")
                        try {
                            val body = call.receiveText()
                            logger.debug("Key query request body length: ${body.length}")
                            val requestBody = Json.parseToJsonElement(body).jsonObject
                            val serverKeys = requestBody["server_keys"]?.jsonObject ?: JsonObject(emptyMap())

                            val response = mutableMapOf<String, Any>()

                            // Process server keys
                            val serverKeysResponse = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
                            for (serverName in serverKeys.keys) {
                                logger.debug("Processing key query for server: $serverName")
                                val requestedKeysJson = serverKeys[serverName]
                                val globalServerKeys = utils.serverKeys[serverName] ?: emptyMap<String, Map<String, Any?>>()
                                val serverKeyData = mutableMapOf<String, Map<String, Any?>>()

                                if (requestedKeysJson is JsonNull) {
                                    // Return all keys for this server
                                    logger.trace("Returning all keys for server: $serverName")
                                    for (keyId in globalServerKeys.keys) {
                                        val keyInfo = globalServerKeys[keyId]
                                        if (keyInfo != null) {
                                            @Suppress("UNCHECKED_CAST")
                                            serverKeyData[keyId] = keyInfo as Map<String, Any?>
                                        }
                                    }
                                } else if (requestedKeysJson is JsonObject) {
                                    val keyList = requestedKeysJson["key_ids"]?.jsonArray ?: JsonArray(emptyList())
                                    logger.trace("Returning specific keys for server $serverName: ${keyList.map { it.jsonPrimitive.content }}")
                                    // Return only requested keys
                                    for (keyElement in keyList) {
                                        val keyId = keyElement.jsonPrimitive.content
                                        val keyInfo = globalServerKeys[keyId]
                                        if (keyInfo != null) {
                                            @Suppress("UNCHECKED_CAST")
                                            serverKeyData[keyId] = keyInfo as Map<String, Any?>
                                        }
                                    }
                                }

                                if (serverKeyData.isNotEmpty()) {
                                    serverKeysResponse[serverName] = serverKeyData
                                }
                            }

                            if (serverKeysResponse.isNotEmpty()) {
                                response["server_keys"] = serverKeysResponse
                            }

                            logger.debug("Key query response contains ${serverKeysResponse.size} server keys")
                            call.respond(response)
                        } catch (e: Exception) {
                            logger.error("Key query error", e)
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
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
                            // Get server's keys from cache or fetch from remote server
                            val serverKeyData = utils.serverKeys[serverName]
                            if (serverKeyData == null) {
                                // Try to fetch from remote server
                                logger.debug("Server keys for $serverName not in cache, fetching from remote")
                                val fetchedKeys = fetchRemoteServerKeys(serverName, client)
                                if (fetchedKeys != null) {
                                    utils.serverKeys[serverName] = fetchedKeys
                                    logger.info("Successfully fetched and cached server keys for $serverName")
                                    call.respond(fetchedKeys)
                                } else {
                                    logger.warn("Failed to fetch server keys for $serverName")
                                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Server keys not found"))
                                }
                            } else {
                                logger.debug("Serving cached server keys for $serverName")
                                call.respond(serverKeyData)
                            }
                        } catch (e: Exception) {
                            logger.error("Query server keys error for $serverName", e)
                            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
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
        keyData["signatures"] = data["signatures"]?.jsonObject ?: emptyMap<String, Any>()

        logger.debug("Successfully parsed server keys for $serverName")
        keyData
    } catch (e: Exception) {
        logger.error("Failed to fetch keys from $serverName", e)
        null
    }
}
