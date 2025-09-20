package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.util.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.http.content.*
import io.ktor.http.content.PartData
import models.Events
import models.Rooms
import models.Filters
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import kotlinx.serialization.json.*
import utils.users
import utils.accessTokens
import routes.server_server.federation.v1.broadcastEDU
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes
import models.AccountData
import io.ktor.websocket.Frame
import utils.MediaStorage
import models.Users
import models.AccessTokens
import at.favre.lib.crypto.bcrypt.BCrypt
import utils.AuthUtils
import utils.connectedClients
import utils.typingMap
import utils.ServerKeys
import utils.OAuthService
import utils.OAuthConfig
import config.ServerConfig
import utils.MatrixPagination

import routes.client_server.client.MATRIX_USER_ID_KEY

fun Route.userRoutes(_config: ServerConfig) {
    // GET /profile/{userId} - Get user profile
    get("/profile/{userId}") {
        try {
            val userId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId parameter"
                ))
                return@get
            }

            // Get user profile from database
            val profile = transaction {
                Users.select { Users.userId eq userId }.singleOrNull()
            }

            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "User not found"
                ))
                return@get
            }

            val response = mutableMapOf<String, String>()

            // Get display name from account data
            val displayName = transaction {
                AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()?.let { row ->
                    Json.parseToJsonElement(row[AccountData.content]).jsonObject["displayname"]?.jsonPrimitive?.content
                }
            }

            if (displayName != null) {
                response["displayname"] = displayName
            }

            // Get avatar URL from account data
            val avatarUrl = transaction {
                AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()?.let { row ->
                    Json.parseToJsonElement(row[AccountData.content]).jsonObject["avatar_url"]?.jsonPrimitive?.content
                }
            }

            if (avatarUrl != null) {
                response["avatar_url"] = avatarUrl
            }

            call.respond(response)

        } catch (e: Exception) {
            println("ERROR: Exception in GET /profile/{userId}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /profile/{userId}/displayname - Set display name
    put("/profile/{userId}/displayname") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val profileUserId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (profileUserId == null || userId != profileUserId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own profile"
                ))
                return@put
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@put
            }
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val displayName = jsonBody["displayname"]?.jsonPrimitive?.content

            // Store display name in account data
            transaction {
                val existing = AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()

                val currentContent = if (existing != null) {
                    Json.parseToJsonElement(existing[AccountData.content]).jsonObject.toMutableMap()
                } else {
                    mutableMapOf()
                }

                if (displayName != null) {
                    currentContent["displayname"] = JsonPrimitive(displayName)
                } else {
                    currentContent.remove("displayname")
                }

                if (existing != null) {
                    AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.direct") and (AccountData.roomId.isNull()) }) {
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                } else {
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = "m.direct"
                        it[AccountData.roomId] = null
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                }
            }

            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /profile/{userId}/displayname: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /profile/{userId}/avatar_url - Set avatar URL
    put("/profile/{userId}/avatar_url") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val profileUserId = call.parameters["userId"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (profileUserId == null || userId != profileUserId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own profile"
                ))
                return@put
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@put
            }
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val avatarUrl = jsonBody["avatar_url"]?.jsonPrimitive?.content

            // Store avatar URL in account data
            transaction {
                val existing = AccountData.select {
                    (AccountData.userId eq userId) and
                    (AccountData.type eq "m.direct") and
                    (AccountData.roomId.isNull())
                }.singleOrNull()

                val currentContent = if (existing != null) {
                    Json.parseToJsonElement(existing[AccountData.content]).jsonObject.toMutableMap()
                } else {
                    mutableMapOf()
                }

                if (avatarUrl != null) {
                    currentContent["avatar_url"] = JsonPrimitive(avatarUrl)
                } else {
                    currentContent.remove("avatar_url")
                }

                if (existing != null) {
                    AccountData.update({ (AccountData.userId eq userId) and (AccountData.type eq "m.direct") and (AccountData.roomId.isNull()) }) {
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                } else {
                    AccountData.insert {
                        it[AccountData.userId] = userId
                        it[AccountData.type] = "m.direct"
                        it[AccountData.roomId] = null
                        it[AccountData.content] = Json.encodeToString(JsonObject.serializer(), JsonObject(currentContent))
                    }
                }
            }

            call.respond(emptyMap<String, Any>())

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /profile/{userId}/avatar_url: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /user/{userId}/account_data/{type} - Get global account data
    get("/user/{userId}/account_data/{type}") {
        try {
            val userId = try {
                call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            } catch (e: Exception) {
                println("ERROR: Failed to get userId from attributes: ${e.message}")
                null
            }
            val accountUserId = call.parameters["userId"]
            val type = call.parameters["type"]

            println("DEBUG: GET account_data - userId: '$userId', accountUserId: '$accountUserId', type: '$type'")

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (accountUserId == null || userId != accountUserId) {
                println("DEBUG: Access forbidden - userId != accountUserId")
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only access your own account data"
                ))
                return@get
            }

            if (type == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
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
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Account data not found"
                ))
                return@get
            }

            try {
                call.respond(accountData)
            } catch (e: Exception) {
                println("ERROR: Failed to send response: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to serialize response"
                ))
            }

        } catch (e: Exception) {
            println("ERROR: Exception in GET /user/{userId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /user/{userId}/account_data/{type} - Set global account data
    put("/user/{userId}/account_data/{type}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val accountUserId = call.parameters["userId"]
            val type = call.parameters["type"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (accountUserId == null || userId != accountUserId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own account data"
                ))
                return@put
            }

            if (type == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing type parameter"
                ))
                return@put
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
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
                    call.respond(HttpStatusCode.BadRequest, mapOf(
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
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to store account data"
                ))
                return@put
            }

            call.respond(HttpStatusCode.OK, buildJsonObject { })

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /user/{userId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /user/{userId}/rooms/{roomId}/account_data/{type} - Get room account data
    get("/user/{userId}/rooms/{roomId}/account_data/{type}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val accountUserId = call.parameters["userId"]
            val roomId = call.parameters["roomId"]
            val type = call.parameters["type"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@get
            }

            if (accountUserId == null || userId != accountUserId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only access your own account data"
                ))
                return@get
            }

            if (roomId == null || type == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
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
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Account data not found"
                ))
                return@get
            }

            try {
                call.respond(accountData)
            } catch (e: Exception) {
                println("ERROR: Failed to send room account data response: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to serialize response"
                ))
            }

        } catch (e: Exception) {
            println("ERROR: Exception in GET /user/{userId}/rooms/{roomId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // PUT /user/{userId}/rooms/{roomId}/account_data/{type} - Set room account data
    put("/user/{userId}/rooms/{roomId}/account_data/{type}") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)
            val accountUserId = call.parameters["userId"]
            val roomId = call.parameters["roomId"]
            val type = call.parameters["type"]

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@put
            }

            if (accountUserId == null || userId != accountUserId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Can only set your own account data"
                ))
                return@put
            }

            if (roomId == null || type == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
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
                    call.respond(HttpStatusCode.BadRequest, mapOf(
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
                call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "errcode" to "M_UNKNOWN",
                    "error" to "Failed to store account data"
                ))
                return@put
            }

            call.respond(HttpStatusCode.OK, buildJsonObject { })

        } catch (e: Exception) {
            println("ERROR: Exception in PUT /user/{userId}/rooms/{roomId}/account_data/{type}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /user_directory/search - Search user directory
    post("/user_directory/search") {
        try {
            val userId = call.attributes.getOrNull(MATRIX_USER_ID_KEY)

            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "errcode" to "M_MISSING_TOKEN",
                    "error" to "Missing access token"
                ))
                return@post
            }

            // Parse request body
            val requestBody = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@post
            }
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val searchTerm = jsonBody["search_term"]?.jsonPrimitive?.content
            val limit = jsonBody["limit"]?.jsonPrimitive?.int ?: 10

            if (searchTerm == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
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

                        mapOf(
                            "user_id" to dbUserId,
                            "display_name" to displayName,
                            "avatar_url" to null // Simplified
                        )
                    }
            }

            call.respond(mapOf(
                "results" to results,
                "limited" to (results.size >= limit)
            ))

        } catch (e: Exception) {
            println("ERROR: Exception in POST /user_directory/search: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // POST /user/{userId}/filter - Create a filter
    post("/user/{userId}/filter") {
        try {
            val userId = call.parameters["userId"]
            val accessToken = call.validateAccessToken() ?: return@post

            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId parameter"
                ))
                return@post
            }

            // Verify the access token belongs to the user
            val tokenUser = transaction {
                AccessTokens.select { AccessTokens.token eq accessToken }
                    .singleOrNull()?.get(AccessTokens.userId)
            }

            if (tokenUser != userId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Access token does not match user"
                ))
                return@post
            }

            val filterJson = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_BAD_JSON",
                    "error" to "Failed to read request body: ${e.message}"
                ))
                return@post
            }

            // Generate a unique filter ID
            val filterId = "filter_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000, 9999)}"

            // Store the filter
            transaction {
                Filters.insert {
                    it[Filters.filterId] = filterId
                    it[Filters.userId] = userId
                    it[Filters.filterJson] = filterJson
                }
            }

            call.respond(mapOf("filter_id" to filterId))

        } catch (e: Exception) {
            println("ERROR: Exception in POST /user/{userId}/filter: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }

    // GET /user/{userId}/filter/{filterId} - Get a filter
    get("/user/{userId}/filter/{filterId}") {
        try {
            val userId = call.parameters["userId"]
            val filterId = call.parameters["filterId"]
            val accessToken = call.validateAccessToken() ?: return@get

            if (userId == null || filterId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId or filterId parameter"
                ))
                return@get
            }

            // Verify the access token belongs to the user
            val tokenUser = transaction {
                AccessTokens.select { AccessTokens.token eq accessToken }
                    .singleOrNull()?.get(AccessTokens.userId)
            }

            if (tokenUser != userId) {
                call.respond(HttpStatusCode.Forbidden, mapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Access token does not match user"
                ))
                return@get
            }

            // Retrieve the filter
            val filter = transaction {
                Filters.select {
                    (Filters.filterId eq filterId) and (Filters.userId eq userId)
                }.singleOrNull()
            }

            if (filter == null) {
                call.respond(HttpStatusCode.NotFound, mapOf(
                    "errcode" to "M_NOT_FOUND",
                    "error" to "Filter not found"
                ))
                return@get
            }

            val filterJson = filter[Filters.filterJson]
            call.respond(filterJson)

        } catch (e: Exception) {
            println("ERROR: Exception in GET /user/{userId}/filter/{filterId}: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}
