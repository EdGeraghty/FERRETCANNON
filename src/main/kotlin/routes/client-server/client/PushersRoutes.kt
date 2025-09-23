package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import models.Pushers

fun Route.pushersRoutes() {
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