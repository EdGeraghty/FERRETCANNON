import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import utils.MatrixAuth
import kotlin.test.assertEquals

class MatrixAuthHashTest {
    @Test
    fun `content hash matches spec vector`() {
        val eventJson = Json.parseToJsonElement("""
        {
          "auth_events": ["${'$'}amxREh8Yxdn7Ajg1isWwiE8vThUUrMTMeAn9vK4iANA", "${'$'}sQzYfaDpsQJg93gkc-0KBVzcKgBTjuzPUQ31aGFxEj4"],
          "content": {"membership": "join"},
          "depth": 1399,
          "origin_server_ts": 1759753025984,
          "prev_events": ["${'$'}jtmwoL0SodCgNmjPwlPdhUVsAIA-elkCs_FI6VO78dQ"],
          "room_id": "!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw",
          "sender": "@ferretcannon:ferretcannon.roflcopter.wtf",
          "state_key": "@ferretcannon:ferretcannon.roflcopter.wtf",
          "type": "m.room.member"
        }
        """ ).jsonObject

        val expectedHash = "x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ"
        val actual = MatrixAuth.computeContentHashPublic(eventJson)
        assertEquals(expectedHash, actual)
    }

    @Test
    fun `reference hash uses url-safe base64`() {
        val eventJson = Json.parseToJsonElement("""{"event_id":"${'$'}abc123"}""").jsonObject
        val contentHash = "x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ"
        val ref = MatrixAuth::class.java.getDeclaredMethod("computeReferenceHash", JsonObject::class.java, String::class.java)
        ref.isAccessible = true
        val result = ref.invoke(null, eventJson, contentHash) as String
        // Ensure result contains only URL-safe chars (no + or / or =)
        assert(!result.contains("+") && !result.contains("/") && !result.contains("="))
    }
}
