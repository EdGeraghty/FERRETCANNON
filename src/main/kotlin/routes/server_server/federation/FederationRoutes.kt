package routes.server_server.federation

import io.ktor.server.application.*
import routes.server_server.federation.v1.federationV1Routes
import routes.server_server.federation.v2.federationV2Routes

fun Application.federationRoutes() {
    federationV1Routes()
    federationV2Routes()
}
