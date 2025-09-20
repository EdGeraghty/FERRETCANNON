package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.AccountData

fun Route.pushRoutes(_config: ServerConfig) {
    // GET /pushrules - Get push rules
    get("/pushrules") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Return default push rules as per Matrix specification
            val response = buildJsonObject {
                put("global", buildJsonObject {
                    put("override", buildJsonArray {
                        // .m.rule.master - Master rule (always enabled)
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.master")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.redaction")
                                })
                            })
                            put("actions", buildJsonArray { })
                        })
                        // .m.rule.suppress_notices - Suppress notices
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.suppress_notices")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "content.msgtype")
                                    put("pattern", "m.notice")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.invite_for_me - Invite for me
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.invite_for_me")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.member")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "content.membership")
                                    put("pattern", "invite")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "state_key")
                                    put("pattern", userId)
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.member_event - Member events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.member_event")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.member")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.is_user_mention - User mentions
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.is_user_mention")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "contains_display_name")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.contains_display_name - Contains display name
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.contains_display_name")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "contains_display_name")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.roomnotif - Room notifications
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.roomnotif")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.message")
                                })
                                add(buildJsonObject {
                                    put("kind", "sender_notification_permission")
                                    put("key", "room")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.tombstone - Tombstone events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.tombstone")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.tombstone")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.reaction - Reactions
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.reaction")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.reaction")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.room.server_acl - Server ACL events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.room.server_acl")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.server_acl")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .org.matrix.msc3786.rule.room.server_acl - MSC3786 Server ACL
                        add(buildJsonObject {
                            put("rule_id", ".org.matrix.msc3786.rule.room.server_acl")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.server_acl")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.suppress_edits - Suppress edits
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.suppress_edits")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.message")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "content")
                                    put("pattern", "org.matrix.room.message.replace")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                    })
                    put("underride", buildJsonArray {
                        // .m.rule.call - Call events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.call")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.call.invite")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "ring")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", false)
                                })
                            })
                        })
                        // .org.matrix.msc3914.rule.room.call - MSC3914 Call events
                        add(buildJsonObject {
                            put("rule_id", ".org.matrix.msc3914.rule.room.call")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "org.matrix.msc3914.call")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "ring")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", false)
                                })
                            })
                        })
                        // .m.rule.encrypted_room_one_to_one - Encrypted 1:1 rooms
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.encrypted_room_one_to_one")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "room_member_count")
                                    put("is", "2")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.encrypted")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.room_one_to_one - 1:1 rooms
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.room_one_to_one")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "room_member_count")
                                    put("is", "2")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.message - Message events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.message")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.message")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.encrypted - Encrypted events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.encrypted")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.encrypted")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                    })
                    put("sender", buildJsonObject { })
                    put("room", buildJsonObject { })
                    put("content", buildJsonObject {
                        // .m.rule.is_room_mention - Room mentions
                        put(".m.rule.is_room_mention", buildJsonObject {
                            put("rule_id", ".m.rule.is_room_mention")
                            put("default", true)
                            put("enabled", true)
                            put("pattern", "@room")
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                    })
                })
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /pushrules/ - Get push rules (with trailing slash)
    get("/pushrules/") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Return default push rules as per Matrix specification
            val response = buildJsonObject {
                put("global", buildJsonObject {
                    put("override", buildJsonArray {
                        // .m.rule.master - Master rule (always enabled)
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.master")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.redaction")
                                })
                            })
                            put("actions", buildJsonArray { })
                        })
                        // .m.rule.suppress_notices - Suppress notices
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.suppress_notices")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "content.msgtype")
                                    put("pattern", "m.notice")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.invite_for_me - Invite for me
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.invite_for_me")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.member")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "content.membership")
                                    put("pattern", "invite")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "state_key")
                                    put("pattern", userId)
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.member_event - Member events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.member_event")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.member")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.is_user_mention - User mentions
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.is_user_mention")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "contains_display_name")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.contains_display_name - Contains display name
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.contains_display_name")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "contains_display_name")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.roomnotif - Room notifications
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.roomnotif")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.message")
                                })
                                add(buildJsonObject {
                                    put("kind", "sender_notification_permission")
                                    put("key", "room")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.tombstone - Tombstone events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.tombstone")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.tombstone")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                        // .m.rule.reaction - Reactions
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.reaction")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.reaction")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.room.server_acl - Server ACL events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.room.server_acl")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.server_acl")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .org.matrix.msc3786.rule.room.server_acl - MSC3786 Server ACL
                        add(buildJsonObject {
                            put("rule_id", ".org.matrix.msc3786.rule.room.server_acl")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.server_acl")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                        // .m.rule.suppress_edits - Suppress edits
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.suppress_edits")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.message")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "content")
                                    put("pattern", "org.matrix.room.message.replace")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("dont_notify")
                            })
                        })
                    })
                    put("underride", buildJsonArray {
                        // .m.rule.call - Call events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.call")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.call.invite")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "ring")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", false)
                                })
                            })
                        })
                        // .org.matrix.msc3914.rule.room.call - MSC3914 Call events
                        add(buildJsonObject {
                            put("rule_id", ".org.matrix.msc3914.rule.room.call")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "org.matrix.msc3914.call")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "ring")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", false)
                                })
                            })
                        })
                        // .m.rule.encrypted_room_one_to_one - Encrypted 1:1 rooms
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.encrypted_room_one_to_one")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "room_member_count")
                                    put("is", "2")
                                })
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.encrypted")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.room_one_to_one - 1:1 rooms
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.room_one_to_one")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "room_member_count")
                                    put("is", "2")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.message - Message events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.message")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.message")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                        // .m.rule.encrypted - Encrypted events
                        add(buildJsonObject {
                            put("rule_id", ".m.rule.encrypted")
                            put("default", true)
                            put("enabled", true)
                            put("conditions", buildJsonArray {
                                add(buildJsonObject {
                                    put("kind", "event_match")
                                    put("key", "type")
                                    put("pattern", "m.room.encrypted")
                                })
                            })
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                            })
                        })
                    })
                    put("sender", buildJsonObject { })
                    put("room", buildJsonObject { })
                    put("content", buildJsonObject {
                        // .m.rule.is_room_mention - Room mentions
                        put(".m.rule.is_room_mention", buildJsonObject {
                            put("rule_id", ".m.rule.is_room_mention")
                            put("default", true)
                            put("enabled", true)
                            put("pattern", "@room")
                            put("actions", buildJsonArray {
                                add("notify")
                                add(buildJsonObject {
                                    put("set_tweak", "sound")
                                    put("value", "default")
                                })
                                add(buildJsonObject {
                                    put("set_tweak", "highlight")
                                    put("value", true)
                                })
                            })
                        })
                    })
                })
            }
            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /pushrules/{scope}/{kind}/{ruleId} - Set push rule
    put("/pushrules/{scope}/{kind}/{ruleId}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val scope = call.parameters["scope"]
            val kind = call.parameters["kind"]
            val ruleId = call.parameters["ruleId"]

            if (scope == null || kind == null || ruleId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val _jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            // TODO: Store push rule in account data
            // For now, just acknowledge
            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // DELETE /pushrules/{scope}/{kind}/{ruleId} - Delete push rule
    delete("/pushrules/{scope}/{kind}/{ruleId}") {
        try {
            val userId = call.validateAccessToken() ?: return@delete
            val scope = call.parameters["scope"]
            val kind = call.parameters["kind"]
            val ruleId = call.parameters["ruleId"]

            if (scope == null || kind == null || ruleId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@delete
            }

            // TODO: Delete push rule from account data
            // For now, just acknowledge
            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
