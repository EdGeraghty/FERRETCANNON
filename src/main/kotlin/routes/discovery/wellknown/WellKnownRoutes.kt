package routes.discovery.wellknown

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import config.ServerConfig
import models.ThirdPartyIdentifiers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.Base64

fun Application.wellKnownRoutes(config: ServerConfig) {
    routing {
        route("/.well-known") {
            route("/matrix") {
                get("/server") {
                    // Server discovery for federation
                    // This tells other servers how to reach this server for federation
                    // Add caching headers for discovery
                    call.response.headers.append("Cache-Control", "public, max-age=86400") // Cache for 24 hours

                    call.respond(mapOf("m.server" to "${config.federation.serverName}:${config.federation.federationPort}"))
                }

                get("/client") {
                    // Client discovery for homeserver configuration
                    // This tells clients how to connect to the homeserver
                    // Add caching headers for discovery
                    call.response.headers.append("Cache-Control", "public, max-age=3600") // Cache for 1 hour

                    // For production, use HTTPS without port (fly.io handles this)
                    // For development, use the configured base URL
                    val baseUrl = if (config.federation.serverName.contains("localhost")) {
                        "http://${config.federation.serverName}:${config.server.port}"
                    } else {
                        "https://${config.federation.serverName}"
                    }

                    call.respond(mapOf(
                        "m.homeserver" to mapOf(
                            "base_url" to baseUrl
                        )
                        // Removed m.identity_server since we don't implement identity server endpoints
                    ))
                }

                // Basic identity server endpoints
                get("/identity/v2") {
                    // Identity server API discovery
                    call.response.headers.append("Cache-Control", "public, max-age=86400")

                    call.respond(emptyMap<String, Any>()) // Empty response for basic implementation
                }

                get("/identity/v2/terms") {
                    // Terms of service for identity server
                    val baseUrl = if (config.federation.serverName.contains("localhost")) {
                        "http://${config.federation.serverName}:${config.server.port}"
                    } else {
                        "https://${config.federation.serverName}"
                    }

                    call.respond(mapOf(
                        "policies" to mapOf(
                            "privacy_policy" to mapOf(
                                "version" to "1.0",
                                "en" to mapOf(
                                    "name" to "Privacy Policy",
                                    "url" to "$baseUrl/privacy"
                                )
                            ),
                            "terms_of_service" to mapOf(
                                "version" to "1.0",
                                "en" to mapOf(
                                    "name" to "Terms of Service",
                                    "url" to "$baseUrl/terms"
                                )
                            )
                        )
                    ))
                }

                get("/identity/v2/hash_details") {
                    // Hash details for 3PID lookups
                    call.response.headers.append("Cache-Control", "public, max-age=86400")

                    call.respond(mapOf(
                        "algorithms" to listOf("sha256"),
                        "lookup_pepper" to "FERRETCANNON_IDENTITY_PEPPER"
                    ))
                }

                post("/identity/v2/lookup") {
                    // 3PID lookup endpoint - full implementation with database lookup
                    try {
                        val requestBody = call.receiveText()
                        val requestJson = Json.parseToJsonElement(requestBody).jsonObject

                        val addressesElement = requestJson["addresses"]
                        val addresses = if (addressesElement is JsonArray) {
                            addressesElement.map { it.jsonPrimitive.content }
                        } else {
                            emptyList<String>()
                        }

                        val algorithm = requestJson["algorithm"]?.jsonPrimitive?.content ?: "sha256"
                        val pepper = requestJson["pepper"]?.jsonPrimitive?.content ?: "FERRETCANNON_IDENTITY_PEPPER"

                        // Validate algorithm (only sha256 is supported)
                        if (algorithm != "sha256") {
                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                "errcode" to "M_INVALID_PARAM",
                                "error" to "Unsupported algorithm: $algorithm"
                            ))
                            return@post
                        }

                        // Validate pepper matches what we advertise
                        if (pepper != "FERRETCANNON_IDENTITY_PEPPER") {
                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                "errcode" to "M_INVALID_PARAM",
                                "error" to "Invalid pepper"
                            ))
                            return@post
                        }

                        // Validate that addresses array is not empty
                        if (addresses.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, mapOf(
                                "errcode" to "M_INVALID_PARAM",
                                "error" to "addresses array cannot be empty"
                            ))
                            return@post
                        }

                        // Perform the actual lookup
                        val mappings = transaction {
                            // Query database for matches - we look up by address directly
                            // (not by hash, as the Matrix spec requires privacy-preserving lookups)
                            val results = mutableMapOf<String, String>()
                            addresses.forEach { address ->
                                // Try to find this address in our database
                                val match = ThirdPartyIdentifiers.select {
                                    ThirdPartyIdentifiers.address eq address
                                }.singleOrNull()

                                if (match != null) {
                                    // Create the hash for the response as per Matrix spec
                                    val hashInput = "$address $pepper"
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    val hashBytes = digest.digest(hashInput.toByteArray(Charsets.UTF_8))
                                    val hash = Base64.getEncoder().encodeToString(hashBytes)

                                    results[hash] = match[ThirdPartyIdentifiers.userId]
                                }
                            }

                            results
                        }

                        call.respond(mapOf(
                            "mappings" to mappings
                        ))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf(
                            "errcode" to "M_INVALID_PARAM",
                            "error" to "Invalid request format"
                        ))
                    }
                }
            }

            // OAuth 2.0 Authorization Server Metadata
            get("/oauth-authorization-server") {
                // OAuth 2.0 discovery endpoint
                // This provides metadata about the OAuth 2.0 authorization server
                // Add caching headers for discovery
                call.response.headers.append("Cache-Control", "public, max-age=3600") // Cache for 1 hour

                val serverName = config.federation.serverName
                val baseUrl = if (serverName.contains("localhost")) {
                    "http://${serverName}:${config.server.port}"
                } else {
                    "https://${serverName}"
                }

                // OAuth 2.0 Authorization Server Metadata according to RFC 8414
                val metadata = buildJsonObject {
                    put("issuer", serverName)  // Server identifier, not full URL
                    put("authorization_endpoint", "$baseUrl/oauth2/authorize")
                    put("token_endpoint", "$baseUrl/oauth2/token")
                    put("revocation_endpoint", "$baseUrl/oauth2/revoke")
                    put("introspection_endpoint", "$baseUrl/oauth2/introspect")
                    put("userinfo_endpoint", "$baseUrl/oauth2/userinfo")
                    put("jwks_uri", "$baseUrl/oauth2/jwks")
                    putJsonArray("response_types_supported") {
                        add("code")
                    }
                    putJsonArray("grant_types_supported") {
                        add("authorization_code")
                        add("refresh_token")
                    }
                    putJsonArray("code_challenge_methods_supported") {
                        add("S256")
                    }
                    putJsonArray("token_endpoint_auth_methods_supported") {
                        add("client_secret_basic")
                        add("client_secret_post")
                    }
                    putJsonArray("revocation_endpoint_auth_methods_supported") {
                        add("client_secret_basic")
                        add("client_secret_post")
                    }
                    putJsonArray("introspection_endpoint_auth_methods_supported") {
                        add("client_secret_basic")
                        add("client_secret_post")
                    }
                    putJsonArray("scopes_supported") {
                        add("openid")
                        add("profile")
                    }
                    putJsonArray("response_modes_supported") {
                        add("query")
                        add("fragment")
                    }
                    put("service_documentation", "$baseUrl/docs/oauth2")
                    putJsonArray("ui_locales_supported") {
                        add("en")
                    }
                }

                call.respond(metadata)
            }
        }
    }
}
