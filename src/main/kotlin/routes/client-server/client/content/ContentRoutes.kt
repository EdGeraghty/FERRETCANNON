package routes.client_server.client.content

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.http.content.PartData
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.MediaStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import models.Media
import utils.AuthUtils
import routes.client_server.client.common.*

fun Route.contentRoutes(config: ServerConfig) {
    // Initialize MediaStorage with configuration
    MediaStorage.initialize(config.media.maxUploadSize, 320)
    // POST /upload - Upload content
    post("/upload") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Handle multipart upload
            val multipart = call.receiveMultipart()
            var fileName: String? = null
            var contentType: String? = null
            var fileBytes: ByteArray? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileName = part.originalFileName
                        contentType = part.contentType?.toString() ?: "application/octet-stream"
                        fileBytes = part.streamProvider().readBytes()
                    }
                    is PartData.FormItem -> {
                        // Handle form fields if needed
                    }
                    else -> {
                        // Ignore other part types
                    }
                }
                part.dispose()
            }

            if (fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_BAD_JSON")
                    put("error", "No file uploaded")
                })
                return@post
            }

            // Validate file size
            if (fileBytes!!.size > config.media.maxUploadSize) {
                call.respond(HttpStatusCode.PayloadTooLarge, buildJsonObject {
                    put("errcode", "M_TOO_LARGE")
                    put("error", "File too large")
                })
                return@post
            }

            // Generate media ID
            val mediaId = AuthUtils.generateMediaId()
            val contentUri = "mxc://${config.federation.serverName}/$mediaId"

            // Store file using MediaStorage
            val success = runBlocking {
                MediaStorage.storeMedia(mediaId, fileBytes!!, contentType!!, fileName, userId)
            }

            if (!success) {
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("errcode", "M_UNKNOWN")
                    put("error", "Failed to store media")
                })
                return@post
            }

            call.respond(buildJsonObject {
                put("content_uri", contentUri)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /download/{serverName}/{mediaId} - Download content
    get("/download/{serverName}/{mediaId}") {
        try {
            val serverName = call.parameters["serverName"]
            val mediaId = call.parameters["mediaId"]

            if (serverName == null || mediaId == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing serverName or mediaId parameter")
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

            // Retrieve file from MediaStorage
            val (content, contentType) = runBlocking {
                MediaStorage.getMedia(mediaId)
            }

            if (content == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Media not found")
                })
                return@get
            }

            // Set appropriate headers
            call.response.headers.apply {
                append(HttpHeaders.ContentType, contentType ?: "application/octet-stream")
                append(HttpHeaders.ContentLength, content.size.toString())
                append(HttpHeaders.CacheControl, "public, max-age=31536000") // Cache for 1 year
            }

            call.respondBytes(content)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /thumbnail/{serverName}/{mediaId} - Get thumbnail
    get("/thumbnail/{serverName}/{mediaId}") {
        try {
            val serverName = call.parameters["serverName"]
            val mediaId = call.parameters["mediaId"]
            val width = call.request.queryParameters["width"]?.toIntOrNull() ?: 320
            val height = call.request.queryParameters["height"]?.toIntOrNull() ?: 240
            val method = call.request.queryParameters["method"] ?: "scale"

            if (serverName == null || mediaId == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing serverName or mediaId parameter")
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

            // Generate or retrieve thumbnail from MediaStorage
            val thumbnailData = runBlocking {
                MediaStorage.generateThumbnail(mediaId, width, height, method)
            }

            if (thumbnailData == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Thumbnail not found or cannot be generated")
                })
                return@get
            }

            // Set appropriate headers
            call.response.headers.apply {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentLength, thumbnailData.size.toString())
                append(HttpHeaders.CacheControl, "public, max-age=31536000") // Cache for 1 year
            }

            call.respondBytes(thumbnailData)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /config - Get upload configuration
    get("/config") {
        try {
            call.validateAccessToken() ?: return@get

            call.respond(buildJsonObject {
                put("m.upload.size", buildJsonObject {
                    put("max", JsonPrimitive(config.media.maxUploadSize.toString()))
                })
                put("m.thumbnail.sizes", buildJsonArray {
                    config.media.thumbnailSizes.forEach { size ->
                        add(buildJsonObject {
                            put("width", size.width)
                            put("height", size.height)
                            put("method", size.method)
                        })
                    }
                })
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
    get("/voip/turnServer") {
        try {
            val accessToken = call.validateAccessToken()

            if (accessToken == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_TOKEN")
                    put("error", "Missing access token")
                })
                return@get
            }

            // Return TURN server configuration
            // In a real implementation, this would return actual TURN server credentials
            // For now, return an empty response indicating no TURN servers configured
            call.respondText("""{"username":"","password":"","uris":[],"ttl":86400}""", ContentType.Application.Json)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
}
