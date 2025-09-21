package utils

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.Signature
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.*

@OptIn(ExperimentalEncodingApi::class)
class CryptoTestVectors {

    private val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    // SIGNING_KEY_SEED from appendices
    private val signingKeySeed = Base64.decode("YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1")

    private val privateKey: EdDSAPrivateKey
    private val publicKey: EdDSAPublicKey

    init {
        val privateKeySpec = EdDSAPrivateKeySpec(signingKeySeed, spec)
        privateKey = EdDSAPrivateKey(privateKeySpec)
        val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, spec)
        publicKey = EdDSAPublicKey(publicKeySpec)
    }

    fun testEmptyObjectSigning(): Boolean {
        val jsonObject = JsonObject(mutableMapOf())
        val canonicalJson = canonicalJson(jsonObject)
        val signature = signJson(canonicalJson)

        val expectedSignature = "K8280/U9SSy9IVtjBuVeLr+HpOB4BQFWbg+UZaADMtTdGYI7Geitb76LTrr5QV/7Xg4ahLwYGYZzuHGZKM5ZAQ"

        return signature == expectedSignature
    }

    fun testDataObjectSigning(): Boolean {
        val jsonObject = buildJsonObject {
            put("one", 1)
            put("two", "Two")
        }
        val canonicalJson = canonicalJson(jsonObject)
        val signature = signJson(canonicalJson)

        val expectedSignature = "KqmLSbO39/Bzb0QIYE82zqLwsA+PDzYIpIRA2sRQ4sL53+sN6/fpNSoqE7BP7vBZhG6kYdD13EIMJpvhJI+6Bw"

        return signature == expectedSignature
    }

    private fun canonicalJson(jsonElement: JsonElement): String {
        return when (jsonElement) {
            is JsonObject -> {
                val sortedEntries = jsonElement.entries.sortedBy { it.key }
                val canonicalEntries = sortedEntries.map { (key, value) ->
                    "\"$key\":${canonicalJson(value)}"
                }
                "{${canonicalEntries.joinToString(",")}}"
            }
            is JsonArray -> {
                val canonicalElements = jsonElement.map { canonicalJson(it) }
                "[${canonicalElements.joinToString(",")}]"
            }
            is JsonPrimitive -> {
                when {
                    jsonElement.isString -> "\"${jsonElement.content}\""
                    jsonElement.booleanOrNull != null -> jsonElement.boolean.toString()
                    jsonElement.longOrNull != null -> {
                        val longValue = jsonElement.long
                        if (longValue in -(2L shl 53) + 1..(2L shl 53) - 1) {
                            longValue.toString()
                        } else {
                            throw IllegalArgumentException("Number out of range for canonical JSON")
                        }
                    }
                    jsonElement.doubleOrNull != null -> {
                        val doubleValue = jsonElement.double
                        if (doubleValue == doubleValue.toLong().toDouble()) {
                            doubleValue.toLong().toString()
                        } else {
                            throw IllegalArgumentException("Float values not permitted in canonical JSON")
                        }
                    }
                    else -> "null"
                }
            }
            else -> throw IllegalArgumentException("Unsupported JSON element type")
        }
    }

    private fun signJson(canonicalJson: String): String {
        val signature = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, "I2P")
        signature.initSign(privateKey)
        signature.update(canonicalJson.toByteArray(Charsets.UTF_8))
        val sigBytes = signature.sign()
        return Base64.encode(sigBytes).replace("=", "")
    }

    fun runTests(): Map<String, Boolean> {
        return mutableMapOf(
            "Empty Object Signing" to testEmptyObjectSigning(),
            "Data Object Signing" to testDataObjectSigning()
        )
    }
}
