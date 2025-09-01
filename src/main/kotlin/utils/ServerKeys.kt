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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.ServerKeys as ServerKeysTable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

object ServerKeys {
    private val logger = LoggerFactory.getLogger("utils.ServerKeys")
    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private lateinit var privateKey: EdDSAPrivateKey
    private lateinit var publicKey: EdDSAPublicKey
    private lateinit var publicKeyBase64: String
    private lateinit var keyId: String
    private lateinit var serverName: String

    // Use a deterministic seed based on server name for consistent key generation
    private val SERVER_KEY_SEED = "FERRETCANNON_MATRIX_SERVER_SEED_2024"

    init {
        logger.info("🔐 ServerKeys utility initialized - deterministic key generation enabled")
        serverName = ServerNameResolver.getServerName()
    }

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
                logger.info("✅ Found existing server key pair with ID: ed25519:0")

                // Generate the same key pair using deterministic seed
                generateDeterministicKeyPair()

                // Verify the stored public key matches our generated one
                if (storedPublicKey != publicKeyBase64) {
                    logger.warn("⚠️  Stored public key doesn't match generated key - updating database")
                    updateStoredKey()
                } else {
                    logger.info("✅ Stored public key matches generated key")
                }
            } else {
                // Generate new deterministic key pair
                logger.info("🔑 No existing key found - generating new deterministic Ed25519 key pair")
                generateDeterministicKeyPair()
                storeKeyInDatabase()
            }
        }
    }

    private fun generateDeterministicKeyPair() {
        logger.debug("Generating deterministic Ed25519 key pair...")
        try {
            // Create a deterministic seed based on server name and fixed seed
            val seedString = "$SERVER_KEY_SEED:$serverName"
            val seedBytes = seedString.toByteArray(Charsets.UTF_8)

            // Use SHA-256 to create a 32-byte seed from our string
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            val deterministicSeed = sha256.digest(seedBytes).copyOfRange(0, 32) // Ed25519 needs 32 bytes

            // Create private key from deterministic seed
            val privateKeySpec = EdDSAPrivateKeySpec(deterministicSeed, spec)
            privateKey = EdDSAPrivateKey(privateKeySpec)

            // Derive public key from private key
            val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, spec)
            publicKey = EdDSAPublicKey(publicKeySpec)

            // Use unpadded Base64 as per Matrix specification appendices
            publicKeyBase64 = Base64.getEncoder().withoutPadding().encodeToString(publicKey.abyte)
            keyId = "ed25519:0"

            logger.info("✅ Generated deterministic server key pair with ID: $keyId")
            logger.debug("Public key (first 32 chars): ${publicKeyBase64.take(32)}...")
        } catch (e: Exception) {
            logger.error("Failed to generate deterministic key pair", e)
            throw e
        }
    }

    private fun updateStoredKey() {
        transaction {
            ServerKeysTable.update({ (ServerKeysTable.serverName eq serverName) and (ServerKeysTable.keyId eq keyId) }) {
                it[ServerKeysTable.publicKey] = publicKeyBase64
                it[ServerKeysTable.keyValidUntilTs] = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L) // Valid for 1 year
                it[ServerKeysTable.tsValidUntilTs] = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L)
            }
            logger.debug("🔒 Updated stored server key in database")
        }
    }

    private fun storeKeyInDatabase() {
        transaction {
            // Store public key in database (we don't store private key for security)
            val validUntilTs = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L) // Valid for 1 year
            val currentTime = System.currentTimeMillis()

            try {
                // Try to insert first
                ServerKeysTable.insert {
                    it[ServerKeysTable.serverName] = serverName
                    it[ServerKeysTable.keyId] = keyId
                    it[ServerKeysTable.publicKey] = publicKeyBase64
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

    fun getStoredKeys(): List<Map<String, Any>> {
        return transaction {
            ServerKeysTable.selectAll().map { row ->
                mapOf(
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
                mapOf(
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

    private fun ensureKeysLoaded() {
        if (!::privateKey.isInitialized) {
            logger.debug("Keys not initialized, loading/generating...")
            loadOrGenerateKeyPair()
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
        ensureKeysLoaded()
        logger.debug("Generating server keys response for server: $serverName")
        val currentTime = System.currentTimeMillis()

        // Get all valid keys from database
        val verifyKeys = mutableMapOf<String, Map<String, Any>>()
        val oldVerifyKeys = mutableMapOf<String, Map<String, Any>>()
        var validUntilTs: Long = currentTime + 86400000 // Default fallback

        transaction {
            ServerKeysTable.select {
                (ServerKeysTable.serverName eq serverName) and
                (ServerKeysTable.keyValidUntilTs greater currentTime)
            }.forEach { row ->
                val keyId = row[ServerKeysTable.keyId]
                val publicKey = row[ServerKeysTable.publicKey]
                val keyValidUntilTs = row[ServerKeysTable.keyValidUntilTs]

                if (keyId == this@ServerKeys.keyId) {
                    // Current key goes in verify_keys - use its stored validity time
                    verifyKeys[keyId] = mapOf("key" to publicKey)
                    validUntilTs = keyValidUntilTs // Use the stored validity time for consistency
                } else {
                    // Old keys go in old_verify_keys
                    oldVerifyKeys[keyId] = mapOf(
                        "key" to publicKey,
                        "expired_ts" to keyValidUntilTs
                    )
                }
            }
        }

        // If no keys found in database, use current key with default validity
        if (verifyKeys.isEmpty()) {
            verifyKeys[keyId] = mapOf("key" to publicKeyBase64)
        }

        val serverKeys = mapOf(
            "server_name" to serverName,
            "valid_until_ts" to validUntilTs,
            "verify_keys" to verifyKeys,
            "old_verify_keys" to oldVerifyKeys,
            "signatures" to mapOf(
                serverName to mapOf(
                    keyId to sign(getServerKeysCanonicalJson(serverName, verifyKeys, oldVerifyKeys, validUntilTs).toByteArray(Charsets.UTF_8))
                )
            )
        )

        logger.debug("Server keys generated with ${verifyKeys.size} verify keys and ${oldVerifyKeys.size} old keys")
        logger.trace("Server keys response: ${serverKeys.keys.joinToString()}")
        return serverKeys
    }

    private fun getServerKeysCanonicalJson(serverName: String, verifyKeys: Map<String, Map<String, Any>>, oldVerifyKeys: Map<String, Map<String, Any>>, validUntilTs: Long): String {
        // Create canonical JSON string manually for signing
        val verifyKeysJson = verifyKeys.entries.joinToString(",") { (keyId, keyData) ->
            "\"$keyId\":{\"key\":\"${keyData["key"]}\"}"
        }
        val oldVerifyKeysJson = oldVerifyKeys.entries.joinToString(",") { (keyId, keyData) ->
            "\"$keyId\":{\"key\":\"${keyData["key"]}\",\"expired_ts\":${keyData["expired_ts"]}}"
        }

        return "{\"server_name\":\"$serverName\",\"valid_until_ts\":$validUntilTs,\"verify_keys\":{$verifyKeysJson},\"old_verify_keys\":{$oldVerifyKeysJson}}"
    }
}
