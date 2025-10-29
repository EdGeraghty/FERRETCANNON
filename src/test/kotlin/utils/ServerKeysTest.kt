package utils

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ServerKeysTest {
    @Test
    fun `server keys signature roundtrip`() {
    // Initialize an in-memory keypair to avoid DB access in unit tests
    ServerKeys.initKeysForTests()
    val serverName = ServerNameResolver.getServerName()

    val verifyKeys = mapOf(ServerKeys.getKeyId() to mapOf("key" to ServerKeys.getPublicKey()))
        val oldVerifyKeys = emptyMap<String, Map<String, Any>>()
        val validUntilTs = System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000L)

        val canonical = ServerKeys.getServerKeysCanonicalJsonPublic(serverName, verifyKeys, oldVerifyKeys, validUntilTs)

        val sig = ServerKeys.sign(canonical.toByteArray(Charsets.UTF_8))
        val verified = ServerKeys.verify(canonical.toByteArray(Charsets.UTF_8), sig)

        assertTrue(verified, "Signature should verify for canonical server keys JSON")
    }
}
