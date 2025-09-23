package routes.client_server.client

import io.ktor.server.routing.*

fun Route.eventRoutes() {
    // All event routes have been extracted to separate modular files:
    // - EventReceiptRoutes.kt for receipt endpoints
    // - EventReadMarkersRoutes.kt for read markers endpoints
    // - EventRedactionRoutes.kt for redaction endpoints
}
