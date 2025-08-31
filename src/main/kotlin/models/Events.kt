package models

import org.jetbrains.exposed.sql.Table

object Events : Table("events") {
    val eventId = varchar("event_id", 255).uniqueIndex()
    val roomId = varchar("room_id", 255)
    val type = varchar("type", 255)
    val sender = varchar("sender", 255)
    val content = text("content")
    val authEvents = text("auth_events") // JSON array of event IDs
    val prevEvents = text("prev_events") // JSON array
    val depth = integer("depth")
    val hashes = text("hashes") // JSON
    val signatures = text("signatures") // JSON
    val originServerTs = long("origin_server_ts")
    val stateKey = varchar("state_key", 255).nullable()
    val unsigned = text("unsigned").nullable()
    val softFailed = bool("soft_failed").default(false)
    val outlier = bool("outlier").default(false)
}

object Rooms : Table("rooms") {
    val roomId = varchar("room_id", 255).uniqueIndex()
    val creator = varchar("creator", 255)
    val name = varchar("name", 255).nullable()
    val topic = text("topic").nullable()
    val visibility = varchar("visibility", 50).default("private")
    val roomVersion = varchar("room_version", 50).default("9")
    val isDirect = bool("is_direct").default(false)
    val currentState = text("current_state") // JSON map of state
    val stateGroups = text("state_groups") // JSON map of state groups for resolution
    val published = bool("published").default(false) // Whether room is published in directory
}

object StateGroups : Table("state_groups") {
    val groupId = integer("group_id").uniqueIndex()
    val roomId = varchar("room_id", 255)
    val state = text("state") // JSON map of state for this group
    val events = text("events") // JSON array of event IDs in this group
}

object AccountData : Table("account_data") {
    val userId = varchar("user_id", 255)
    val type = varchar("type", 255)
    val roomId = varchar("room_id", 255).nullable() // null for global account data
    val content = text("content") // JSON content
    val lastModified = long("last_modified").default(System.currentTimeMillis())

    override val primaryKey = PrimaryKey(userId, type, roomId)
}
