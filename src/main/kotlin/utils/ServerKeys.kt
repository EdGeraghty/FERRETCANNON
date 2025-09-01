package utils

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.Signature
import java.util.Base64
import org.slf4j.LoggerFactory

object ServerKeys {
    private val logger = LoggerFactory.getLogger("utils.ServerKeys")
    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private lateinit var privateKey: EdDSAPrivateKey
    private lateinit var publicKey: EdDSAPublicKey
    private lateinit var publicKeyBase64: String
    private lateinit var keyId: String

    init {
        logger.info("🔐 Initializing ServerKeys with Ed25519 key pair generation")
        generateKeyPair()
    }

    private fun generateKeyPair() {
        logger.debug("Generating new Ed25519 key pair...")
        try {
            val keyPairGenerator = net.i2p.crypto.eddsa.KeyPairGenerator()
            // Try with SecureRandom first
            val secureRandom = java.security.SecureRandom()
            keyPairGenerator.initialize(spec, secureRandom)
            val keyPair = keyPairGenerator.generateKeyPair()

            privateKey = keyPair.private as EdDSAPrivateKey
            publicKey = keyPair.public as EdDSAPublicKey
            // Use unpadded Base64 as per Matrix specification appendices
            publicKeyBase64 = Base64.getEncoder().withoutPadding().encodeToString(publicKey.abyte)
            keyId = "ed25519:0"
            
            logger.info("✅ Generated server key pair with ID: $keyId")
            logger.debug("Public key (first 32 chars): ${publicKeyBase64.take(32)}...")
        } catch (e: Exception) {
            logger.error("Failed to generate key pair with SecureRandom, trying alternative approach", e)
            // Fallback: try with key size instead of AlgorithmParameterSpec
            try {
                val keyPairGenerator = net.i2p.crypto.eddsa.KeyPairGenerator()
                val secureRandom = java.security.SecureRandom()
                keyPairGenerator.initialize(25519, secureRandom) // Ed25519 key size
                val keyPair = keyPairGenerator.generateKeyPair()

                privateKey = keyPair.private as EdDSAPrivateKey
                publicKey = keyPair.public as EdDSAPublicKey
                publicKeyBase64 = Base64.getEncoder().withoutPadding().encodeToString(publicKey.abyte)
                keyId = "ed25519:0"
                
                logger.info("✅ Generated server key pair with key size method, ID: $keyId")
                logger.debug("Public key (first 32 chars): ${publicKeyBase64.take(32)}...")
            } catch (fallbackError: Exception) {
                logger.error("Both key generation methods failed", fallbackError)
                throw fallbackError
            }
        }
    }

    // Test function to verify against Matrix specification test vectors
    fun testWithSpecificationSeed(): Map<String, Boolean> {
        // SIGNING_KEY_SEED from Matrix specification appendices
        val testSeed = Base64.getDecoder().decode("YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1")

        val testPrivateKeySpec = EdDSAPrivateKeySpec(testSeed, spec)
        val testPrivateKey = EdDSAPrivateKey(testPrivateKeySpec)
        val testPublicKeySpec = EdDSAPublicKeySpec(testPrivateKey.a, spec)
        val testPublicKey = EdDSAPublicKey(testPublicKeySpec)

        val results = mutableMapOf<String, Boolean>()

        // Test 1: Empty JSON object
        val canonicalEmpty = "{}"
        val signatureEmpty = signWithKey(canonicalEmpty.toByteArray(Charsets.UTF_8), testPrivateKey)
        val expectedEmpty = "K8280/U9SSy9IVtjBuVeLr+HpOB4BQFWbg+UZaADMtTdGYI7Geitb76LTrr5QV/7Xg4ahLwYGYZzuHGZKM5ZAQ"
        results["Empty Object"] = signatureEmpty == expectedEmpty

        // Test 2: JSON object with data
        val canonical4444 = "{\"one\":1,\"two\":\"Two\"}"
        val signatureData = signWithKey(canonical4444.toByteArray(Charsets.UTF_8), testPrivateKey)
        val expectedData = "KqmLSbO39/Bzb0QIYE82zqLwsA+PDzYIpIRA2sRQ4sL53+sN6/fpNSoqE7BP7vBZhG6kYdD13EIMJpvhJI+6Bw"
        results["Data Object"] = signatureData == expectedData

        return results
    }

    private fun signWithKey(data: ByteArray, key: EdDSAPrivateKey): String {
        val signature = Signature.getInstance("EdDSA", "I2P")
        signature.initSign(key)
        signature.update(data)
        val signatureBytes = signature.sign()
        // Use unpadded Base64 as per Matrix specification appendices
        return Base64.getEncoder().withoutPadding().encodeToString(signatureBytes)
    }

