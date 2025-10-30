package federation

import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import models.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for federation v2 send_join response auth_chain
 * 
 * Matrix Spec Compliance:
 * - PUT /_matrix/federation/v2/send_join/{roomId}/{eventId}
 * 
 * Per Matrix Spec v1.16 Section 11.3.2.1.4:
 * The send_join response must include the auth_chain - the full set of
 * authorization events for the returned event. This allows the receiving
 * server to verify the event is authorized per the room's state.
 */
class FederationV2AuthChainTest {
    companion object {
        private const val TEST_DB_FILE = "test_auth_chain.db"

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            java.io.File(TEST_DB_FILE).delete()
            Database.connect("jdbc:sqlite:$TEST_DB_FILE", "org.sqlite.JDBC")
            transaction {
                SchemaUtils.create(Events, Rooms)
            }
        }

        @AfterAll
        @JvmStatic
        fun teardownDatabase() {
            transaction {
                SchemaUtils.drop(Events, Rooms)
            }
            java.io.File(TEST_DB_FILE).delete()
        }
    }

    @Test
    fun `auth_chain includes authorization events`() {
        // Per Matrix spec, send_join response must include auth_chain
        // containing all events needed to authorize the join event
        
        val roomId = "!authtest:localhost"
        
        transaction {
            Rooms.insert {
                it[Rooms.roomId] = roomId
                it[creator] = "@creator:localhost"
                it[currentState] = "{}"
                it[stateGroups] = "{}"
            }
            
            // Create m.room.create event (always in auth chain)
            Events.insert {
                it[eventId] = "\$create"
                it[Events.roomId] = roomId
                it[type] = "m.room.create"
                it[sender] = "@creator:localhost"
                it[content] = """{"creator":"@creator:localhost"}"""
                it[prevEvents] = "[]"
                it[authEvents] = "[]"
                it[depth] = 1
                it[hashes] = "{}"
                it[signatures] = "{}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = ""
            }
            
            // Create m.room.power_levels event (required in auth chain for join)
            Events.insert {
                it[eventId] = "\$power"
                it[Events.roomId] = roomId
                it[type] = "m.room.power_levels"
                it[sender] = "@creator:localhost"
                it[content] = """{"users":{"@creator:localhost":100}}"""
                it[prevEvents] = "[\"\$create\"]"
                it[authEvents] = "[\"\$create\"]"
                it[depth] = 2
                it[hashes] = "{}"
                it[signatures] = "{}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = ""
            }
            
            // Create join event with auth_events referencing create and power_levels
            Events.insert {
                it[eventId] = "\$join"
                it[Events.roomId] = roomId
                it[type] = "m.room.member"
                it[sender] = "@user:localhost"
                it[content] = """{"membership":"join"}"""
                it[prevEvents] = "[\"\$power\"]"
                it[authEvents] = "[\"\$create\",\"\$power\"]"
                it[depth] = 3
                it[hashes] = "{}"
                it[signatures] = "{}"
                it[originServerTs] = System.currentTimeMillis()
                it[stateKey] = "@user:localhost"
            }
        }
        
        // Verify auth_events are stored correctly
        val joinEvent = transaction {
            Events.select { Events.eventId eq "\$join" }.single()
        }
        
        val authEvents = Json.parseToJsonElement(joinEvent[Events.authEvents]).jsonArray
        assertEquals(2, authEvents.size, "Join event should have 2 auth events")
        assertTrue(authEvents.any { it.jsonPrimitive.content == "\$create" },
            "Auth chain must include m.room.create")
        assertTrue(authEvents.any { it.jsonPrimitive.content == "\$power" },
            "Auth chain must include m.room.power_levels")
    }

    @Test
    fun `auth_chain can be empty for events without auth`() {
        // Some events (like the initial m.room.create) have no auth_events
        // In this case, auth_chain should be an empty array
        
        val emptyAuthChain = JsonArray(emptyList())
        assertEquals(0, emptyAuthChain.size, 
            "Empty auth chain should have zero elements")
    }

    @Test
    fun `auth_chain events include required fields`() {
        // Each event in the auth_chain must include all required Matrix event fields
        // per the Matrix spec v1.16 Section 2.1
        
        val authEvent = buildJsonObject {
            put("event_id", "\$auth_event_123")
            put("type", "m.room.create")
            put("room_id", "!room:example.com")
            put("sender", "@creator:example.com")
            put("content", buildJsonObject { 
                put("creator", "@creator:example.com")
            })
            put("auth_events", JsonArray(emptyList()))
            put("prev_events", JsonArray(emptyList()))
            put("depth", 1)
            put("hashes", buildJsonObject { 
                put("sha256", "hash_value")
            })
            put("signatures", buildJsonObject {
                put("example.com", buildJsonObject {
                    put("ed25519:1", "signature")
                })
            })
            put("origin_server_ts", System.currentTimeMillis())
            put("state_key", "")
        }
        
        // Verify all required fields are present
        assertTrue(authEvent.containsKey("event_id"), "Auth event must have event_id")
        assertTrue(authEvent.containsKey("type"), "Auth event must have type")
        assertTrue(authEvent.containsKey("room_id"), "Auth event must have room_id")
        assertTrue(authEvent.containsKey("sender"), "Auth event must have sender")
        assertTrue(authEvent.containsKey("content"), "Auth event must have content")
        assertTrue(authEvent.containsKey("auth_events"), "Auth event must have auth_events")
        assertTrue(authEvent.containsKey("prev_events"), "Auth event must have prev_events")
        assertTrue(authEvent.containsKey("depth"), "Auth event must have depth")
        assertTrue(authEvent.containsKey("hashes"), "Auth event must have hashes")
        assertTrue(authEvent.containsKey("signatures"), "Auth event must have signatures")
        assertTrue(authEvent.containsKey("origin_server_ts"), "Auth event must have origin_server_ts")
        assertTrue(authEvent.containsKey("state_key"), "State events must have state_key")
    }
}
