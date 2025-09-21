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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import models.PushRules

fun Route.pushRoutes() {
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
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
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
            val globalRules = buildJsonObject {
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
            }
            call.respond(globalRules)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
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
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            // Extract rule properties from request body
            val actions = jsonBody["actions"]?.toString()
            val conditions = jsonBody["conditions"]?.toString()
            val before = jsonBody["before"]?.jsonPrimitive?.content
            val after = jsonBody["after"]?.jsonPrimitive?.content

            // Store push rule in database
            transaction {
                // If before or after is specified, we need to adjust priority
                var priorityClass = 0
                var priorityIndex = 0

                if (before != null || after != null) {
                    // Get existing rules to determine priority
                    val existingRules = PushRules.select {
                        (PushRules.userId eq userId) and
                        (PushRules.scope eq scope) and
                        (PushRules.kind eq kind)
                    }.orderBy(PushRules.priorityClass to SortOrder.DESC, PushRules.priorityIndex to SortOrder.DESC)

                    if (before != null) {
                        // Find the rule with ruleId == before and insert before it
                        val beforeRule = existingRules.find { it[PushRules.ruleId] == before }
                        if (beforeRule != null) {
                            priorityClass = beforeRule[PushRules.priorityClass]
                            priorityIndex = beforeRule[PushRules.priorityIndex] - 1
                        }
                    } else if (after != null) {
                        // Find the rule with ruleId == after and insert after it
                        val afterRule = existingRules.find { it[PushRules.ruleId] == after }
                        if (afterRule != null) {
                            priorityClass = afterRule[PushRules.priorityClass]
                            priorityIndex = afterRule[PushRules.priorityIndex] + 1
                        }
                    }
                }

                // Insert or update the push rule
                val existingRule = PushRules.select {
                    (PushRules.userId eq userId) and
                    (PushRules.scope eq scope) and
                    (PushRules.kind eq kind) and
                    (PushRules.ruleId eq ruleId)
                }.singleOrNull()

                if (existingRule != null) {
                    // Update existing rule
                    PushRules.update({
                        (PushRules.userId eq userId) and
                        (PushRules.scope eq scope) and
                        (PushRules.kind eq kind) and
                        (PushRules.ruleId eq ruleId)
                    }) {
                        it[PushRules.conditions] = conditions
                        it[PushRules.actions] = actions
                        it[PushRules.priorityClass] = priorityClass
                        it[PushRules.priorityIndex] = priorityIndex
                    }
                } else {
                    // Insert new rule
                    PushRules.insert {
                        it[PushRules.userId] = userId
                        it[PushRules.scope] = scope
                        it[PushRules.kind] = kind
                        it[PushRules.ruleId] = ruleId
                        it[PushRules.conditions] = conditions
                        it[PushRules.actions] = actions
                        it[PushRules.priorityClass] = priorityClass
                        it[PushRules.priorityIndex] = priorityIndex
                    }
                }
            }

            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
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
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing required parameters"
                ))
                return@delete
            }

            // Delete push rule from database
            val deletedRows = transaction {
                PushRules.deleteWhere {
                    (PushRules.userId eq userId) and
                    (PushRules.scope eq scope) and
                    (PushRules.kind eq kind) and
                    (PushRules.ruleId eq ruleId)
                }
            }

            if (deletedRows == 0) {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Push rule not found"
                ))
                return@delete
            }

            call.respond(mapOf<String, Any>())

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
