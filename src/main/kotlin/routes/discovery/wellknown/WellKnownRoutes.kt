package routes.discovery.wellknown

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*

fun Application.wellKnownRoutes() {
    routing {
        route("/.well-known") {
            route("/matrix") {
                get("/server") {
                    call.respond(mapOf("m.server" to "localhost:8080"))
                }
            }
        }
    }
}
