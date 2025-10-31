package utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import org.imgscalr.Scalr
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import models.Media
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Media Storage and Processing utilities for Matrix Content Repository
 */
object MediaStorage {

    private const val MEDIA_DIR = "media"
    private const val THUMBNAIL_DIR = "thumbnails"

    // Configuration values - will be set during initialization
    private var maxFileSize: Long = 50 * 1024 * 1024 // 50MB default
    private var maxThumbnailSize: Int = 320 // Max dimension for thumbnails default

    init {
        // Create media directories
        createDirectories()
    }

    /**
     * Initialize MediaStorage with configuration values
     */
    fun initialize(maxFileSizeBytes: Long, maxThumbnailDimension: Int) {
        this.maxFileSize = maxFileSizeBytes
        this.maxThumbnailSize = maxThumbnailDimension
        println("MediaStorage initialized with maxFileSize: $maxFileSize bytes, maxThumbnailSize: $maxThumbnailSize px")
    }

    private fun createDirectories() {
        val mediaPath = Paths.get(MEDIA_DIR)
        val thumbnailPath = Paths.get(MEDIA_DIR, THUMBNAIL_DIR)

        try {
            Files.createDirectories(mediaPath)
            Files.createDirectories(thumbnailPath)
        } catch (e: Exception) {
            println("Error creating media directories: ${e.message}")
        }
    }

