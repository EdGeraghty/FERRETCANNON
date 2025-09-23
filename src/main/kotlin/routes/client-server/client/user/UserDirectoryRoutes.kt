package routes.client_server.client.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.AccountData
import models.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import routes.client_server.client.common.*

fun Route.userDirectoryRoutes() {
    // POST /user_directory/search - Search user directory
    post("/user_directory/search") {
        try {
            call.validateAccessToken() ?: return@post

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@post
            }
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val searchTerm = jsonBody["search_term"]?.jsonPrimitive?.content
            val limit = jsonBody["limit"]?.jsonPrimitive?.int ?: 10

            if (searchTerm == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing search_term"
                ))
                return@post
            }

            // Search users in database
            val results = transaction {
                Users.selectAll()
                    .filter { row ->
                        val dbUserId = row[Users.userId]
                        dbUserId.contains(searchTerm, ignoreCase = true)
                    }
                    .take(limit)
                    .map { row ->
                        val dbUserId = row[Users.userId]

                        // Get display name from account data
                        val displayName = AccountData.select {
                            (AccountData.userId eq dbUserId) and
                            (AccountData.type eq "m.direct") and
                            (AccountData.roomId.isNull())
                        }.singleOrNull()?.let { accountRow ->
                            Json.parseToJsonElement(accountRow[AccountData.content]).jsonObject["displayname"]?.jsonPrimitive?.content
                        }

                        mutableMapOf(
                            "user_id" to dbUserId,
                            "display_name" to displayName,
                            "avatar_url" to null // Simplified
                        )
                    }
            }

            call.respond(mutableMapOf(
                "results" to results,
                "limited" to (results.size >= limit)
            ))

        } catch (e: Exception) {
            println("ERROR: Exception in POST /user_directory/search: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}