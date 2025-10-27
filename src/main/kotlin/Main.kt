import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.EngineConnectorBuilder
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
import models.Pushers
import models.RoomAliases
import models.RegistrationTokens
import models.ServerKeys
import models.Filters
import models.ThirdPartyIdentifiers
import models.ApplicationServices
import models.LoginTokens
import models.CrossSigningKeys
import models.DehydratedDevices
import models.RoomKeyVersions
import models.RoomKeys
import models.OneTimeKeys
import models.KeySignatures
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
import routes.testEndpoints
import config.ConfigLoader
import config.ServerConfig
import config.SSLMode
import utils.ServerNameResolver
import org.slf4j.LoggerFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.io.File
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.Level
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Date
import java.math.BigInteger
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.x500.X500Name

private val logger = LoggerFactory.getLogger("Main")

fun Application.configureFederationServer(config: ServerConfig) {
    logger.debug("Configuring federation server plugins and routes...")

    logger.trace("Installing ContentNegotiation plugin...")
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
            encodeDefaults = false
            coerceInputValues = true
        })
    }
    logger.debug("✅ ContentNegotiation plugin installed")

    logger.trace("Installing Authentication plugin...")
    install(Authentication) {
    }
    logger.debug("✅ Authentication plugin installed")
    
    // Federation routes
    logger.debug("Setting up federation routes...")
    federationRoutes()
    logger.info("✅ Federation routes configured")

    // Key routes (federation endpoints)
    logger.debug("Setting up key routes...")
    keyRoutes()
    logger.info("✅ Key routes configured")

    logger.info("✅ Federation server configuration complete")
}

fun createSelfSignedCertificate(): Pair<KeyStore, String> {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048, SecureRandom())
    val keyPair = keyPairGenerator.generateKeyPair()

    val subject = X500Name("CN=localhost")
    val serialNumber = BigInteger(64, SecureRandom())
    val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24)
    val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365)

    val certificateBuilder = JcaX509v3CertificateBuilder(
        subject,
        serialNumber,
        notBefore,
        notAfter,
        subject,
        keyPair.public
    )

    val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.private)
    val certificateHolder = certificateBuilder.build(signer)
    val certificate = JcaX509CertificateConverter().getCertificate(certificateHolder)

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setKeyEntry("key", keyPair.private, "password".toCharArray(), arrayOf<Certificate>(certificate))

    return Pair(keyStore, "password")
}

fun loadKeyStoreFromFiles(certificatePath: String, privateKeyPath: String, certificateChainPath: String? = null): KeyStore {
    val certificateFactory = CertificateFactory.getInstance("X.509")
    
    // Load the certificate
    val certificateFile = File(certificatePath)
    if (!certificateFile.exists()) {
        throw IllegalArgumentException("Certificate file not found: $certificatePath")
    }
    
    val certificate = certificateFile.inputStream().use { inputStream ->
        certificateFactory.generateCertificate(inputStream) as X509Certificate
    }
    
    // Load certificate chain if provided
    val certificateChain = if (certificateChainPath != null) {
        val chainFile = File(certificateChainPath)
        if (chainFile.exists()) {
            chainFile.inputStream().use { inputStream ->
                certificateFactory.generateCertificates(inputStream).toTypedArray()
            }
        } else {
            arrayOf<Certificate>(certificate)
        }
    } else {
        arrayOf<Certificate>(certificate)
    }
    
    // Load the private key
    val privateKeyFile = File(privateKeyPath)
    if (!privateKeyFile.exists()) {
        throw IllegalArgumentException("Private key file not found: $privateKeyPath")
    }
    
    val privateKey = privateKeyFile.readText().let { keyText ->
        // Remove PEM headers/footers if present
        val cleanKey = keyText
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        
        val keyBytes = java.util.Base64.getDecoder().decode(cleanKey)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA") // Assume RSA for now
        keyFactory.generatePrivate(keySpec)
    }
    
    // Create KeyStore
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setKeyEntry("key", privateKey, "password".toCharArray(), certificateChain)
    
    return keyStore
}

