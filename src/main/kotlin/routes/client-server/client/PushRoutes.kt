package routes.client_server.client

import io.ktor.server.routing.*

fun Route.pushRoutes() {
    pushRulesRoutes()
    pushersRoutes()
}
