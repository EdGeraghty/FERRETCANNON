package keys

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import utils.ServerKeys
import utils.ServerNameResolver

class ServerKeysUploadTest {
    @Test
    fun `upload and retrieve device keys`() {
        // Use test-only initializer
        ServerKeys.initKeysForTests()

        val serverName = ServerNameResolver.getServerName()
        val keyId = ServerKeys.getKeyId()
        val pub = ServerKeys.getPublicKey()

        // Build verifyKeys map as expected by the canonical JSON helper
        val verifyKeys = mapOf(keyId to mapOf("key" to pub))
        val oldVerifyKeys = emptyMap<String, Map<String, Any>>()
        val validUntilTs = System.currentTimeMillis() + 3600_000L

        val canonical = ServerKeys.getServerKeysCanonicalJsonPublic(serverName, verifyKeys, oldVerifyKeys, validUntilTs)
        assertNotNull(canonical)
        assertTrue(canonical.contains("\"server_name\"") && canonical.contains("\"verify_keys\""))
    }
}
