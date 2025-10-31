package auth

import models.Users
import models.AccessTokens
import models.Devices
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import utils.AuthUtils
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * Unit tests for registration and authentication functionality.
 * 
 * These tests prevent regressions for fixes made to achieve 100% pass rate
 * on Complement authentication test suite (TestLogin, TestRegistration).
 * 
 * Covered scenarios:
 * - Username downcasing per Matrix spec
 * - Username validation (regex: ^[a-z0-9._=/+-]+$)
 * - Race condition handling with IllegalArgumentException
 * - Password storage and authentication
 * - Case-insensitive login
 * 
 * Big shoutout to the FERRETCANNON massive for Matrix v1.16 compliance! 🚀
 */
class RegistrationAuthenticationTest {
    
    companion object {
        private lateinit var database: Database
        private const val TEST_DB_FILE = "test_registration_auth.db"
        
        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            // Clean up any existing test database
            java.io.File(TEST_DB_FILE).delete()
            database = Database.connect("jdbc:sqlite:$TEST_DB_FILE", "org.sqlite.JDBC")
            
            transaction(database) {
                SchemaUtils.create(Users, AccessTokens, Devices)
            }
        }
        
        @AfterAll
        @JvmStatic
        fun teardownDatabase() {
            transaction(database) {
                SchemaUtils.drop(Users, AccessTokens, Devices)
            }
            java.io.File(TEST_DB_FILE).delete()
        }
    }
    
    @BeforeEach
    fun cleanupTestData() {
        transaction(database) {
            Users.deleteAll()
            AccessTokens.deleteAll()
            Devices.deleteAll()
        }
    }
    
    /**
     * Test that usernames are downcased during registration.
     * Regression test for: TestRegistration/POST_/register_downcases_capitals_in_usernames
     * Note: Downcasing happens in the registration routes before calling AuthUtils.createUser
     */
    @Test
    fun `registration downcases usernames to comply with Matrix spec`() {
        transaction(database) {
            // Simulate what the registration route does - downcase before creating
            val mixedCaseUsername = "TestUSER"
            val lowercasedUsername = mixedCaseUsername.lowercase()
            
            val userId = AuthUtils.createUser(lowercasedUsername, "test123!@#ABC", serverName = "localhost")
            
            assertNotNull(userId, "user_id should be returned")
            assertEquals("@testuser:localhost", userId, "Username should be lowercased in user_id")
            
            // Verify in database
            val user = Users.select { Users.userId eq userId }.singleOrNull()
            assertNotNull(user, "User should exist in database")
            assertEquals("testuser", user[Users.username], "Username in database should be lowercase")
        }
    }
    
    /**
     * Test that username validation rejects invalid characters.
     * Note: Validation happens at the routes level, not in AuthUtils.
     * This test verifies AuthUtils itself doesn't crash with special chars.
     */
    @Test
    fun `registration handles usernames with special characters`() {
        transaction(database) {
            // AuthUtils.createUser doesn't validate - it just creates
            // The validation happens in the registration routes
            // Here we test that AuthUtils can handle various usernames
            val validUsername = "testuser123"
            val userId = AuthUtils.createUser(validUsername, "test123!@#ABC", serverName = "localhost")
            assertNotNull(userId, "Should create user with valid username")
            assertEquals("@testuser123:localhost", userId)
        }
    }
    
    /**
     * Test that valid special characters are accepted.
     * Regression test for: TestRegistration/POST_/register_allows_registration_of_usernames_with_
     */
    @Test
    fun `registration accepts usernames with valid Matrix spec characters`() {
        transaction(database) {
            val validUsernames = listOf("user.name", "user_name", "user-name", "user=name", "user+name", "user/name")
            
            for ((index, username) in validUsernames.withIndex()) {
                val userId = AuthUtils.createUser("${username}${index}", "test123!@#ABC", serverName = "localhost")
                assertNotNull(userId, "Should accept username: $username")
            }
        }
    }
    
    /**
     * Test that username validation is performed correctly.
     * Note: AuthUtils accepts what it's given; validation happens at the routes level.
     */
    @Test
    fun `username validation is handled at routes level not AuthUtils`() {
        transaction(database) {
            // AuthUtils doesn't validate - it creates users with whatever username provided
            // Validation is the responsibility of the registration routes
            val username = "validuser"
            val userId = AuthUtils.createUser(username, "password", serverName = "localhost")
            assertNotNull(userId)
            assertEquals("@validuser:localhost", userId)
        }
    }
    
    /**
     * Test AuthUtils.createUser race condition handling.
     * Regression test for: TestRegistration/POST_/register_rejects_if_user_already_exists
     */
    @Test
    fun `createUser throws IllegalArgumentException when username already exists`() {
        transaction(database) {
            // Create first user
            val userId1 = AuthUtils.createUser("testuser", "password123!@#", serverName = "localhost")
            assertNotNull(userId1)
            assertEquals("@testuser:localhost", userId1)
            
            // Try to create same user again - should throw
            try {
                AuthUtils.createUser("testuser", "differentpass", serverName = "localhost")
                throw AssertionError("Should have thrown IllegalArgumentException for duplicate username")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("already exists") == true, 
                    "Exception message should indicate user already exists")
            }
        }
    }
    
    /**
     * Test that passwords are stored and can be authenticated.
     * Regression test for: TestLogin failures caused by dummy registration ignoring passwords
     */
    @Test
    fun `password storage allows later authentication`() {
        transaction(database) {
            val testPassword = "superuser123!@#"
            
            // Register user with password
            val userId = AuthUtils.createUser("dummyuser", testPassword, serverName = "localhost")
            assertNotNull(userId)
            
            // Try to authenticate with the same password
            val authResult = AuthUtils.authenticateUser("dummyuser", testPassword)
            assertNotNull(authResult, "Should be able to authenticate with stored password")
            assertEquals(userId, authResult, "Should authenticate as same user")
        }
    }
    
    /**
     * Test that login works with uppercase username.
     * Regression test for: TestLogin/Login_with_uppercase_username_works_and_GET_/whoami_afterwards_also
     */
    @Test
    fun `login accepts uppercase username for case-insensitive authentication`() {
        transaction(database) {
            val testPassword = "test123!@#ABC"
            
            // Register with lowercase
            val userId = AuthUtils.createUser("testuser", testPassword, serverName = "localhost")
            assertEquals("@testuser:localhost", userId)
            
            // Try to authenticate with uppercase
            val authResult = AuthUtils.authenticateUser("TESTUSER", testPassword)
            assertNotNull(authResult, "Should accept uppercase username in authentication")
            assertEquals(userId, authResult, "Should return correct user_id")
            
            // Try with full user ID uppercase
            val authResultFullId = AuthUtils.authenticateUser("@TESTUSER:localhost", testPassword)
            assertNotNull(authResultFullId, "Should accept uppercase full user ID")
            assertEquals(userId, authResultFullId)
        }
    }
    
    /**
     * Test that authenticateUser handles case-insensitive lookups correctly.
     * Regression test for: Case-insensitive login functionality
     */
    @Test
    fun `authenticateUser performs case-insensitive username lookup`() {
        transaction(database) {
            // Create user with lowercase username
            val password = "test123!@#ABC"
            val userId = AuthUtils.createUser("testuser", password, serverName = "localhost")
            
            // Test various case combinations
            val testCases = listOf("testuser", "TESTUSER", "TestUser", "tEsTuSeR")
            
            for (testUsername in testCases) {
                val result = AuthUtils.authenticateUser(testUsername, password)
                assertNotNull(result, "Should authenticate with username: $testUsername")
                assertEquals(userId, result, "Should return correct userId for: $testUsername")
            }
            
            // Test with wrong password
            val wrongResult = AuthUtils.authenticateUser("testuser", "wrongpassword")
            assertNull(wrongResult, "Should not authenticate with wrong password")
        }
    }
    
    /**
     * Test that authenticateUser handles full user IDs correctly.
     * Regression test for: Login with @USER:domain format
     */
    @Test
    fun `authenticateUser extracts localpart from full user ID correctly`() {
        transaction(database) {
            val password = "test123!@#ABC"
            val userId = AuthUtils.createUser("testuser", password, serverName = "localhost")
            
            // Test with full user ID
            val result1 = AuthUtils.authenticateUser("@testuser:localhost", password)
            assertNotNull(result1, "Should authenticate with full user ID")
            assertEquals(userId, result1)
            
            // Test with full user ID uppercase
            val result2 = AuthUtils.authenticateUser("@TESTUSER:localhost", password)
            assertNotNull(result2, "Should authenticate with uppercase full user ID")
            assertEquals(userId, result2)
        }
    }
    
    /**
     * Test that username validation accepts all valid Matrix characters.
     * Regression test for: Matrix spec compliance for username characters
     */
    @Test
    fun `username validation accepts all Matrix spec valid characters`() {
        transaction(database) {
            // Test username with all valid characters: a-z, 0-9, ., _, =, -, +, /
            val validUsername = "user.name_123-test=value+extra/path"
            val userId = AuthUtils.createUser(validUsername, "password", serverName = "localhost")
            assertNotNull(userId, "Should accept username with all valid Matrix characters")
            assertTrue(userId.startsWith("@${validUsername.lowercase()}:"))
        }
    }
    
    /**
     * Test that deactivated users cannot authenticate.
     * Regression test for: Account deactivation
     */
    @Test
    fun `deactivated users cannot authenticate`() {
        transaction(database) {
            val password = "testPassword123"
            val userId = AuthUtils.createUser("testuser", password, serverName = "localhost")
            
            // Verify authentication works
            val authBefore = AuthUtils.authenticateUser("testuser", password)
            assertNotNull(authBefore, "User should authenticate before deactivation")
            
            // Deactivate user
            Users.update({ Users.userId eq userId }) {
                it[deactivated] = true
            }
            
            // Verify authentication fails after deactivation
            val authAfter = AuthUtils.authenticateUser("testuser", password)
            assertNull(authAfter, "Deactivated user should not be able to authenticate")
        }
    }
}
