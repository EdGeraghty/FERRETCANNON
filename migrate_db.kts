#!/usr/bin/env kotlin

// Migration script to add missing columns to existing database
// Run with: kotlin migrate_db.kts

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import java.io.File

println("Starting database migration...")

// Connect to the database
val dbFile = File("/data/ferretcannon.db")
if (!dbFile.exists()) {
    println("Database file does not exist at /data/ferretcannon.db")
    System.exit(1)
}

Database.connect("jdbc:sqlite:/data/ferretcannon.db", driver = "org.sqlite.JDBC")

transaction {
    // Check if is_admin column exists, if not add it
    val result = exec("PRAGMA table_info(users)") { rs ->
        var hasIsAdmin = false
        while (rs.next()) {
            val columnName = rs.getString("name")
            if (columnName == "is_admin") {
                hasIsAdmin = true
                break
            }
        }
        hasIsAdmin
    }

    if (result == false) {
        println("Adding is_admin column to users table...")
        exec("ALTER TABLE users ADD COLUMN is_admin BOOLEAN DEFAULT 0")
        println("✅ Added is_admin column")
    } else {
        println("✅ is_admin column already exists")
    }

    // Check for other potentially missing columns
    // Add more column checks here as needed
}

println("Database migration completed successfully.")
