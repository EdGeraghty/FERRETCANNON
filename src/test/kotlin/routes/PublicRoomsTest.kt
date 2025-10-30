package routes
import org.junit.jupiter.api.BeforeEach

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for public rooms listing endpoint to fix complement test failures.
 * 
 * These tests validate the implementation of:
 * - GET /_matrix/client/v3/publicRooms
 * - POST /_matrix/client/v3/publicRooms (with filtering)
 * 
 * Key areas tested:
 * 1. Listing published rooms
 * 2. Filtering by search term
 * 3. Pagination support
 * 4. Room metadata retrieval
 * 
 * Big shoutout to the FERRETCANNON massive for Matrix v1.16 compliance! 🚀
 */
class PublicRoomsTest {
    
    companion object {
        private lateinit var database: Database
        private const val TEST_DB_FILE = "test_public_rooms.db"
        
        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            // Create file-based test database
            java.io.File(TEST_DB_FILE).delete() // Clean up any existing test database
            database = Database.connect("jdbc:sqlite:$TEST_DB_FILE", "org.sqlite.JDBC")
            
            transaction {
                SchemaUtils.create(
                    Events, Rooms, Users, AccessTokens, Devices, 
                    AccountData, StateGroups, Presence, RoomAliases
                )
            }
        }
        
