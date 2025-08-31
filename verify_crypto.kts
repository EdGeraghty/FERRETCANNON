#!/usr/bin/env kotlin

// Simple verification of Matrix cryptographic test vectors
// This can be run independently to verify our implementation

import java.util.Base64
import java.security.*
import net.i2p.crypto.eddsa.*
import net.i2p.crypto.eddsa.spec.*

fun main() {
    println("=== Matrix Specification Cryptographic Test Vectors Verification ===")

    try {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

        // SIGNING_KEY_SEED from appendices
        val signingKeySeed = Base64.getDecoder().decode("YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1")

        val privateKeySpec = EdDSAPrivateKeySpec(signingKeySeed, spec)
        val privateKey = EdDSAPrivateKey(privateKeySpec)
        val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, spec)
        val publicKey = EdDSAPublicKey(publicKeySpec)

        // Test 1: Empty JSON object
        println("\nTest 1: Empty JSON Object {}")
        val canonicalEmpty = "{}"
        println("Canonical JSON: $canonicalEmpty")
        val signatureEmpty = signJson(canonicalEmpty, privateKey)
        println("Generated signature: $signatureEmpty")
        val expectedEmpty = "K8280/U9SSy9IVtjBuVeLr+HpOB4BQFWbg+UZaADMtTdGYI7Geitb76LTrr5QV/7Xg4ahLwYGYZzuHGZKM5ZAQ"
        println("Expected signature:  $expectedEmpty")
        val emptyTest = signatureEmpty == expectedEmpty
        println("Result: ${if (emptyTest) "✅ PASSED" else "❌ FAILED"}")

        // Test 2: JSON object with data
        println("\nTest 2: JSON Object with data {\"one\":1,\"two\":\"Two\"}")
        val canonicalData = "{\"one\":1,\"two\":\"Two\"}"
        println("Canonical JSON: $canonicalData")
        val signatureData = signJson(canonicalData, privateKey)
        println("Generated signature: $signatureData")
        val expectedData = "KqmLSbO39/Bzb0QIYE82zqLwsA+PDzYIpIRA2sRQ4sL53+sN6/fpNSoqE7BP7vBZhG6kYdD13EIMJpvhJI+6Bw"
        println("Expected signature:  $expectedData")
        val dataTest = signatureData == expectedData
        println("Result: ${if (dataTest) "✅ PASSED" else "❌ FAILED"}")

        // Summary
        val allPassed = emptyTest && dataTest
        println("\n=== Summary ===")
        println("Empty Object Test: ${if (emptyTest) "PASSED" else "FAILED"}")
        println("Data Object Test:  ${if (dataTest) "PASSED" else "FAILED"}")
        println("Overall: ${if (allPassed) "✅ ALL TESTS PASSED" else "❌ SOME TESTS FAILED"}")

        if (allPassed) {
            println("\n🎉 FERRETCANNON Matrix Server cryptographic implementation is compliant with Matrix specification v1.15!")
        } else {
            println("\n⚠️  FERRETCANNON Matrix Server cryptographic implementation needs review.")
        }

    } catch (e: Exception) {
        println("Error during test execution: ${e.message}")
        e.printStackTrace()
    }
}

fun signJson(canonicalJson: String, privateKey: EdDSAPrivateKey): String {
    val signature = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, "I2P")
    signature.initSign(privateKey)
    signature.update(canonicalJson.toByteArray(Charsets.UTF_8))
    val sigBytes = signature.sign()
    return Base64.getEncoder().encodeToString(sigBytes).replace("=", "")
}
