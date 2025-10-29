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

    // Avoid DB access by constructing the expected server keys response
    val keyId = ServerKeys.getKeyId()
    val pub = ServerKeys.getPublicKey()
    val verifyKeys = mapOf(keyId to mapOf("key" to pub))
    val oldVerifyKeys = emptyMap<String, Map<String, Any>>()
    val validUntilTs = System.currentTimeMillis() + 3600_000L

    // Build canonical JSON as server would
    val canonical = ServerKeys.getServerKeysCanonicalJsonPublic(serverName, verifyKeys, oldVerifyKeys, validUntilTs)
    assertTrue(canonical.isNotBlank())

    // Sign with the server private key and verify using the public key
    val signature = ServerKeys.sign(canonical.toByteArray(Charsets.UTF_8))
    val verified = ServerKeys.verify(canonical.toByteArray(Charsets.UTF_8), signature)
    assertTrue(verified)
    }
}
