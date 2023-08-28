package routes.server_server.key

import io.ktor.server.application.*
import routes.server_server.key.v2.keyV2Routes

fun Application.keyRoutes() {
    keyV2Routes()
}
