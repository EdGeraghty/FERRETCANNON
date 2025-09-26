#!/usr/bin/env kotlin

// Query rooms from database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import models.*

fun main() {
    println("=== Rooms in Database ===")

    try {
        // Connect to database
        Database.connect("jdbc:sqlite:ferretcannon.db", driver = "org.sqlite.JDBC")

        transaction {
            Rooms.selectAll().forEach { row ->
                println("Room ID: ${row[Rooms.roomId]}")
                println("Room Version: ${row[Rooms.roomVersion]}")
                println("Creator: ${row[Rooms.creator]}")
                println("---")
            }
        }
    } catch (e: Exception) {
        println("❌ Error querying database: ${e.message}")
        e.printStackTrace()
    }
}
