package routes.client_server.client.directory

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import routes.client_server.client.common.*
import routes.server_server.federation.v1.findRoomByAlias
import routes.server_server.federation.v1.getRoomInfo
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Rooms
import models.Events

/**
 * Room directory routes for room alias resolution and public room listings
 * Matrix Spec: https://spec.matrix.org/v1.16/client-server-api/#room-directory
 */
fun Route.roomDirectoryRoutes() {
    // GET /directory/room/{roomAlias} - Get room ID from alias
    get("/directory/room/{roomAlias}") {
        try {
            call.validateAccessToken() ?: return@get
            
            val roomAlias = call.parameters["roomAlias"]
            
            if (roomAlias == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_PARAM")
                    put("error", "Missing room alias")
                })
                return@get
            }
            
            // Look up room by alias
            val roomId = findRoomByAlias(roomAlias)
            
            if (roomId == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Room alias not found")
                })
                return@get
            }
            
            // Build response with room_id and servers
            call.respond(buildJsonObject {
                put("room_id", roomId)
                put("servers", buildJsonArray {
                    // Extract server from room alias
                    val aliasServer = roomAlias.substringAfter(":", "")
                    if (aliasServer.isNotEmpty()) {
                        add(aliasServer)
                    }
                })
            })
            
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
    
    // GET /publicRooms - Get list of public rooms
    get("/publicRooms") {
        try {
            // Optional authentication (endpoint can be called with or without auth)
            call.validateAccessToken()
            
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val since = call.request.queryParameters["since"]
            
            // Get published rooms with pagination
            val publishedRooms = transaction {
                val query = Rooms.select { Rooms.published eq true }
                    .orderBy(Rooms.roomId)
                    .limit(limit)
                
                if (since != null) {
                    query.andWhere { Rooms.roomId greater since }
                }
                
                query.map { roomRow ->
                    val roomId = roomRow[Rooms.roomId]
                    
                    // Get room name from state events
                    val name = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.name") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["name"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Get room topic from state events
                    val topic = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.topic") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["topic"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Get canonical alias from state events
                    val canonicalAlias = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.canonical_alias") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["alias"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Get avatar URL from state events
                    val avatarUrl = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.avatar") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["url"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Count joined members
                    val joinedMembers = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.member")
                    }.groupBy { event ->
                        event[Events.stateKey]
                    }.mapNotNull { (_, events) ->
                        events.maxByOrNull { it[Events.originServerTs] }
                    }.count { event ->
                        try {
                            Json.parseToJsonElement(event[Events.content])
                                .jsonObject["membership"]?.jsonPrimitive?.content == "join"
                        } catch (e: Exception) {
                            false
                        }
                    }
                    
                    // Get join rules
                    val joinRules = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.join_rules") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["join_rule"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                "invite"
                            }
                        } ?: "invite"
                    
                    // Get history visibility
                    val worldReadable = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.history_visibility") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["history_visibility"]?.jsonPrimitive?.content == "world_readable"
                            } catch (e: Exception) {
                                false
                            }
                        } ?: false
                    
                    val guestCanJoin = joinRules == "public"
                    
                    buildJsonObject {
                        put("room_id", roomId)
                        if (name != null) put("name", name)
                        if (topic != null) put("topic", topic)
                        if (canonicalAlias != null) put("canonical_alias", canonicalAlias)
                        put("num_joined_members", joinedMembers)
                        put("world_readable", worldReadable)
                        put("guest_can_join", guestCanJoin)
                        if (avatarUrl != null) put("avatar_url", avatarUrl)
                    }
                }
            }
            
            // Calculate next batch token (last room ID if there are more rooms)
            val nextBatch = if (publishedRooms.size >= limit) {
                publishedRooms.lastOrNull()?.jsonObject?.get("room_id")?.jsonPrimitive?.content
            } else {
                null
            }
            
            call.respond(buildJsonObject {
                putJsonArray("chunk") {
                    publishedRooms.forEach { room ->
                        add(room)
                    }
                }
                if (nextBatch != null) {
                    put("next_batch", nextBatch)
                }
                if (since != null) {
                    put("prev_batch", since)
                }
                put("total_room_count_estimate", publishedRooms.size)
            })
            
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
    
    // POST /publicRooms - Get list of public rooms with filtering
    post("/publicRooms") {
        try {
            // Optional authentication
            call.validateAccessToken()
            
            val requestBody = try {
                Json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {}
            }
            
            val limit = requestBody["limit"]?.jsonPrimitive?.intOrNull ?: 100
            val since = requestBody["since"]?.jsonPrimitive?.content
            val filter = requestBody["filter"]?.jsonObject
            val searchTerm = filter?.get("generic_search_term")?.jsonPrimitive?.content
            
            // Get published rooms with pagination and filtering
            val publishedRooms = transaction {
                var query = Rooms.select { Rooms.published eq true }
                    .orderBy(Rooms.roomId)
                    .limit(limit)
                
                if (since != null) {
                    query = query.andWhere { Rooms.roomId greater since }
                }
                
                query.map { roomRow ->
                    val roomId = roomRow[Rooms.roomId]
                    
                    // Get room name
                    val name = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.name") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["name"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Get room topic
                    val topic = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.topic") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["topic"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Get canonical alias
                    val canonicalAlias = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.canonical_alias") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["alias"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Get avatar URL
                    val avatarUrl = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.avatar") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["url"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                null
                            }
                        }
                    
                    // Count joined members
                    val joinedMembers = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.member")
                    }.groupBy { event ->
                        event[Events.stateKey]
                    }.mapNotNull { (_, events) ->
                        events.maxByOrNull { it[Events.originServerTs] }
                    }.count { event ->
                        try {
                            Json.parseToJsonElement(event[Events.content])
                                .jsonObject["membership"]?.jsonPrimitive?.content == "join"
                        } catch (e: Exception) {
                            false
                        }
                    }
                    
                    // Get join rules
                    val joinRules = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.join_rules") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["join_rule"]?.jsonPrimitive?.content
                            } catch (e: Exception) {
                                "invite"
                            }
                        } ?: "invite"
                    
                    // Get history visibility
                    val worldReadable = Events.select {
                        (Events.roomId eq roomId) and
                        (Events.type eq "m.room.history_visibility") and
                        (Events.stateKey eq "")
                    }.orderBy(Events.originServerTs to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.let { event ->
                            try {
                                Json.parseToJsonElement(event[Events.content])
                                    .jsonObject["history_visibility"]?.jsonPrimitive?.content == "world_readable"
                            } catch (e: Exception) {
                                false
                            }
                        } ?: false
                    
                    val guestCanJoin = joinRules == "public"
                    
                    // Apply search filter if provided
                    if (searchTerm != null) {
                        val matchesSearch = 
                            (name?.contains(searchTerm, ignoreCase = true) == true) ||
                            (topic?.contains(searchTerm, ignoreCase = true) == true) ||
                            (canonicalAlias?.contains(searchTerm, ignoreCase = true) == true)
                        
                        if (!matchesSearch) {
                            return@map null
                        }
                    }
                    
                    buildJsonObject {
                        put("room_id", roomId)
                        if (name != null) put("name", name)
                        if (topic != null) put("topic", topic)
                        if (canonicalAlias != null) put("canonical_alias", canonicalAlias)
                        put("num_joined_members", joinedMembers)
                        put("world_readable", worldReadable)
                        put("guest_can_join", guestCanJoin)
                        if (avatarUrl != null) put("avatar_url", avatarUrl)
                    }
                }.filterNotNull()
            }
            
            // Calculate next batch token
            val nextBatch = if (publishedRooms.size >= limit) {
                publishedRooms.lastOrNull()?.jsonObject?.get("room_id")?.jsonPrimitive?.content
            } else {
                null
            }
            
            call.respond(buildJsonObject {
                putJsonArray("chunk") {
                    publishedRooms.forEach { room ->
                        add(room)
                    }
                }
                if (nextBatch != null) {
                    put("next_batch", nextBatch)
                }
                if (since != null) {
                    put("prev_batch", since)
                }
                put("total_room_count_estimate", publishedRooms.size)
            })
            
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }
}
