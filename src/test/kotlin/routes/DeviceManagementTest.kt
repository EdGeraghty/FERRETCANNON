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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for device management endpoints to fix complement test failures.
 * 
 * These tests validate the implementation of:
 * - PUT /_matrix/client/v3/devices/{deviceId} returns 404 for unknown devices
 * - Device display name updates work correctly
 * 
 * Key areas tested:
 * 1. Updating device display name for existing devices
 * 2. Returning 404 when attempting to update non-existent devices
 * 3. Device retrieval and validation
 * 
 * Big shoutout to the FERRETCANNON massive for Matrix v1.16 compliance! 🚀
 */
class DeviceManagementTest {
    
    companion object {
        private lateinit var database: Database
        private const val TEST_DB_FILE = "test_device_management.db"
        
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
     * Test that updating a device display name works for existing devices
     */
    @Test
    fun `updateDeviceDisplayName succeeds for existing device`() {
        val testUserId = "@devicetest1:localhost"
        val testDeviceId = "DEVICE123"
        val newDisplayName = "My Test Device"
        
        // Create test user
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest1"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Create test device
        transaction {
            Devices.insert {
                it[userId] = testUserId
                it[deviceId] = testDeviceId
                it[displayName] = "Old Name"
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "127.0.0.1"
            }
        }
        
        // Update device display name
        val updated = AuthUtils.updateDeviceDisplayName(testUserId, testDeviceId, newDisplayName)
        
        assertTrue(updated, "Update should succeed for existing device")
        
        // Verify the update
        val device = transaction {
            Devices.select { 
                (Devices.userId eq testUserId) and (Devices.deviceId eq testDeviceId)
            }.singleOrNull()
        }
        
        assertNotNull(device, "Device should still exist")
        assertEquals(newDisplayName, device[Devices.displayName], "Display name should be updated")
    }
    
    /**
     * Test that updating a non-existent device returns false
     */
    @Test
    fun `updateDeviceDisplayName returns false for non-existent device`() {
        val testUserId = "@devicetest2:localhost"
        val nonExistentDeviceId = "NONEXISTENT"
        
        // Create test user but no device
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest2"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Attempt to update non-existent device
        val updated = AuthUtils.updateDeviceDisplayName(testUserId, nonExistentDeviceId, "New Name")
        
        assertFalse(updated, "Update should fail for non-existent device")
    }
    
    /**
     * Test that updating device for wrong user returns false
     */
    @Test
    fun `updateDeviceDisplayName returns false when device belongs to different user`() {
        val testUserId1 = "@devicetest3a:localhost"
        val testUserId2 = "@devicetest3b:localhost"
        val testDeviceId = "DEVICE456"
        
        // Create two users
        transaction {
            Users.insert {
                it[userId] = testUserId1
                it[username] = "devicetest3a"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
            Users.insert {
                it[userId] = testUserId2
                it[username] = "devicetest3b"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Create device for user1
        transaction {
            Devices.insert {
                it[userId] = testUserId1
                it[deviceId] = testDeviceId
                it[displayName] = "User1 Device"
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "127.0.0.1"
            }
        }
        
        // Attempt to update device as user2
        val updated = AuthUtils.updateDeviceDisplayName(testUserId2, testDeviceId, "Hacked Name")
        
        assertFalse(updated, "Update should fail when device belongs to different user")
        
        // Verify device display name wasn't changed
        val device = transaction {
            Devices.select { 
                (Devices.userId eq testUserId1) and (Devices.deviceId eq testDeviceId)
            }.singleOrNull()
        }
        
        assertNotNull(device, "Device should still exist")
        assertEquals("User1 Device", device[Devices.displayName], "Display name should not be changed")
    }
    
    /**
     * Test that device display name can be set to null (cleared)
     */
    @Test
    fun `updateDeviceDisplayName can clear display name by setting to null`() {
        val testUserId = "@devicetest4:localhost"
        val testDeviceId = "DEVICE789"
        
        // Create test user
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest4"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Create test device with display name
        transaction {
            Devices.insert {
                it[userId] = testUserId
                it[deviceId] = testDeviceId
                it[displayName] = "Initial Name"
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "127.0.0.1"
            }
        }
        
        // Clear device display name
        val updated = AuthUtils.updateDeviceDisplayName(testUserId, testDeviceId, null)
        
        assertTrue(updated, "Update should succeed")
        
        // Verify the display name is null
        val device = transaction {
            Devices.select { 
                (Devices.userId eq testUserId) and (Devices.deviceId eq testDeviceId)
            }.singleOrNull()
        }
        
        assertNotNull(device, "Device should still exist")
        assertNull(device[Devices.displayName], "Display name should be null")
    }
    
    /**
     * Test that getUserDevice returns null for non-existent device
     */
    @Test
    fun `getUserDevice returns null for non-existent device`() {
        val testUserId = "@devicetest5:localhost"
        val nonExistentDeviceId = "DOESNOTEXIST"
        
        // Create test user
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest5"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query non-existent device
        val device = AuthUtils.getUserDevice(testUserId, nonExistentDeviceId)
        
        assertNull(device, "getUserDevice should return null for non-existent device")
    }
    
    /**
     * Test that getUserDevice returns correct device info
     */
    @Test
    fun `getUserDevice returns correct device information`() {
        val testUserId = "@devicetest6:localhost"
        val testDeviceId = "DEVICEABC"
        val testDisplayName = "Test Device ABC"
        
        // Create test user
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest6"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Create test device
        transaction {
            Devices.insert {
                it[userId] = testUserId
                it[deviceId] = testDeviceId
                it[displayName] = testDisplayName
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "192.168.1.1"
            }
        }
        
        // Query the device
        val device = AuthUtils.getUserDevice(testUserId, testDeviceId)
        
        assertNotNull(device, "Device should be found")
        assertEquals(testDeviceId, device["device_id"], "Device ID should match")
        assertEquals(testDisplayName, device["display_name"], "Display name should match")
    }
    
    /**
     * Test that getUserDevices returns empty list for user with no devices
     */
    @Test
    fun `getUserDevices returns empty list for user with no devices`() {
        val testUserId = "@devicetest7:localhost"
        
        // Create test user with no devices
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest7"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Query devices
        val devices = AuthUtils.getUserDevices(testUserId)
        
        assertTrue(devices.isEmpty(), "getUserDevices should return empty list")
    }
    
    /**
     * Test that getUserDevices returns all user devices
     */
    @Test
    fun `getUserDevices returns all devices for a user`() {
        val testUserId = "@devicetest8:localhost"
        
        // Create test user
        transaction {
            Users.insert {
                it[userId] = testUserId
                it[username] = "devicetest8"
                it[passwordHash] = "dummy_hash"
                it[createdAt] = System.currentTimeMillis()
                it[lastSeen] = System.currentTimeMillis()
            }
        }
        
        // Create multiple devices
        transaction {
            Devices.insert {
                it[userId] = testUserId
                it[deviceId] = "DEV1"
                it[displayName] = "Device One"
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "127.0.0.1"
            }
            Devices.insert {
                it[userId] = testUserId
                it[deviceId] = "DEV2"
                it[displayName] = "Device Two"
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "127.0.0.2"
            }
            Devices.insert {
                it[userId] = testUserId
                it[deviceId] = "DEV3"
                it[displayName] = "Device Three"
                it[lastSeen] = System.currentTimeMillis()
                it[ipAddress] = "127.0.0.3"
            }
        }
        
        // Query devices
        val devices = AuthUtils.getUserDevices(testUserId)
        
        assertEquals(3, devices.size, "Should return all 3 devices")
        
        val deviceIds = devices.map { it["device_id"] as String }.toSet()
        assertTrue(deviceIds.contains("DEV1"), "Should include DEV1")
        assertTrue(deviceIds.contains("DEV2"), "Should include DEV2")
        assertTrue(deviceIds.contains("DEV3"), "Should include DEV3")
    }
}
