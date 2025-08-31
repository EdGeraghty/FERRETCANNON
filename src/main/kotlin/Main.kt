import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import models.Events
import models.Rooms
import models.StateGroups
import models.AccountData
import models.Users
import models.AccessTokens
import models.Devices
import models.OAuthAuthorizationCodes
import models.OAuthAccessTokens
import models.OAuthStates
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import java.time.Duration
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import utils.connectedClients
import utils.presenceMap
import utils.receiptsMap
import utils.typingMap
import routes.client_server.client.clientRoutes
import routes.server_server.federation.federationRoutes
import routes.server_server.key.keyRoutes
import routes.discovery.wellknown.wellKnownRoutes
import io.ktor.server.plugins.cors.routing.*
// import io.ktor.server.plugins.multipart.*

// In-memory storage for EDUs
// val presenceMap = mutableMapOf<String, String>() // userId to presence
// val receiptsMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (eventId to ts)
// val typingMap = mutableMapOf<String, MutableSet<String>>() // roomId to set of typing users

// Connected clients for broadcasting
// val connectedClients = mutableMapOf<String, MutableList<WebSocketSession>>() // roomId to list of sessions

fun main() {
    println("Starting FERRETCANNON Matrix Server...")
    
    // Database setup
    Database.connect("jdbc:sqlite:ferretcannon.db", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(Events, Rooms, StateGroups, AccountData, Users, AccessTokens, Devices, OAuthAuthorizationCodes, OAuthAccessTokens, OAuthStates)
    }
    
    val federationServer = "localhost:8080" // TODO: Make configurable
    
    try {
        println("About to create embedded server")
        val server = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            println("Inside embeddedServer block")

            // Install CORS for client-server API
            install(CORS) {
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Requested-With")
                allowCredentials = true
                anyHost() // In production, specify allowed origins
            }

            // Install rate limiting (simplified for now)
            // install(io.ktor.server.plugins.ratelimit.RateLimit) {
            //     global {
            //         rateLimiter(limit = 300, refillPeriod = java.time.Duration.ofMinutes(1)) // 300 requests per minute
            //     }
            // }

            // Request size limiting - simplified version
            intercept(ApplicationCallPipeline.Call) {
                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength > 1024 * 1024) { // 1MB limit
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "errcode" to "M_TOO_LARGE",
                        "error" to "Request too large"
                    ))
                    finish()
                }
            }

            install(ContentNegotiation) {
                json()
            }
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            // install(Multipart) {
            //     maxPartSize = 10 * 1024 * 1024 // 10MB
            // }
            
            // Call route setup functions on the application
            println("About to call clientRoutes()")
            clientRoutes()
            println("clientRoutes() completed")
            
            println("About to call federationRoutes()")
            federationRoutes()
            println("federationRoutes() completed")
            println("About to call keyRoutes()")
            keyRoutes()
            println("keyRoutes() completed")
            println("About to call wellKnownRoutes()")
            wellKnownRoutes()
            println("wellKnownRoutes() completed")
            
            routing {
                println("Setting up routes...")
                get("/") {
                    println("Handling root request")
                    call.respondText("Hello, FERRETCANNON Matrix Server!", ContentType.Text.Plain)
                }
                webSocket("/ws/room/{roomId}") {
                    val roomId = call.parameters["roomId"] ?: return@webSocket
                    val session = this
                    val clients = connectedClients.getOrPut(roomId) { mutableListOf() }
                    clients.add(session as io.ktor.server.websocket.DefaultWebSocketServerSession)
                    try {
                        for (frame in incoming) {
                            // Handle client messages if needed
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    println("Received: $text")
                                }
                                else -> {}
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        // Client disconnected
                    } finally {
                        clients.remove(session)
                    }
                }
                println("Basic routes set up")
            }
            println("Server configuration complete")
        }
        
        println("About to start server...")
        server.start(wait = false)
        println("Server started successfully!")
        
        // Keep the main thread alive
        println("Server is running on port 8080. Press Ctrl+C to stop.")
        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down server...")
            server.stop(1000, 1000)
        })
        
        // Wait indefinitely
        Thread.currentThread().join()
        
    } catch (e: Exception) {
        println("Error starting server: ${e.message}")
        e.printStackTrace()
    }
    
    println("Main function completed")
}
