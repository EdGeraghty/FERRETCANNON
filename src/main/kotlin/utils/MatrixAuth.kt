package utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import java.security.spec.X509EncodedKeySpec
import java.security.MessageDigest
import java.security.Signature
import java.util.*
import kotlinx.coroutines.runBlocking
import javax.net.ssl.*
import java.security.cert.X509Certificate
import java.security.KeyStore
import java.io.FileInputStream
import io.ktor.client.statement.*

import org.slf4j.LoggerFactory

object MatrixAuth {
    private val logger = LoggerFactory.getLogger("utils.MatrixAuth")

    private val client = HttpClient(CIO)

    fun verifyAuth(call: ApplicationCall, authHeader: String?, body: String?): Boolean {
        if (authHeader == null) return false
        try {
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            val destination = ServerNameResolver.getServerName() // This server's name

            // Parse auth header to get origin
            val authParams = parseAuthorization(authHeader) ?: return false
            val origin = authParams["origin"] ?: return false

            return runBlocking {
                verifyRequest(method, uri, origin, destination, body, authHeader)
            }
        } catch (e: Exception) {
            return false
        }
    }

    fun verifyEventHash(event: JsonObject): Boolean {
        val hashes = event["hashes"]?.jsonObject ?: return false
        val sha256Hash = hashes["sha256"]?.jsonPrimitive?.content ?: return false

        val computedHash = computeContentHash(event)
        return computedHash == sha256Hash
    }

    suspend fun verifyRequest(
        method: String,
        uri: String,
        origin: String,
        destination: String,
        content: String?,
        authorization: String
    ): Boolean {
        // Parse Authorization header
        val authParams = parseAuthorization(authorization) ?: return false

        val keyId = authParams["key"] ?: return false
        val sig = authParams["sig"] ?: return false
        val authOrigin = authParams["origin"] ?: return false
        val authDestination = authParams["destination"] ?: return false

        if (authOrigin != origin || authDestination != destination) return false

        // Fetch public key
        val publicKey = fetchPublicKey(origin, keyId) ?: return false

        // Build JSON with sorted keys for canonical form
        val jsonMap = mutableMapOf<String, Any>()
        jsonMap["method"] = method
        jsonMap["uri"] = uri
        jsonMap["origin"] = origin
        jsonMap["destination"] = destination
        if (content != null) {
            jsonMap["content"] = Json.parseToJsonElement(content)
        }

        val canonicalJson = canonicalizeJson(jsonMap)

        // Verify signature
        return verifySignature(canonicalJson, sig, publicKey)
    }

