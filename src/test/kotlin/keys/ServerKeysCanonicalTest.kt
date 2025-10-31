package keys

import kotlin.test.Test
import kotlin.test.assertTrue
import utils.ServerKeys
import utils.ServerNameResolver

class ServerKeysCanonicalTest {
    @Test
    fun `canonical json signing roundtrip`() {
        ServerKeys.initKeysForTests()
        val serverName = ServerNameResolver.getServerName()
        val keyId = ServerKeys.getKeyId()
        val pub = ServerKeys.getPublicKey()
        val verifyKeys = mapOf(keyId to mapOf("key" to pub))
        val oldVerifyKeys = emptyMap<String, Map<String, Any>>()
        val validUntilTs = System.currentTimeMillis() + 3600_000L

        val canonical = ServerKeys.getServerKeysCanonicalJsonPublic(serverName, verifyKeys, oldVerifyKeys, validUntilTs)
        assertTrue(canonical.isNotBlank())

        // Sign using ServerKeys and verify signature using public key
        val signature = ServerKeys.sign(canonical.toByteArray(Charsets.UTF_8))
        val verified = ServerKeys.verify(canonical.toByteArray(Charsets.UTF_8), signature)
        assertTrue(verified)
    }
}
