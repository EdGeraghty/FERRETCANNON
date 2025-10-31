import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.*
import routes.server_server.federation.v1.findMissingEvents
import kotlin.test.assertEquals

class MissingEventsTest {
    companion object {
        private const val TEST_DB_FILE = "test_missing_events.db"

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
    fun `findMissingEvents returns intermediate events oldest first`() {
        val roomId = "!testchain:localhost"

        transaction {
            Rooms.insert {
                it[Rooms.roomId] = roomId
                it[Rooms.creator] = "@tester:localhost"
                it[Rooms.currentState] = "{}"
                it[Rooms.stateGroups] = "{}"
            }

            // Insert three events A -> B -> C (A is earliest)
            Events.insert {
                it[Events.eventId] = "\$A"
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.message"
                it[Events.sender] = "@a:localhost"
                it[Events.content] = "{}"
                it[Events.prevEvents] = "[]"
                it[Events.authEvents] = "[]"
                it[Events.depth] = 1
                it[Events.hashes] = "{}"
                it[Events.signatures] = "{}"
                it[Events.originServerTs] = System.currentTimeMillis() - 3000
                it[Events.stateKey] = null
            }

            Events.insert {
                it[Events.eventId] = "\$B"
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.message"
                it[Events.sender] = "@b:localhost"
                it[Events.content] = "{}"
                it[Events.prevEvents] = "[\"\$A\"]"
                it[Events.authEvents] = "[]"
                it[Events.depth] = 2
                it[Events.hashes] = "{}"
                it[Events.signatures] = "{}"
                it[Events.originServerTs] = System.currentTimeMillis() - 2000
                it[Events.stateKey] = null
            }

            Events.insert {
                it[Events.eventId] = "\$C"
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.message"
                it[Events.sender] = "@c:localhost"
                it[Events.content] = "{}"
                it[Events.prevEvents] = "[\"\$B\"]"
                it[Events.authEvents] = "[]"
                it[Events.depth] = 3
                it[Events.hashes] = "{}"
                it[Events.signatures] = "{}"
                it[Events.originServerTs] = System.currentTimeMillis() - 1000
                it[Events.stateKey] = null
            }
        }

        // earliest is A, latest is C, we expect to get B and C back, oldest-first -> [B, C]
        val result = findMissingEvents(roomId, listOf("\$A"), listOf("\$C"), 10)

        // Extract event_ids in order
        val ids = result.map { it["event_id"] as String }
        assertEquals(listOf("\$B", "\$C"), ids)
    }
}
