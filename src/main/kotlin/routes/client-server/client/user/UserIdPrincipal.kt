package routes.client_server.client.user

import io.ktor.server.auth.*
import routes.client_server.client.common.*

/**
 * Principal class for Matrix user authentication
 */
data class UserIdPrincipal(val name: String) : Principal
