package routes.server_server.federation.v2

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*

fun Application.federationV2Routes() {
    routing {
        route("/_matrix") {
            route("/federation") {
                route("/v2") {
                    put("/send_join/{roomId}/{eventId}") {
                        call.respond(HttpStatusCode.NotImplemented, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Endpoint not implemented"))
                    }
                    put("/invite/{roomId}/{eventId}") {
                        call.respond(HttpStatusCode.NotImplemented, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Endpoint not implemented"))
                    }
                    put("/send_leave/{roomId}/{eventId}") {
                        call.respond(HttpStatusCode.NotImplemented, mapOf("errcode" to "M_UNRECOGNIZED", "error" to "Endpoint not implemented"))
                    }
                }
            }
        }
    }
}
