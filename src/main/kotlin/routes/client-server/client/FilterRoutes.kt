package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.Filters
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.filterRoutes() {
    // POST /user/{userId}/filter - Create a filter
    post("/user/{userId}/filter") {
        try {
            val userId = call.parameters["userId"]
            val authenticatedUserId = call.validateAccessToken() ?: return@post

            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId parameter"
                ))
                return@post
            }

            // Verify the authenticated user matches the requested user
            if (authenticatedUserId != userId) {
                println("DEBUG: Filter creation forbidden - authenticatedUserId: '$authenticatedUserId', requestedUserId: '$userId'")
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
                    "errcode" to "M_FORBIDDEN",
                    "error" to "Access token does not match user"
                ))
                return@post
            }

            val filterJson = try {
                call.receiveText()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
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

            call.respond(mutableMapOf("filter_id" to filterId))

        } catch (e: Exception) {
            println("ERROR: Exception in POST /user/{userId}/filter: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
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
            val authenticatedUserId = call.validateAccessToken() ?: return@get

            if (userId == null || filterId == null) {
                call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                    "errcode" to "M_INVALID_PARAM",
                    "error" to "Missing userId or filterId parameter"
                ))
                return@get
            }

            // Verify the authenticated user matches the requested user
            if (authenticatedUserId != userId) {
                println("DEBUG: Filter retrieval forbidden - authenticatedUserId: '$authenticatedUserId', requestedUserId: '$userId'")
                call.respond(HttpStatusCode.Forbidden, mutableMapOf(
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
                call.respond(HttpStatusCode.NotFound, mutableMapOf(
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
            call.respond(HttpStatusCode.InternalServerError, mutableMapOf(
                "errcode" to "M_UNKNOWN",
                "error" to "Internal server error"
            ))
        }
    }
}