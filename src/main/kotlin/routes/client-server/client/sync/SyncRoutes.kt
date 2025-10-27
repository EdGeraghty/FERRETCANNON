package routes.client_server.client.sync

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import utils.SyncManager
import utils.MatrixPagination
import config.ServerConfig
import routes.client_server.client.common.*

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("routes.client_server.client.sync.SyncRoutes")

fun Route.syncRoutes() {
    // GET /sync - Sync endpoint
    get("/sync") {
        try {
            logger.debug("SyncRoutes - /sync called")
            val accessToken = call.validateAccessToken()
            logger.debug("SyncRoutes - accessToken: $accessToken")

            if (accessToken == null) {
                logger.debug("SyncRoutes - accessToken is null, returning unauthorized")
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@get
            }

            // Get userId from attributes (set by authentication middleware)
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            if (userId == null) {
                logger.debug("SyncRoutes - userId is null")
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@get
            }

            // Parse query parameters
            val since = call.request.queryParameters["since"]
            val useStateAfter = call.request.queryParameters["use_state_after"]?.toBoolean() ?: false

            logger.debug("SyncRoutes - performing sync for user: $userId, useStateAfter: $useStateAfter")
            // Perform sync
            val syncResponse = try {
                SyncManager.performSync(
                    userId = userId,
                    since = since?.let { MatrixPagination.parseSyncToken(it) },
                    fullState = false,
                    useStateAfter = useStateAfter
                )
            } catch (e: Exception) {
                logger.error("SyncRoutes - Exception in SyncManager.performSync: ${e.message}", e)
                throw e
            }

            logger.debug("SyncRoutes - sync completed, responding")
            call.respond(syncResponse)

        } catch (e: Exception) {
            logger.error("SyncRoutes - Exception in /sync: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
}
