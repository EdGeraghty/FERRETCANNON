package routes.client_server.client.push

import io.ktor.server.routing.*
import routes.client_server.client.common.*

fun Route.pushRoutes() {
    pushRulesRoutes()
    pushersRoutes()
}
