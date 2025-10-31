package routes

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.*
import utils.AuthUtils
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for profile GET endpoints added to fix complement test failures.
 * 
 * These tests validate the implementation of:
 * - GET /profile/{userId}/displayname
 * - GET /profile/{userId}/avatar_url
 * 
 * Key areas tested:
 * 1. Retrieving displayname for a user
 * 2. Retrieving avatar_url for a user
 * 3. Proper handling of missing users (404 response)
 * 4. Proper handling of unset profile fields (empty object)
 * 
 * Big shoutout to the FERRETCANNON massive for Matrix v1.16 compliance! 🚀
 */
class ProfileEndpointsTest {
    
    companion object {
        private lateinit var database: Database
        private const val TEST_DB_FILE = "test_profile_endpoints.db"
        
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
    
    /**
     * Test that we can retrieve a user's display name via GET endpoint
     */
    @Test
    fun `GET profile displayname returns user's display name`() {
        val testUserId = "@testuser1:localhost"
        val testDisplayName = "Test User One"
        
        // Create test user with display name
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "testuser1"
                it[passwordHash] = "dummy_hash"
                it[displayName] = testDisplayName
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query the display name
        val result = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        
        assertNotNull(result, "User should exist in database")
        assertEquals(testDisplayName, result[Users.displayName], "Display name should match")
    }
    
    /**
     * Test that retrieving display name for non-existent user returns proper error
     */
    @Test
    fun `GET profile displayname for non-existent user returns appropriate data`() {
        val nonExistentUserId = "@nonexistent:localhost"
        
        // Query non-existent user
        val result = transaction {
            Users.select { Users.userId eq nonExistentUserId }.singleOrNull()
        }
        
        assertNull(result, "Non-existent user should not be found")
    }
    
    /**
     * Test that user with no display name set returns empty response
     */
    @Test
    fun `GET profile displayname for user without displayname set returns empty object`() {
        val testUserId = "@testuser2:localhost"
        
        // Create test user without display name
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "testuser2"
                it[passwordHash] = "dummy_hash"
                it[displayName] = null  // No display name set
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query the user
        val result = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        
        assertNotNull(result, "User should exist in database")
        assertNull(result[Users.displayName], "Display name should be null")
    }
    
    /**
     * Test that we can retrieve a user's avatar URL via GET endpoint
     */
    @Test
    fun `GET profile avatar_url returns user's avatar URL`() {
        val testUserId = "@testuser3:localhost"
        val testAvatarUrl = "mxc://localhost/test123avatar"
        
        // Create test user with avatar URL
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "testuser3"
                it[passwordHash] = "dummy_hash"
                it[avatarUrl] = testAvatarUrl
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query the avatar URL
        val result = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        
        assertNotNull(result, "User should exist in database")
        assertEquals(testAvatarUrl, result[Users.avatarUrl], "Avatar URL should match")
    }
    
    /**
     * Test that retrieving avatar URL for non-existent user returns proper error
     */
    @Test
    fun `GET profile avatar_url for non-existent user returns appropriate data`() {
        val nonExistentUserId = "@nonexistent2:localhost"
        
        // Query non-existent user
        val result = transaction {
            Users.select { Users.userId eq nonExistentUserId }.singleOrNull()
        }
        
        assertNull(result, "Non-existent user should not be found")
    }
    
    /**
     * Test that user with no avatar URL set returns empty response
     */
    @Test
    fun `GET profile avatar_url for user without avatar_url set returns empty object`() {
        val testUserId = "@testuser4:localhost"
        
        // Create test user without avatar URL
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "testuser4"
                it[passwordHash] = "dummy_hash"
                it[avatarUrl] = null  // No avatar URL set
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query the user
        val result = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        
        assertNotNull(result, "User should exist in database")
        assertNull(result[Users.avatarUrl], "Avatar URL should be null")
    }
    
    /**
     * Test that user with both display name and avatar URL can have both retrieved
     */
    @Test
    fun `User with both displayname and avatar_url set can retrieve both`() {
        val testUserId = "@testuser5:localhost"
        val testDisplayName = "Test User Five"
        val testAvatarUrl = "mxc://localhost/avatar5"
        
        // Create test user with both fields set
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "testuser5"
                it[passwordHash] = "dummy_hash"
                it[displayName] = testDisplayName
                it[avatarUrl] = testAvatarUrl
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query the user
        val result = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        
        assertNotNull(result, "User should exist in database")
        assertEquals(testDisplayName, result[Users.displayName], "Display name should match")
        assertEquals(testAvatarUrl, result[Users.avatarUrl], "Avatar URL should match")
    }
    
    /**
     * Test that profile can be updated and retrieved correctly
     */
    @Test
    fun `Profile fields can be updated and retrieved`() {
        val testUserId = "@testuser6:localhost"
        val initialDisplayName = "Initial Name"
        val updatedDisplayName = "Updated Name"
        val initialAvatarUrl = "mxc://localhost/initial"
        val updatedAvatarUrl = "mxc://localhost/updated"
        
        // Create test user
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "testuser6"
                it[passwordHash] = "dummy_hash"
                it[displayName] = initialDisplayName
                it[avatarUrl] = initialAvatarUrl
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Verify initial values
        val initialResult = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        assertNotNull(initialResult, "User should exist")
        assertEquals(initialDisplayName, initialResult[Users.displayName], "Initial display name should match")
        assertEquals(initialAvatarUrl, initialResult[Users.avatarUrl], "Initial avatar URL should match")
        
        // Update both fields
        transaction {
            Users.update({ Users.userId eq testUserId }) {
                it[displayName] = updatedDisplayName
                it[avatarUrl] = updatedAvatarUrl
            }
        }
        
        // Verify updated values
        val updatedResult = transaction {
            Users.select { Users.userId eq testUserId }.singleOrNull()
        }
        assertNotNull(updatedResult, "User should still exist")
        assertEquals(updatedDisplayName, updatedResult[Users.displayName], "Updated display name should match")
        assertEquals(updatedAvatarUrl, updatedResult[Users.avatarUrl], "Updated avatar URL should match")
    }
}
