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
import io.ktor.server.plugins.cors.routing.*
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
import models.Media
import models.Receipts
import models.Presence
import models.PushRules
import models.RoomAliases
import models.RegistrationTokens
import models.ServerKeys
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import java.time.Duration
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import utils.AuthUtils
import utils.connectedClients
import routes.client_server.client.clientRoutes
import routes.server_server.federation.federationRoutes
import routes.server_server.key.keyRoutes
import routes.discovery.wellknown.wellKnownRoutes
import config.ConfigLoader
import config.ServerConfig

// In-memory storage for EDUs
// val presenceMap = mutableMapOf<String, String>() // userId to presence
// val receiptsMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (eventId to ts)
// val typingMap = mutableMapOf<String, MutableSet<String>>() // roomId to set of typing users

// Connected clients for broadcasting
// val connectedClients = mutableMapOf<String, MutableList<WebSocketSession>>() // roomId to list of sessions

fun main() {
    println("Starting FERRETCANNON Matrix Server...")

    // Load configuration
    val config = ConfigLoader.loadConfig()
    println("Configuration loaded successfully")

    // Database setup with configurable connection
    Database.connect(config.database.url, driver = config.database.driver)
    transaction {
        SchemaUtils.create(Events, Rooms, StateGroups, AccountData, Users, AccessTokens, Devices, OAuthAuthorizationCodes, OAuthAccessTokens, OAuthStates, Media, Receipts, Presence, PushRules, RoomAliases, RegistrationTokens, ServerKeys)

        // Create test user for development (if enabled in config)
        if (config.development.createTestUser) {
            val testUserExists = Users.select { Users.username eq config.development.testUsername }.count() > 0
            if (!testUserExists) {
                utils.AuthUtils.createUser(
                    config.development.testUsername,
                    config.development.testPassword,
                    config.development.testDisplayName
                )
                println("Created test user: ${config.development.testUsername} with password: ${config.development.testPassword}")
            }
        }
    }

    // Initialize MediaStorage with configuration
    utils.MediaStorage.initialize(
        config.media.maxUploadSize,
        800 // Use largest thumbnail dimension as max
    )
    
    try {
        println("About to create embedded server")
        val server = embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            println("Inside embeddedServer block")

            // Install CORS for client-server API with configurable origins
            install(CORS) {
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Requested-With")
                allowCredentials = true
                if (config.server.corsAllowedOrigins.contains("*")) {
                    anyHost() // Allow all origins in development
                } else {
                    config.server.corsAllowedOrigins.forEach { allowHost(it) }
                }
            }

            // Install rate limiting (simplified for now)
            // install(io.ktor.server.plugins.ratelimit.RateLimit) {
            //     global {
            //         rateLimiter(limit = 300, refillPeriod = java.time.Duration.ofMinutes(1)) // 300 requests per minute
            //     }
            // }

            // Request size limiting with configurable limit
            intercept(ApplicationCallPipeline.Call) {
                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength > config.server.maxRequestSize) {
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
            //     maxPartSize = config.media.maxUploadSize
            // }
            
            // Call route setup functions on the application
            println("About to call clientRoutes()")
            clientRoutes(config)
            println("clientRoutes() completed")
            
            println("About to call federationRoutes()")
            federationRoutes()
            println("federationRoutes() completed")
            println("About to call keyRoutes()")
            keyRoutes()
            println("keyRoutes() completed")
            println("About to call wellKnownRoutes()")
            wellKnownRoutes(config)
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
        println("Server is running on ${config.server.host}:${config.server.port}. Press Ctrl+C to stop.")
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
