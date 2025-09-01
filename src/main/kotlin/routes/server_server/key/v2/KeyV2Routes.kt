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

fun Application.keyV2Routes() {
    val client = HttpClient(CIO)

    routing {
        route("/_matrix") {
            route("/key") {
                route("/v2") {
                    get("/server") {
                        val serverName = utils.ServerNameResolver.getServerName() // Dynamic server name resolution
                        val validUntilTs = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours

                        // Get the actual server key
                        val publicKeyBase64 = ServerKeys.getPublicKey()
                        val keyId = ServerKeys.getKeyId()

                        call.respond(mapOf(
                            "server_name" to serverName,
                            "signatures" to mapOf(
                                serverName to mapOf(
                                    keyId to ServerKeys.sign("$serverName valid_until_ts:$validUntilTs".toByteArray())
                                )
                            ),
                            "valid_until_ts" to validUntilTs,
                            "verify_keys" to mapOf(
                                keyId to mapOf("key" to publicKeyBase64)
                            )
                        ))
                    }
                    post("/query") {
                        // Authenticate the request
                        val body = call.receiveText()
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@post
                        }

                        try {
                            val requestBody = Json.parseToJsonElement(body).jsonObject
                            val serverKeys = requestBody["server_keys"]?.jsonObject ?: JsonObject(emptyMap())

                            val response = mutableMapOf<String, Any>()

                            // Process server keys
                            val serverKeysResponse = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
                            for (serverName in serverKeys.keys) {
                                val requestedKeysJson = serverKeys[serverName]
                                val globalServerKeys = utils.serverKeys[serverName] ?: emptyMap<String, Map<String, Any?>>()
                                val serverKeyData = mutableMapOf<String, Map<String, Any?>>()

                                if (requestedKeysJson is JsonNull) {
                                    // Return all keys for this server
                                    for (keyId in globalServerKeys.keys) {
                                        val keyInfo = globalServerKeys[keyId]
                                        if (keyInfo != null) {
                                            @Suppress("UNCHECKED_CAST")
                                            serverKeyData[keyId] = keyInfo as Map<String, Any?>
                                        }
                                    }
                                } else if (requestedKeysJson is JsonObject) {
                                    val keyList = requestedKeysJson["key_ids"]?.jsonArray ?: JsonArray(emptyList())
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

                            call.respond(response)
                        } catch (e: Exception) {
                            println("Query keys error: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_BAD_JSON", "error" to "Invalid JSON"))
                        }
                    }
                    get("/query/{serverName}") {
                        val serverName = call.parameters["serverName"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Authenticate the request
                        val authHeader = call.request.headers["Authorization"]
                        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
                            return@get
                        }

                        try {
                            // Get server's keys from cache or fetch from remote server
                            val serverKeyData = utils.serverKeys[serverName]
                            if (serverKeyData == null) {
                                // Try to fetch from remote server
                                val fetchedKeys = fetchRemoteServerKeys(serverName, client)
                                if (fetchedKeys != null) {
                                    utils.serverKeys[serverName] = fetchedKeys
                                    call.respond(fetchedKeys)
                                } else {
                                    call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Server keys not found"))
                                }
                            } else {
                                call.respond(serverKeyData)
                            }
                        } catch (e: Exception) {
                            println("Query server keys error: ${e.message}")
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
        val response = client.get("https://$serverName/_matrix/key/v2/server")
        val json = response.body<String>()
        val data = Json.parseToJsonElement(json).jsonObject

        // Validate the response has required fields
        val serverNameResponse = data["server_name"]?.jsonPrimitive?.content
        val verifyKeys = data["verify_keys"]?.jsonObject
        val validUntilTs = data["valid_until_ts"]?.jsonPrimitive?.long

        if (serverNameResponse == null || verifyKeys == null || validUntilTs == null) {
            return null
        }

        // Cache the keys
        val keyData = mutableMapOf<String, Any?>()
        keyData["server_name"] = serverNameResponse
        keyData["verify_keys"] = verifyKeys
        keyData["valid_until_ts"] = validUntilTs
        keyData["signatures"] = data["signatures"]?.jsonObject ?: emptyMap<String, Any>()

        keyData
    } catch (e: Exception) {
        println("Failed to fetch keys from $serverName: ${e.message}")
        null
    }
}
