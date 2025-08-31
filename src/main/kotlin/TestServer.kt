import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import utils.CryptoTestVectors

fun main() {
    println("Starting FERRETCANNON Matrix Server Test...")

    // Run cryptographic test vectors
    println("\n=== Running Cryptographic Test Vectors ===")
    val cryptoTests = CryptoTestVectors().runTests()
    cryptoTests.forEach { (testName, passed) ->
        val status = if (passed) "✅ PASSED" else "❌ FAILED"
        println("$testName: $status")
    }

    val allPassed = cryptoTests.values.all { it }
    if (allPassed) {
        println("\n🎉 All cryptographic tests PASSED! Implementation is compliant with Matrix specification.")
    } else {
        println("\n⚠️  Some cryptographic tests FAILED. Please check the implementation.")
    }

    println("\n=== Starting Server ===")
    embeddedServer(Netty, port = 8081, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText("FERRETCANNON Matrix Server - Cryptographic Tests: ${if (allPassed) "PASSED" else "FAILED"}", ContentType.Text.Plain)
            }
            get("/crypto-test") {
                val results = cryptoTests.map { (name, passed) -> "$name: ${if (passed) "PASSED" else "FAILED"}" }
                call.respondText("Cryptographic Test Results:\n${results.joinToString("\n")}", ContentType.Text.Plain)
            }
        }
    }.start(wait = true)
}
