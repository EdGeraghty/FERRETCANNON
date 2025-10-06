package routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import utils.MatrixAuth
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TestEndpoints")

/**
 * Test endpoints for compliance testing.
 * These endpoints allow external test suites to verify canonical JSON generation,
 * event hashing, and signature computation against the Matrix specification.
 * 
 * Big shoutout to the FERRETCANNON massive for helping test this! 🎆
 */
fun Route.testEndpoints() {
    route("/_matrix/test") {
        
        /**
         * Test endpoint for canonical JSON generation.
         * POST /_matrix/test/canonical-json
         * 
         * Takes arbitrary JSON input and returns the canonical JSON representation
         * as per Matrix specification section 3.4.2.
         * 
         * Request body: Any valid JSON
         * Response: { "canonical_json": "..." }
         */
        post("/canonical-json") {
            try {
                val input = call.receive<JsonObject>()
                logger.info("Canonical JSON test request received")
                
                // Convert to native types and canonicalize
                val native = MatrixAuth.jsonElementToNative(input)
                val canonical = when (native) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        MatrixAuth.canonicalizeJson(native as Map<String, Any?>)
                    }
                    else -> {
                        logger.error("Input must be a JSON object")
                        call.respond(HttpStatusCode.BadRequest, mapOf(
                            "errcode" to "M_BAD_JSON",
                            "error" to "Input must be a JSON object"
                        ))
                        return@post
                    }
                }
                
                call.respond(mapOf(
                    "canonical_json" to canonical
                ))
                
            } catch (e: Exception) {
                logger.error("Error in canonical JSON test", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to generate canonical JSON: ${e.message}"
                ))
            }
        }
        
        /**
         * Test endpoint for event content hash computation.
         * POST /_matrix/test/compute-hash
         * 
         * Takes a Matrix event and returns the canonical JSON and content hash
         * as per Matrix specification section 6.1.3.
         * 
         * Request body: Matrix event JSON
         * Response: {
         *   "canonical_json": "...",
         *   "hash": "..."
         * }
         */
        post("/compute-hash") {
            try {
                val event = call.receive<JsonObject>()
                logger.info("Event hash test request received")
                
                // Compute the content hash and get canonical JSON
                val hash = MatrixAuth.computeContentHash(event)
                
                // Also return the canonical JSON for verification
                val native = MatrixAuth.jsonElementToNative(event)
                
                // Remove signatures, hashes, and unsigned fields as per spec
                val cleaned = if (native is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val map = (native as Map<String, Any?>).toMutableMap()
                    map.remove("signatures")
                    map.remove("hashes")
                    map.remove("unsigned")
                    map
                } else {
                    logger.error("Event must be a JSON object")
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "errcode" to "M_BAD_JSON",
                        "error" to "Event must be a JSON object"
                    ))
                    return@post
                }
                
                val canonical = MatrixAuth.canonicalizeJson(cleaned)
                
                call.respond(mapOf(
                    "canonical_json" to canonical,
                    "hash" to hash
                ))
                
            } catch (e: Exception) {
                logger.error("Error in event hash test", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to compute event hash: ${e.message}"
                ))
            }
        }
        
        /**
         * Server info endpoint for test suite connectivity check.
         * GET /_matrix/test/server-info
         * 
         * Simple endpoint to verify the server is running and accessible.
         */
        get("/server-info") {
            call.respond(mapOf(
                "server" to "FERRETCANNON",
                "version" to "1.0-SNAPSHOT",
                "message" to "Big shoutout to the FERRETCANNON massive! 🎆"
            ))
        }
    }
}