fun main() {
    println("FERRETCANNON MAIN STARTED - NEW CODE VERSION")
    logger.info("🚀 Starting FERRETCANNON Matrix Server...")
    logger.debug("Initializing server components...")

    // Load configuration
    val config = ConfigLoader.loadConfig()
    logger.info("✅ Configuration loaded successfully from: ${config::class.simpleName}")
    logger.debug("Server config: host=${config.server.host}, port=${config.server.port}")
    logger.debug("Database config: ${config.database.url}")
    logger.debug("Media config: maxUploadSize=${config.media.maxUploadSize}")

    // Debug: print the enableDebugLogging value
    println("enableDebugLogging: ${config.development.enableDebugLogging}")

    // Configure logging levels based on config
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    if (config.development.enableDebugLogging) {
        loggerContext.getLogger("Exposed").level = Level.DEBUG
        loggerContext.getLogger("routes").level = Level.TRACE
        loggerContext.getLogger("utils").level = Level.TRACE
        loggerContext.getLogger("models").level = Level.DEBUG
        loggerContext.getLogger("family.geraghty.ed.yolo.ferretcannon").level = Level.TRACE
        loggerContext.getLogger("io.ktor.server").level = Level.TRACE
        loggerContext.getLogger("kotlinx").level = Level.DEBUG
        logger.info("🔧 Debug logging enabled for Exposed ORM and application components")
    } else {
        loggerContext.getLogger("Exposed").level = Level.INFO
        loggerContext.getLogger("routes").level = Level.INFO
        loggerContext.getLogger("utils").level = Level.INFO
        loggerContext.getLogger("models").level = Level.INFO
        loggerContext.getLogger("family.geraghty.ed.yolo.ferretcannon").level = Level.INFO
        loggerContext.getLogger("io.ktor.server").level = Level.INFO
        loggerContext.getLogger("kotlinx").level = Level.INFO
        logger.info("🔧 Debug logging disabled for Exposed ORM and application components")
    }

    // Set root logger level based on config
    val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    if (config.development.enableDebugLogging) {
        rootLogger.level = Level.DEBUG
        logger.info("🔧 Root logging level set to DEBUG")
    } else {
        rootLogger.level = Level.INFO
        logger.info("🔧 Root logging level set to INFO")
    }

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
        SchemaUtils.create(Events, Rooms, StateGroups, AccountData, Users, AccessTokens, Devices, CrossSigningKeys, DehydratedDevices, OAuthAuthorizationCodes, OAuthAccessTokens, OAuthStates, Media, Receipts, Presence, PushRules, Pushers, RoomAliases, RegistrationTokens, ServerKeys, Filters, ThirdPartyIdentifiers, ApplicationServices, LoginTokens, RoomKeyVersions, RoomKeys, OneTimeKeys, KeySignatures)
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
        logger.debug("Creating embedded client server instance...")
        val clientServer = embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            logger.debug("Configuring client server plugins and routes...")

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
                anyHost() // Allow all origins for debugging
            }
            logger.debug("✅ CORS plugin installed")

            // Request size limiting with configurable limit
            logger.trace("Installing request size interceptor...")
            intercept(ApplicationCallPipeline.Call) {
                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength > config.server.maxRequestSize) {
                    logger.warn("Request rejected: size ${contentLength} exceeds limit ${config.server.maxRequestSize}")
                    call.respond(HttpStatusCode.BadRequest, mutableMapOf(
                        "errcode" to "M_TOO_LARGE",
                        "error" to "Request too large"
                    ))
                    finish()
                }
            }

            logger.trace("Installing ContentNegotiation plugin...")
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                    allowStructuredMapKeys = true
                    encodeDefaults = false
                    coerceInputValues = true
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
            }
            logger.debug("✅ Authentication plugin installed")
            
            // Call route setup functions on the application
            logger.debug("Setting up client routes...")
            clientRoutes(config)
            logger.info("✅ Client routes configured")

            logger.debug("Setting up well-known routes...")
            wellKnownRoutes(config)
            logger.info("✅ Well-known routes configured")
            
            routing {
                logger.debug("Setting up test endpoints for compliance testing...")
                testEndpoints()
                logger.info("✅ Test endpoints configured")
                
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
            logger.info("✅ Client server configuration complete")
        }

        logger.debug("Creating embedded federation server instance...")
        // Configure federation server with TLS when requested in config
        val federationServer = try {
            when (config.ssl.mode) {
                SSLMode.DISABLED -> {
                    logger.info("SSL mode DISABLED - starting federation over HTTP")
                    embeddedServer(Netty, port = config.federation.federationPort, host = config.server.host) {
                        configureFederationServer(config)
                    }
                }
                SSLMode.SNAKEOIL -> {
                    logger.info("SSL mode SNAKEOIL - generating self-signed certificate")
                    val (keyStore, keyPassword) = createSelfSignedCertificate()
                    // Use applicationEngineEnvironment to add an sslConnector for Netty
                    val environment = applicationEngineEnvironment {
                        module {
                            configureFederationServer(config)
                        }
                        sslConnector(
                            keyStore = keyStore,
                            keyAlias = "key",
                            keyStorePassword = { keyPassword.toCharArray() },
                            privateKeyPassword = { keyPassword.toCharArray() }
                        ) {
                            this.host = config.server.host
                            this.port = config.federation.federationPort
                        }
                    }
                    embeddedServer(Netty, environment)
                }
                SSLMode.CUSTOM -> {
                    logger.info("SSL mode CUSTOM - loading certificate and key from files")
                    val certPath = config.ssl.certificatePath
                    val keyPath = config.ssl.privateKeyPath
                    val chainPath = config.ssl.certificateChainPath
                    if (certPath == null || keyPath == null) {
                        throw IllegalArgumentException("Custom SSL mode selected but certificate or key path missing in config")
                    }
                    val keyStore = loadKeyStoreFromFiles(certPath, keyPath, chainPath)
                    // Use applicationEngineEnvironment to add an sslConnector for Netty
                    val environment = applicationEngineEnvironment {
                        module {
                            configureFederationServer(config)
                        }
                        sslConnector(
                            keyStore = keyStore,
                            keyAlias = "key",
                            keyStorePassword = { "password".toCharArray() },
                            privateKeyPassword = { "password".toCharArray() }
                        ) {
                            this.host = config.server.host
                            this.port = config.federation.federationPort
                        }
                    }
                    embeddedServer(Netty, environment)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to start federation server with SSL, falling back to HTTP", e)
            embeddedServer(Netty, port = config.federation.federationPort, host = config.server.host) {
                configureFederationServer(config)
            }
        }
        
        logger.debug("Starting client server...")
        clientServer.start(wait = false)
        logger.info("🎉 Client server started successfully!")
        logger.info("🌐 Client server is running on ${config.server.host}:${config.server.port}")
        
        logger.debug("Starting federation server...")
        federationServer.start(wait = false)
        logger.info("🎉 Federation server started successfully!")
        logger.info("🌐 Federation server is running on ${config.server.host}:${config.federation.federationPort}")
        if (config.ssl.mode == SSLMode.DISABLED) {
            logger.warn("⚠️  Federation server is currently running over HTTP (SSL mode DISABLED)")
        } else {
            logger.info("🔒 Federation server is running with TLS (mode=${config.ssl.mode})")
        }
        
        logger.info("📋 Available endpoints:")
        logger.info("   - Client API: http://${config.server.host}:${config.server.port}/_matrix/client/")
        logger.info("   - Federation API: http://${config.server.host}:${config.federation.federationPort}/_matrix/federation/")
        logger.info("   - Key API: http://${config.server.host}:${config.federation.federationPort}/_matrix/key/")
        logger.info("   - Well-known: http://${config.server.host}:${config.server.port}/.well-known/matrix/")
        logger.info("   - Server Info: http://${config.server.host}:${config.server.port}/_matrix/server-info")
        logger.info("   - WebSocket: ws://${config.server.host}:${config.server.port}/ws/room/{roomId}")
        logger.info("🛑 Press Ctrl+C to stop the servers")
        
        // Keep the main thread alive
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("🛑 Shutting down servers...")
            clientServer.stop(1000, 1000)
            federationServer.stop(1000, 1000)
            logger.info("✅ Servers shutdown complete")
        })
        
        // Wait indefinitely
        Thread.currentThread().join()
        
    } catch (e: Exception) {
        logger.error("💥 Error starting server", e)
        e.printStackTrace()
    }
    
    logger.info("🏁 Main function completed")
}
