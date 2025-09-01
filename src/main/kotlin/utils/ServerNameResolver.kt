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
        // Strategy 1: Environment variable
        System.getenv("MATRIX_SERVER_NAME")?.let { envName ->
            if (envName.isNotBlank()) {
                println("Using server name from MATRIX_SERVER_NAME: $envName")
                return envName
            }
        }

        // Strategy 2: Configuration file override
        try {
            val configFile = java.io.File("config.yml")
            if (configFile.exists()) {
                val configContent = configFile.readText()
                val serverNamePattern = Regex("serverName:\\s*[\"']([^\"']+)[\"']")
                serverNamePattern.find(configContent)?.let { match ->
                    val configServerName = match.groupValues[1]
                    if (configServerName.isNotBlank() && configServerName != "localhost:8080") {
                        println("Using server name from config.yml: $configServerName")
                        return configServerName
                    }
                }
            }
        } catch (e: Exception) {
            println("Warning: Could not read config.yml for server name: ${e.message}")
        }

        // Strategy 3: Try to get FQDN
        try {
            val localHost = InetAddress.getLocalHost()
            val fqdn = localHost.canonicalHostName
            if (fqdn != "localhost" && fqdn != "127.0.0.1" && !fqdn.contains("localhost")) {
                println("Using FQDN as server name: $fqdn")
                return fqdn
            }
        } catch (e: Exception) {
            println("Warning: Could not determine FQDN: ${e.message}")
        }

        // Strategy 4: Try to find a non-loopback network interface
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val hostname = address.canonicalHostName
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

        // Strategy 5: Try reverse DNS lookup of public IP
        try {
            // This is a simplified approach - in production you'd use a service
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
