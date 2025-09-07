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
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Check if media exists
            if (!MediaStorage.mediaExists(mediaId)) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Media not found")
                })
                return@get
            }

            // Get media content
            val (content, contentType) = MediaStorage.getMedia(mediaId)
            if (content == null || contentType == null) {
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("errcode", "M_UNKNOWN")
                    put("error", "Failed to retrieve media")
                })
                return@get
            }

            // Get metadata
            val metadata = MediaStorage.getMediaMetadata(mediaId)

            // Return multipart response as per spec
            val boundary = "boundary_${System.currentTimeMillis()}"

            // Create metadata part
            val metadataMap = buildJsonObject {
                put("content_uri", "mxc://localhost/$mediaId")
                put("content_type", contentType)
                put("content_length", content.size)
            }

            val metadataJson = Json.encodeToString(JsonObject.serializer(), metadataMap)

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
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
    get("/media/thumbnail/{mediaId}") {
        val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, "")) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
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
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing width or height parameter")
                })
                return@get
            }

            // Validate method parameter
            if (method !in setOf("crop", "scale")) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid method parameter")
                })
                return@get
            }

            // Validate dimensions
            if (width <= 0 || height <= 0 || width > 1000 || height > 1000) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Invalid dimensions")
                })
                return@get
            }

            // Check if media exists
            if (!MediaStorage.mediaExists(mediaId)) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Media not found")
                })
                return@get
            }

            // Generate thumbnail
            val thumbnailData = MediaStorage.generateThumbnail(mediaId, width, height, method)
            if (thumbnailData == null) {
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("errcode", "M_UNKNOWN")
                    put("error", "Failed to generate thumbnail")
                })
                return@get
            }

            // Return multipart response as per spec
            val boundary = "boundary_${System.currentTimeMillis()}"

            // Create metadata part
            val metadata = buildJsonObject {
                put("content_uri", "mxc://localhost/$mediaId")
                put("content_type", "image/jpeg")
                put("content_length", thumbnailData.size)
                put("width", width)
                put("height", height)
                put("method", method)
                put("animated", animated)
            }

            val metadataJson = Json.encodeToString(JsonObject.serializer(), metadata)

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
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message ?: "Unknown error")
            })
        }
    }
}