    internal fun parseAuthorization(auth: String): Map<String, String>? {
        if (!auth.startsWith("X-Matrix ")) return null
        val params = auth.substring(8).split(",").associate {
            val (key, value) = it.split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
        return params
    }

    private suspend fun fetchPublicKey(serverName: String, keyId: String): EdDSAPublicKey? {
        return try {
            // Use server discovery to resolve the server name
            val connectionDetails = ServerDiscovery.resolveServerName(serverName)
            if (connectionDetails == null) {
                logger.warn("Failed to resolve server name: $serverName")
                return null
            }

            val url = if (connectionDetails.tls) {
                "https://${connectionDetails.host}:${connectionDetails.port}/_matrix/key/v2/server"
            } else {
                "http://${connectionDetails.host}:${connectionDetails.port}/_matrix/key/v2/server"
            }

            val response = client.get(url) {
                header("Host", connectionDetails.hostHeader)
                // Note: In a production implementation, you would configure the HTTP client
                // with proper SSL context and certificate validation
            }

            // Check if the response is successful
            if (!response.status.isSuccess()) {
                logger.warn("Failed to fetch keys from $serverName (${connectionDetails.host}:${connectionDetails.port}): HTTP ${response.status}")
                return null
            }

            val json = response.body<String>()
            val data = Json.parseToJsonElement(json).jsonObject
            val verifyKeys = data["verify_keys"]?.jsonObject ?: return null
            val keyData = verifyKeys[keyId]?.jsonObject ?: return null
            val keyBase64 = keyData["key"]?.jsonPrimitive?.content ?: return null
            // Handle both padded and unpadded Base64 for compatibility
            val keyBytes = try {
                Base64.getDecoder().decode(keyBase64)
            } catch (e: IllegalArgumentException) {
                Base64.getDecoder().decode(keyBase64)
            }
            val spec = X509EncodedKeySpec(keyBytes)
            EdDSAPublicKey(spec)
        } catch (e: Exception) {
            logger.error("Error fetching public key from $serverName", e)
            null
        }
    }

    /**
     * Validate TLS certificate for a server
     * This is a basic implementation - in production, you would use proper certificate validation
     */
    fun validateServerCertificate(serverName: String, certificate: X509Certificate?): Boolean {
        if (certificate == null) return false

        return try {
            // Basic validation: check if certificate is valid for the server name
            val certCN = certificate.subjectX500Principal.name
            val certSANs = certificate.subjectAlternativeNames

            // Check common name
            if (certCN.contains("CN=$serverName")) {
                return true
            }

            // Check subject alternative names
            certSANs?.forEach { san ->
                val (type, value) = san as List<*>
                if (type == 2 && value == serverName) { // 2 = DNS name
                    return true
                }
            }

            false
        } catch (e: Exception) {
            logger.error("Certificate validation error for $serverName", e)
            false
        }
    }

    suspend fun verifyEventSignatures(event: JsonObject): Boolean {
        val signatures = event["signatures"]?.jsonObject ?: return false
        val sender = event["sender"]?.jsonPrimitive?.content ?: return false
        val origin = event["origin"]?.jsonPrimitive?.content ?: return false

        // Verify sender's signature
        val senderServer = sender.substringAfter("@").substringAfter(":")
        val senderKey = fetchPublicKey(senderServer, "ed25519:key1") ?: return false // Assume key1
        val senderSig = signatures[sender]?.jsonObject?.get("ed25519:key1")?.jsonPrimitive?.content ?: return false
        if (!verifySignature(canonicalizeJson(event.toMap()), senderSig, senderKey)) return false

        // Verify origin's signature
        val originKey = fetchPublicKey(origin, "ed25519:key1") ?: return false
        val originSig = signatures[origin]?.jsonObject?.get("ed25519:key1")?.jsonPrimitive?.content ?: return false
        if (!verifySignature(canonicalizeJson(event.toMap()), originSig, originKey)) return false

        return true
    }

    private fun verifySignature(data: String, signature: String, publicKey: EdDSAPublicKey): Boolean {
        return try {
            val sig = EdDSAEngine()
            sig.initVerify(publicKey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            // Handle both padded and unpadded Base64 for compatibility
            val sigBytes = try {
                Base64.getDecoder().decode(signature)
            } catch (e: IllegalArgumentException) {
                Base64.getDecoder().decode(signature)
            }
            sig.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
    }

    private fun computeContentHash(event: JsonObject): String {
        val copy = event.toMutableMap()
        copy.remove("signatures")
        copy.remove("hashes")
        copy.remove("unsigned")
        val canonical = canonicalizeJson(copy)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        // Use unpadded Base64 as per Matrix specification appendices
        return Base64.getEncoder().withoutPadding().encodeToString(hash)
    }

    fun canonicalizeJson(data: Any): String {
        return when (data) {
            is Map<*, *> -> {
                val stringKeyMap = data.entries.associate { (key, value) ->
                    key.toString() to value
                }
                val sortedMap = stringKeyMap.toSortedMap(compareBy { it })
                val jsonString = buildString {
                    append("{")
                    sortedMap.entries.forEachIndexed { index, (key, value) ->
                        if (index > 0) append(",")
                        append("\"$key\":")
                        append(canonicalizeValue(value))
                    }
                    append("}")
                }
                jsonString
            }
            is JsonObject -> canonicalizeJson(data.toMap())
            else -> data.toString()
        }
    }

    private fun canonicalizeValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is Number -> {
                // Ensure integers are in valid range and format without exponents
                val num = value.toDouble()
                if (num.isNaN() || num.isInfinite()) {
                    throw IllegalArgumentException("Invalid number in JSON")
                }
                // Convert to long if it's a whole number, otherwise keep as is
                if (num == num.toLong().toDouble()) {
                    num.toLong().toString()
                } else {
                    throw IllegalArgumentException("Non-integer numbers not allowed in canonical JSON")
                }
            }
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
            is List<*> -> {
                buildString {
                    append("[")
                    value.forEachIndexed { index, item ->
                        if (index > 0) append(",")
                        append(canonicalizeValue(item))
                    }
                    append("]")
                }
            }
            is Map<*, *> -> canonicalizeJson(value)
            else -> "\"${value.toString()}\""
        }
    }

    fun signJson(data: Any): String {
        val canonicalJson = canonicalizeJson(data)
        return ServerKeys.sign(canonicalJson.toByteArray(Charsets.UTF_8))
    }

    fun hashAndSignEvent(event: JsonObject, serverName: String): JsonObject {
        val contentHash = computeContentHash(event)

        val eventWithHashes = event.toMutableMap()
        eventWithHashes["hashes"] = JsonObject(mutableMapOf("sha256" to JsonPrimitive(contentHash)))
        eventWithHashes["signatures"] = JsonObject(mutableMapOf(serverName to JsonObject(mutableMapOf(ServerKeys.getKeyId() to JsonPrimitive("")))))

        val canonicalJson = canonicalizeJson(eventWithHashes)
        val signature = ServerKeys.sign(canonicalJson.toByteArray(Charsets.UTF_8))

        val signatures = JsonObject(mutableMapOf(serverName to JsonObject(mutableMapOf(ServerKeys.getKeyId() to JsonPrimitive(signature)))))
        eventWithHashes["signatures"] = signatures

        return JsonObject(eventWithHashes)
    }

    private fun computeReferenceHash(event: JsonObject, contentHash: String): String {
        val eventId = event["event_id"]?.jsonPrimitive?.content ?: ""
        val referenceData = "$eventId|$contentHash"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(referenceData.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    /**
     * Validate a Matrix server name according to the specification
     * Server names must be valid DNS names or IP addresses
     */
    fun isValidServerName(serverName: String): Boolean {
        // Basic validation: check for valid hostname format
        val hostnameRegex = "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$".toRegex()
        return hostnameRegex.matches(serverName) && serverName.length <= 253
    }

    /**
     * Validate a Matrix user ID according to the specification
     * User IDs must follow the format: @localpart:domain
     */
    fun isValidUserId(userId: String): Boolean {
        if (!userId.startsWith("@") || userId.length > 255) return false

        val parts = userId.substring(1).split(":", limit = 2)
        if (parts.size != 2) return false

        val localpart = parts[0]
        val domain = parts[1]

        // Localpart validation: must contain only a-z, 0-9, ., _, =, -, /, +
        if (localpart.isEmpty()) return false
        val localpartRegex = "^[a-z0-9._=/+-]+\$".toRegex(RegexOption.IGNORE_CASE)
        if (!localpartRegex.matches(localpart)) return false

        // Domain validation
        return isValidServerName(domain)
    }

    /**
     * Validate a Matrix room ID according to the specification
     * Room IDs must follow the format: !opaque_id:domain or !opaque_id
     */
    fun isValidRoomId(roomId: String): Boolean {
        if (!roomId.startsWith("!") || roomId.length > 255) return false

        val parts = roomId.substring(1).split(":", limit = 2)
        if (parts.isEmpty()) return false

        val opaqueId = parts[0]

        // Opaque ID must contain only valid characters and not be empty
        if (opaqueId.isEmpty()) return false
        val opaqueIdRegex = "^[0-9A-Za-z._~-]+\$".toRegex()
        if (!opaqueIdRegex.matches(opaqueId)) return false

        // If domain is present, validate it
        if (parts.size == 2) {
            val domain = parts[1]
            return isValidServerName(domain)
        }

        return true
    }

    /**
     * Validate a Matrix room alias according to the specification
     * Room aliases must follow the format: #room_alias:domain
     */
    fun isValidRoomAlias(roomAlias: String): Boolean {
        if (!roomAlias.startsWith("#") || roomAlias.length > 255) return false

        val parts = roomAlias.substring(1).split(":", limit = 2)
        if (parts.size != 2) return false

        val alias = parts[0]
        val domain = parts[1]

        // Alias can contain any valid non-surrogate Unicode codepoints except : and NUL
        if (alias.isEmpty() || alias.contains('\u0000') || alias.contains(':')) return false

        // Domain validation
        return isValidServerName(domain)
    }

    /**
     * Validate a Matrix event ID according to the specification
     * Event IDs must follow the format: $opaque_id or $opaque_id:domain
     */
    fun isValidEventId(eventId: String): Boolean {
        if (!eventId.startsWith("\$") || eventId.length > 255) return false

        val parts = eventId.substring(1).split(":", limit = 2)
        if (parts.isEmpty()) return false

        val opaqueId = parts[0]

        // Opaque ID must contain only valid characters and not be empty
        if (opaqueId.isEmpty()) return false
        val opaqueIdRegex = "^[0-9A-Za-z._~-]+\$".toRegex()
        if (!opaqueIdRegex.matches(opaqueId)) return false

        // If domain is present, validate it
        if (parts.size == 2) {
            val domain = parts[1]
            return isValidServerName(domain)
        }

        return true
    }

    /**
     * Validate a Matrix key ID according to the specification
     * Key IDs must follow the format: algorithm:identifier
     */
    fun isValidKeyId(keyId: String): Boolean {
        val parts = keyId.split(":", limit = 2)
        if (parts.size != 2) return false

        val algorithm = parts[0]
        val identifier = parts[1]

        // Algorithm should be a valid namespaced identifier
        val algorithmRegex = "^[a-z][a-z0-9._-]*\$".toRegex()
        if (!algorithmRegex.matches(algorithm)) return false

        // Identifier should contain only valid characters
        val identifierRegex = "^[a-zA-Z0-9._~-]+\$".toRegex()
        return identifierRegex.matches(identifier)
    }

    /**
     * Send an invite event to a remote server via federation
     */
    suspend fun sendFederationInvite(remoteServer: String, roomId: String, inviteEvent: JsonObject, config: config.ServerConfig) {
        try {
            val eventId = inviteEvent["event_id"]?.jsonPrimitive?.content ?: return

            // Build the authorization header
            val method = "PUT"
            val uri = "/_matrix/federation/v1/invite/$roomId/$eventId"
            val destination = remoteServer
            val origin = config.federation.serverName

            val authHeader = buildAuthHeader(method, uri, origin, destination, Json.encodeToString(JsonObject.serializer(), inviteEvent))

            // Send the request
            val response = client.put("https://$remoteServer$uri") {
                header("Authorization", authHeader)
                header("Content-Type", "application/json")
                setBody(Json.encodeToString(JsonObject.serializer(), inviteEvent))
            }

            if (response.status.value != 200) {
                val responseBody = runBlocking { response.bodyAsText() }
                logger.error("Federation invite failed: ${response.status} - $responseBody")
            } else {
                logger.info("Federation invite sent successfully to $remoteServer")
            }
        } catch (e: Exception) {
            logger.error("Error sending federation invite", e)
        }
    }

    /**
     * Build authorization header for federation requests
     */
    private fun buildAuthHeader(method: String, uri: String, origin: String, destination: String, content: String?): String {
        val keyId = "ed25519:${ServerKeys.getKeyId()}"
        val timestamp = System.currentTimeMillis() / 1000

        // Build the JSON to sign
        val jsonMap = mutableMapOf<String, Any>()
        jsonMap["method"] = method
        jsonMap["uri"] = uri
        jsonMap["origin"] = origin
        jsonMap["destination"] = destination
        jsonMap["timestamp"] = timestamp
        if (content != null) {
            jsonMap["content"] = Json.parseToJsonElement(content)
        }

        val canonicalJson = canonicalizeJson(jsonMap)

        // Sign the request
        val signature = ServerKeys.sign(canonicalJson.toByteArray())

        return "X-Matrix origin=\"$origin\",destination=\"$destination\",key=\"$keyId\",sig=\"$signature\",timestamp=\"$timestamp\""
    }
}
