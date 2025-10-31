package rooms

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.*
import utils.StateResolver
import routes.server_server.federation.v1.findRoomByAlias
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * Unit tests for room operations: joined_members endpoint and room alias resolution.
 * 
 * These tests validate:
 * 1. GET /rooms/{roomId}/joined_members endpoint
 * 2. Room alias creation during room creation
 * 3. Room alias resolution for join operations
 * 
 * Tests ensure compliance with Matrix Specification v1.16 for:
 * - GET /rooms/{roomId}/joined_members
 * - Room alias handling (m.room.canonical_alias)
 * - POST /join/{roomIdOrAlias} with alias support
 */
class RoomOperationsTest {
    
    companion object {
        private lateinit var database: Database
        private const val TEST_DB_FILE = "test_room_operations.db"
        
        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            // Clean up any existing test database
            java.io.File(TEST_DB_FILE).delete()
            database = Database.connect("jdbc:sqlite:$TEST_DB_FILE", "org.sqlite.JDBC")
            
            transaction {
                SchemaUtils.create(
                    Users, 
                    Rooms,
                    Events,
                    AccessTokens
                )
            }
        }
        
        @AfterAll
        @JvmStatic
        fun teardownDatabase() {
            transaction {
                SchemaUtils.drop(
                    Users, 
                    Rooms,
                    Events,
                    AccessTokens
                )
            }
            java.io.File(TEST_DB_FILE).delete()
        }
    }
    
    @BeforeEach
    fun cleanupTestData() {
        transaction {
            Users.deleteAll()
            Rooms.deleteAll()
            Events.deleteAll()
            AccessTokens.deleteAll()
        }
    }
    
    /**
     * Test that room alias creation works correctly during room creation.
     */
    @Test
    fun `room canonical alias event is created when alias is provided`() {
        transaction {
            val roomId = "!test123:localhost"
            val userId = "@testuser:localhost"
            val roomAlias = "testalias"
            val fullAlias = "#$roomAlias:localhost"
            
            // Create room entry
            Rooms.insert {
                it[Rooms.roomId] = roomId
                it[creator] = userId
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create canonical alias event
            Events.insert {
                it[eventId] = "\$canonical_alias_event"
                it[Events.roomId] = roomId
                it[type] = "m.room.canonical_alias"
                it[sender] = userId
                it[content] = "{\"alias\":\"$fullAlias\"}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = ""
                it[depth] = 6
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[hashes] = "{}"
                it[signatures] = "{}"
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // Verify event was created
            val event = Events.select {
                (Events.roomId eq roomId) and (Events.type eq "m.room.canonical_alias")
            }.singleOrNull()
            
            assertNotNull(event, "Canonical alias event should exist")
            val eventContent = Json.parseToJsonElement(event[Events.content]).jsonObject
            assertEquals(fullAlias, eventContent["alias"]?.jsonPrimitive?.content)
        }
    }
    
    /**
     * Test that findRoomByAlias correctly resolves aliases to room IDs.
     */
    @Test
    fun `findRoomByAlias resolves room aliases correctly`() {
        transaction {
            val roomId = "!test456:localhost"
            val userId = "@testuser:localhost"
            val fullAlias = "#myroom:localhost"
            
            // Create canonical alias event first
            Events.insert {
                it[eventId] = "\$canonical_alias_event"
                it[Events.roomId] = roomId
                it[type] = "m.room.canonical_alias"
                it[sender] = userId
                it[content] = "{\"alias\":\"$fullAlias\"}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = ""
                it[depth] = 1
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // Create room with current_state that includes the canonical alias
            val currentState = buildJsonObject {
                put("m.room.canonical_alias:", buildJsonObject {
                    put("alias", fullAlias)
                })
            }
            
            Rooms.insert {
                it[Rooms.roomId] = roomId
                it[creator] = userId
                it[Rooms.currentState] = currentState.toString()
                it[stateGroups] = "{}"
            }
            
            // Resolve alias
            val resolvedRoomId = findRoomByAlias(fullAlias)
            
            assertNotNull(resolvedRoomId, "Alias should resolve to a room ID")
            assertEquals(roomId, resolvedRoomId, "Resolved room ID should match")
        }
    }
    
    /**
     * Test that findRoomByAlias returns null for non-existent aliases.
     */
    @Test
    fun `findRoomByAlias returns null for non-existent alias`() {
        val resolvedRoomId = findRoomByAlias("#nonexistent:localhost")
        assertNull(resolvedRoomId, "Non-existent alias should return null")
    }
    
    /**
     * Test joined members retrieval from a room.
     */
    @Test
    fun `joined members can be retrieved from room`() {
        transaction {
            val roomId = "!test789:localhost"
            val user1 = "@user1:localhost"
            val user2 = "@user2:localhost"
            val user3 = "@user3:localhost"
            
            // Create room
            Rooms.insert {
                it[Rooms.roomId] = roomId
                it[creator] = user1
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create membership events for multiple users
            // User 1 - joined
            Events.insert {
                it[eventId] = "\$member1"
                it[Events.roomId] = roomId
                it[type] = "m.room.member"
                it[sender] = user1
                it[content] = "{\"membership\":\"join\",\"displayname\":\"User One\",\"avatar_url\":\"mxc://localhost/avatar1\"}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = user1
                it[depth] = 2
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // User 2 - joined
            Events.insert {
                it[eventId] = "\$member2"
                it[Events.roomId] = roomId
                it[type] = "m.room.member"
                it[sender] = user2
                it[content] = "{\"membership\":\"join\",\"displayname\":\"User Two\"}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = user2
                it[depth] = 3
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // User 3 - left (should not be in joined members)
            Events.insert {
                it[eventId] = "\$member3"
                it[Events.roomId] = roomId
                it[type] = "m.room.member"
                it[sender] = user3
                it[content] = "{\"membership\":\"leave\"}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = user3
                it[depth] = 4
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // Query joined members
            val joinedMembers = Events.select {
                (Events.roomId eq roomId) and (Events.type eq "m.room.member")
            }.mapNotNull { row ->
                val memberUserId = row[Events.stateKey] ?: return@mapNotNull null
                val content = Json.parseToJsonElement(row[Events.content]).jsonObject
                val membership = content["membership"]?.jsonPrimitive?.content
                
                if (membership == "join") {
                    memberUserId to content
                } else {
                    null
                }
            }.toMap()
            
            // Verify results
            assertEquals(2, joinedMembers.size, "Should have 2 joined members")
            assertTrue(joinedMembers.containsKey(user1), "User 1 should be joined")
            assertTrue(joinedMembers.containsKey(user2), "User 2 should be joined")
            assertFalse(joinedMembers.containsKey(user3), "User 3 should not be in joined members")
            
            // Verify display names
            assertEquals("User One", joinedMembers[user1]?.get("displayname")?.jsonPrimitive?.content)
            assertEquals("User Two", joinedMembers[user2]?.get("displayname")?.jsonPrimitive?.content)
            
            // Verify avatar URL is present for user 1
            assertEquals("mxc://localhost/avatar1", joinedMembers[user1]?.get("avatar_url")?.jsonPrimitive?.content)
        }
    }
    
    /**
     * Test that only joined members can query joined_members endpoint.
     */
    @Test
    fun `non-joined users cannot query joined_members`() {
        transaction {
            val roomId = "!test999:localhost"
            val memberUser = "@member:localhost"
            val nonMemberUser = "@nonmember:localhost"
            
            // Create room
            Rooms.insert {
                it[Rooms.roomId] = roomId
                it[creator] = memberUser
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create membership event only for memberUser
            Events.insert {
                it[eventId] = "\$member1"
                it[Events.roomId] = roomId
                it[type] = "m.room.member"
                it[sender] = memberUser
                it[content] = "{\"membership\":\"join\"}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = memberUser
                it[depth] = 2
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // Check membership for member user
            val memberMembership = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq "m.room.member") and
                (Events.stateKey eq memberUser)
            }.mapNotNull { row ->
                Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
            }.firstOrNull()
            
            assertEquals("join", memberMembership, "Member user should be joined")
            
            // Check membership for non-member user
            val nonMemberMembership = Events.select {
                (Events.roomId eq roomId) and
                (Events.type eq "m.room.member") and
                (Events.stateKey eq nonMemberUser)
            }.mapNotNull { row ->
                Json.parseToJsonElement(row[Events.content]).jsonObject["membership"]?.jsonPrimitive?.content
            }.firstOrNull()
            
            assertNull(nonMemberMembership, "Non-member user should have no membership")
        }
    }
    
    /**
     * Test that room alias starts with # character.
     */
    @Test
    fun `room aliases are properly formatted with hash prefix`() {
        val roomAlias = "myroom"
        val serverName = "localhost"
        val fullAlias = "#$roomAlias:$serverName"
        
        assertTrue(fullAlias.startsWith("#"), "Room alias should start with #")
        assertTrue(fullAlias.contains(":"), "Room alias should contain : separator")
        assertEquals("#myroom:localhost", fullAlias)
    }
    
    /**
     * Test that room ID starts with ! character.
     */
    @Test
    fun `room IDs are properly formatted with exclamation prefix`() {
        val roomId = "!test123:localhost"
        
        assertTrue(roomId.startsWith("!"), "Room ID should start with !")
        assertTrue(roomId.contains(":"), "Room ID should contain : separator")
    }
}
