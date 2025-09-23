package routes.client_server.client.user

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.AccountData
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import routes.client_server.client.common.*

fun Route.accountDataRoutes() {
    // GET /user/{userId}/account_data/{type} - Get global account data
    get("/user/{userId}/account_data/{type}") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val accountUserId = call.parameters["userId"]
            val type = call.parameters["type"]

            println("DEBUG: GET account_data - userId: '$userId', accountUserId: '$accountUserId', type: '$type'")

            if (accountUserId == null || userId != accountUserId) {
                println("DEBUG: Access forbidden - userId: '$userId', accountUserId: '$accountUserId'")
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only access your own account data"
                ))
                return@get
            }

            if (type == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing type parameter"
                ))
                return@get
            }

            // Get account data from database
            val accountData = try {
                transaction {
                    println("DEBUG: Executing database query")
                    val result = AccountData.select {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq type) and
                        (AccountData.roomId.isNull())
                    }.singleOrNull()
                    println("DEBUG: Database query result: $result")
                    result
                }?.let { row ->
                    try {
                        val content = row[AccountData.content]
                        println("DEBUG: Found content: '$content'")
                        if (content.isBlank()) {
                            buildJsonObject { }
                        } else {
                            Json.parseToJsonElement(content)
                        }
                    } catch (e: Exception) {
                        println("ERROR: Failed to parse account data JSON: ${e.message}")
                        println("ERROR: Raw content: '$row[AccountData.content]'")
                        null
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Database transaction failed: ${e.message}")
                e.printStackTrace()
                null
            }

            println("DEBUG: accountData result: $accountData")

            if (accountData == null) {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Account data not found"
                ))
                return@get
            }

            try {
                call.respond(accountData)
            } catch (e: Exception) {
                println("ERROR: Failed to send response: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to serialize response"
                ))
            }

        } catch (e: Exception) {
            println("ERROR: Exception in GET /user/{userId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /user/{userId}/account_data/{type} - Set global account data
    put("/user/{userId}/account_data/{type}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val accountUserId = call.parameters["userId"]
            val type = call.parameters["type"]

            if (accountUserId == null || userId != accountUserId) {
                println("DEBUG: Access forbidden - userId: '$userId', accountUserId: '$accountUserId'")
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own account data"
                ))
                return@put
            }

            if (type == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing type parameter"
                ))
                return@put
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@put
            }
            val jsonBody = if (requestBody.isBlank()) {
                buildJsonObject { }
            } else {
                try {
                    Json.parseToJsonElement(requestBody)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                        "errcode" to "M_BAD_JSON",
                        "error" to "Invalid JSON: ${e.message}"
                    ))
                    return@put
                }
            }

            // Store account data in database
            try {
                transaction {
                    // Delete existing entry first, then insert (manual upsert)
                    AccountData.deleteWhere {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq type) and
                        (AccountData.roomId.isNull())
                    }

                    // Insert new entry
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = type
                        it[AccountData.roomId] = null
                        it[AccountData.content] = jsonBody.toString()
                        it[AccountData.lastModified] = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Failed to store account data: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to store account data"
                ))
                return@put
            }

            call.respond(HttpStatusCode.OK, buildJsonObject { })

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /user/{userId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /user/{userId}/rooms/{roomId}/account_data/{type} - Get room account data
    get("/user/{userId}/rooms/{roomId}/account_data/{type}") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val accountUserId = call.parameters["userId"]
            val roomId = call.parameters["roomId"]
            val type = call.parameters["type"]

            if (accountUserId == null || userId != accountUserId) {
                println("DEBUG: Access forbidden - userId: '$userId', accountUserId: '$accountUserId' (room account data)")
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only access your own account data"
                ))
                return@get
            }

            if (roomId == null || type == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId or type parameter"
                ))
                return@get
            }

            // Get room account data from database
            val accountData = try {
                transaction {
                    AccountData.select {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq type) and
                        (AccountData.roomId eq roomId)
                    }.singleOrNull()?.let { row ->
                        try {
                            val content = row[AccountData.content]
                            println("DEBUG: Found room account data content: '$content'")
                            if (content.isBlank()) {
                                buildJsonObject { }
                            } else {
                                Json.parseToJsonElement(content)
                            }
                        } catch (e: Exception) {
                            println("ERROR: Failed to parse room account data JSON: ${e.message}")
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Database transaction failed for room account data: ${e.message}")
                null
            }

            if (accountData == null) {
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Account data not found"
                ))
                return@get
            }

            try {
                call.respond(accountData)
            } catch (e: Exception) {
                println("ERROR: Failed to send room account data response: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to serialize response"
                ))
            }

        } catch (e: Exception) {
            println("ERROR: Exception in GET /user/{userId}/rooms/{roomId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /user/{userId}/rooms/{roomId}/account_data/{type} - Set room account data
    put("/user/{userId}/rooms/{roomId}/account_data/{type}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val accountUserId = call.parameters["userId"]
            val roomId = call.parameters["roomId"]
            val type = call.parameters["type"]

            if (accountUserId == null || userId != accountUserId) {
                println("DEBUG: Access forbidden - userId: '$userId', accountUserId: '$accountUserId' (room account data)")
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own account data"
                ))
                return@put
            }

            if (roomId == null || type == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing roomId or type parameter"
                ))
                return@put
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = if (requestBody.isBlank()) {
                buildJsonObject { }
            } else {
                try {
                    Json.parseToJsonElement(requestBody)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                        "errcode" to "M_BAD_JSON",
                        "error" to "Invalid JSON: ${e.message}"
                    ))
                    return@put
                }
            }

            // Store room account data in database
            try {
                transaction {
                    // Delete existing entry first, then insert (manual upsert)
                    AccountData.deleteWhere {
                        (AccountData.userId eq userId) and
                        (AccountData.type eq type) and
                        (AccountData.roomId eq roomId)
                    }

                    // Insert new entry
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = type
                        it[AccountData.roomId] = roomId
                        it[AccountData.content] = jsonBody.toString()
                        it[AccountData.lastModified] = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Failed to store room account data: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to store account data"
                ))
                return@put
            }

            call.respond(HttpStatusCode.OK, buildJsonObject { })

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /user/{userId}/rooms/{roomId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}