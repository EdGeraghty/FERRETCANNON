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
                            val serverKeysRequested = requestBody["server_keys"]?.jsonObject ?: JsonObject(mutableMapOf())

                            val localServerName = utils.ServerNameResolver.getServerName()

                            val client = HttpClient(CIO)
                            try {
                                // First, collect responses per-server (we can call suspend functions here)
                                val perServerResponses = mutableMapOf<String, JsonElement>()
                                for (serverName in serverKeysRequested.keys) {
                                    logger.debug("Processing key query for server: $serverName")

                                    // If querying ourselves, return our canonical /server response
                                    if (serverName == localServerName) {
                                        perServerResponses[serverName] = ServerKeys.getServerKeys(serverName)
                                        continue
                                    }

                                    // Try to fetch remote server's published keys
                                    val remote = fetchRemoteServerKeys(serverName, client)
                                    if (remote != null) {
                                        perServerResponses[serverName] = remote
                                        continue
                                    }

                                    // Fall back to any locally stored key records (best-effort)
                                    val stored = ServerKeysStorage.getServerKeys(serverName)
                                    if (stored.isNotEmpty()) {
                                        val built = buildJsonObject {
                                            putJsonObject("verify_keys") {
                                                stored.forEach { keyData ->
                                                    val keyId = keyData["key_id"] as? String ?: return@forEach
                                                    val publicKey = keyData["public_key"] as? String ?: return@forEach
                                                    putJsonObject(keyId) {
                                                        put("key", publicKey)
                                                    }
                                                }
                                            }
                                            putJsonObject("old_verify_keys") { }
                                        }
                                        perServerResponses[serverName] = built
                                    }
                                }

                                // Now assemble final response without suspension
                                val response = buildJsonObject {
                                    putJsonObject("server_keys") {
                                        for ((serverName, json) in perServerResponses) {
                                            put(serverName, json)
                                        }
                                    }
                                }

                                logger.debug("Key query response built successfully")
                                call.respond(response)
                            } finally {
                                client.close()
                            }
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

                        val localServerName = utils.ServerNameResolver.getServerName()
                        try {
                            if (serverName == localServerName) {
                                // Return our canonical, signed server keys response
                                val serverKeysResponse = ServerKeys.getServerKeys(serverName)
                                call.respond(serverKeysResponse)
                                return@get
                            }

                            // Try to fetch remote server keys live
                            val client = HttpClient(CIO)
                            try {
                                val remote = fetchRemoteServerKeys(serverName, client)
                                if (remote != null) {
                                    call.respond(remote)
                                    return@get
                                }
                            } finally {
                                client.close()
                            }

                            // Fall back to locally stored keys if available
                            val serverKeyData = ServerKeysStorage.getServerKeys(serverName)
                            if (serverKeyData.isNotEmpty()) {
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
                                    putJsonObject("old_verify_keys") { }
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

suspend fun fetchRemoteServerKeys(serverName: String, client: HttpClient): JsonObject? {
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

        // Validate required fields are present
        val serverNameResponse = data["server_name"]?.jsonPrimitive?.content
        val verifyKeys = data["verify_keys"]?.jsonObject
        val validUntilTs = data["valid_until_ts"]?.jsonPrimitive?.long

        if (serverNameResponse == null || verifyKeys == null || validUntilTs == null) {
            logger.warn("Invalid server key response from $serverName - missing required fields")
            return null
        }

        logger.debug("Successfully parsed server keys for $serverName")
        data
    } catch (e: Exception) {
        logger.error("Failed to fetch keys from $serverName", e)
        null
    }
}
