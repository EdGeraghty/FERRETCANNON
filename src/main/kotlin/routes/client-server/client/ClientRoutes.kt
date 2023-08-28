package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.util.*
import models.Events
import models.Rooms
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.*
import utils.users

fun Application.clientRoutes() {
    install(Authentication) {
        bearer("matrix-auth") {
            authenticate { tokenCredential ->
                // Simple token validation - in real implementation, validate against DB
                val userId = users.entries.find { it.value == tokenCredential.token }?.key
                if (userId != null) {
                    UserIdPrincipal(userId)
                } else {
                    null
                }
            }
        }
    }

    // Custom authentication for Matrix access_token query parameter
    intercept(ApplicationCallPipeline.Call) {
        val accessToken = call.request.queryParameters["access_token"]
        if (accessToken != null) {
            val userId = users.entries.find { it.value == accessToken }?.key
            if (userId != null) {
                call.attributes.put(AttributeKey("matrix-user"), UserIdPrincipal(userId))
            }
        }
    }

    routing {
        route("/_matrix") {
            route("/client") {
                route("/v3") {
                    get("/versions") {
                        println("Handling versions request")
                        call.respond(mapOf("versions" to listOf("v1.1", "v1.2", "v1.3", "v1.4", "v1.5", "v1.6", "v1.7", "v1.8", "v1.9", "v1.10", "v1.11", "v1.12", "v1.13", "v1.14", "v1.15")))
                    }

                    // Login endpoint
                    post("/login") {
                        val request = call.receiveText()
                        val json = Json.parseToJsonElement(request).jsonObject
                        val userId = json["user"]?.jsonPrimitive?.content ?: "user1"
                        val password = json["password"]?.jsonPrimitive?.content ?: "pass"

                        // Simple login - generate token
                        val token = "token_${userId}_${System.currentTimeMillis()}"
                        users[userId] = token

                        call.respond(mapOf(
                            "user_id" to userId,
                            "access_token" to token,
                            "home_server" to "localhost:8080"
                        ))
                    }

                    // Sync endpoint
                    get("/sync") {
                        println("Sync endpoint called")
                        val accessToken = call.request.queryParameters["access_token"]
                        println("Access token from query: $accessToken")
                        val userId = if (accessToken != null) {
                            val foundUser = users.entries.find { it.value == accessToken }?.key
                            println("Found user for token: $foundUser")
                            foundUser
                        } else {
                            call.principal<UserIdPrincipal>()?.name
                        }

                        println("Final userId: $userId")
                        if (userId == null) {
                            println("Returning unauthorized")
                            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing access token"))
                        }

                        // Simplified sync response
                        println("Returning sync response")
                        call.respond(mapOf(
                            "next_batch" to "next_batch_token",
                            "rooms" to mapOf("join" to emptyMap<String, Any>()),
                            "presence" to mapOf("events" to emptyList<Any>()),
                            "account_data" to mapOf("events" to emptyList<Any>()),
                            "to_device" to mapOf("events" to emptyList<Any>()),
                            "device_lists" to mapOf("changed" to emptyList<String>(), "left" to emptyList<String>()),
                            "device_one_time_keys_count" to emptyMap<String, Int>()
                        ))
                    }
                }
            }
        }
    }
}
