import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.*
import utils.SyncManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

/**
 * Unit tests for sync endpoint fixes implemented in PR #4.
 * 
 * These tests validate the critical fixes that ensure invite events
 * are properly delivered through the /sync API endpoint, preventing
 * regression of the bug where TestIsDirectFlagLocal would timeout.
 * 
 * Key areas tested:
 * 1. Invited rooms detection in sync responses
 * 2. Stripped state retrieval for invites
 * 3. is_direct flag handling in invite events
 * 4. Sync response structure with invited rooms
 * 
 * Big shoutout to the FERRETCANNON massive for Matrix v1.16 compliance! 🚀
 */
class SyncEndpointTest {
    
    companion object {
        private lateinit var database: Database
        private const val TEST_DB_FILE = "test_sync_endpoint.db"
        
        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            // Create file-based test database
            java.io.File(TEST_DB_FILE).delete() // Clean up any existing test database
            database = Database.connect("jdbc:sqlite:$TEST_DB_FILE", "org.sqlite.JDBC")
            
            transaction {
                SchemaUtils.create(Events, Rooms, Users, AccessTokens, Devices, AccountData, StateGroups, Presence, RoomAliases)
            }
        }
        
        @AfterAll
        @JvmStatic
        fun teardownDatabase() {
            transaction {
                SchemaUtils.drop(Events, Rooms, Users, AccessTokens, Devices, AccountData, StateGroups, Presence, RoomAliases)
            }
            // Clean up the test database file
            java.io.File(TEST_DB_FILE).delete()
        }
    }
    
    /**
     * Test that invited rooms are correctly identified for a user.
     * This validates the fix for getUserInvitedRooms() functionality.
     */
    @Test
    fun `getUserInvitedRooms returns rooms with invite membership`() {
        transaction {
            // Create test room
            Rooms.insert {
                it[roomId] = "!test1:localhost"
                it[creator] = "@creator1:localhost"
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create invite event for test user
            Events.insert {
                it[eventId] = "\$invite_event_1"
                it[roomId] = "!test1:localhost"
                it[type] = "m.room.member"
                it[sender] = "@creator1:localhost"
                it[stateKey] = "@invitee1:localhost"
                it[content] = """{"membership":"invite"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        // Execute sync to get invited rooms
        val syncResponse = SyncManager.performSync(
            userId = "@invitee1:localhost",
            since = null,
            fullState = false
        )
        
        // Verify invited rooms are included in response
        val rooms = syncResponse["rooms"]?.jsonObject
        assertNotNull(rooms, "Rooms object should exist in sync response")
        
        val invite = rooms["invite"]?.jsonObject
        assertNotNull(invite, "Invite object should exist in rooms")
        
        assertTrue(
            invite.containsKey("!test1:localhost"),
            "Invited room should be present in sync response"
        )
    }
    
    /**
     * Test that stripped state for invited rooms includes the invite event.
     * This validates the fix for getStrippedState() to properly return invite events.
     */
    @Test
    fun `getStrippedState includes invite event for invited user`() {
        transaction {
            // Create test room with is_direct flag
            Rooms.insert {
                it[roomId] = "!direct2:localhost"
                it[creator] = "@creator2:localhost"
                it[isDirect] = true
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create room create event
            Events.insert {
                it[eventId] = "\$create_event_2"
                it[roomId] = "!direct2:localhost"
                it[type] = "m.room.create"
                it[sender] = "@creator2:localhost"
                it[stateKey] = ""
                it[content] = """{"creator":"@creator2:localhost"}"""
                it[originServerTs] = System.currentTimeMillis() - 1000
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
            
            // Create invite event with is_direct flag
            Events.insert {
                it[eventId] = "\$invite_event_2"
                it[roomId] = "!direct2:localhost"
                it[type] = "m.room.member"
                it[sender] = "@creator2:localhost"
                it[stateKey] = "@user2:localhost"
                it[content] = """{"membership":"invite","is_direct":true}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 2
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        // Perform sync and check stripped state
        val syncResponse = SyncManager.performSync(
            userId = "@user2:localhost",
            since = null,
            fullState = false
        )
        
        val inviteRoom = syncResponse["rooms"]?.jsonObject
            ?.get("invite")?.jsonObject
            ?.get("!direct2:localhost")?.jsonObject
        
        assertNotNull(inviteRoom, "Invite room should exist in sync response")
        
        val inviteState = inviteRoom["invite_state"]?.jsonObject
        assertNotNull(inviteState, "invite_state should exist")
        
        val events = inviteState["events"]?.jsonArray
        assertNotNull(events, "Events array should exist in invite_state")
        assertTrue(events.size > 0, "Stripped state should contain at least one event")
        
        // Find the invite event
        val inviteEvent = events.firstOrNull { event ->
            val eventObj = event.jsonObject
            eventObj["type"]?.jsonPrimitive?.content == "m.room.member" &&
            eventObj["state_key"]?.jsonPrimitive?.content == "@user2:localhost"
        }
        
        assertNotNull(inviteEvent, "Invite event should be present in stripped state")
        
        // Verify is_direct flag is present
        val content = inviteEvent.jsonObject["content"]?.jsonObject
        assertNotNull(content, "Invite event should have content")
        
        val isDirect = content["is_direct"]?.jsonPrimitive?.boolean
        assertEquals(true, isDirect, "is_direct flag should be true for direct messaging room")
    }
    
    /**
     * Test that sync response structure correctly includes invited rooms section.
     * This validates the fix for the sync response format.
     */
    @Test
    fun `sync response includes invite section with proper structure`() {
        transaction {
            // Create test room
            Rooms.insert {
                it[roomId] = "!room3:localhost"
                it[creator] = "@alice3:localhost"
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create invite event
            Events.insert {
                it[eventId] = "\$invite_123"
                it[roomId] = "!room3:localhost"
                it[type] = "m.room.member"
                it[sender] = "@alice3:localhost"
                it[stateKey] = "@bob3:localhost"
                it[content] = """{"membership":"invite"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        val syncResponse = SyncManager.performSync(
            userId = "@bob3:localhost",
            since = null,
            fullState = false
        )
        
        // Verify top-level structure
        assertTrue(syncResponse.containsKey("rooms"), "Sync response should contain 'rooms' key")
        assertTrue(syncResponse.containsKey("next_batch"), "Sync response should contain 'next_batch' key")
        
        val rooms = syncResponse["rooms"]?.jsonObject
        assertNotNull(rooms, "Rooms should not be null")
        
        // Verify rooms structure contains invite section
        assertTrue(rooms.containsKey("invite"), "Rooms should contain 'invite' section")
        
        val invite = rooms["invite"]?.jsonObject
        assertNotNull(invite, "Invite section should not be null")
        
        // Verify invite room structure
        val invitedRoom = invite["!room3:localhost"]?.jsonObject
        assertNotNull(invitedRoom, "Invited room should be present")
        
        assertTrue(
            invitedRoom.containsKey("invite_state"),
            "Invited room should contain 'invite_state' key"
        )
        
        val inviteState = invitedRoom["invite_state"]?.jsonObject
        assertNotNull(inviteState, "invite_state should not be null")
        
        assertTrue(
            inviteState.containsKey("events"),
            "invite_state should contain 'events' array"
        )
    }
    
    /**
     * Test that is_direct flag is correctly set in invite content for direct rooms.
     * This validates the fix for InviteHandler.sendInvite() is_direct flag handling.
     */
    @Test
    fun `invite event includes is_direct flag for direct messaging rooms`() {
        transaction {
            // Create direct messaging room
            Rooms.insert {
                it[roomId] = "!dm4:localhost"
                it[creator] = "@sender4:localhost"
                it[isDirect] = true
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create invite event with is_direct flag
            Events.insert {
                it[eventId] = "\$dm_invite_4"
                it[roomId] = "!dm4:localhost"
                it[type] = "m.room.member"
                it[sender] = "@sender4:localhost"
                it[stateKey] = "@recipient4:localhost"
                it[content] = """{"membership":"invite","is_direct":true}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        val syncResponse = SyncManager.performSync(
            userId = "@recipient4:localhost",
            since = null,
            fullState = false
        )
        
        val inviteRoom = syncResponse["rooms"]?.jsonObject
            ?.get("invite")?.jsonObject
            ?.get("!dm4:localhost")?.jsonObject
        
        assertNotNull(inviteRoom, "Direct message invite room should exist")
        
        val events = inviteRoom["invite_state"]?.jsonObject
            ?.get("events")?.jsonArray
        
        assertNotNull(events, "Events should exist in invite_state")
        
        // Find and verify the invite event
        val inviteEvent = events.firstOrNull { event ->
            val obj = event.jsonObject
            obj["type"]?.jsonPrimitive?.content == "m.room.member"
        }
        
        assertNotNull(inviteEvent, "Invite event should be in stripped state")
        
        val content = inviteEvent.jsonObject["content"]?.jsonObject
        assertNotNull(content, "Invite event content should exist")
        
        assertEquals(
            "invite",
            content["membership"]?.jsonPrimitive?.content,
            "Membership should be 'invite'"
        )
        
        assertEquals(
            true,
            content["is_direct"]?.jsonPrimitive?.boolean,
            "is_direct flag should be true for direct messaging room"
        )
    }
    
    /**
     * Test that regular (non-direct) rooms do not include is_direct flag.
     * This ensures we don't incorrectly mark non-direct rooms.
     */
    @Test
    fun `invite event omits is_direct flag for non-direct rooms`() {
        transaction {
            // Create regular (non-direct) room
            Rooms.insert {
                it[roomId] = "!regular5:localhost"
                it[creator] = "@admin5:localhost"
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create invite event without is_direct flag
            Events.insert {
                it[eventId] = "\$regular_invite_5"
                it[roomId] = "!regular5:localhost"
                it[type] = "m.room.member"
                it[sender] = "@admin5:localhost"
                it[stateKey] = "@user5:localhost"
                it[content] = """{"membership":"invite"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        val syncResponse = SyncManager.performSync(
            userId = "@user5:localhost",
            since = null,
            fullState = false
        )
        
        val inviteRoom = syncResponse["rooms"]?.jsonObject
            ?.get("invite")?.jsonObject
            ?.get("!regular5:localhost")?.jsonObject
        
        assertNotNull(inviteRoom, "Regular room invite should exist")
        
        val events = inviteRoom["invite_state"]?.jsonObject
            ?.get("events")?.jsonArray
        
        assertNotNull(events, "Events should exist")
        
        val inviteEvent = events.firstOrNull { event ->
            val obj = event.jsonObject
            obj["type"]?.jsonPrimitive?.content == "m.room.member"
        }
        
        assertNotNull(inviteEvent, "Invite event should exist")
        
        val content = inviteEvent.jsonObject["content"]?.jsonObject
        assertNotNull(content, "Content should exist")
        
        // Verify is_direct is either false or not present
        val isDirect = content["is_direct"]?.jsonPrimitive?.boolean
        if (isDirect != null) {
            assertFalse(isDirect, "is_direct should be false or absent for non-direct rooms")
        }
    }
    
    /**
     * Test that multiple invited rooms are all included in sync response.
     * This ensures the sync endpoint handles multiple invites correctly.
     */
    @Test
    fun `sync response includes all invited rooms for a user`() {
        transaction {
            // Create multiple rooms with invites
            for (i in 6..8) {
                Rooms.insert {
                    it[roomId] = "!room$i:localhost"
                    it[creator] = "@creator6:localhost"
                    it[isDirect] = false
                    it[currentState] = "{}"
                    it[stateGroups] = "{}"
                }
                
                Events.insert {
                    it[eventId] = "\$invite_$i"
                    it[roomId] = "!room$i:localhost"
                    it[type] = "m.room.member"
                    it[sender] = "@creator6:localhost"
                    it[stateKey] = "@invitee6:localhost"
                    it[content] = """{"membership":"invite"}"""
                    it[originServerTs] = System.currentTimeMillis() + i
                    it[prevEvents] = "[]"
                    it[authEvents] = "[]"
                    it[depth] = 1
                    it[hashes] = "{}"
                    it[signatures] = "{}"
                }
            }
        }
        
        val syncResponse = SyncManager.performSync(
            userId = "@invitee6:localhost",
            since = null,
            fullState = false
        )
        
        val invite = syncResponse["rooms"]?.jsonObject
            ?.get("invite")?.jsonObject
        
        assertNotNull(invite, "Invite section should exist")
        
        // Verify all three rooms are present
        assertEquals(3, invite.size, "All three invited rooms should be in sync response")
        assertTrue(invite.containsKey("!room6:localhost"), "Room 6 should be present")
        assertTrue(invite.containsKey("!room7:localhost"), "Room 7 should be present")
        assertTrue(invite.containsKey("!room8:localhost"), "Room 8 should be present")
    }
    
    /**
     * Test that joined rooms don't appear in invite section.
     * This ensures proper membership state filtering.
     */
    @Test
    fun `sync response excludes joined rooms from invite section`() {
        transaction {
            // Create room
            Rooms.insert {
                it[roomId] = "!joined7:localhost"
                it[creator] = "@user7:localhost"
                it[isDirect] = false
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create join event (more recent than any potential invite)
            Events.insert {
                it[eventId] = "\$join_event_7"
                it[roomId] = "!joined7:localhost"
                it[type] = "m.room.member"
                it[sender] = "@user7:localhost"
                it[stateKey] = "@user7:localhost"
                it[content] = """{"membership":"join"}"""
                it[originServerTs] = System.currentTimeMillis()
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 2
                it[hashes] = "{}"
                it[signatures] = "{}"
            }
        }
        
        val syncResponse = SyncManager.performSync(
            userId = "@user7:localhost",
            since = null,
            fullState = false
        )
        
        val invite = syncResponse["rooms"]?.jsonObject
            ?.get("invite")?.jsonObject
        
        // Verify joined room is not in invite section
        if (invite != null) {
            assertFalse(
                invite.containsKey("!joined7:localhost"),
                "Joined room should not appear in invite section"
            )
        }
        
        // Verify it appears in join section instead
        val join = syncResponse["rooms"]?.jsonObject
            ?.get("join")?.jsonObject
        
        assertNotNull(join, "Join section should exist")
        assertTrue(
            join.containsKey("!joined7:localhost"),
            "Joined room should appear in join section"
        )
    }
}
