#!/usr/bin/env kotlin

// Create test access token for FERRETCANNON server testing

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import models.*
import utils.AuthUtils

fun main() {
    println("=== Creating Test Access Token for FERRETCANNON ===")

    try {
        // Connect to database
        Database.connect("jdbc:sqlite:ferretcannon.db", driver = "org.sqlite.JDBC")

        // Create schema if needed
        transaction {
            SchemaUtils.create(Users, AccessTokens, Devices)
        }

        // Create test user if doesn't exist
        val testUserId = "@testuser:localhost"
        val testDeviceId = "test_device_123"

        transaction {
            val existingUser = Users.select { Users.userId eq testUserId }.singleOrNull()
            if (existingUser == null) {
                Users.insert {
                    it[userId] = testUserId
                    it[username] = "testuser"
                    it[passwordHash] = AuthUtils.hashPassword("TestPass123!")
                    it[displayName] = "Test User"
                    it[isGuest] = false
                    it[deactivated] = false
                    it[createdAt] = System.currentTimeMillis()
                }
                println("✅ Created test user: $testUserId")
            } else {
                println("ℹ️  Test user already exists: $testUserId")
            }
        }

        // Create access token
        val accessToken = AuthUtils.createAccessToken(
            userId = testUserId,
            deviceId = testDeviceId,
            userAgent = "TestScript/1.0",
            ipAddress = "127.0.0.1"
        )

        println("✅ Access Token Created Successfully!")
        println("🔑 Access Token: $accessToken")
        println("👤 User ID: $testUserId")
        println("📱 Device ID: $testDeviceId")
        println("\n📋 Test the capabilities endpoint with:")
        println("curl -H \"Authorization: Bearer $accessToken\" http://localhost:8080/_matrix/client/v3/capabilities")

    } catch (e: Exception) {
        println("❌ Error creating test token: ${e.message}")
        e.printStackTrace()
    }
}
