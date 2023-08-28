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
        publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.abyte)
        keyId = "ed25519:0"
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
        return Base64.getEncoder().encodeToString(signatureBytes)
    }

    fun verify(data: ByteArray, signature: String): Boolean {
        return try {
            val signatureBytes = Base64.getDecoder().decode(signature)
            val sig = Signature.getInstance("EdDSA", "I2P")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
