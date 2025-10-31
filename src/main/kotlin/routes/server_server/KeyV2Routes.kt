package routes.server_server

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.ServerKeys
import utils.ServerNameResolver
import utils.MatrixAuth
import java.time.Instant

fun Route.keyV2Routes() {
    route("/_matrix/key/v2") {
        get("/server") {
            try {
                val serverName = ServerNameResolver.getServerName()
                val keyId = ServerKeys.getKeyId()
                val publicKey = ServerKeys.getPublicKey()
                val validUntilTs = Instant.now().plusSeconds(86400).epochSecond // 24 hours from now

                // Build the response without signatures first
                val responseWithoutSigs = buildJsonObject {
                    put("server_name", serverName)
                    put("valid_until_ts", validUntilTs)
                    put("verify_keys", buildJsonObject {
                        put(keyId, buildJsonObject {
                            put("key", publicKey)
                        })
                    })
                    put("old_verify_keys", buildJsonObject { })
                }

                // Sign the response
                val signature = MatrixAuth.signJson(responseWithoutSigs)

                // Add signatures to the response
                val response = buildJsonObject {
                    put("server_name", serverName)
                    put("valid_until_ts", validUntilTs)
                    put("verify_keys", buildJsonObject {
                        put(keyId, buildJsonObject {
                            put("key", publicKey)
                        })
                    })
                    put("old_verify_keys", buildJsonObject { })
                    put("signatures", buildJsonObject {
                        put(serverName, buildJsonObject {
                            put(keyId, signature)
                        })
                    })
                }

                call.respondText(response.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    buildJsonObject {
                        put("errcode", "M_UNKNOWN")
                        put("error", "Internal server error")
                    }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        get("/server/{keyId}") {
            try {
                val requestedKeyId = call.parameters["keyId"]
                val serverName = ServerNameResolver.getServerName()
                val keyId = ServerKeys.getKeyId()

                if (requestedKeyId != keyId) {
                    call.respondText(
                        buildJsonObject {
                            put("errcode", "M_NOT_FOUND")
                            put("error", "Key not found")
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                    return@get
                }

                val publicKey = ServerKeys.getPublicKey()
                val validUntilTs = Instant.now().plusSeconds(86400).epochSecond

                // Build the response without signatures first
                val responseWithoutSigs = buildJsonObject {
                    put("server_name", serverName)
                    put("valid_until_ts", validUntilTs)
                    put("verify_keys", buildJsonObject {
                        put(keyId, buildJsonObject {
                            put("key", publicKey)
                        })
                    })
                    put("old_verify_keys", buildJsonObject { })
                }

                // Sign the response
                val signature = MatrixAuth.signJson(responseWithoutSigs)

                // Add signatures to the response
                val response = buildJsonObject {
                    put("server_name", serverName)
                    put("valid_until_ts", validUntilTs)
                    put("verify_keys", buildJsonObject {
                        put(keyId, buildJsonObject {
                            put("key", publicKey)
                        })
                    })
                    put("old_verify_keys", buildJsonObject { })
                    put("signatures", buildJsonObject {
                        put(serverName, buildJsonObject {
                            put(keyId, signature)
                        })
                    })
                }

                call.respondText(response.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    buildJsonObject {
                        put("errcode", "M_UNKNOWN")
                        put("error", "Internal server error")
                    }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }
    }
}
