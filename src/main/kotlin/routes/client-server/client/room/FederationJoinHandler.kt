package routes.client_server.client.room

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import config.ServerConfig
import models.Rooms
import models.Events
import utils.MatrixAuth
import utils.ServerConnectionDetails

/**
 * Handles federation join operations following Matrix Specification v1.16
 */
object FederationJoinHandler {
    
    data class JoinResult(
        val success: Boolean,
        val roomId: String? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )
    
    /**
     * Performs a federation join using make_join/send_join flow
     */
    suspend fun performFederationJoin(
        userId: String,
        roomId: String,
        serverNames: List<String>,
        config: ServerConfig
    ): JoinResult {
        if (serverNames.isEmpty()) {
            return JoinResult(
                success = false,
                errorCode = "M_INVALID_PARAM",
                errorMessage = "No federation servers provided"
            )
        }
        
        val httpClient = createHttpClient()
        
        try {
            val targetServer = serverNames.first()
            val serverDetails = utils.ServerDiscovery.resolveServerName(targetServer)
            
            if (serverDetails == null) {
                return JoinResult(
                    success = false,
                    errorCode = "M_UNKNOWN",
                    errorMessage = "Failed to resolve federation server: $targetServer"
                )
            }
            
            println("Resolved $targetServer to ${serverDetails.host}:${serverDetails.port}")
            
            // Step 1: GET /make_join
            val makeJoinResult = performMakeJoin(httpClient, userId, roomId, targetServer, serverDetails, config)
            if (!makeJoinResult.success) {
                return JoinResult(
                    success = false,
                    errorCode = makeJoinResult.errorCode,
                    errorMessage = makeJoinResult.errorMessage
                )
            }
            
            val eventTemplate = makeJoinResult.eventTemplate!!
            
            // Step 2: Sign the event
            val signedEvent = signJoinEvent(eventTemplate, config)
            
            // Step 3: PUT /send_join
            val sendJoinResult = performSendJoin(
                httpClient, roomId, signedEvent, targetServer, serverDetails, config
            )
            
            if (!sendJoinResult.success) {
                return JoinResult(
                    success = false,
                    errorCode = sendJoinResult.errorCode,
                    errorMessage = sendJoinResult.errorMessage
                )
            }
            
            // Step 4: Store state locally
            storeRoomState(roomId, userId, signedEvent, sendJoinResult.stateEvents!!, sendJoinResult.authChain!!, makeJoinResult.roomVersion)
            
            // Step 5: Broadcast to other servers
            broadcastJoinToServers(httpClient, roomId, signedEvent, sendJoinResult.stateEvents, config)
            
            return JoinResult(success = true, roomId = roomId)
            
        } catch (e: Exception) {
            println("Federation join error: ${e.message}")
            e.printStackTrace()
            return JoinResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Federation join failed: ${e.message}"
            )
        } finally {
            httpClient.close()
        }
    }
    
    private data class MakeJoinResult(
        val success: Boolean,
        val eventTemplate: JsonObject? = null,
        val roomVersion: String = "12",
        val errorCode: String? = null,
        val errorMessage: String? = null
    )
    
    private suspend fun performMakeJoin(
        httpClient: HttpClient,
        userId: String,
        roomId: String,
        targetServer: String,
        serverDetails: ServerConnectionDetails,
        config: ServerConfig
    ): MakeJoinResult {
        // Include supported room versions as per Matrix spec
        val supportedVersions = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12")
        val versionParams = supportedVersions.joinToString("&") { "ver=$it" }
        val makeJoinUrl = "https://${serverDetails.host}:${serverDetails.port}/_matrix/federation/v1/make_join/$roomId/$userId?$versionParams"
        println("Making /make_join request to: $makeJoinUrl")
        
        val makeJoinAuthHeader = MatrixAuth.buildAuthHeader(
            method = "GET",
            uri = "/_matrix/federation/v1/make_join/$roomId/$userId?$versionParams",
            origin = config.federation.serverName,
            destination = targetServer,
            content = ""
        )
        
        val makeJoinResponse = try {
            httpClient.get(makeJoinUrl) {
                header("Authorization", makeJoinAuthHeader)
            }
        } catch (e: Exception) {
            println("make_join request exception: ${e.javaClass.name}: ${e.message}")
            return MakeJoinResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Federation make_join request failed: ${e.message}"
            )
        }
        
        if (!makeJoinResponse.status.isSuccess()) {
            val errorBody = makeJoinResponse.bodyAsText()
            println("make_join failed: ${makeJoinResponse.status} - $errorBody")
            return MakeJoinResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Federation make_join failed: ${makeJoinResponse.status} - $errorBody"
            )
        }
        
        val makeJoinBody = makeJoinResponse.bodyAsText()
        println("make_join response: $makeJoinBody")
        val makeJoinJson = Json.parseToJsonElement(makeJoinBody).jsonObject
        val eventTemplate = makeJoinJson["event"]?.jsonObject
        val roomVersion = makeJoinJson["room_version"]?.jsonPrimitive?.content ?: "12"
        
        if (eventTemplate == null) {
            return MakeJoinResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Invalid make_join response: missing event template",
                roomVersion = roomVersion
            )
        }
        
        return MakeJoinResult(
            success = true,
            eventTemplate = eventTemplate,
            roomVersion = roomVersion
        )
    }
    
    private fun signJoinEvent(eventTemplate: JsonObject, config: ServerConfig): JsonObject {
        // DO NOT modify the event template - use it exactly as provided by make_join
        // The origin_server_ts is already set correctly by the remote server
        val signedJoinEvent = MatrixAuth.hashAndSignEvent(eventTemplate, config.federation.serverName)
        
        // For room versions 4+, the event_id is NOT included in the event itself
        // It's derived from the content hash and used only in URLs
        // Return the signed event WITHOUT adding event_id to it
        return signedJoinEvent
    }
    
    private data class SendJoinResult(
        val success: Boolean,
        val stateEvents: JsonArray? = null,
        val authChain: JsonArray? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )
    
    private suspend fun performSendJoin(
        httpClient: HttpClient,
        roomId: String,
        signedEvent: JsonObject,
        targetServer: String,
        serverDetails: ServerConnectionDetails,
        config: ServerConfig
    ): SendJoinResult {
        // For room versions 4+, event_id is derived from the full event hash including signatures
        val eventId = signedEvent["event_id"]?.jsonPrimitive?.content ?: return SendJoinResult(
            success = false,
            errorCode = "M_UNKNOWN",
            errorMessage = "Signed event missing event_id"
        )
        
        val sendJoinUrl = "https://${serverDetails.host}:${serverDetails.port}/_matrix/federation/v2/send_join/$roomId/$eventId"
        println("Sending /send_join request to: $sendJoinUrl")
        
        // CRITICAL: Send as canonical JSON to match the key ordering we used when computing the hash
        // Remove event_id for sending, as per Matrix spec
        val eventForSending = signedEvent.toMutableMap()
        // eventForSending.remove("event_id")  // Include event_id in body for compatibility
        val signedEventNative = utils.MatrixAuth.jsonElementToNative(JsonObject(eventForSending))
            ?: return SendJoinResult(success = false, errorCode = "M_UNKNOWN", errorMessage = "Failed to convert event to native types")
        val sendJoinBodyJson = utils.MatrixAuth.canonicalizeJson(signedEventNative)
        
        println("Event being sent as canonical JSON (FULL): $sendJoinBodyJson")
        println("Event ID from full hash in URL: $eventId")
        println("Event content hash in body: \$${signedEvent["hashes"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content}")
        
        val sendJoinAuthHeader = utils.MatrixAuth.buildAuthHeader(
            method = "PUT",
            uri = "/_matrix/federation/v2/send_join/$roomId/$eventId",
            origin = config.federation.serverName,
            destination = targetServer,
            content = sendJoinBodyJson
        )
        
        val sendJoinResponse = try {
            httpClient.put(sendJoinUrl) {
                header("Authorization", sendJoinAuthHeader)
                contentType(ContentType.Application.Json)
                setBody(sendJoinBodyJson)
            }
        } catch (e: Exception) {
            println("send_join request exception: ${e.javaClass.name}: ${e.message}")
            return SendJoinResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Federation send_join request failed: ${e.message}"
            )
        }
        
        if (!sendJoinResponse.status.isSuccess()) {
            val errorBody = sendJoinResponse.bodyAsText()
            println("send_join failed: ${sendJoinResponse.status} - $errorBody")
            return SendJoinResult(
                success = false,
                errorCode = "M_UNKNOWN",
                errorMessage = "Federation send_join failed: ${sendJoinResponse.status} - $errorBody"
            )
        }
        
        val sendJoinBody = sendJoinResponse.bodyAsText()
        println("send_join response: $sendJoinBody")
        val sendJoinJson = Json.parseToJsonElement(sendJoinBody).jsonObject
        
        val stateEvents = sendJoinJson["state"]?.jsonArray ?: JsonArray(emptyList())
        val authChain = sendJoinJson["auth_chain"]?.jsonArray ?: JsonArray(emptyList())
        
        return SendJoinResult(
            success = true,
            stateEvents = stateEvents,
            authChain = authChain
        )
    }
    
    private fun storeRoomState(
        roomId: String,
        userId: String,
        joinEvent: JsonObject,
        stateEvents: JsonArray,
        authChain: JsonArray,
        roomVersion: String = "12"
    ) {
        val currentTime = System.currentTimeMillis()
        val eventId = joinEvent["event_id"]?.jsonPrimitive?.content ?: return
        
        transaction {
            // Create room entry
            Rooms.insertIgnore {
                it[Rooms.roomId] = roomId
                it[Rooms.creator] = ""
                it[Rooms.name] = null
                it[Rooms.topic] = null
                it[Rooms.visibility] = "private"
                it[Rooms.roomVersion] = roomVersion
                it[Rooms.isDirect] = false
                it[Rooms.currentState] = "{}"
                it[Rooms.stateGroups] = "{}"
                it[Rooms.published] = false
            }
            
            // Store join event
            Events.insertIgnore {
                it[Events.eventId] = eventId
                it[Events.roomId] = roomId
                it[Events.type] = "m.room.member"
                it[Events.sender] = userId
                it[Events.content] = (joinEvent["content"] ?: buildJsonObject { put("membership", "join") }).toString()
                it[Events.authEvents] = (joinEvent["auth_events"] ?: JsonArray(emptyList())).toString()
                it[Events.prevEvents] = (joinEvent["prev_events"] ?: JsonArray(emptyList())).toString()
                it[Events.depth] = (joinEvent["depth"]?.jsonPrimitive?.int ?: 1)
                it[Events.hashes] = (joinEvent["hashes"] ?: buildJsonObject {}).toString()
                it[Events.signatures] = (joinEvent["signatures"] ?: buildJsonObject {}).toString()
                it[Events.originServerTs] = currentTime
                it[Events.stateKey] = userId
                it[Events.unsigned] = "{}"
                it[Events.softFailed] = false
                it[Events.outlier] = false
            }
            
            // Store state events
            storeEvents(roomId, currentTime, stateEvents)
            
            // Store auth chain events
            storeEvents(roomId, currentTime, authChain)
        }
    }
    
    private fun storeEvents(roomId: String, currentTime: Long, events: JsonArray) {
        for (eventElement in events) {
            val event = eventElement.jsonObject
            val evId = event["event_id"]?.jsonPrimitive?.content ?: continue
            
            Events.insertIgnore {
                it[Events.eventId] = evId
                it[Events.roomId] = roomId
                it[Events.type] = event["type"]?.jsonPrimitive?.content ?: ""
                it[Events.sender] = event["sender"]?.jsonPrimitive?.content ?: ""
                it[Events.content] = (event["content"] ?: buildJsonObject {}).toString()
                it[Events.authEvents] = (event["auth_events"] ?: JsonArray(emptyList())).toString()
                it[Events.prevEvents] = (event["prev_events"] ?: JsonArray(emptyList())).toString()
                it[Events.depth] = (event["depth"]?.jsonPrimitive?.int ?: 0)
                it[Events.hashes] = (event["hashes"] ?: buildJsonObject {}).toString()
                it[Events.signatures] = (event["signatures"] ?: buildJsonObject {}).toString()
                it[Events.originServerTs] = event["origin_server_ts"]?.jsonPrimitive?.long ?: currentTime
                it[Events.stateKey] = event["state_key"]?.jsonPrimitive?.content
                it[Events.unsigned] = (event["unsigned"] ?: buildJsonObject {}).toString()
                it[Events.softFailed] = false
                it[Events.outlier] = false
            }
        }
    }
    
    private suspend fun broadcastJoinToServers(
        httpClient: HttpClient,
        roomId: String,
        joinEvent: JsonObject,
        stateEvents: JsonArray,
        config: ServerConfig
    ) {
        try {
            println("Broadcasting join event to federated servers...")
            
            val serversInRoom = mutableSetOf<String>()
            for (stateEventElement in stateEvents) {
                val stateEvent = stateEventElement.jsonObject
                val sender = stateEvent["sender"]?.jsonPrimitive?.content
                if (sender != null && sender.contains(":")) {
                    val serverName = sender.substringAfter(":")
                    if (serverName != config.federation.serverName) {
                        serversInRoom.add(serverName)
                    }
                }
            }
            
            println("Found ${serversInRoom.size} servers to notify: $serversInRoom")
            
            for (remoteServer in serversInRoom) {
                try {
                    sendJoinToServer(httpClient, remoteServer, roomId, joinEvent, config)
                } catch (e: Exception) {
                    println("Error notifying $remoteServer: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error broadcasting join: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun sendJoinToServer(
        httpClient: HttpClient,
        remoteServer: String,
        roomId: String,
        joinEvent: JsonObject,
        config: ServerConfig
    ) {
        val txnId = java.util.UUID.randomUUID().toString()
        val remoteServerDetails = utils.ServerDiscovery.resolveServerName(remoteServer)
        
        if (remoteServerDetails == null) {
            println("Failed to resolve server: $remoteServer")
            return
        }
        
        val sendUrl = "https://${remoteServerDetails.host}:${remoteServerDetails.port}/_matrix/federation/v1/send/$txnId"
        val sendBody = buildJsonObject {
            putJsonArray("pdus") { add(joinEvent) }
            putJsonArray("edus") {}
        }.toString()
        
        val sendAuthHeader = MatrixAuth.buildAuthHeader(
            method = "PUT",
            uri = "/_matrix/federation/v1/send/$txnId",
            origin = config.federation.serverName,
            destination = remoteServer,
            content = sendBody
        )
        
        println("Sending join event to $remoteServer...")
        val sendResponse = httpClient.put(sendUrl) {
            header("Authorization", sendAuthHeader)
            contentType(ContentType.Application.Json)
            setBody(sendBody)
        }
        
        if (sendResponse.status.isSuccess()) {
            println("Successfully notified $remoteServer of join")
        } else {
            println("Failed to notify $remoteServer: ${sendResponse.status}")
        }
    }
    
    private fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                // Disable HTTP/2 to avoid stream reset issues
                protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }
}
