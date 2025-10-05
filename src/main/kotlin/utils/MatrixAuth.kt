package utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import java.net.URLEncoder
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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object MatrixAuth {
    private val logger = LoggerFactory.getLogger("utils.MatrixAuth")

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
            }
        }
    }

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
        println("Hash verification: expected='$sha256Hash', computed='$computedHash'")
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

        println("verifyRequest: origin=$origin, destination=$destination, keyId=$keyId")

        if (authOrigin != origin || authDestination != destination) return false

        // Fetch public key
        val publicKey = fetchPublicKey(origin, keyId) ?: return false

        println("verifyRequest: public key fetched successfully")

        // Build JSON with sorted keys for canonical form
        val jsonMap = mutableMapOf<String, Any>()
        jsonMap["method"] = method
        jsonMap["uri"] = uri
        jsonMap["origin"] = origin
        jsonMap["destination"] = destination
        authParams["timestamp"]?.let { jsonMap["timestamp"] = it.toLong() }
        if (content != null) {
            // Parse the content as JsonElement and convert to a native map structure
            // to avoid double-encoding issues in canonical JSON
            val contentElement = Json.parseToJsonElement(content)
            val nativeContent = jsonElementToNative(contentElement)
            if (nativeContent != null) {
                jsonMap["content"] = nativeContent
            }
        }

        println("verifyRequest: jsonMap: $jsonMap")

        val canonicalJson = canonicalizeJson(jsonMap)

        println("verifyRequest: canonical JSON: $canonicalJson")
        println("verifyRequest: signature to verify: $sig")

        // Verify signature
        val sigVerified = verifySignature(canonicalJson, sig, publicKey)
        println("verifyRequest: signature verification result: $sigVerified")
        return sigVerified
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
            println("fetchPublicKey: fetching key $keyId from $serverName")
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
            // Matrix uses base64url encoding for keys, but some servers may use standard base64
            val keyBytes = try {
                Base64.getUrlDecoder().decode(keyBase64)
            } catch (e: IllegalArgumentException) {
                // Fallback to standard base64 decoding
                Base64.getDecoder().decode(keyBase64)
            }
            // Create EdDSA public key from raw bytes
            val spec = net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(keyBytes, net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName("Ed25519"))
            val publicKey = net.i2p.crypto.eddsa.EdDSAPublicKey(spec)
            println("fetchPublicKey: successfully fetched and parsed key $keyId from $serverName")
            publicKey
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
        logger.info("=== EVENT SIGNATURE VERIFICATION (Matrix Spec Compliant) ===")
        logger.info("Original event JSON: $event")
        
        val signatures = event["signatures"]?.jsonObject ?: return false.also { logger.info("No signatures found") }
        val sender = event["sender"]?.jsonPrimitive?.content ?: return false.also { logger.info("No sender found") }

        // Get the server from the sender
        val senderServer = sender.substringAfter(":") // Extract server name from @user:server format
        logger.info("sender='$sender', senderServer='$senderServer'")
        
        // Look for signatures under the server name
        val serverSignatures = signatures[senderServer]?.jsonObject ?: return false.also { 
            logger.info("No signatures for server '$senderServer'. Available servers: ${signatures.keys}")
        }
        
        logger.info("Found signatures for server '$senderServer': ${serverSignatures.keys}")
        
        // Matrix spec: "First the signature is checked. The event is redacted following the redaction algorithm,
        // and the resultant object is checked for a signature from the originating server"
        
        // Try to find a valid signature for this server
        for ((keyName, sigValue) in serverSignatures) {
            if (keyName.startsWith("ed25519:")) {
                val signature = sigValue.jsonPrimitive.content
                logger.info("Verifying signature with key: $keyName")
                val publicKey = fetchPublicKey(senderServer, keyName)
                if (publicKey == null) {
                    logger.info("Failed to fetch public key for $senderServer/$keyName")
                    continue
                }
                
                // Apply Matrix redaction algorithm for event signature verification
                val redactedEvent = createRedactedEvent(event)
                logger.info("Redacted event for signature verification: $redactedEvent")
                
                // Convert to native types for canonical JSON computation
                val nativeEvent = jsonElementToNative(redactedEvent)
                if (nativeEvent !is MutableMap<*, *>) {
                    logger.error("Failed to convert event to mutable map")
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val eventForSigning = nativeEvent as MutableMap<String, Any?>
                // Remove signatures and unsigned for canonical JSON (as per Matrix signing spec)
                // IMPORTANT: Keep hashes - they are included when verifying signatures
                eventForSigning.remove("signatures")
                eventForSigning.remove("unsigned")
                
                logger.info("Event for canonical JSON has keys: ${eventForSigning.keys}")
                val canonicalJson = canonicalizeJson(eventForSigning)
                logger.info("Canonical JSON for signature: $canonicalJson")
                
                val verified = verifySignature(canonicalJson, signature, publicKey)
                logger.info("Signature verification result for $keyName: $verified")
                if (verified) {
                    logger.info("✅ Event signature verified successfully")
                    
                    // After signature verification passes, verify event hash
                    val originalHash = event["hashes"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content
                    val computedHash = computeContentHash(event)
                    logger.info("Original event hash: $originalHash")
                    logger.info("Computed event hash: $computedHash")
                    
                    if (originalHash != computedHash) {
                        logger.error("❌ Event hash mismatch! Original: $originalHash, Computed: $computedHash")
                        return false
                    }
                    logger.info("✅ Event hash verification passed")
                    
                    return true
                }
            }
        }
        
        logger.info("❌ All event signature verifications failed")
        return false
    }
    
    // Apply Matrix redaction algorithm for event signature verification
    private fun createRedactedEvent(event: JsonObject): JsonObject {
        val eventType = event["type"]?.jsonPrimitive?.content
        
        val redactedMap = mutableMapOf<String, JsonElement>()
        
        // Core fields always preserved in redaction (Matrix spec Room Version 11)
        event["event_id"]?.let { redactedMap["event_id"] = it }
        event["type"]?.let { redactedMap["type"] = it }
        event["room_id"]?.let { redactedMap["room_id"] = it }
        event["sender"]?.let { redactedMap["sender"] = it }
        event["state_key"]?.let { redactedMap["state_key"] = it }
        event["depth"]?.let { redactedMap["depth"] = it }
        event["prev_events"]?.let { redactedMap["prev_events"] = it }
        event["auth_events"]?.let { redactedMap["auth_events"] = it }
        event["origin_server_ts"]?.let { redactedMap["origin_server_ts"] = it }
        event["hashes"]?.let { redactedMap["hashes"] = it }
        event["signatures"]?.let { redactedMap["signatures"] = it }
        
        // Apply content redaction based on event type (Matrix spec Room Version 11)
        event["content"]?.let { content ->
            val redactedContent = when (eventType) {
                "m.room.member" -> {
                    // Per Matrix spec, m.room.member events preserve membership in content
                    val originalContent = content.jsonObject
                    val contentMap = mutableMapOf<String, JsonElement>()
                    originalContent["membership"]?.let { contentMap["membership"] = it }
                    originalContent["join_authorised_via_users_server"]?.let { 
                        contentMap["join_authorised_via_users_server"] = it 
                    }
                    JsonObject(contentMap)
                }
                "m.room.create" -> content // Allow all keys for m.room.create
                "m.room.join_rules" -> {
                    val originalContent = content.jsonObject
                    val contentMap = mutableMapOf<String, JsonElement>()
                    originalContent["join_rule"]?.let { contentMap["join_rule"] = it }
                    originalContent["allow"]?.let { contentMap["allow"] = it }
                    JsonObject(contentMap)
                }
                "m.room.power_levels" -> {
                    val originalContent = content.jsonObject
                    val contentMap = mutableMapOf<String, JsonElement>()
                    originalContent["ban"]?.let { contentMap["ban"] = it }
                    originalContent["events"]?.let { contentMap["events"] = it }
                    originalContent["events_default"]?.let { contentMap["events_default"] = it }
                    originalContent["invite"]?.let { contentMap["invite"] = it }
                    originalContent["kick"]?.let { contentMap["kick"] = it }
                    originalContent["redact"]?.let { contentMap["redact"] = it }
                    originalContent["state_default"]?.let { contentMap["state_default"] = it }
                    originalContent["users"]?.let { contentMap["users"] = it }
                    originalContent["users_default"]?.let { contentMap["users_default"] = it }
                    JsonObject(contentMap)
                }
                "m.room.history_visibility" -> {
                    val originalContent = content.jsonObject
                    val contentMap = mutableMapOf<String, JsonElement>()
                    originalContent["history_visibility"]?.let { contentMap["history_visibility"] = it }
                    JsonObject(contentMap)
                }
                "m.room.redaction" -> {
                    val originalContent = content.jsonObject
                    val contentMap = mutableMapOf<String, JsonElement>()
                    originalContent["redacts"]?.let { contentMap["redacts"] = it }
                    JsonObject(contentMap)
                }
                else -> JsonObject(emptyMap()) // Strip all content for other event types
            }
            redactedMap["content"] = redactedContent
        }
        
        // Explicitly exclude 'unsigned' field as per Matrix spec
        return JsonObject(redactedMap)
    }

    private fun verifySignature(data: String, signature: String, publicKey: EdDSAPublicKey): Boolean {
        return try {
            val sig = EdDSAEngine()
            sig.initVerify(publicKey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            
            logger.info("verifySignature: Attempting to decode signature: '$signature'")
            logger.info("verifySignature: Signature length: ${signature.length}")
            
            // Try multiple decoding strategies as Matrix spec is not completely clear
            val decodingStrategies = listOf(
                { Base64.getDecoder().decode(signature) },
                { Base64.getUrlDecoder().decode(signature) },
                { Base64.getDecoder().decode(signature + "=".repeat((4 - signature.length % 4) % 4)) },
                { Base64.getUrlDecoder().decode(signature + "=".repeat((4 - signature.length % 4) % 4)) }
            )
            
            for ((index, strategy) in decodingStrategies.withIndex()) {
                try {
                    val sigBytes = strategy()
                    logger.info("verifySignature: Strategy $index succeeded, signature bytes length: ${sigBytes.size}")
                    val result = sig.verify(sigBytes)
                    logger.info("verifySignature: Ed25519 verification result with strategy $index: $result")
                    if (result) return true
                } catch (e: Exception) {
                    logger.info("verifySignature: Strategy $index failed: ${e.message}")
                }
            }
            false
        } catch (e: Exception) {
            logger.error("verifySignature: Exception during signature verification", e)
            false
        }
    }

    private fun computeContentHash(event: JsonObject): String {
        // Convert JsonObject to native types properly
        val native = jsonElementToNative(event)
        if (native !is MutableMap<*, *>) {
            throw IllegalArgumentException("Event must be a JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        val nativeMap = native as MutableMap<String, Any?>
        
        // Add debug logging to see exactly what's being hashed
        logger.info("computeContentHash: event before cleanup has keys: ${event.keys}")
        
        // Per Matrix spec section 27.4: only remove unsigned, signatures, and hashes
        nativeMap.remove("signatures")
        nativeMap.remove("hashes")
        nativeMap.remove("unsigned")
        
        logger.info("computeContentHash: event after cleanup has keys: ${nativeMap.keys}")
        
        val canonical = canonicalizeJson(nativeMap)
        
        logger.info("computeContentHash: canonical JSON length: ${canonical.length}")
        logger.info("computeContentHash: canonical JSON (first 500 chars): ${canonical.take(500)}")
        
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

    private fun jsonElementToNative(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    else -> {
                        // Try to parse as number
                        element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
                    }
                }
            }
            is JsonArray -> element.map { jsonElementToNative(it) }
            is JsonObject -> element.map { it.key to jsonElementToNative(it.value) }.toMap()
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
        println("sendFederationInvite called: remoteServer=$remoteServer, roomId=$roomId")
        try {
            val eventId = inviteEvent["event_id"]?.jsonPrimitive?.content ?: return

            // Fetch invite_room_state - stripped state events for context
            val inviteRoomState = buildJsonArray {
                transaction {
                    // Get essential room state events for the invitee
                    val stateTypes = listOf(
                        "m.room.create",
                        "m.room.join_rules",
                        "m.room.name",
                        "m.room.avatar",
                        "m.room.canonical_alias",
                        "m.room.encryption",
                        "m.room.topic"
                    )
                    
                    stateTypes.forEach { type ->
                        val event = models.Events.select {
                            (models.Events.roomId eq roomId) and
                            (models.Events.type eq type)
                        }.orderBy(models.Events.originServerTs, SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()
                        
                        if (event != null) {
                            add(buildJsonObject {
                                put("type", event[models.Events.type])
                                put("sender", event[models.Events.sender])
                                put("state_key", event[models.Events.stateKey] ?: "")
                                put("content", Json.parseToJsonElement(event[models.Events.content]))
                            })
                        }
                    }
                }
            }

            // Get room version
            val roomVersion = transaction {
                models.Rooms.select { models.Rooms.roomId eq roomId }
                    .singleOrNull()
                    ?.get(models.Rooms.roomVersion) ?: "10"
            }

            // Build v2 invite request body
            val inviteRequestBody = buildJsonObject {
                put("event", inviteEvent)
                put("invite_room_state", inviteRoomState)
                put("room_version", roomVersion)
            }

            // Resolve server via .well-known
            val serverDetails = ServerDiscovery.resolveServerName(remoteServer)
            if (serverDetails == null) {
                logger.error("Failed to resolve server: $remoteServer")
                return
            }

            // Build the authorization header
            val method = "PUT"
            val uri = "/_matrix/federation/v2/invite/$roomId/$eventId"
            val destination = remoteServer
            val origin = config.federation.serverName

            val requestBodyString = Json.encodeToString(JsonObject.serializer(), inviteRequestBody)
            val authHeader = buildAuthHeader(method, uri, origin, destination, requestBodyString)

            // Send the request using resolved server details
            val fullUrl = "https://${serverDetails.host}:${serverDetails.port}$uri"
            logger.info("Sending federation invite to: $fullUrl")
            
            val response = client.put(fullUrl) {
                header("Authorization", authHeader)
                header("Content-Type", "application/json")
                setBody(requestBodyString)
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
     * Build authorization header for federation requests according to Matrix spec
     * https://spec.matrix.org/v1.16/server-server-api/#request-authentication
     */
    fun buildAuthHeader(method: String, uri: String, origin: String, destination: String, content: String?): String {
        val keyId = ServerKeys.getKeyId()

        // Build the JSON to sign according to Matrix spec
        val jsonMap = mutableMapOf<String, Any>()
        jsonMap["method"] = method
        jsonMap["uri"] = uri
        jsonMap["origin"] = origin
        jsonMap["destination"] = destination
        
        // Content must be parsed JSON, not a string
        if (content != null && content.isNotEmpty()) {
            val contentElement = Json.parseToJsonElement(content)
            val nativeContent = jsonElementToNative(contentElement)
            if (nativeContent != null) {
                jsonMap["content"] = nativeContent
            }
        }

        // Compute canonical JSON
        val canonicalJson = canonicalizeJson(jsonMap)
        
        logger.info("buildAuthHeader: canonical JSON for signing: $canonicalJson")
        
        // Sign the canonical JSON
        val signature = ServerKeys.sign(canonicalJson.toByteArray(Charsets.UTF_8))
        
        logger.info("buildAuthHeader: signature (base64): $signature")

        // Build authorization header (keyId already includes "ed25519:" prefix)
        return "X-Matrix origin=\"$origin\",destination=\"$destination\",key=\"$keyId\",sig=\"$signature\""
    }
}
