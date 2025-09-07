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

fun Route.syncRoutes(config: ServerConfig) {
    // GET /sync - Sync endpoint
    get("/sync") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
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
                timeout = timeout,
                setPresence = setPresence,
                filter = filter
            )

            // Convert SyncResponse to Map for JSON response
            val responseMap = mapOf(
                "next_batch" to syncResponse.nextBatch,
                "rooms" to syncResponse.rooms,
                "presence" to syncResponse.presence,
                "account_data" to syncResponse.accountData,
                "device_lists" to syncResponse.deviceLists,
                "device_one_time_keys_count" to syncResponse.deviceOneTimeKeysCount,
                "to_device" to syncResponse.toDevice
            ).filterValues { it != null }

            call.respond(responseMap)

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error: ${e.message}"
            ))
        }
    }
}
