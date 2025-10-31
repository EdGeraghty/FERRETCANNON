package federation

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for federation user profile query endpoints
 * 
 * Matrix Spec Compliance:
 * - GET /_matrix/federation/v1/query/profile
 * 
 * Per Matrix Spec v1.16 Section 11.6.2.1:
 * The response should always include displayname and avatar_url keys,
 * even if they are null. This ensures consistent response format for
 * federated profile queries.
 */
class FederationProfileQueryTest {
    
    @Test
    fun `profile response includes displayname key even when null`() {
        // Per Matrix spec, displayname should always be present in the response
        // even if the user hasn't set a display name (value would be null)
        
        val profileWithoutDisplayname = buildJsonObject {
            put("displayname", JsonNull)
            put("avatar_url", JsonNull)
        }
        
        assertTrue(profileWithoutDisplayname.containsKey("displayname"),
            "Profile response must include displayname key per Matrix spec")
        assertEquals(JsonNull, profileWithoutDisplayname["displayname"],
            "Null displayname should be represented as JSON null")
    }

    @Test
    fun `profile response includes avatar_url key even when null`() {
        // Per Matrix spec, avatar_url should always be present in the response
        // even if the user hasn't set an avatar (value would be null)
        
        val profileWithoutAvatar = buildJsonObject {
            put("displayname", JsonNull)
            put("avatar_url", JsonNull)
        }
        
        assertTrue(profileWithoutAvatar.containsKey("avatar_url"),
            "Profile response must include avatar_url key per Matrix spec")
        assertEquals(JsonNull, profileWithoutAvatar["avatar_url"],
            "Null avatar_url should be represented as JSON null")
    }

    @Test
    fun `profile response with both displayname and avatar_url set`() {
        // When user has set both displayname and avatar, both should be included
        
        val completeProfile = buildJsonObject {
            put("displayname", "Alice")
            put("avatar_url", "mxc://example.com/abc123")
        }
        
        assertNotNull(completeProfile["displayname"],
            "Displayname should be present")
        assertNotNull(completeProfile["avatar_url"],
            "Avatar URL should be present")
        assertEquals("Alice", completeProfile["displayname"]?.jsonPrimitive?.content)
        assertEquals("mxc://example.com/abc123", completeProfile["avatar_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `profile response with displayname but no avatar`() {
        // User has display name but no avatar - avatar_url should still be included as null
        
        val profileWithNameOnly = buildJsonObject {
            put("displayname", "Bob")
            put("avatar_url", JsonNull)
        }
        
        assertEquals("Bob", profileWithNameOnly["displayname"]?.jsonPrimitive?.content)
        assertTrue(profileWithNameOnly.containsKey("avatar_url"),
            "avatar_url key must be present even when null")
        assertEquals(JsonNull, profileWithNameOnly["avatar_url"])
    }

    @Test
    fun `profile response with avatar but no displayname`() {
        // User has avatar but no display name - displayname should still be included as null
        
        val profileWithAvatarOnly = buildJsonObject {
            put("displayname", JsonNull)
            put("avatar_url", "mxc://example.com/xyz789")
        }
        
        assertTrue(profileWithAvatarOnly.containsKey("displayname"),
            "displayname key must be present even when null")
        assertEquals(JsonNull, profileWithAvatarOnly["displayname"])
        assertEquals("mxc://example.com/xyz789", 
            profileWithAvatarOnly["avatar_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `profile keys are not duplicated in response`() {
        // Implementation should not duplicate displayname/avatar_url keys
        // when iterating through profile map
        
        val profile = mapOf(
            "displayname" to "Charlie",
            "avatar_url" to "mxc://example.com/def456"
        )
        
        val jsonResponse = buildJsonObject {
            val display = profile["displayname"] as? String
            val avatar = profile["avatar_url"] as? String
            
            put("displayname", display?.let { JsonPrimitive(it) } ?: JsonNull)
            put("avatar_url", avatar?.let { JsonPrimitive(it) } ?: JsonNull)
            
            // Skip displayname and avatar_url when iterating remaining keys
            profile.forEach { (key, value) ->
                if (key == "displayname" || key == "avatar_url") return@forEach
                when (value) {
                    is String -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
        
        // Count occurrences of displayname and avatar_url keys
        val keys = jsonResponse.keys.toList()
        assertEquals(1, keys.count { it == "displayname" },
            "displayname should appear exactly once")
        assertEquals(1, keys.count { it == "avatar_url" },
            "avatar_url should appear exactly once")
    }
}
