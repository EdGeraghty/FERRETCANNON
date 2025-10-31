package routes.client_server.client

import io.ktor.server.auth.*

/**
 * Principal class for Matrix user authentication
 */
data class UserIdPrincipal(val name: String) : Principal
