package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.AuthUtils
import routes.client_server.client.MATRIX_USER_ID_KEY
import routes.client_server.client.MATRIX_TOKEN_KEY

fun Route.authRoutes(config: ServerConfig) {
    // GET /login - Get available login flows
    get("/login") {
        call.respond(buildJsonObject {
            putJsonArray("flows") {
                addJsonObject {
                    put("type", "m.login.password")
                }
            }
        })
    }

    // POST /login - User login
    post("/login") {
        try {
            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val deviceId = jsonBody["device_id"]?.jsonPrimitive?.content ?: AuthUtils.generateDeviceId()

            // Extract user identifier
            var username: String?
            val identifier = jsonBody["identifier"]?.jsonObject
            if (identifier != null) {
                val idType = identifier["type"]?.jsonPrimitive?.content
                when (idType) {
                    "m.id.user" -> {
                        username = identifier["user"]?.jsonPrimitive?.content
                    }
                    "m.id.thirdparty" -> {
                        // For now, we don't support 3PID login
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("errcode", "M_UNKNOWN")
                            put("error", "Third-party login not supported")
                        })
                        return@post
                    }
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                            put("errcode", "M_UNKNOWN")
                            put("error", "Unknown identifier type")
                        })
                        return@post
                    }
                }
            } else {
                // Fallback to old format for backward compatibility
                username = jsonBody["user"]?.jsonPrimitive?.content
            }

            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_BAD_JSON")
                    put("error", "Missing user identifier or password")
                })
                return@post
            }

            // Authenticate user
            val authenticatedUserId = AuthUtils.authenticateUser(username, password)
            if (authenticatedUserId == null) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                    put("errcode", "M_FORBIDDEN")
                    put("error", "Invalid username or password")
                })
                return@post
            }

            // Create access token
            val accessToken = AuthUtils.createAccessToken(
                userId = authenticatedUserId,
                deviceId = deviceId,
                userAgent = call.request.headers["User-Agent"],
                ipAddress = call.request.headers["X-Forwarded-For"] ?: call.request.headers["X-Real-IP"]
            )

            // Return successful login response
            call.respond(buildJsonObject {
                put("user_id", authenticatedUserId)
                put("access_token", accessToken)
                put("device_id", deviceId)
                put("home_server", config.federation.serverName)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // POST /register - User registration
    post("/register") {
        try {
            // Check if registration is disabled
            if (config.security.disableRegistration) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                    put("errcode", "M_FORBIDDEN")
                    put("error", "Registration is disabled on this server")
                })
                return@post
            }

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            val username = jsonBody["username"]?.jsonPrimitive?.content
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val auth = jsonBody["auth"]?.jsonObject
            val inhibitLogin = jsonBody["inhibit_login"]?.jsonPrimitive?.boolean ?: false
            val deviceIdFromRequest = jsonBody["device_id"]?.jsonPrimitive?.content

            // Check if auth is provided
            if (auth == null) {
                // No auth provided - return UIA challenge
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    putJsonArray("flows") {
                        addJsonObject {
                            putJsonArray("stages") {
                                add("m.login.password")
                            }
                        }
                    }
                    put("session", "reg_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                })
                return@post
            }

            // Validate auth object
            val authType = auth["type"]?.jsonPrimitive?.content
            val authSession = auth["session"]?.jsonPrimitive?.content

            if (authType == null) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_PARAM")
                    put("error", "Missing auth type")
                    putJsonArray("flows") {
                        addJsonObject {
                            putJsonArray("stages") {
                                add("m.login.password")
                            }
                        }
                    }
                    put("session", authSession ?: "reg_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                })
                return@post
            }

            if (authType != "m.login.password" && authType != "m.login.dummy") {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_UNKNOWN")
                    put("error", "Unsupported authentication type")
                    putJsonArray("flows") {
                        addJsonObject {
                            putJsonArray("stages") {
                                add("m.login.password")
                            }
                        }
                    }
                    put("session", authSession ?: "reg_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                })
                return@post
            }

            // For m.login.dummy, we don't need credentials
            if (authType == "m.login.dummy") {
                // Check if registration is disabled (including guest)
                if (config.security.disableRegistration) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject {
                        put("errcode", "M_FORBIDDEN")
                        put("error", "Registration is disabled on this server")
                    })
                    return@post
                }

                // Generate a random username if not provided
                val finalUsername = username ?: "user_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}"
                val finalPassword = "dummy_password_${System.currentTimeMillis()}"

                // Create the user
                val userId = AuthUtils.createUser(finalUsername, finalPassword, serverName = config.federation.serverName)

                // Generate device ID or use provided one
                val deviceId = deviceIdFromRequest ?: AuthUtils.generateDeviceId()
                val accessToken = AuthUtils.createAccessToken(userId, deviceId)

                val response = buildJsonObject {
                    put("user_id", userId)
                    put("device_id", deviceId)
                    put("home_server", config.federation.serverName)
                    if (!inhibitLogin) {
                        put("access_token", accessToken)
                    }
                }

                call.respond(response)
                return@post
            }

            // For m.login.password, validate credentials
            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_PARAM")
                    put("error", "Missing username or password")
                    putJsonArray("flows") {
                        addJsonObject {
                            putJsonArray("stages") {
                                add("m.login.password")
                            }
                        }
                    }
                    put("session", authSession ?: "reg_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000000)}")
                })
                return@post
            }

            // Validate username format
            if (!username.matches(Regex("^[a-zA-Z0-9._-]+\$")) || username.length < 1 || username.length > 255) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_INVALID_USERNAME")
                    put("error", "Invalid username format")
                })
                return@post
            }

            // Check if username is available
            if (!AuthUtils.isUsernameAvailable(username)) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_USER_IN_USE")
                    put("error", "Username already taken")
                })
                return@post
            }

            // Validate password strength
            val (isValidPassword, passwordError) = AuthUtils.validatePasswordStrength(password)
            if (!isValidPassword) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_WEAK_PASSWORD")
                    put("error", passwordError)
                })
                return@post
            }

            // Create the user
            val userId = AuthUtils.createUser(username, password, serverName = config.federation.serverName)

            // Generate device ID or use provided one
            val deviceId = deviceIdFromRequest ?: AuthUtils.generateDeviceId()
            val accessToken = AuthUtils.createAccessToken(userId, deviceId)

            val response = buildJsonObject {
                put("user_id", userId)
                put("device_id", deviceId)
                put("home_server", config.federation.serverName)
                if (!inhibitLogin) {
                    put("access_token", accessToken)
                }
            }

            call.respond(response)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }

    // POST /logout - User logout
    post("/logout") {
        try {
            val userId = call.validateAccessToken() ?: return@post

            // Get the access token from the request
            val accessToken = call.attributes.getOrNull(MATRIX_TOKEN_KEY) as? String
            if (accessToken != null) {
                // Invalidate the access token
                AuthUtils.deleteAccessToken(accessToken)
            }

            // Return empty response
            call.respond(buildJsonObject { })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // GET /register/available - Check username availability
    get("/register/available") {
        val username = call.request.queryParameters["username"]

        if (username == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_MISSING_PARAM")
                put("error", "Missing username parameter")
            })
            return@get
        }

        // Check actual availability in database
        val available = AuthUtils.isUsernameAvailable(username)
        call.respond(buildJsonObject {
            put("available", available)
        })
    }

    // GET /register - Query supported registration methods
    get("/register") {
        if (config.security.disableRegistration) {
            call.respond(buildJsonObject {
                putJsonArray("flows") {
                    // Empty flows array when registration is disabled
                }
            })
        } else {
            call.respond(buildJsonObject {
                putJsonArray("flows") {
                    addJsonObject {
                        putJsonArray("stages") {
                            add("m.login.password")
                        }
                    }
                }
            })
        }
    }

    // GET /org.matrix.msc2965/auth_metadata - MSC2965 Authentication Metadata
    get("/auth_metadata") {
        val flows = buildJsonArray {
            // Password-based authentication flow
            addJsonObject {
                put("type", "m.login.password")
                putJsonObject("params") {
                    put("user_field", "identifier")
                    putJsonArray("identifier_types") {
                        add("m.id.user")
                        add("m.id.thirdparty")
                    }
                }
            }

            if (!config.security.disableRegistration) {
                // Dummy authentication flow (for testing/guest)
                addJsonObject {
                    put("type", "m.login.dummy")
                    putJsonObject("params") {
                        // No parameters required for dummy auth
                    }
                }
            }

            // Token-based authentication flow
            addJsonObject {
                put("type", "m.login.token")
                putJsonObject("params") {
                    put("token_field", "token")
                }
            }

            // SSO authentication flows
            addJsonObject {
                put("type", "m.login.sso")
                putJsonObject("params") {
                    putJsonArray("identity_providers") {
                        // Add SSO providers if configured
                    }
                }
            }
        }

        call.respond(buildJsonObject {
            putJsonObject("auth_metadata") {
                put("flows", flows)
                putJsonObject("params") {
                    put("server_name", config.federation.serverName)
                    put("server_version", "FERRETCANNON-1.0")
                    put("supports_login", true)
                    put("supports_registration", !config.security.disableRegistration)
                    put("supports_guest", !config.security.disableRegistration)
                    put("supports_3pid_login", false)
                    put("supports_3pid_registration", false)
                }
            }
        })
    }
}
