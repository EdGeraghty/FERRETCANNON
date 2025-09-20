package routes.client_server.client

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
import routes.client_server.client.MATRIX_USER_ID_KEY

fun Route.contentRoutes(config: ServerConfig) {
    // POST /upload - Upload content
    post("/upload") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Handle multipart upload
            val multipart = call.receiveMultipart()
            var _fileName: String? = null
            var _contentType: String? = null
            var fileBytes: ByteArray? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        _fileName = part.originalFileName
                        _contentType = part.contentType?.toString()
                        fileBytes = part.streamProvider().readBytes()
                    }
                    is PartData.FormItem -> {
                        // Handle form fields if needed
                    }
                    else -> {
                        // Handle other part types
                    }
                }
                part.dispose()
            }

            if (fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "No file uploaded"
                ))
                return@post
            }

            // Generate content URI
            val contentUri = "mxc://${config.federation.serverName}/${System.currentTimeMillis()}"

            // TODO: Store file using MediaStorage
            // MediaStorage.saveFile(contentUri, fileBytes!!, contentType)

            call.respond(mapOf(
                "content_uri" to contentUri
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /download/{serverName}/{mediaId} - Download content
    get("/download/{serverName}/{mediaId}") {
        try {
            val serverName = call.parameters["serverName"]
            val mediaId = call.parameters["mediaId"]

            if (serverName == null || mediaId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing serverName or mediaId parameter"
                ))
                return@get
            }

            // TODO: Retrieve file from MediaStorage
            // val fileData = MediaStorage.getFile("mxc://$serverName/$mediaId")

            // For now, return a mock response
            call.respond(HttpStatusCode.NotFound, mapOf(
                "errcode" to "M_NOT_FOUND",
                "error" to "Media not found"
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /thumbnail/{serverName}/{mediaId} - Get thumbnail
    get("/thumbnail/{serverName}/{mediaId}") {
        try {
            val serverName = call.parameters["serverName"]
            val mediaId = call.parameters["mediaId"]
            val _width = call.request.queryParameters["width"]?.toIntOrNull()
            val _height = call.request.queryParameters["height"]?.toIntOrNull()
            val _method = call.request.queryParameters["method"] ?: "scale"

            if (serverName == null || mediaId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing serverName or mediaId parameter"
                ))
                return@get
            }

            // TODO: Generate or retrieve thumbnail from MediaStorage
            // val thumbnailData = MediaStorage.getThumbnail("mxc://$serverName/$mediaId", width, height, method)

            // For now, return a mock response
            call.respond(HttpStatusCode.NotFound, mapOf(
                "errcode" to "M_NOT_FOUND",
                "error" to "Thumbnail not found"
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /voip/turnServer - Get TURN server credentials
    get("/voip/turnServer") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            // Return TURN server configuration
            // In a real implementation, this would return actual TURN server credentials
            // For now, return an empty response indicating no TURN servers configured
            call.respond(mapOf(
                "username" to "",
                "password" to "",
                "uris" to emptyList<String>(),
                "ttl" to 86400
            ))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
