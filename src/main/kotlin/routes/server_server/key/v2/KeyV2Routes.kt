package routes.server_server.key.v2

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.HttpStatusCode

fun Application.keyV2Routes() {
    routing {
        route("/_matrix") {
            route("/key") {
                route("/v2") {
                    get("/server") {
                        call.respond(mapOf(
                            "server_name" to "localhost",
                            "signatures" to emptyMap<String, Any>(),
                            "valid_until_ts" to System.currentTimeMillis() + 86400000,
                            "verify_keys" to mapOf(
                                "ed25519:key1" to mapOf("key" to "placeholder_key")
                            )
                        ))
                    }
                    post("/query") {
                        call.respond(HttpStatusCode.NotImplemented, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Endpoint not implemented"))
                    }
                    get("/query/{serverName}") {
                        call.respond(HttpStatusCode.NotImplemented, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Endpoint not implemented"))
                    }
                }
            }
        }
    }
}
