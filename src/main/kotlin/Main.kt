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
import io.ktor.server.request.*
import io.ktor.utils.io.*
import io.ktor.http.content.*
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
import models.Filters
import models.ThirdPartyIdentifiers
import models.ApplicationServices
import models.LoginTokens
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
import utils.ServerNameResolver
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import routes.client_server.client.UserIdPrincipal
import java.io.File

// In-memory storage for EDUs
// val presenceMap = mutableMapOf<String, String>() // userId to presence
// val receiptsMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (eventId to ts)
// val typingMap = mutableMapOf<String, MutableSet<String>>() // roomId to set of typing users

// Connected clients for broadcasting
// val connectedClients = mutableMapOf<String, MutableList<WebSocketSession>>() // roomId to list of sessions

private val logger = LoggerFactory.getLogger("Main")

fun main() {
    logger.info("🚀 Starting FERRETCANNON Matrix Server...")
    logger.debug("Initializing server components...")

    // Load configuration
    val config = ConfigLoader.loadConfig()
    logger.info("✅ Configuration loaded successfully from: ${config::class.simpleName}")
    logger.debug("Server config: host=${config.server.host}, port=${config.server.port}")
    logger.debug("Database config: ${config.database.url}")
    logger.debug("Media config: maxUploadSize=${config.media.maxUploadSize}")

    // Delete database on debug run
    if (config.development.isDebug) {
        val dbFile = File("ferretcannon.db")
        if (dbFile.exists()) {
            dbFile.delete()
            logger.info("🗑️ Database deleted for debug run")
        }
    }

    // Database setup with configurable connection
    logger.debug("Connecting to database...")
    Database.connect(config.database.url, driver = config.database.driver)
    logger.info("✅ Database connected successfully")

    transaction {
        logger.debug("Creating database schema...")
        SchemaUtils.create(Events, Rooms, StateGroups, AccountData, Users, AccessTokens, Devices, OAuthAuthorizationCodes, OAuthAccessTokens, OAuthStates, Media, Receipts, Presence, PushRules, RoomAliases, RegistrationTokens, ServerKeys, Filters, ThirdPartyIdentifiers, ApplicationServices, LoginTokens)
        logger.info("✅ Database schema created/verified")

        // Create test user for development (if enabled in config)
        if (config.development.createTestUser) {
            logger.debug("Checking for test user creation...")
            val testUserExists = Users.select { Users.username eq config.development.testUsername }.count() > 0
            if (!testUserExists) {
                logger.debug("Creating test user: ${config.development.testUsername}")
                utils.AuthUtils.createUser(
                    config.development.testUsername,
                    config.development.testPassword,
                    config.development.testDisplayName,
                    serverName = config.federation.serverName,
                    isAdmin = true // Make test user an admin for development
                )
                logger.info("✅ Created test user: ${config.development.testUsername}")
                logger.warn("⚠️  TEST USER PASSWORD: ${config.development.testPassword} - Change in production!")
            } else {
                logger.debug("Test user already exists")
            }

            // Always create a fresh access token for the test user on startup
            logger.debug("Creating fresh access token for test user...")
            val testUserId = "@${config.development.testUsername}:${config.federation.serverName}"
            val testDeviceId = "test_device_${System.currentTimeMillis()}"

            // Delete any existing access tokens for this user to avoid conflicts
            AccessTokens.deleteWhere { AccessTokens.userId eq testUserId }

            // Create new access token
            val accessToken = utils.AuthUtils.createAccessToken(
                userId = testUserId,
                deviceId = testDeviceId,
                userAgent = "FERRETCANNON-TestClient/1.0",
                ipAddress = "127.0.0.1"
            )

            logger.info("✅ Created fresh access token for test user")
            logger.info("🔑 Access Token: $accessToken")
            logger.info("👤 User ID: $testUserId")
            logger.info("📱 Device ID: $testDeviceId")
            logger.info("📋 Test endpoints with:")
            logger.info("   curl -H \"Authorization: Bearer $accessToken\" http://localhost:${config.server.port}/_matrix/client/v3/capabilities")
        }
    }

    // Initialize MediaStorage with configuration
    logger.debug("Initializing media storage...")
    utils.MediaStorage.initialize(
        config.media.maxUploadSize,
        800 // Use largest thumbnail dimension as max
    )
    logger.info("✅ Media storage initialized with max upload size: ${config.media.maxUploadSize} bytes")

    // Initialize ServerNameResolver with the configured port
    logger.debug("Initializing server name resolver...")
    utils.ServerNameResolver.setServerPort(config.server.port)
    logger.info("✅ Server name resolver initialized")

    try {
        logger.debug("Creating embedded server instance...")
        val server = embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            logger.debug("Configuring server plugins and routes...")

            // Install CORS for client-server API with configurable origins
            logger.trace("Installing CORS plugin...")
            install(CORS) {
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options) // Add OPTIONS for preflight requests
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Requested-With")
                allowCredentials = true
                if (config.server.corsAllowedOrigins.contains("*")) {
                    logger.debug("CORS: Allowing all origins (development mode)")
                    anyHost() // Allow all origins in development
                } else {
                    logger.debug("CORS: Configuring specific origins: ${config.server.corsAllowedOrigins}")
                    config.server.corsAllowedOrigins.forEach { origin ->
                        // Extract host from origin (remove scheme if present)
                        val host = if (origin.startsWith("http://") || origin.startsWith("https://")) {
                            origin.substringAfter("://").substringBefore("/")
                        } else {
                            origin
                        }
                        // Allow the host for both HTTP and HTTPS
                        allowHost(host, listOf("https", "http"))
                    }
                }
            }
            logger.debug("✅ CORS plugin installed")

            // Request size limiting with configurable limit
            logger.trace("Installing request size interceptor...")
            intercept(ApplicationCallPipeline.Call) {
                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength > config.server.maxRequestSize) {
                    logger.warn("Request rejected: size ${contentLength} exceeds limit ${config.server.maxRequestSize}")
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "errcode" to "M_TOO_LARGE",
                        "error" to "Request too large"
                    ))
                    finish()
                }
            }

            logger.trace("Installing ContentNegotiation plugin...")
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    allowStructuredMapKeys = true
                    encodeDefaults = false
                })
            }
            logger.debug("✅ ContentNegotiation plugin installed")

            logger.trace("Installing WebSockets plugin...")
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            logger.debug("✅ WebSockets plugin installed")

            logger.trace("Installing Authentication plugin...")
            install(Authentication) {
                // Temporarily disable bearer authentication to avoid conflicts with attribute-based auth
                // bearer("matrix-auth") {
                //     authenticate { tokenCredential ->
                //         // Use new database-backed authentication
                //         val result = AuthUtils.validateAccessToken(tokenCredential.token)
                //         if (result != null) {
                //             UserIdPrincipal(result.first)
                //         } else {
                //             null
                //         }
                //     }
                // }
            }
            logger.debug("✅ Authentication plugin installed")
            
            // Call route setup functions on the application
            logger.debug("Setting up client routes...")
            clientRoutes(config)
            logger.info("✅ Client routes configured")
            
            logger.debug("Setting up federation routes...")
            federationRoutes()
            logger.info("✅ Federation routes configured")

            logger.debug("Setting up key routes...")
            keyRoutes()
            logger.info("✅ Key routes configured")

            logger.debug("Setting up well-known routes...")
            wellKnownRoutes(config)
            logger.info("✅ Well-known routes configured")
            
            routing {
                logger.debug("Setting up basic routes...")
                get("/") {
                    logger.trace("Handling root request")
                    call.respondText("Hello, FERRETCANNON Matrix Server!", ContentType.Text.Plain)
                }
                get("/_matrix/server-info") {
                    // Debug endpoint to show server information
                    logger.trace("Handling server-info request")
                    try {
                        val serverInfo = ServerNameResolver.getServerInfo()
                        logger.debug("Server info retrieved: ${serverInfo.keys.joinToString()}")
                        call.respondText(
                            kotlinx.serialization.json.Json.encodeToString(
                                kotlinx.serialization.json.JsonObject.serializer(),
                                kotlinx.serialization.json.JsonObject(serverInfo.mapValues { 
                                    kotlinx.serialization.json.JsonPrimitive(it.value.toString())
                                })
                            ),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to get server info", e)
                        call.respondText(
                            """{"error": "Failed to get server info", "message": "${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }
                webSocket("/ws/room/{roomId}") {
                    val roomId = call.parameters["roomId"]
                    if (roomId == null) {
                        logger.warn("WebSocket connection rejected: missing roomId parameter")
                        return@webSocket
                    }
                    
                    logger.info("WebSocket connection established for room: $roomId")
                    val session = this
                    val clients = connectedClients.getOrPut(roomId) { mutableListOf() }
                    clients.add(session)
                    
                    try {
                        for (frame in incoming) {
                            // Handle client messages if needed
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    logger.debug("WebSocket message received in room $roomId: $text")
                                }
                                else -> {
                                    logger.trace("WebSocket frame received in room $roomId: ${frame.frameType}")
                                }
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        logger.debug("WebSocket client disconnected from room $roomId")
                    } catch (e: Exception) {
                        logger.error("WebSocket error in room $roomId", e)
                    } finally {
                        clients.remove(session)
                        logger.debug("WebSocket session cleaned up for room $roomId")
                    }
                }
                logger.debug("✅ Basic routes configured")
            }
            logger.info("✅ Server configuration complete")
        }
        
        logger.debug("Starting server...")
        server.start(wait = false)
        logger.info("🎉 Server started successfully!")
        logger.info("🌐 Server is running on ${config.server.host}:${config.server.port}")
        logger.info("📋 Available endpoints:")
        logger.info("   - Client API: http://${config.server.host}:${config.server.port}/_matrix/client/")
        logger.info("   - Federation API: http://${config.server.host}:${config.server.port}/_matrix/federation/")
        logger.info("   - Key API: http://${config.server.host}:${config.server.port}/_matrix/key/")
        logger.info("   - Well-known: http://${config.server.host}:${config.server.port}/.well-known/matrix/")
        logger.info("   - Server Info: http://${config.server.host}:${config.server.port}/_matrix/server-info")
        logger.info("   - WebSocket: ws://${config.server.host}:${config.server.port}/ws/room/{roomId}")
        logger.info("🛑 Press Ctrl+C to stop the server")
        
        // Keep the main thread alive
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("🛑 Shutting down server...")
            server.stop(1000, 1000)
            logger.info("✅ Server shutdown complete")
        })
        
        // Wait indefinitely
        Thread.currentThread().join()
        
    } catch (e: Exception) {
        logger.error("💥 Error starting server", e)
        e.printStackTrace()
    }
    
    logger.info("🏁 Main function completed")
}