    /**
     * Store media content
     */
    suspend fun storeMedia(mediaId: String, content: ByteArray, contentType: String, userId: String = "@anonymous:example.com"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Validate file size
                if (content.size > maxFileSize) {
                    println("Media file too large: ${content.size} bytes (max: $maxFileSize)")
                    return@withContext false
                }

                // Generate file path
                val filePath = getMediaFilePath(mediaId)

                // Write file
                Files.write(filePath, content)

                // Store metadata in database
                transaction {
                    Media.insert {
                        it[Media.mediaId] = mediaId
                        it[Media.userId] = userId
                        it[Media.filename] = ""
                        it[Media.contentType] = contentType
                        it[Media.size] = content.size.toLong()
                        it[Media.uploadTime] = System.currentTimeMillis()
                        it[Media.thumbnailMediaId] = null
                        it[Media.width] = null
                        it[Media.height] = null
                        it[Media.duration] = null
                        it[Media.hash] = null
                    }
                }

                true
            } catch (e: Exception) {
                println("Error storing media $mediaId: ${e.message}")
                false
            }
        }
    }

    /**
     * Store media content with filename
     */
    suspend fun storeMedia(mediaId: String, content: ByteArray, contentType: String, filename: String?, userId: String = "@anonymous:example.com"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Validate file size
                if (content.size > maxFileSize) {
                    println("Media file too large: ${content.size} bytes (max: $maxFileSize)")
                    return@withContext false
                }

                // Generate file path
                val filePath = getMediaFilePath(mediaId)

                // Write file
                Files.write(filePath, content)

                // Store metadata in database
                transaction {
                    Media.insert {
                        it[Media.mediaId] = mediaId
                        it[Media.userId] = userId
                        it[Media.filename] = filename ?: ""
                        it[Media.contentType] = contentType
                        it[Media.size] = content.size.toLong()
                        it[Media.uploadTime] = System.currentTimeMillis()
                        it[Media.thumbnailMediaId] = null
                        it[Media.width] = null
                        it[Media.height] = null
                        it[Media.duration] = null
                        it[Media.hash] = null
                    }
                }

                true
            } catch (e: Exception) {
                println("Error storing media $mediaId: ${e.message}")
                false
            }
        }
    }

    /**
     * Retrieve media content
     */
    suspend fun getMedia(mediaId: String): Pair<ByteArray?, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val filePath = getMediaFilePath(mediaId)
                if (!Files.exists(filePath)) {
                    return@withContext Pair(null, null)
                }

                val content = Files.readAllBytes(filePath)
                val contentType = transaction {
                    Media.select { Media.mediaId eq mediaId }
                        .singleOrNull()?.get(Media.contentType) ?: "application/octet-stream"
                }

                Pair(content, contentType)
            } catch (e: Exception) {
                println("Error retrieving media $mediaId: ${e.message}")
                Pair(null, null)
            }
        }
    }

    /**
     * Generate and store thumbnail
     */
    suspend fun generateThumbnail(mediaId: String, width: Int, height: Int, method: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                // Get original media
                val (originalContent, contentType) = getMedia(mediaId)
                if (originalContent == null || !isImageType(contentType)) {
                    return@withContext null
                }

                // Load image
                val originalImage = ImageIO.read(ByteArrayInputStream(originalContent))
                if (originalImage == null) {
                    return@withContext null
                }

                // Generate thumbnail
                val thumbnail = createThumbnail(originalImage, width, height, method)

                // Convert to bytes
                val outputStream = ByteArrayOutputStream()
                ImageIO.write(thumbnail, "JPEG", outputStream)
                outputStream.toByteArray()

            } catch (e: Exception) {
                println("Error generating thumbnail for $mediaId: ${e.message}")
                null
            }
        }
    }

    private fun createThumbnail(original: BufferedImage, width: Int, height: Int, method: String): BufferedImage {
        val scaledWidth: Int
        val scaledHeight: Int

        when (method) {
            "crop" -> {
                // Crop to exact dimensions
                scaledWidth = width
                scaledHeight = height
            }
            "scale" -> {
                // Scale maintaining aspect ratio
                val aspectRatio = original.width.toDouble() / original.height.toDouble()
                if (width / aspectRatio <= height) {
                    scaledWidth = width
                    scaledHeight = (width / aspectRatio).toInt()
                } else {
                    scaledHeight = height
                    scaledWidth = (height * aspectRatio).toInt()
                }
            }
            else -> {
                // Default to scale
                val aspectRatio = original.width.toDouble() / original.height.toDouble()
                if (width / aspectRatio <= height) {
                    scaledWidth = width
                    scaledHeight = (width / aspectRatio).toInt()
                } else {
                    scaledHeight = height
                    scaledWidth = (height * aspectRatio).toInt()
                }
            }
        }

        // Ensure dimensions don't exceed maximum
        val finalWidth = minOf(scaledWidth, maxThumbnailSize)
        val finalHeight = minOf(scaledHeight, maxThumbnailSize)

        return Scalr.resize(original, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.AUTOMATIC, finalWidth, finalHeight)
    }

    private fun isImageType(contentType: String?): Boolean {
        return contentType?.startsWith("image/") == true
    }

    private fun getMediaFilePath(mediaId: String): Path {
        return Paths.get(MEDIA_DIR, "$mediaId.dat")
    }

    /**
     * Check if media exists
     */
    fun mediaExists(mediaId: String): Boolean {
        val filePath = getMediaFilePath(mediaId)
        return Files.exists(filePath)
    }

    /**
     * Get media metadata
     */
    fun getMediaMetadata(mediaId: String): MediaMetadata? {
        return transaction {
            Media.select { Media.mediaId eq mediaId }
                .singleOrNull()?.let { row ->
                    MediaMetadata(
                        mediaId = row[Media.mediaId],
                        userId = row[Media.userId],
                        filename = row[Media.filename],
                        contentType = row[Media.contentType],
                        size = row[Media.size].toInt(),
                        uploadTime = row[Media.uploadTime],
                        thumbnailMediaId = row[Media.thumbnailMediaId],
                        width = row[Media.width],
                        height = row[Media.height],
                        duration = row[Media.duration],
                        hash = row[Media.hash]
                    )
                }
        }
    }

    /**
     * Clean up old media files (basic implementation)
     */
    suspend fun cleanupOldMedia(maxAgeDays: Int = 30) {
        withContext(Dispatchers.IO) {
            try {
                val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
                val mediaDir = Paths.get(MEDIA_DIR)

                Files.walk(mediaDir)
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        try {
                            val lastModified = Files.getLastModifiedTime(file).toMillis()
                            if (lastModified < cutoffTime) {
                                Files.delete(file)
                                println("Deleted old media file: ${file.fileName}")
                            }
                        } catch (e: Exception) {
                            println("Error deleting file ${file.fileName}: ${e.message}")
                        }
                    }
            } catch (e: Exception) {
                println("Error during media cleanup: ${e.message}")
            }
        }
    }
}

data class MediaMetadata(
    val mediaId: String,
    val userId: String,
    val filename: String,
    val contentType: String,
    val size: Int,
    val uploadTime: Long,
    val thumbnailMediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val hash: String? = null
)
