package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.SyncManager
import utils.MatrixPagination
import config.ServerConfig
import routes.client_server.client.MATRIX_USER_ID_KEY

fun Route.syncRoutes(_config: ServerConfig) {
    // GET /sync - Sync endpoint
    get("/sync") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@get
            }

            // Parse query parameters
            val since = call.request.queryParameters["since"]
            val timeout = call.request.queryParameters["timeout"]?.toLongOrNull() ?: 0L
            val setPresence = call.request.queryParameters["set_presence"] ?: "online"
            val filter = call.request.queryParameters["filter"]

            // Perform sync
            val syncResponse = SyncManager.performSync(
                userId = userId,
                since = since?.let { MatrixPagination.parseSyncToken(it) },
                _timeout = timeout?.toLongOrNull() ?: 30000,
                _setPresence = setPresence,
                _filter = filter
            )

            call.respond(syncResponse)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
}
