package keys

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import utils.ServerKeys
import utils.ServerNameResolver

class ServerKeysEndpointTest {
    @Test
    fun `server keys endpoint includes old_verify_keys and signature verifies`() {
        ServerKeys.initKeysForTests()

        val serverName = ServerNameResolver.getServerName()
        val serverKeys = ServerKeys.getServerKeys(serverName)

        // Ensure old_verify_keys exists (may be empty object)
        assertTrue(serverKeys.jsonObject.containsKey("old_verify_keys"))

        // Extract valid_until_ts from response
        val validUntilTs = serverKeys.jsonObject["valid_until_ts"]?.jsonPrimitive?.long
        assertNotNull(validUntilTs)

        // Reconstruct verifyKeys map for canonical JSON generation
        val keyId = ServerKeys.getKeyId()
        val pub = ServerKeys.getPublicKey()
        val verifyKeys = mapOf(keyId to mapOf("key" to pub))
        val oldVerifyKeys = emptyMap<String, Map<String, Any>>()

        val canonical = ServerKeys.getServerKeysCanonicalJsonPublic(serverName, verifyKeys, oldVerifyKeys, validUntilTs!!)
        assertTrue(canonical.isNotBlank())

        // Extract signature from the returned serverKeys JSON
        val signatures = serverKeys.jsonObject["signatures"]?.jsonObject
        assertNotNull(signatures)
        val serverSigs = signatures[serverName]?.jsonObject
        assertNotNull(serverSigs)
        val signature = serverSigs[keyId]?.jsonPrimitive?.content
        assertNotNull(signature)

        // Verify signature
        val verified = ServerKeys.verify(canonical.toByteArray(Charsets.UTF_8), signature!!)
        assertTrue(verified)
    }
}
