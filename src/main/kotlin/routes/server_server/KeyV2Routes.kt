package routes.server_server

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.ServerKeys
import utils.ServerNameResolver
import java.time.Instant
import java.time.format.DateTimeFormatter

fun Route.keyV2Routes() {
    route("/_matrix/key/v2") {
        get("/server") {
            try {
                val serverName = ServerNameResolver.getServerName()
                val keyId = ServerKeys.getKeyId()
                val publicKey = ServerKeys.getPublicKey()
                val validUntilTs = Instant.now().plusSeconds(86400).epochSecond // 24 hours from now

                val response = JsonObject(mutableMapOf(
                    "server_name" to JsonPrimitive(serverName),
                    "valid_until_ts" to JsonPrimitive(validUntilTs),
                    "verify_keys" to JsonObject(mutableMapOf(
                        keyId to JsonObject(mutableMapOf(
                            "key" to JsonPrimitive(publicKey)
                        ))
                    )),
                    "old_verify_keys" to JsonObject(mutableMapOf())
                ))

                call.respondText(response.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_UNKNOWN"),
                        "error" to JsonPrimitive("Internal server error")
                    )).toString(),
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
                        JsonObject(mutableMapOf(
                            "errcode" to JsonPrimitive("M_NOT_FOUND"),
                            "error" to JsonPrimitive("Key not found")
                        )).toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                    return@get
                }

                val publicKey = ServerKeys.getPublicKey()
                val validUntilTs = Instant.now().plusSeconds(86400).epochSecond

                val response = JsonObject(mutableMapOf(
                    "server_name" to JsonPrimitive(serverName),
                    "valid_until_ts" to JsonPrimitive(validUntilTs),
                    "verify_keys" to JsonObject(mutableMapOf(
                        keyId to JsonObject(mutableMapOf(
                            "key" to JsonPrimitive(publicKey)
                        ))
                    )),
                    "old_verify_keys" to JsonObject(mutableMapOf())
                ))

                call.respondText(response.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    JsonObject(mutableMapOf(
                        "errcode" to JsonPrimitive("M_UNKNOWN"),
                        "error" to JsonPrimitive("Internal server error")
                    )).toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }
    }
}
