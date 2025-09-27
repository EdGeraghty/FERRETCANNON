package utils

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.Signature
import java.security.Security
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import java.util.Base64
import org.slf4j.LoggerFactory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.ServerKeys as ServerKeysTable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put

// Register I2P provider at class loading time
private object I2PProviderRegistrar {
    private val provider = EdDSASecurityProvider()
    init {
        Security.addProvider(provider)
    }
    
    fun getProvider(): EdDSASecurityProvider = provider
}

object ServerKeys {
    private val logger = LoggerFactory.getLogger("utils.ServerKeys")
    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private lateinit var privateKey: EdDSAPrivateKey
    private lateinit var publicKey: EdDSAPublicKey
    private lateinit var publicKeyBase64: String
    private lateinit var keyId: String
    private var keysLoaded = false
    private var privateKeySeed: ByteArray? = null
    private val serverName: String = ServerNameResolver.getServerName()

    private fun loadOrGenerateKeyPair() {
        logger.debug("Loading or generating Ed25519 key pair for server: $serverName")

        transaction {
            // Try to load existing key from database
            val existingKey = ServerKeysTable.select {
                (ServerKeysTable.serverName eq serverName) and
                (ServerKeysTable.keyId eq "ed25519:0")
            }.singleOrNull()

            if (existingKey != null) {
                // Load existing key
                val storedPublicKey = existingKey[ServerKeysTable.publicKey]
                val storedPrivateKey = existingKey[ServerKeysTable.privateKey]
                logger.info("✅ Found existing server key pair with ID: ed25519:0")

                if (storedPrivateKey != null) {
                    // Load the private key
                    val privateKeyBytes = Base64.getDecoder().decode(storedPrivateKey)
                    privateKeySeed = privateKeyBytes
                    val privateKeySpec = EdDSAPrivateKeySpec(privateKeyBytes, spec)
                    privateKey = EdDSAPrivateKey(privateKeySpec)

                    // Derive public key from private key
                    val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, spec)
                    publicKey = EdDSAPublicKey(publicKeySpec)
                    publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.abyte)
                    keyId = "ed25519:0"

                    // Verify the stored public key matches
                    if (storedPublicKey != publicKeyBase64) {
                        logger.warn("⚠️  Stored public key doesn't match derived key - updating database")
                        updateStoredKey()
                    } else {
                        logger.info("✅ Stored public key matches derived key")
                    }
                } else {
                    // No private key stored, regenerate (for backward compatibility)
                    generateKeyPair()
                    // Update the database with the new private key
                    updateStoredKey()
                }
            } else {
                // Generate new key pair
                logger.info("🔑 No existing key found - generating new Ed25519 key pair")
                generateKeyPair()
                storeKeyInDatabase()
            }
        }
        keysLoaded = true
    }

    private fun generateKeyPair() {
        logger.debug("Generating Ed25519 key pair...")
        try {
            // Generate a random 32-byte seed for Ed25519
            val random = java.security.SecureRandom()
            val seedBytes = ByteArray(32)
            random.nextBytes(seedBytes)
            privateKeySeed = seedBytes

            // Create private key from random seed
            val privateKeySpec = EdDSAPrivateKeySpec(seedBytes, spec)
            privateKey = EdDSAPrivateKey(privateKeySpec)

            // Derive public key from private key
            val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, spec)
            publicKey = EdDSAPublicKey(publicKeySpec)

            // Use unpadded URL-safe Base64 as per Matrix specification appendices
            publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.abyte)
            keyId = "ed25519:0"

            logger.info("✅ Generated server key pair with ID: $keyId")
            logger.debug("Public key (first 32 chars): ${publicKeyBase64.take(32)}...")
        } catch (e: Exception) {
            logger.error("Failed to generate key pair", e)
            throw e
        }
    }

    private fun updateStoredKey() {
        transaction {
            val currentKeyId = keyId
            val currentPublicKeyBase64 = publicKeyBase64
            val currentPrivateKeyBase64 = Base64.getEncoder().encodeToString(privateKeySeed!!)
            
            ServerKeysTable.update({ (ServerKeysTable.serverName eq serverName) and (ServerKeysTable.keyId eq currentKeyId) }) {
                it[ServerKeysTable.publicKey] = currentPublicKeyBase64
                it[ServerKeysTable.privateKey] = currentPrivateKeyBase64
                it[ServerKeysTable.keyValidUntilTs] = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L) // Valid for 1 year
                it[ServerKeysTable.tsValidUntilTs] = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L)
            }
            logger.debug("🔒 Updated stored server key in database")
        }
    }

    private fun storeKeyInDatabase() {
        transaction {
            // Store public and private keys in database
            val validUntilTs = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L) // Valid for 1 year
            val currentTime = System.currentTimeMillis()
            
            val currentKeyId = keyId
            val currentPublicKeyBase64 = publicKeyBase64
            val currentPrivateKeyBase64 = Base64.getEncoder().encodeToString(privateKeySeed!!)

            try {
                // Try to insert first
                ServerKeysTable.insert {
                    it[ServerKeysTable.serverName] = serverName
                    it[ServerKeysTable.keyId] = currentKeyId
                    it[ServerKeysTable.publicKey] = currentPublicKeyBase64
                    it[ServerKeysTable.privateKey] = currentPrivateKeyBase64
                    it[ServerKeysTable.keyValidUntilTs] = validUntilTs
                    it[ServerKeysTable.tsValidUntilTs] = validUntilTs
                    it[ServerKeysTable.tsAddedTs] = currentTime
                }
                logger.debug("🔒 Inserted new server key in database")
            } catch (e: Exception) {
                // If insert fails (key already exists), try update
                logger.debug("Key already exists, updating...")
                updateStoredKey()
            }

            logger.debug("🔒 Stored server key in database with validity until: $validUntilTs")
        }
    }

    // Test function to verify against Matrix specification test vectors
    fun testWithSpecificationSeed(): Map<String, Boolean> {
        // SIGNING_KEY_SEED from Matrix specification appendices
        val testSeed = Base64.getDecoder().decode("YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1")

        val testPrivateKeySpec = EdDSAPrivateKeySpec(testSeed, spec)
        val testPrivateKey = EdDSAPrivateKey(testPrivateKeySpec)

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
        val signature = EdDSAEngine()
        signature.initSign(key)
        signature.update(data)
        val signatureBytes = signature.sign()
        // Use unpadded Base64 as per Matrix specification appendices
        return Base64.getEncoder().withoutPadding().encodeToString(signatureBytes)
    }

    fun getStoredKeys(): List<Map<String, Any>> {
        return transaction {
            ServerKeysTable.selectAll().map { row ->
                mutableMapOf(
                    "server_name" to row[ServerKeysTable.serverName],
                    "key_id" to row[ServerKeysTable.keyId],
                    "public_key" to row[ServerKeysTable.publicKey],
                    "key_valid_until_ts" to row[ServerKeysTable.keyValidUntilTs],
                    "ts_added_ts" to row[ServerKeysTable.tsAddedTs],
                    "ts_valid_until_ts" to row[ServerKeysTable.tsValidUntilTs]
                )
            }
        }
    }

    fun getStoredKey(keyId: String): Map<String, Any>? {
        return transaction {
            ServerKeysTable.select {
                (ServerKeysTable.serverName eq serverName) and
                (ServerKeysTable.keyId eq keyId)
            }.singleOrNull()?.let { row ->
                mutableMapOf(
                    "server_name" to row[ServerKeysTable.serverName],
                    "key_id" to row[ServerKeysTable.keyId],
                    "public_key" to row[ServerKeysTable.publicKey],
                    "key_valid_until_ts" to row[ServerKeysTable.keyValidUntilTs],
                    "ts_added_ts" to row[ServerKeysTable.tsAddedTs],
                    "ts_valid_until_ts" to row[ServerKeysTable.tsValidUntilTs]
                )
            }
        }
    }

    fun getPublicKey(): String {
        ensureKeysLoaded()
        return publicKeyBase64
    }

    fun getKeyId(): String {
        ensureKeysLoaded()
        return keyId
    }

    fun generateEd25519KeyPair(): EdDSAPrivateKey {
        ensureKeysLoaded()
        return privateKey
    }

    fun encodeEd25519PublicKey(publicKey: EdDSAPublicKey): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.abyte)
    }

    fun generateKeyId(): String {
        ensureKeysLoaded()
        return keyId
    }

    private fun ensureKeysLoaded() {
        if (!keysLoaded) {
            logger.debug("Keys not initialized, loading/generating...")
            loadOrGenerateKeyPair()
            keysLoaded = true
            debugStoredKeys()
        }
    }

    fun debugStoredKeys() {
        logger.info("=== DEBUG: Stored Keys in Database ===")
        val storedKeys = getStoredKeys()
        logger.info("Found ${storedKeys.size} keys in database:")
        storedKeys.forEach { key ->
            logger.info("  Server: ${key["server_name"]}, Key ID: ${key["key_id"]}")
            logger.info("  Public Key: ${(key["public_key"] as String).take(50)}...")
            logger.info("  Valid Until: ${key["key_valid_until_ts"]}")
        }
    }

    fun sign(data: ByteArray): String {
        ensureKeysLoaded()
        logger.trace("Signing ${data.size} bytes of data")
        try {
            val signature = EdDSAEngine()
            signature.initSign(privateKey)
            signature.update(data)
            val signatureBytes = signature.sign()
            // Use unpadded Base64 as per Matrix specification appendices
            val signatureBase64 = Base64.getEncoder().withoutPadding().encodeToString(signatureBytes)
            logger.trace("Generated signature: ${signatureBase64.take(32)}...")
            return signatureBase64
        } catch (e: Exception) {
            logger.error("Failed to sign data with EdDSA", e)
            throw e
        }
    }

    fun verify(data: ByteArray, signature: String): Boolean {
        ensureKeysLoaded()
        logger.trace("Verifying signature for ${data.size} bytes of data")
        return try {
            // Handle both padded and unpadded Base64 for compatibility
            val signatureBytes = try {
                Base64.getDecoder().decode(signature)
            } catch (e: IllegalArgumentException) {
                // Try with padding if unpadded decoding fails
                Base64.getDecoder().decode(signature)
            }

            val sig = EdDSAEngine()
            sig.initVerify(publicKey)
            sig.update(data)
            val result = sig.verify(signatureBytes)
            logger.trace("Signature verification result: $result")
            result
        } catch (e: Exception) {
            logger.warn("Signature verification failed", e)
            false
        }
    }

    fun getServerKeys(serverName: String): JsonObject {
        ensureKeysLoaded()
        logger.debug("Generating server keys response for server: $serverName")
        val currentTime = System.currentTimeMillis()

        // Capture values to avoid smart cast issues with mutable properties
        val currentKeyId = keyId
        val currentPublicKeyBase64 = publicKeyBase64

        // Get all valid keys from database
        val verifyKeys = mutableMapOf<String, Map<String, Any>>()
        val oldVerifyKeys = mutableMapOf<String, Map<String, Any>>()
        val validUntilTs = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L) // Current time + 1 year

        transaction {
            ServerKeysTable.select {
                (ServerKeysTable.serverName eq serverName) and
                (ServerKeysTable.keyValidUntilTs greater currentTime)
            }.forEach { row ->
                val keyId = row[ServerKeysTable.keyId]
                val publicKey = row[ServerKeysTable.publicKey]
                val keyValidUntilTs = row[ServerKeysTable.keyValidUntilTs]

                if (keyId == currentKeyId) {
                    // Current key goes in verify_keys - use its stored validity time
                    verifyKeys[keyId] = mutableMapOf("key" to publicKey)
                } else {
                    // Old keys go in old_verify_keys
                    oldVerifyKeys[keyId] = mutableMapOf(
                        "key" to publicKey,
                        "expired_ts" to keyValidUntilTs
                    )
                }
            }
        }

        // If no keys found in database, use current key
        if (verifyKeys.isEmpty()) {
            verifyKeys[currentKeyId] = mutableMapOf("key" to currentPublicKeyBase64)
        }

        val serverKeys = buildJsonObject {
            put("server_name", serverName)
            put("valid_until_ts", validUntilTs)
            putJsonObject("verify_keys") {
                verifyKeys.forEach { (keyId, keyData) ->
                    putJsonObject(keyId) {
                        put("key", keyData["key"] as String)
                    }
                }
            }
            if (oldVerifyKeys.isNotEmpty()) {
                putJsonObject("old_verify_keys") {
                    oldVerifyKeys.forEach { (keyId, keyData) ->
                        putJsonObject(keyId) {
                            put("key", keyData["key"] as String)
                            put("expired_ts", keyData["expired_ts"] as Long)
                        }
                    }
                }
            }
            putJsonObject("signatures") {
                putJsonObject(serverName) {
                    put(currentKeyId, sign(getServerKeysCanonicalJson(serverName, verifyKeys, oldVerifyKeys, validUntilTs).toByteArray(Charsets.UTF_8)))
                }
            }
        }

        // Test signature verification
        val canonicalJson = getServerKeysCanonicalJson(serverName, verifyKeys, oldVerifyKeys, validUntilTs)
        val signatures = serverKeys["signatures"] as JsonObject
        val serverSigs = signatures[serverName] as JsonObject
        val generatedSignature = (serverSigs[currentKeyId] as JsonPrimitive).content
        val testVerify = verify(canonicalJson.toByteArray(Charsets.UTF_8), generatedSignature)
        logger.info("Signature verification test for generated keys: $testVerify")
        if (!testVerify) {
            logger.error("Generated signature does not verify against canonical JSON!")
        }

        logger.debug("Server keys generated with ${verifyKeys.size} verify keys and ${oldVerifyKeys.size} old keys")
        logger.trace("Server keys response: ${serverKeys}")
        return serverKeys
    }

    private fun getServerKeysCanonicalJson(serverName: String, verifyKeys: Map<String, Map<String, Any>>, oldVerifyKeys: Map<String, Map<String, Any>>, validUntilTs: Long): String {
        // Create canonical JSON string with top-level keys in lexicographical order, sub-keys sorted
        val verifyKeysJson = verifyKeys.entries.sortedBy { it.key }.joinToString(",") { (keyId, keyData) ->
            "\"$keyId\":{\"key\":\"${keyData["key"]}\"}"
        }

        val oldVerifyKeysJson = oldVerifyKeys.entries.sortedBy { it.key }.joinToString(",") { (keyId, keyData) ->
            "\"$keyId\":{\"expired_ts\":${keyData["expired_ts"]},\"key\":\"${keyData["key"]}\"}"
        }

        // Collect parts in a map to sort keys
        val parts = mutableMapOf<String, String>()
        parts["server_name"] = "\"server_name\":\"$serverName\""
        parts["valid_until_ts"] = "\"valid_until_ts\":$validUntilTs"
        parts["verify_keys"] = "\"verify_keys\":{$verifyKeysJson}"
        if (oldVerifyKeys.isNotEmpty()) {
            parts["old_verify_keys"] = "\"old_verify_keys\":{$oldVerifyKeysJson}"
        }

        return "{${parts.entries.sortedBy { it.key }.joinToString(",") { it.value }}}"
    }
}
