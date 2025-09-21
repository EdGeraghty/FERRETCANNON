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
import models.Pushers

fun Route.pushRoutes() {
    // Function to build default global push rules
    fun buildGlobalRules(userId: String): JsonObject {
        return buildJsonObject {
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
                // .m.rule.is_user_mention - User mentions (intentional only per MSC4142)
                add(buildJsonObject {
                    put("rule_id", ".m.rule.is_user_mention")
                    put("default", true)
                    put("enabled", true)
                    put("conditions", buildJsonArray {
                        add(buildJsonObject {
                            put("kind", "org.matrix.experimental.msc4142.is_user_mention")
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
                // .m.rule.is_room_mention - Room mentions (intentional only per MSC4142)
                put(".m.rule.is_room_mention", buildJsonObject {
                    put("rule_id", ".m.rule.is_room_mention")
                    put("default", true)
                    put("enabled", true)
                    put("conditions", buildJsonArray {
                        add(buildJsonObject {
                            put("kind", "org.matrix.experimental.msc4142.is_room_mention")
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
            })
        }
    }

    // GET /pushrules and /pushrules/ - Get push rules
    route("/pushrules") {
        get("") {
            try {
                val userId = call.validateAccessToken() ?: return@get

                val response = buildJsonObject {
                    put("global", buildGlobalRules(userId))
                }
                call.respond(response)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Internal server error"
                ))
            }
        }

        get("/") {
            try {
                val userId = call.validateAccessToken() ?: return@get

                val response = buildJsonObject {
                    put("global", buildGlobalRules(userId))
                }
                call.respond(response)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Internal server error"
                ))
            }
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

    // POST /pushers - Set pusher
    post("/pushers") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            val pushkey = jsonBody["pushkey"]?.jsonPrimitive?.content
            val kind = jsonBody["kind"]?.jsonPrimitive?.content
            val appId = jsonBody["app_id"]?.jsonPrimitive?.content
            val appDisplayName = jsonBody["app_display_name"]?.jsonPrimitive?.content
            val deviceDisplayName = jsonBody["device_display_name"]?.jsonPrimitive?.content
            val profileTag = jsonBody["profile_tag"]?.jsonPrimitive?.content
            val lang = jsonBody["lang"]?.jsonPrimitive?.content ?: "en"
            val data = jsonBody["data"]?.toString()

            if (pushkey == null || kind == null || appId == null || data == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_MISSING_PARAM",
                    "error" to "Missing required parameters: pushkey, kind, app_id, data"
                ))
                return@post
            }

            if (kind != "http") {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Only 'http' kind is supported"
                ))
                return@post
            }

            // Store pusher in database
            transaction {
                val existingPusher = Pushers.select {
                    (Pushers.userId eq userId) and (Pushers.pushkey eq pushkey)
                }.singleOrNull()

                if (existingPusher != null) {
                    // Update existing pusher
                    Pushers.update({
                        (Pushers.userId eq userId) and (Pushers.pushkey eq pushkey)
                    }) {
                        it[Pushers.kind] = kind
                        it[Pushers.appId] = appId
                        it[Pushers.appDisplayName] = appDisplayName
                        it[Pushers.deviceDisplayName] = deviceDisplayName
                        it[Pushers.profileTag] = profileTag
                        it[Pushers.lang] = lang
                        it[Pushers.data] = data
                        it[Pushers.lastSeen] = System.currentTimeMillis()
                    }
                } else {
                    // Insert new pusher
                    Pushers.insert {
                        it[Pushers.userId] = userId
                        it[Pushers.pushkey] = pushkey
                        it[Pushers.kind] = kind
                        it[Pushers.appId] = appId
                        it[Pushers.appDisplayName] = appDisplayName
                        it[Pushers.deviceDisplayName] = deviceDisplayName
                        it[Pushers.profileTag] = profileTag
                        it[Pushers.lang] = lang
                        it[Pushers.data] = data
                        it[Pushers.createdAt] = System.currentTimeMillis()
                        it[Pushers.lastSeen] = System.currentTimeMillis()
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

    // GET /pushers - Get pushers
    get("/pushers") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Get pushers from database
            val pushers = transaction {
                Pushers.select { Pushers.userId eq userId }
                    .map { pusherRow ->
                        val dataStr = pusherRow[Pushers.data]
                        val dataJson = if (dataStr.isEmpty()) Json.parseToJsonElement("{}") else Json.parseToJsonElement(dataStr)
                        buildJsonObject {
                            put("pushkey", pusherRow[Pushers.pushkey])
                            put("kind", pusherRow[Pushers.kind])
                            put("app_id", pusherRow[Pushers.appId])
                            put("app_display_name", pusherRow[Pushers.appDisplayName])
                            put("device_display_name", pusherRow[Pushers.deviceDisplayName])
                            put("profile_tag", pusherRow[Pushers.profileTag])
                            put("lang", pusherRow[Pushers.lang])
                            put("data", dataJson)
                        }
                    }
            }

            call.respond(mapOf("pushers" to pushers))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // DELETE /pushers/{pushkey} - Delete pusher
    delete("/pushers/{pushkey}") {
        try {
            val userId = call.validateAccessToken() ?: return@delete
            val pushkey = call.parameters["pushkey"]

            if (pushkey == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing pushkey parameter"
                ))
                return@delete
            }

            // Delete pusher from database
            val deletedRows = transaction {
                Pushers.deleteWhere {
                    (Pushers.userId eq userId) and (Pushers.pushkey eq pushkey)
                }
            }

            if (deletedRows == 0) {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Pusher not found"
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
