package utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.UnknownHostException
import org.xbill.DNS.*

/**
 * Server Discovery utilities for Matrix federation
 * Implements the server name resolution algorithm from the Matrix spec
 */
object ServerDiscovery {

    private val client = HttpClient(CIO)

    /**
     * Resolve a server name to connection details
     * Follows the algorithm from https://spec.matrix.org/v1.15/server-server-api/#resolving-server-names
     */
    fun resolveServerName(serverName: String): ServerConnectionDetails? {
        return try {
            // Step 1: If hostname is an IP literal
            if (isIpLiteral(serverName)) {
                return ServerConnectionDetails(
                    host = serverName,
                    port = 8448,
                    tls = true,
                    hostHeader = serverName
                )
            }

            // Step 2: If hostname is not IP literal and server name includes explicit port
            val (hostname, port) = parseServerName(serverName)
            if (port != null) {
                return ServerConnectionDetails(
                    host = hostname,
                    port = port,
                    tls = true,
                    hostHeader = serverName
                )
            }

            // Step 3: Try .well-known
            val wellKnownResult = fetchWellKnown(hostname)
            if (wellKnownResult != null) {
                return wellKnownResult
            }

            // Step 4: Try SRV records (_matrix-fed._tcp.hostname)
            val srvResult = lookupSrvRecord("_matrix-fed._tcp.$hostname")
            if (srvResult != null) {
                return srvResult
            }

            // Step 5: Try deprecated SRV records (_matrix._tcp.hostname)
            val deprecatedSrvResult = lookupSrvRecord("_matrix._tcp.$hostname")
            if (deprecatedSrvResult != null) {
                return deprecatedSrvResult
            }

            // Step 6: Default to hostname:8448
            ServerConnectionDetails(
                host = hostname,
                port = 8448,
                tls = true,
                hostHeader = hostname
            )

        } catch (e: Exception) {
            println("Error resolving server name $serverName: ${e.message}")
            null
        }
    }

    private fun isIpLiteral(hostname: String): Boolean {
        return try {
            InetAddress.getByName(hostname)
            true
        } catch (e: UnknownHostException) {
            false
        }
    }

    private fun parseServerName(serverName: String): Pair<String, Int?> {
        val parts = serverName.split(":")
        return if (parts.size == 2) {
            val port = parts[1].toIntOrNull()
            Pair(parts[0], port)
        } else {
            Pair(serverName, null)
        }
    }

    private fun fetchWellKnown(hostname: String): ServerConnectionDetails? {
        return try {
            val response = runBlocking {
                client.get("https://$hostname/.well-known/matrix/server")
            }

            if (response.status == HttpStatusCode.OK) {
                val json = runBlocking { response.body<String>() }
                val data = Json.parseToJsonElement(json).jsonObject
                val server = data["m.server"]?.jsonPrimitive?.content ?: return null

                val (delegatedHost, delegatedPort) = parseServerName(server)

                if (isIpLiteral(delegatedHost)) {
                    // IP literal case
                    ServerConnectionDetails(
                        host = delegatedHost,
                        port = delegatedPort ?: 8448,
                        tls = true,
                        hostHeader = delegatedHost
                    )
                } else if (delegatedPort != null) {
                    // Hostname with explicit port
                    ServerConnectionDetails(
                        host = delegatedHost,
                        port = delegatedPort,
                        tls = true,
                        hostHeader = "$delegatedHost:$delegatedPort"
                    )
                } else {
                    // Try SRV for delegated hostname
                    val srvResult = lookupSrvRecord("_matrix-fed._tcp.$delegatedHost")
                    if (srvResult != null) {
                        srvResult
                    } else {
                        ServerConnectionDetails(
                            host = delegatedHost,
                            port = 8448,
                            tls = true,
                            hostHeader = delegatedHost
                        )
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            println("Error fetching .well-known for $hostname: ${e.message}")
            null
        }
    }

    private fun lookupSrvRecord(srvName: String): ServerConnectionDetails? {
        return try {
            // Create a DNS lookup for SRV records
            val lookup = Lookup(srvName, Type.SRV)
            val records = lookup.run()

            if (records == null || records.isEmpty()) {
                return null
            }

            // Find the SRV record with the lowest priority
            var bestRecord: SRVRecord? = null
            var bestPriority = Int.MAX_VALUE
            var bestWeight = 0

            for (record in records) {
                if (record is SRVRecord) {
                    val priority = record.priority
                    val weight = record.weight

                    if (priority < bestPriority || (priority == bestPriority && weight > bestWeight)) {
                        bestRecord = record
                        bestPriority = priority
                        bestWeight = weight
                    }
                }
            }

            if (bestRecord == null) {
                return null
            }

            // Resolve the target hostname to an IP address
            val target = bestRecord.target.toString().removeSuffix(".")
            val port = bestRecord.port

            // Validate that we can resolve the target
            val addresses = InetAddress.getAllByName(target)
            if (addresses.isEmpty()) {
                return null
            }

            ServerConnectionDetails(
                host = target,
                port = port,
                tls = true,
                hostHeader = target
            )
        } catch (e: Exception) {
            println("Error looking up SRV record for $srvName: ${e.message}")
            null
        }
    }
}

data class ServerConnectionDetails(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val hostHeader: String
)
