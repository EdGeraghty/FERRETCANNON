import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import utils.MatrixAuth
import kotlin.test.assertEquals

/**
 * Canonical JSON test suite using test vectors from the Matrix specification.
 * 
 * These tests validate that FERRETCANNON's canonical JSON implementation
 * matches the Matrix specification requirements for:
 * - Lexicographically sorted keys
 * - No unnecessary whitespace
 * - Proper number formatting
 * - Correct string escaping
 * - UTF-8 encoding
 * 
 * Canonical JSON is critical for event hashing and signing - if two implementations
 * produce different canonical JSON for the same event, they will compute different
 * hashes and federation will fail.
 * 
 * Big shoutout to the FERRETCANNON massive for spec compliance! 🎆
 */
class CanonicalJsonTest {
    
    @Test
    fun `canonical json - simple object with sorted keys`() {
        val input = Json.parseToJsonElement("""{"one": 1, "two": "Two"}""").jsonObject
        val expected = """{"one":1,"two":"Two"}"""
        
        val actual = MatrixAuth.canonicalizeJsonPublic(input)
        
        assertEquals(expected, actual, "Simple object keys should be sorted lexicographically")
    }
    
    @Test
    fun `canonical json - large integers (timestamps and depths)`() {
        val input = Json.parseToJsonElement("""
            {
              "origin_server_ts": 1759753025984,
              "depth": 1399
            }
        """).jsonObject
        val expected = """{"depth":1399,"origin_server_ts":1759753025984}"""
        
        val actual = MatrixAuth.canonicalizeJsonPublic(input)
        
        assertEquals(expected, actual, "Large integers (timestamps, depths) must be formatted correctly")
    }
    
    @Test
    fun `canonical json - string escaping`() {
        val input = Json.parseToJsonElement("""
            {
              "quote": "He said \"hello\"",
              "backslash": "C:\\Users\\test",
              "newline": "Line1\nLine2",
              "tab": "Col1\tCol2"
            }
        """).jsonObject
        val expected = """{"backslash":"C:\\Users\\test","newline":"Line1\nLine2","quote":"He said \"hello\"","tab":"Col1\tCol2"}"""
        
        val actual = MatrixAuth.canonicalizeJsonPublic(input)
        
        assertEquals(expected, actual, "Special characters must be properly escaped")
    }
    
    @Test
    fun `canonical json - matrix event structure`() {
        val input = Json.parseToJsonElement("""
            {
              "auth_events": ["${'$'}event1", "${'$'}event2"],
              "content": {"membership": "join"},
              "depth": 100,
              "origin_server_ts": 1234567890,
              "prev_events": ["${'$'}event3"],
              "room_id": "!room:example.com",
              "sender": "@user:example.com",
              "state_key": "@user:example.com",
              "type": "m.room.member"
            }
        """).jsonObject
        val expected = """{"auth_events":["${'$'}event1","${'$'}event2"],"content":{"membership":"join"},"depth":100,"origin_server_ts":1234567890,"prev_events":["${'$'}event3"],"room_id":"!room:example.com","sender":"@user:example.com","state_key":"@user:example.com","type":"m.room.member"}"""
        
        val actual = MatrixAuth.canonicalizeJsonPublic(input)
        
        assertEquals(expected, actual, "Real Matrix event structures must canonicalize correctly")
    }
    
    @Test
    fun `canonical json - boolean and null values`() {
        val input = Json.parseToJsonElement("""
            {
              "enabled": true,
              "disabled": false,
              "value": null
            }
        """).jsonObject
        val expected = """{"disabled":false,"enabled":true,"value":null}"""
        
        val actual = MatrixAuth.canonicalizeJsonPublic(input)
        
        assertEquals(expected, actual, "Boolean and null values must be handled correctly")
    }
}
