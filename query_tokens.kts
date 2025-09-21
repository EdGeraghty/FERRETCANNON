#!/usr/bin/env kotlin

// Query access tokens from database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import models.*

fun main() {
    println("=== Access Tokens in Database ===")

    try {
        // Connect to database
        Database.connect("jdbc:sqlite:ferretcannon.db", driver = "org.sqlite.JDBC")

        transaction {
            AccessTokens.selectAll().forEach { row ->
                println("Token: ${row[AccessTokens.token]}")
                println("User ID: ${row[AccessTokens.userId]}")
                println("Device ID: ${row[AccessTokens.deviceId]}")
                println("Created: ${row[AccessTokens.createdAt]}")
                println("---")
            }
        }
    } catch (e: Exception) {
        println("❌ Error querying database: ${e.message}")
        e.printStackTrace()
    }
}
