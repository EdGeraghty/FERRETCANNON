package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import utils.MatrixAuth
import utils.MediaStorage

fun Route.federationV1Media() {
    get("/media/download/{mediaId}") {
        val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            // Check if media exists
            if (!MediaStorage.mediaExists(mediaId)) {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Media not found"))
                return@get
            }

            // Get media content
            val (content, contentType) = MediaStorage.getMedia(mediaId)
            if (content == null || contentType == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to "Failed to retrieve media"))
                return@get
            }

            // Get metadata
            val metadata = MediaStorage.getMediaMetadata(mediaId)

            // Return multipart response as per spec
            val boundary = "boundary_${System.currentTimeMillis()}"

            // Create metadata part
            val metadataMap = mapOf(
                "content_uri" to "mxc://localhost/$mediaId",
                "content_type" to contentType,
                "content_length" to content.size
            )

            val metadataJson = Json.encodeToString(JsonObject.serializer(), JsonObject(metadataMap.mapValues { JsonPrimitive(it.value.toString()) }))

            // Create the multipart response
            val multipartContent = """
--$boundary
Content-Type: application/json

$metadataJson
--$boundary
Content-Type: $contentType

${String(content, Charsets.UTF_8)}
--$boundary--
            """.trimIndent()

            call.respondText(
                contentType = ContentType.MultiPart.Mixed.withParameter("boundary", boundary),
                text = multipartContent
            )

        } catch (e: Exception) {
            println("Media download error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
    get("/media/thumbnail/{mediaId}") {
        val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("errcode" to "M_UNAUTHORIZED", "error" to "Invalid signature"))
            return@get
        }

        try {
            // Parse query parameters
            val width = call.request.queryParameters["width"]?.toIntOrNull()
            val height = call.request.queryParameters["height"]?.toIntOrNull()
            val method = call.request.queryParameters["method"] ?: "scale"
            val animated = call.request.queryParameters["animated"]?.toBoolean() ?: false
            val timeoutMs = call.request.queryParameters["timeout_ms"]?.toLongOrNull() ?: 20000L

            // Validate required parameters
            if (width == null || height == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Missing width or height parameter"))
                return@get
            }

            // Validate method parameter
            if (method !in setOf("crop", "scale")) {
                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid method parameter"))
                return@get
            }

            // Validate dimensions
            if (width <= 0 || height <= 0 || width > 1000 || height > 1000) {
                call.respond(HttpStatusCode.BadRequest, mapOf("errcode" to "M_INVALID_PARAM", "error" to "Invalid dimensions"))
                return@get
            }

            // Check if media exists
            if (!MediaStorage.mediaExists(mediaId)) {
                call.respond(HttpStatusCode.NotFound, mapOf("errcode" to "M_NOT_FOUND", "error" to "Media not found"))
                return@get
            }

            // Generate thumbnail
            val thumbnailData = MediaStorage.generateThumbnail(mediaId, width, height, method)
            if (thumbnailData == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to "Failed to generate thumbnail"))
                return@get
            }

            // Return multipart response as per spec
            val boundary = "boundary_${System.currentTimeMillis()}"

            // Create metadata part
            val metadata = mapOf(
                "content_uri" to "mxc://localhost/$mediaId",
                "content_type" to "image/jpeg",
                "content_length" to thumbnailData.size,
                "width" to width,
                "height" to height,
                "method" to method,
                "animated" to animated
            )

            val metadataJson = Json.encodeToString(JsonObject.serializer(), JsonObject(metadata.mapValues { JsonPrimitive(it.value.toString()) }))

            // Create the multipart response
            val multipartContent = """
--$boundary
Content-Type: application/json

$metadataJson
--$boundary
Content-Type: image/jpeg

${String(thumbnailData, Charsets.UTF_8)}
--$boundary--
            """.trimIndent()

            call.respondText(
                contentType = ContentType.MultiPart.Mixed.withParameter("boundary", boundary),
                text = multipartContent
            )

        } catch (e: Exception) {
            println("Media thumbnail error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("errcode" to "M_UNKNOWN", "error" to e.message))
        }
    }
}
