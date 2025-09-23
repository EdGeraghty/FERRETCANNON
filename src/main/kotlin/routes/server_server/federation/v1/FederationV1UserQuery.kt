package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Events
import models.Rooms
import utils.StateResolver
import utils.MatrixAuth

fun Route.federationV1UserQuery() {
    get("/query/directory") {
        val roomAlias = call.request.queryParameters["room_alias"]

        if (roomAlias == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing room_alias parameter")
            })
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        val isAuthValid = try {
            authHeader != null && MatrixAuth.verifyAuth(call, authHeader, null)
        } catch (e: Exception) {
            false
        }
        if (!isAuthValid) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Look up room by alias
            val roomId = findRoomByAlias(roomAlias)

            if (roomId == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room alias not found")
                })
                return@get
            }

            // Get room information
            val roomInfo = getRoomInfo(roomId)
            if (roomInfo == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room not found")
                })
                return@get
            }

            val servers = listOf("localhost") // In a real implementation, this would include all servers that know about the room

            call.respond(buildJsonObject {
                put("room_id", roomId)
                put("servers", Json.encodeToJsonElement(servers))
            })
        } catch (e: Exception) {
            println("Query directory error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
    get("/query/profile") {
        val userId = call.request.queryParameters["user_id"]
        val field = call.request.queryParameters["field"]

        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing user_id parameter")
            })
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        val isAuthValid = try {
            authHeader != null && MatrixAuth.verifyAuth(call, authHeader, null)
        } catch (e: Exception) {
            false
        }
        if (!isAuthValid) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Get user profile information
            val profile = getUserProfile(userId, field)
            if (profile != null) {
                call.respond(profile)
            } else {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "User not found")
                })
            }
        } catch (e: Exception) {
            println("Query profile error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
    get("/query/displayname") {
        val userId = call.request.queryParameters["user_id"]

        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing user_id parameter")
            })
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        val isAuthValid = try {
            authHeader != null && MatrixAuth.verifyAuth(call, authHeader, null)
        } catch (e: Exception) {
            false
        }
        if (!isAuthValid) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Get user display name specifically
            val profile = getUserProfile(userId, "displayname")
            if (profile != null && profile.containsKey("displayname")) {
                call.respond(buildJsonObject {
                    put("displayname", Json.encodeToJsonElement(profile["displayname"]))
                })
            } else {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "User display name not found")
                })
            }
        } catch (e: Exception) {
            println("Query displayname error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
    get("/query/avatar_url") {
        val userId = call.request.queryParameters["user_id"]

        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing user_id parameter")
            })
            return@get
        }

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        val isAuthValid = try {
            authHeader != null && MatrixAuth.verifyAuth(call, authHeader, null)
        } catch (e: Exception) {
            false
        }
        if (!isAuthValid) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            // Get user avatar URL specifically
            val profile = getUserProfile(userId, "avatar_url")
            if (profile != null && profile.containsKey("avatar_url")) {
                call.respond(buildJsonObject {
                    put("avatar_url", Json.encodeToJsonElement(profile["avatar_url"]))
                })
            } else {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "User avatar URL not found")
                })
            }
        } catch (e: Exception) {
            println("Query avatar_url error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
    get("/query") {
        // Generic /query endpoint - returns information about available query types
        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
            put("errcode", "M_INVALID_PARAM")
            put("error", "Query type required. Available query types: directory, profile, displayname, avatar_url")
        })
    }
    get("/query/{queryType}") {
        val queryType = call.parameters["queryType"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        // Authenticate the request
        val authHeader = call.request.headers["Authorization"]
        val isAuthValid = try {
            authHeader != null && MatrixAuth.verifyAuth(call, authHeader, null)
        } catch (e: Exception) {
            false
        }
        if (!isAuthValid) {
            call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                put("errcode", "M_UNAUTHORIZED")
                put("error", "Invalid signature")
            })
            return@get
        }

        try {
            when (queryType) {
                "directory" -> {
                    // This is handled by the specific directory endpoint above
                    call.respond(HttpStatusCode.NotFound, buildJsonObject {
                        put("errcode", "M_UNRECOGNIZED")
                        put("error", "Use /query/directory endpoint")
                    })
                }
                "profile" -> {
                    // This is handled by the specific profile endpoint above
                    call.respond(HttpStatusCode.NotFound, buildJsonObject {
                        put("errcode", "M_UNRECOGNIZED")
                        put("error", "Use /query/profile endpoint")
                    })
                }
                else -> {
                    // Unknown query type
                    call.respond(HttpStatusCode.NotFound, buildJsonObject {
                        put("errcode", "M_UNRECOGNIZED")
                        put("error", "Unknown query type: $queryType")
                    })
                }
            }
        } catch (e: Exception) {
            println("Query error: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", e.message)
            })
        }
    }
}
