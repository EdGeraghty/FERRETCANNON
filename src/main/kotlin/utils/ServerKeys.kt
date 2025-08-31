package utils

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.Signature
import java.util.Base64

object ServerKeys {
    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private lateinit var privateKey: EdDSAPrivateKey
    private lateinit var publicKey: EdDSAPublicKey
    private lateinit var publicKeyBase64: String
    private lateinit var keyId: String

    init {
        generateKeyPair()
    }

    private fun generateKeyPair() {
        val keyPairGenerator = net.i2p.crypto.eddsa.KeyPairGenerator()
        keyPairGenerator.initialize(spec, null)
        val keyPair = keyPairGenerator.generateKeyPair()

        privateKey = keyPair.private as EdDSAPrivateKey
        publicKey = keyPair.public as EdDSAPublicKey
        // Use unpadded Base64 as per Matrix specification appendices
        publicKeyBase64 = Base64.getEncoder().withoutPadding().encodeToString(publicKey.abyte)
        keyId = "ed25519:0"
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
        val canonicalData = "{\"one\":1,\"two\":\"Two\"}"
        val signatureData = signWithKey(canonicalData.toByteArray(Charsets.UTF_8), testPrivateKey)
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
        val signature = Signature.getInstance("EdDSA", "I2P")
        signature.initSign(privateKey)
        signature.update(data)
        val signatureBytes = signature.sign()
        // Use unpadded Base64 as per Matrix specification appendices
        return Base64.getEncoder().withoutPadding().encodeToString(signatureBytes)
    }

    fun verify(data: ByteArray, signature: String): Boolean {
        return try {
            // Handle both padded and unpadded Base64 for compatibility
            val signatureBytes = try {
                Base64.getDecoder().decode(signature)
            } catch (e: IllegalArgumentException) {
                // Try with padding if unpadded decoding fails
                Base64.getDecoder().decode(signature)
            }
            val sig = Signature.getInstance("EdDSA", "I2P")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
