import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import utils.MatrixAuth
import kotlin.test.assertEquals

/**
 * Test suite validating FERRETCANNON's event signing and hashing implementation
 * against test vectors extracted from Synapse's test suite.
 * 
 * These tests ensure FERRETCANNON produces identical outputs to Synapse for
 * critical cryptographic operations, which is essential for federation compatibility.
 * 
 * Test vectors source: element-hq/synapse/tests/crypto/test_event_signing.py
 * 
 * Big shoutout to the FERRETCANNON massive for rigorous testing! 🎆
 */
class SynapseEventSigningTest {
    
    @Test
    fun `synapse test vector - minimal event hash`() {
        // Test vector from Synapse: minimal event with only required fields
        val event = Json.parseToJsonElement("""
            {
              "event_id": "${'$'}0:domain",
              "origin_server_ts": 1000000,
              "signatures": {},
              "type": "X",
              "unsigned": {
                "age_ts": 1000000
              }
            }
        """).jsonObject
        
        val expectedHash = "A6Nco6sqoy18PPfPDVdYvoowfc0PVBk9g9OiyT3ncRM"
        val actualHash = MatrixAuth.computeContentHashPublic(event)
        
        assertEquals(
            expectedHash,
            actualHash,
            "Hash for minimal event should match Synapse's computation"
        )
    }
    
    @Test
    fun `synapse test vector - message event hash`() {
        // Test vector from Synapse: full room message event
        val event = Json.parseToJsonElement("""
            {
              "content": {
                "body": "Here is the message content"
              },
              "event_id": "${'$'}0:domain",
              "origin_server_ts": 1000000,
              "type": "m.room.message",
              "room_id": "!r:domain",
              "sender": "@u:domain",
              "signatures": {},
              "unsigned": {
                "age_ts": 1000000
              }
            }
        """).jsonObject
        
        val expectedHash = "rDCeYBepPlI891h/RkI2/Lkf9bt7u0TxFku4tMs7WKk"
        val actualHash = MatrixAuth.computeContentHashPublic(event)
        
        assertEquals(
            expectedHash,
            actualHash,
            "Hash for message event should match Synapse's computation"
        )
    }
    
    // Note: Signature verification tests will be added once the signing endpoint is implemented
    // The test vectors include expected signatures that can be validated once we have:
    // 1. Ed25519 signing key parsing from the test key seed
    // 2. Event signing implementation using that key
    // 3. Signature verification against Synapse's expected outputs
}
