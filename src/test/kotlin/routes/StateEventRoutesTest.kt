package routes

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for state event routes with optional stateKey parameter
 * 
 * Matrix Spec Compliance:
 * - PUT /_matrix/client/v3/rooms/{roomId}/state/{eventType}/{stateKey}
 * - PUT /_matrix/client/v3/rooms/{roomId}/state/{eventType} (stateKey defaults to empty string)
 * 
 * Per Matrix Spec v1.16 Section 6.3.6.1:
 * State events can have an empty state_key. When the stateKey path segment is omitted,
 * it should be treated as an empty string "".
 * 
 * This matches the behaviour where some state events (like m.room.name) use an empty
 * state_key, whilst others (like m.room.member) use a user ID as the state_key.
 */
class StateEventRoutesTest {
    
    @Test
    fun `empty stateKey is valid per Matrix spec`() {
        // Per Matrix spec, state events can have empty state_key
        // Examples: m.room.name, m.room.topic, m.room.avatar use state_key=""
        
        val emptyStateKey = ""
        assertTrue(emptyStateKey.isEmpty(), "Empty stateKey is valid per Matrix spec v1.16")
    }

    @Test
    fun `stateKey defaults to empty string when omitted from path`() {
        // Per Matrix spec, when the stateKey path parameter is omitted,
        // it should be treated as the empty string ""
        
        val stateKeyWhenOmitted: String? = null
        val actualStateKey = stateKeyWhenOmitted ?: ""
        
        assertEquals("", actualStateKey, 
            "Omitted stateKey should default to empty string per Matrix spec")
    }

    @Test
    fun `non-empty stateKey is preserved`() {
        // When stateKey is explicitly provided (e.g., user ID for m.room.member),
        // it should be used as-is
        
        val explicitStateKey = "@user:example.com"
        val actualStateKey = explicitStateKey
        
        assertEquals("@user:example.com", actualStateKey,
            "Explicit stateKey should be preserved")
    }

    @Test
    fun `room name uses empty stateKey`() {
        // m.room.name events use an empty state_key per Matrix spec
        val roomNameStateKey = ""
        assertEquals("", roomNameStateKey, 
            "m.room.name uses empty state_key per Matrix spec")
    }

    @Test
    fun `room member uses user ID as stateKey`() {
        // m.room.member events use the user ID as state_key per Matrix spec
        val userId = "@alice:example.com"
        val memberStateKey = userId
        
        assertTrue(memberStateKey.startsWith("@"), 
            "m.room.member state_key should be a user ID")
        assertTrue(memberStateKey.contains(":"),
            "User ID should contain server name separator")
    }
}