    fun getPublicKey(): String = publicKeyBase64
    fun getKeyId(): String = keyId
    fun getPrivateKey(): EdDSAPrivateKey = privateKey
    fun getPublicKeyObject(): EdDSAPublicKey = publicKey

    fun sign(data: ByteArray): String {
        logger.trace("Signing ${data.size} bytes of data")
        try {
            // Use EdDSAEngine with SHA-512 (required for Ed25519)
            val signatureEngine = net.i2p.crypto.eddsa.EdDSAEngine(java.security.MessageDigest.getInstance("SHA-512"))
            signatureEngine.initSign(privateKey)
            signatureEngine.update(data)
            val signatureBytes = signatureEngine.sign()
            // Use unpadded Base64 as per Matrix specification appendices
            val signatureBase64 = Base64.getEncoder().withoutPadding().encodeToString(signatureBytes)
            logger.trace("Generated signature: ${signatureBase64.take(32)}...")
            return signatureBase64
        } catch (e: Exception) {
            logger.error("Failed to sign data with EdDSAEngine", e)
            throw e
        }
    }

    fun verify(data: ByteArray, signature: String): Boolean {
        logger.trace("Verifying signature for ${data.size} bytes of data")
        return try {
            // Handle both padded and unpadded Base64 for compatibility
            val signatureBytes = try {
                Base64.getDecoder().decode(signature)
            } catch (e: IllegalArgumentException) {
                // Try with padding if unpadded decoding fails
                Base64.getDecoder().decode(signature)
            }
            
            // Use EdDSAEngine with SHA-512 (required for Ed25519)
            val signatureEngine = net.i2p.crypto.eddsa.EdDSAEngine(java.security.MessageDigest.getInstance("SHA-512"))
            signatureEngine.initVerify(publicKey)
            signatureEngine.update(data)
            val result = signatureEngine.verify(signatureBytes)
            logger.trace("Signature verification result: $result")
            result
        } catch (e: Exception) {
            logger.warn("Signature verification failed", e)
            false
        }
    }

    fun getServerKeys(serverName: String): Map<String, Any> {
        logger.debug("Generating server keys response for server: $serverName")
        val validUntilTs = System.currentTimeMillis() + 86400000 // Valid for 24 hours
        
        val serverKeys = mapOf(
            "server_name" to serverName,
            "valid_until_ts" to validUntilTs,
            "verify_keys" to mapOf(
                keyId to mapOf(
                    "key" to publicKeyBase64
                )
            ),
            "old_verify_keys" to emptyMap<String, Any>(),
            "signatures" to mapOf(
                serverName to mapOf(
                    keyId to sign(getServerKeysCanonicalJson(serverName).toByteArray(Charsets.UTF_8))
                )
            )
        )
        
        logger.debug("Server keys generated with valid_until_ts: $validUntilTs")
        logger.trace("Server keys response: ${serverKeys.keys.joinToString()}")
        return serverKeys
    }

    private fun getServerKeysCanonicalJson(serverName: String): String {
        // Create canonical JSON for signing (without signatures field)
        val canonicalData = mapOf(
            "server_name" to serverName,
            "valid_until_ts" to (System.currentTimeMillis() + 86400000),
            "verify_keys" to mapOf(
                keyId to mapOf(
                    "key" to publicKeyBase64
                )
            ),
            "old_verify_keys" to emptyMap<String, Any>()
        )

        // Convert to canonical JSON string
        return kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(canonicalData.mapValues { value ->
                when (value.value) {
                    is String -> kotlinx.serialization.json.JsonPrimitive(value.value as String)
                    is Long -> kotlinx.serialization.json.JsonPrimitive(value.value as Long)
                    is Map<*, *> -> kotlinx.serialization.json.JsonObject((value.value as Map<String, Any>).mapValues { innerValue ->
                        when (innerValue.value) {
                            is String -> kotlinx.serialization.json.JsonPrimitive(innerValue.value as String)
                            is Map<*, *> -> kotlinx.serialization.json.JsonObject((innerValue.value as Map<String, Any>).mapValues { innermostValue ->
                                kotlinx.serialization.json.JsonPrimitive(innermostValue.value as String)
                            })
                            else -> kotlinx.serialization.json.JsonPrimitive(innerValue.value.toString())
                        }
                    })
                    else -> kotlinx.serialization.json.JsonPrimitive(value.value.toString())
                }
            })
        )
    }
}