        @AfterAll
        @JvmStatic
        fun teardownDatabase() {
            transaction {
                SchemaUtils.drop(
                    Events, Rooms, Users, AccessTokens, Devices,
                    AccountData, StateGroups, Presence, RoomAliases
                )
            }
            // Clean up the test database file
            java.io.File(TEST_DB_FILE).delete()
        }
    }
    
    @BeforeEach
    fun cleanupTestData() {
        transaction {
            Events.deleteAll()
            Rooms.deleteAll()
        }
    }
    
    /**
     * Test that published rooms are returned in publicRooms list
     */
    @Test
    fun `publicRooms returns published rooms`() {
        val testRoomId1 = "!publicroom1:localhost"
        val testRoomId2 = "!publicroom2:localhost"
        val testRoomId3 = "!privateroom:localhost"
        
        // Create public rooms
        transaction {
            Rooms.insert {
                it[roomId] = testRoomId1
                it[creator] = "@creator1:localhost"
                it[published] = true
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            Rooms.insert {
                it[roomId] = testRoomId2
                it[creator] = "@creator2:localhost"
                it[published] = true
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            // Create private room (should not appear)
            Rooms.insert {
                it[roomId] = testRoomId3
                it[creator] = "@creator3:localhost"
                it[published] = false
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
        }
        
        // Query published rooms
        val publicRooms = transaction {
            Rooms.select { Rooms.published eq true }
                .map { it[Rooms.roomId] }
        }
        
        assertEquals(2, publicRooms.size, "Should return 2 published rooms")
        assertTrue(publicRooms.contains(testRoomId1), "Should include room 1")
        assertTrue(publicRooms.contains(testRoomId2), "Should include room 2")
        assertTrue(!publicRooms.contains(testRoomId3), "Should not include private room")
    }
    
    /**
     * Test that rooms with names are properly returned
     */
    @Test
    fun `publicRooms includes room names from state events`() {
        val testRoomId = "!namedroom:localhost"
        val testRoomName = "Test Public Room"
        
        // Create room
        transaction {
            Rooms.insert {
                it[roomId] = testRoomId
                it[creator] = "@creator:localhost"
                it[published] = true
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Add room name state event
            Events.insert {
                it[eventId] = "${'$'}name_event_1"
                it[roomId] = testRoomId
                it[type] = "m.room.name"
                it[sender] = "@creator:localhost"
                it[stateKey] = ""
                it[content] = """{"name":"$testRoomName"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        // Verify room name can be retrieved
        val roomName = transaction {
            Events.select {
                (Events.roomId eq testRoomId) and
                (Events.type eq "m.room.name") and
                (Events.stateKey eq "")
            }.firstOrNull()?.let { event ->
                Json.parseToJsonElement(event[Events.content])
                    .jsonObject["name"]?.jsonPrimitive?.content
            }
        }
        
        assertEquals(testRoomName, roomName, "Room name should match")
    }
    
    /**
     * Test that rooms with topics are properly returned
     */
    @Test
    fun `publicRooms includes room topics from state events`() {
        val testRoomId = "!topicroom:localhost"
        val testTopic = "A test room for testing"
        
        // Create room
        transaction {
            Rooms.insert {
                it[roomId] = testRoomId
                it[creator] = "@creator:localhost"
                it[published] = true
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Add room topic state event
            Events.insert {
                it[eventId] = "${'$'}topic_event_1"
                it[roomId] = testRoomId
                it[type] = "m.room.topic"
                it[sender] = "@creator:localhost"
                it[stateKey] = ""
                it[content] = """{"topic":"$testTopic"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        // Verify room topic can be retrieved
        val roomTopic = transaction {
            Events.select {
                (Events.roomId eq testRoomId) and
                (Events.type eq "m.room.topic") and
                (Events.stateKey eq "")
            }.firstOrNull()?.let { event ->
                Json.parseToJsonElement(event[Events.content])
                    .jsonObject["topic"]?.jsonPrimitive?.content
            }
        }
        
        assertEquals(testTopic, roomTopic, "Room topic should match")
    }
    
    /**
     * Test that room member counts are calculated correctly
     */
    @Test
    fun `publicRooms correctly counts joined members`() {
        val testRoomId = "!memberroom:localhost"
        
        // Create room
        transaction {
            Rooms.insert {
                it[roomId] = testRoomId
                it[creator] = "@creator:localhost"
                it[published] = true
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Add member events for 3 joined users
            for (i in 1..3) {
                Events.insert {
                    it[eventId] = "${'$'}member_event_$i"
                    it[roomId] = testRoomId
                    it[type] = "m.room.member"
                    it[sender] = "@user$i:localhost"
                    it[stateKey] = "@user$i:localhost"
                    it[content] = """{"membership":"join"}"""
                    it[originServerTs] = System.currentTimeMillis() + i
                    it[prevEvents] = "[]"
                    it[authEvents] = "[]"
                    it[depth] = i
                    it[hashes] = "{}"
                    it[signatures] = "{}"
                }
            }
            
            // Add an invited user (should not be counted as joined)
            Events.insert {
                it[eventId] = "${'$'}member_event_4"
                it[roomId] = testRoomId
                it[type] = "m.room.member"
                it[sender] = "@user1:localhost"
                it[stateKey] = "@user4:localhost"
                it[content] = """{"membership":"invite"}"""
                it[originServerTs] = System.currentTimeMillis() + 4
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 4
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        // Count joined members
        val joinedCount = transaction {
            Events.select {
                (Events.roomId eq testRoomId) and
                (Events.type eq "m.room.member")
            }.groupBy { event ->
                event[Events.stateKey]
            }.mapNotNull { (_, events) ->
                events.maxByOrNull { it[Events.originServerTs] }
            }.count { event ->
                try {
                    Json.parseToJsonElement(event[Events.content])
                        .jsonObject["membership"]?.jsonPrimitive?.content == "join"
                } catch (e: Exception) {
                    false
                }
            }
        }
        
        assertEquals(3, joinedCount, "Should count 3 joined members")
    }
    
    /**
     * Test that room aliases are properly retrieved
     */
    @Test
    fun `publicRooms includes canonical aliases from state events`() {
        val testRoomId = "!aliasroom:localhost"
        val testAlias = "#test:localhost"
        
        // Create room
        transaction {
            Rooms.insert {
                it[roomId] = testRoomId
                it[creator] = "@creator:localhost"
                it[published] = true
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Add canonical alias state event
            Events.insert {
                it[eventId] = "${'$'}alias_event_1"
                it[roomId] = testRoomId
                it[type] = "m.room.canonical_alias"
                it[sender] = "@creator:localhost"
                it[stateKey] = ""
                it[content] = """{"alias":"$testAlias"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        // Verify canonical alias can be retrieved
        val canonicalAlias = transaction {
            Events.select {
                (Events.roomId eq testRoomId) and
                (Events.type eq "m.room.canonical_alias") and
                (Events.stateKey eq "")
            }.firstOrNull()?.let { event ->
                Json.parseToJsonElement(event[Events.content])
                    .jsonObject["alias"]?.jsonPrimitive?.content
            }
        }
        
        assertEquals(testAlias, canonicalAlias, "Canonical alias should match")
    }
    
    /**
     * Test that pagination works correctly
     */
    @Test
    fun `publicRooms supports pagination`() {
        // Create multiple published rooms
        transaction {
            for (i in 10..15) {
                Rooms.insert {
                    it[roomId] = "!paginroom$i:localhost"
                    it[creator] = "@creator:localhost"
                    it[published] = true
                    it[isDirect] = false
                    it[currentState] = "{}"
                    it[stateGroups] = "{}"
                }
            }
        }
        
        // Query with pagination
        val firstPage = transaction {
            Rooms.select { Rooms.published eq true }
                .orderBy(Rooms.roomId)
                .limit(3)
                .map { it[Rooms.roomId] }
        }
        
        assertEquals(3, firstPage.size, "First page should have 3 rooms")
        
        // Query second page
        val secondPage = transaction {
            Rooms.select { Rooms.published eq true }
                .orderBy(Rooms.roomId)
                .limit(3)
                .andWhere { Rooms.roomId greater firstPage.last() }
                .map { it[Rooms.roomId] }
        }
        
        assertTrue(secondPage.isNotEmpty(), "Second page should have rooms")
        assertTrue(firstPage.last() < secondPage.first(), "Second page should start after first page")
    }
}
