package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import models.RoomKeyVersions
import models.RoomKeys
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.*

fun Route.roomKeysRoutes(config: ServerConfig) {
    // PUT /room_keys/version - Create a new room key backup version
    put("/room_keys/version") {
        try {
            val userId = call.validateAccessToken() ?: return@put

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val algorithm = jsonBody["algorithm"]?.jsonPrimitive?.content ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing algorithm")
            })
            val authData = jsonBody["auth_data"]?.jsonObject ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing auth_data")
            })

            val version = transaction {
                // Generate a new version (simple increment)
                val latestVersion = RoomKeyVersions.select { RoomKeyVersions.userId eq userId }
                    .orderBy(RoomKeyVersions.version, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                val newVersion = (latestVersion?.get(RoomKeyVersions.version)?.toIntOrNull() ?: 0) + 1

                RoomKeyVersions.insert {
                    it[RoomKeyVersions.userId] = userId
                    it[RoomKeyVersions.version] = newVersion.toString()
                    it[RoomKeyVersions.algorithm] = algorithm
                    it[RoomKeyVersions.authData] = Json.encodeToString(JsonObject.serializer(), authData)
                    it[RoomKeyVersions.createdAt] = System.currentTimeMillis()
                }

                newVersion.toString()
            }

            call.respond(buildJsonObject {
                put("version", version)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /room_keys/version - Get the current version of the user's room keys
    get("/room_keys/version") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            // Get the latest room key version for the user
            val latestVersion = transaction {
                RoomKeyVersions.select { RoomKeyVersions.userId eq userId }
                    .orderBy(RoomKeyVersions.version, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
            }

            if (latestVersion == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "No key backup found")
                })
                return@get
            }

            call.respond(buildJsonObject {
                put("version", latestVersion[RoomKeyVersions.version])
                put("algorithm", latestVersion[RoomKeyVersions.algorithm])
                put("auth_data", Json.parseToJsonElement(latestVersion[RoomKeyVersions.authData]).jsonObject)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // PUT /room_keys/keys - Upload room keys
    put("/room_keys/keys") {
        try {
            val userId = call.validateAccessToken() ?: return@put

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val version = jsonBody["version"]?.jsonPrimitive?.content ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing version")
            })
            val rooms = jsonBody["rooms"]?.jsonObject ?: buildJsonObject { }

            // Check if version exists
            val versionExists = transaction {
                RoomKeyVersions.select { (RoomKeyVersions.userId eq userId) and (RoomKeyVersions.version eq version) }.count() > 0
            }

            if (!versionExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Version not found")
                })
                return@put
            }

            transaction {
                // Store the keys
                for (roomId in rooms.keys) {
                    val roomSessions = rooms[roomId]?.jsonObject ?: continue
                    for (sessionId in roomSessions.keys) {
                        val sessionData = roomSessions[sessionId]?.jsonObject ?: continue
                        RoomKeys.insert {
                            it[RoomKeys.userId] = userId
                            it[RoomKeys.version] = version
                            it[RoomKeys.roomId] = roomId
                            it[RoomKeys.sessionId] = sessionId
                            it[RoomKeys.keyData] = Json.encodeToString(JsonObject.serializer(), sessionData)
                        }
                    }
                }
            }

            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /room_keys/keys - Download room keys
    get("/room_keys/keys") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val version = call.request.queryParameters["version"]

            val rooms = transaction {
                val query = RoomKeys.select { RoomKeys.userId eq userId }
                if (version != null) {
                    query.andWhere { RoomKeys.version eq version }
                }

                val roomMap = mutableMapOf<String, MutableMap<String, JsonElement>>()
                query.forEach { row ->
                    val roomId = row[RoomKeys.roomId]
                    val sessionId = row[RoomKeys.sessionId]
                    val sessionData = Json.parseToJsonElement(row[RoomKeys.keyData]).jsonObject
                    roomMap.computeIfAbsent(roomId) { mutableMapOf() }[sessionId] = sessionData
                }
                roomMap
            }

            call.respond(buildJsonObject {
                put("rooms", Json.encodeToJsonElement(rooms))
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // PUT /room_keys/keys/{roomId} - Upload room keys for a specific room
    put("/room_keys/keys/{roomId}") {
        try {
            val userId = call.validateAccessToken() ?: return@put
            val roomId = call.parameters["roomId"] ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing roomId")
            })

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val version = jsonBody["version"]?.jsonPrimitive?.content ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing version")
            })
            val sessions = jsonBody["sessions"]?.jsonObject ?: buildJsonObject { }

            // Check if version exists
            val versionExists = transaction {
                RoomKeyVersions.select { (RoomKeyVersions.userId eq userId) and (RoomKeyVersions.version eq version) }.count() > 0
            }

            if (!versionExists) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Version not found")
                })
                return@put
            }

            transaction {
                // Store the keys
                for (sessionId in sessions.keys) {
                    val sessionData = sessions[sessionId]?.jsonObject ?: continue
                    RoomKeys.insert {
                        it[RoomKeys.userId] = userId
                        it[RoomKeys.version] = version
                        it[RoomKeys.roomId] = roomId
                        it[RoomKeys.sessionId] = sessionId
                        it[RoomKeys.keyData] = Json.encodeToString(JsonObject.serializer(), sessionData)
                    }
                }
            }

            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /room_keys/keys/{roomId} - Download room keys for a specific room
    get("/room_keys/keys/{roomId}") {
        try {
            val userId = call.validateAccessToken() ?: return@get
            val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing roomId")
            })
            val version = call.request.queryParameters["version"]

            val sessions = transaction {
                val query = RoomKeys.select { (RoomKeys.userId eq userId) and (RoomKeys.roomId eq roomId) }
                if (version != null) {
                    query.andWhere { RoomKeys.version eq version }
                }

                val sessionMap = mutableMapOf<String, JsonElement>()
                query.forEach { row ->
                    val sessionId = row[RoomKeys.sessionId]
                    val sessionData = Json.parseToJsonElement(row[RoomKeys.keyData]).jsonObject
                    sessionMap[sessionId] = sessionData
                }
                sessionMap
            }

            call.respond(buildJsonObject {
                put("sessions", Json.encodeToJsonElement(sessions))
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
}