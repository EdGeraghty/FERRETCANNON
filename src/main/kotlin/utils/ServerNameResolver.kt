package utils

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*
import kotlin.system.exitProcess

/**
 * Dynamic Server Name Resolution Utility
 * Implements automatic server name detection for Matrix federation
 */
object ServerNameResolver {

    private var cachedServerName: String? = null
    private var cachedServerPort: Int = 8080

    /**
     * Get the server's hostname/domain name dynamically
     * Follows Matrix specification server name resolution
     */
    fun getServerName(): String {
        cachedServerName?.let { return it }

        val serverName = determineServerName()
        cachedServerName = serverName
        return serverName
    }

    /**
     * Get the server's port
     */
    fun getServerPort(): Int = cachedServerPort

    /**
     * Set the server port (called during server startup)
     */
    fun setServerPort(port: Int) {
        cachedServerPort = port
        // Invalidate cache when port changes
        cachedServerName = null
    }

    /**
     * Get the full server address (hostname:port) for federation
     * For federation, we need to advertise the external HTTPS port (443), not internal port
     */
    fun getServerAddress(): String {
        return "${getServerName()}:443"
    }

    /**
     * Get the full server address with internal port (for internal use)
     */
    fun getServerAddressInternal(): String {
        return "${getServerName()}:${getServerPort()}"
    }

    /**
     * Get the HTTPS base URL for the server
     */
    fun getServerBaseUrl(): String {
        return "https://${getServerName()}:${getServerPort()}"
    }

    /**
     * Get the HTTP base URL for the server (for development)
     */
    fun getServerBaseUrlHttp(): String {
        return "http://${getServerName()}:${getServerPort()}"
    }

    /**
     * Determine the server name using multiple strategies
     */
    private fun determineServerName(): String {
        // Strategy 1: Environment variable (highest priority)
        System.getenv("MATRIX_SERVER_NAME")?.let { envName ->
            if (envName.isNotBlank()) {
                println("Using server name from MATRIX_SERVER_NAME: $envName")
                return envName
            }
        }

        // Strategy 2: Configuration file override (high priority)
        try {
            val configFile = java.io.File("config.yml")
            if (configFile.exists()) {
                val configContent = configFile.readText()
                println("DEBUG: Config file content length: ${configContent.length}")
                println("DEBUG: Config file content preview: ${configContent.take(500)}")
                
                // First try federation.serverName (production format) - handle YAML indentation
                val federationServerNamePattern = Regex("federation:\\s*\n\\s*serverName:\\s*[\"']([^\"']+)[\"']", RegexOption.DOT_MATCHES_ALL)
                federationServerNamePattern.find(configContent)?.let { match ->
                    val configServerName = match.groupValues[1]
                    println("DEBUG: Found federation server name: $configServerName")
                    if (configServerName.isNotBlank()) {
                        println("Using federation server name from config.yml: $configServerName")
                        return configServerName
                    }
                }
                
                // Fallback to top-level serverName
                val serverNamePattern = Regex("serverName:\\s*[\"']([^\"']+)[\"']")
                serverNamePattern.find(configContent)?.let { match ->
                    val configServerName = match.groupValues[1]
                    println("DEBUG: Found top-level server name: $configServerName")
                    if (configServerName.isNotBlank()) {
                        println("Using server name from config.yml: $configServerName")
                        return configServerName
                    }
                }
            } else {
                println("DEBUG: Config file does not exist at: ${configFile.absolutePath}")
            }
        } catch (e: Exception) {
            println("Warning: Could not read config.yml for server name: ${e.message}")
            e.printStackTrace()
        }

        // Strategy 3: Try to get hostname (not FQDN)
        try {
            val localHost = InetAddress.getLocalHost()
            val hostname = localHost.hostName
            if (hostname != "localhost" && hostname != "127.0.0.1" && !hostname.contains("localhost")) {
                println("Using hostname as server name: $hostname")
                return hostname
            }
        } catch (e: Exception) {
            println("Warning: Could not determine hostname: ${e.message}")
        }

        // Strategy 4: Try to find a non-loopback network interface hostname
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val hostname = address.hostName
                        if (hostname != "localhost" && hostname != address.hostAddress) {
                            println("Using network interface hostname: $hostname")
                            return hostname
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            println("Warning: Could not enumerate network interfaces: ${e.message}")
        }

        // Strategy 5: Try reverse DNS lookup of public IP (only as last resort)
        try {
            val localHost = InetAddress.getLocalHost()
            if (localHost.hostAddress != "127.0.0.1") {
                println("Using local host address: ${localHost.hostAddress}")
                return localHost.hostAddress
            }
        } catch (e: Exception) {
            println("Warning: Could not get local host address: ${e.message}")
        }

        // Strategy 6: Fallback to localhost with warning
        println("WARNING: Could not determine a proper server name. Using 'localhost' as fallback.")
        println("For production deployment, set the MATRIX_SERVER_NAME environment variable")
        println("or configure serverName in config.yml")
        return "localhost"
    }

    /**
     * Validate if a server name is reachable
     */
    fun validateServerName(serverName: String): Boolean {
        return try {
            val address = InetAddress.getByName(serverName)
            !address.isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get server info for debugging
     */
    fun getServerInfo(): Map<String, Any> {
        return try {
            mapOf(
                "serverName" to getServerName(),
                "serverPort" to getServerPort(),
                "serverAddress" to getServerAddress(),
                "serverAddressInternal" to getServerAddressInternal(),
                "serverBaseUrl" to getServerBaseUrl(),
                "localHostname" to (try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "unknown" }),
                "canonicalHostname" to (try { InetAddress.getLocalHost().canonicalHostName } catch (e: Exception) { "unknown" }),
                "matrixServerNameEnv" to (System.getenv("MATRIX_SERVER_NAME") ?: "not set")
            )
        } catch (e: Exception) {
            // Fallback response if anything goes wrong
            mapOf(
                "error" to "Failed to get server info: ${e.message}",
                "serverName" to (cachedServerName ?: "unknown"),
                "serverPort" to cachedServerPort
            )
        }
    }
}
