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
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.Signature
import java.util.*
import kotlinx.coroutines.runBlocking

object MatrixAuth {

    private val client = HttpClient(CIO)

    fun verifyAuth(call: ApplicationCall, authHeader: String, body: String): Boolean {
        val method = call.request.httpMethod.value
        val uri = call.request.uri
        val destination = "localhost" // This server's name

        // Parse auth header to get origin
        val authParams = parseAuthorization(authHeader) ?: return false
        val origin = authParams["origin"] ?: return false

        return runBlocking {
            verifyRequest(method, uri, origin, destination, body, authHeader)
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

        val canonicalJson = buildCanonicalJson(jsonMap)

        // Verify signature
        return verifySignature(canonicalJson, sig, publicKey)
    }

    private fun buildCanonicalJson(map: Map<String, Any>): String {
        val sortedMap = map.toSortedMap()
        return Json.encodeToString(JsonElement.serializer(), JsonObject(sortedMap.mapValues { (_, value) ->
            when (value) {
                is String -> JsonPrimitive(value)
                is JsonElement -> value
                else -> JsonPrimitive(value.toString())
            }
        }))
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
            val response = client.get("https://$serverName/_matrix/key/v2/server")
            val json = response.body<String>()
            val data = Json.parseToJsonElement(json).jsonObject
            val verifyKeys = data["verify_keys"]?.jsonObject ?: return null
            val keyData = verifyKeys[keyId]?.jsonObject ?: return null
            val keyBase64 = keyData["key"]?.jsonPrimitive?.content ?: return null
            val keyBytes = Base64.getDecoder().decode(keyBase64)
            val spec = EdDSAPublicKeySpec(keyBytes, null)
            EdDSAPublicKey(spec)
        } catch (e: Exception) {
            null
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
        if (!verifySignature(buildCanonicalJson(event.toMap()), senderSig, senderKey)) return false

        // Verify origin's signature
        val originKey = fetchPublicKey(origin, "ed25519:key1") ?: return false
        val originSig = signatures[origin]?.jsonObject?.get("ed25519:key1")?.jsonPrimitive?.content ?: return false
        if (!verifySignature(buildCanonicalJson(event.toMap()), originSig, originKey)) return false

        return true
    }

    private fun verifySignature(data: String, signature: String, publicKey: EdDSAPublicKey): Boolean {
        return try {
            val sig = Signature.getInstance("Ed25519", "EdDSA")
            sig.initVerify(publicKey)
            sig.update(data.toByteArray(Charsets.UTF_8))
            val sigBytes = Base64.getDecoder().decode(signature)
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
        val canonical = buildCanonicalJson(copy)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    fun canonicalizeJson(data: Any): String {
        return when (data) {
            is Map<*, *> -> {
                val sortedMap = (data as Map<String, Any>).toSortedMap()
                Json.encodeToString(JsonElement.serializer(), JsonObject(sortedMap.mapValues { (_, value) ->
                    when (value) {
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is JsonElement -> value
                        is List<*> -> JsonArray(value.map { item ->
                            when (item) {
                                is String -> JsonPrimitive(item)
                                is Number -> JsonPrimitive(item)
                                is Boolean -> JsonPrimitive(item)
                                is JsonElement -> item
                                else -> JsonPrimitive(item.toString())
                            }
                        })
                        is Map<*, *> -> Json.parseToJsonElement(canonicalizeJson(value))
                        else -> JsonPrimitive(value.toString())
                    }
                }))
            }
            is JsonObject -> buildCanonicalJson(data.toMap())
            else -> data.toString()
        }
    }

    fun signJson(data: Any): String {
        val canonicalJson = canonicalizeJson(data)
        return ServerKeys.sign(canonicalJson.toByteArray(Charsets.UTF_8))
    }

    fun hashAndSignEvent(event: JsonObject, serverName: String): JsonObject {
        val contentHash = computeContentHash(event)

        val eventWithHashes = event.toMutableMap()
        eventWithHashes["hashes"] = JsonObject(mapOf("sha256" to JsonPrimitive(contentHash)))
        eventWithHashes["signatures"] = JsonObject(mapOf(serverName to JsonObject(mapOf(ServerKeys.getKeyId() to JsonPrimitive("")))))

        val canonicalJson = canonicalizeJson(eventWithHashes)
        val signature = ServerKeys.sign(canonicalJson.toByteArray(Charsets.UTF_8))

        val signatures = JsonObject(mapOf(serverName to JsonObject(mapOf(ServerKeys.getKeyId() to JsonPrimitive(signature)))))
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
}
