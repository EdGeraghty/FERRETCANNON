package routes.client_server.client

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import config.ServerConfig
import utils.AuthUtils

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
            val user = jsonBody["user"]?.jsonPrimitive?.content
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val type = jsonBody["type"]?.jsonPrimitive?.content ?: "m.login.password"

            if (user == null || password == null) {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                    put("errcode", "M_BAD_JSON")
                    put("error", "Missing user or password")
                })
                return@post
            }

            // TODO: Implement actual authentication
            // For now, return a mock response
            call.respond(buildJsonObject {
                put("user_id", "@$user:${config.federation.serverName}")
                put("access_token", "mock_access_token_${System.currentTimeMillis()}")
                put("device_id", "mock_device")
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
            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject

            // Check if this is an initial registration request (empty or minimal body)
            if (jsonBody.isEmpty() || (!jsonBody.containsKey("username") && !jsonBody.containsKey("password"))) {
                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                    put("errcode", "M_MISSING_PARAM")
                    put("error", "Missing parameters")
                    putJsonArray("flows") {
                        addJsonObject {
                            putJsonArray("stages") {
                                add("m.login.password")
                            }
                        }
                    }
                    put("session", "dummy_session_${System.currentTimeMillis()}")
                })
                return@post
            }

            val username = jsonBody["username"]?.jsonPrimitive?.content
            val password = jsonBody["password"]?.jsonPrimitive?.content
            val auth = jsonBody["auth"]?.jsonObject
            val initialDeviceDisplayName = jsonBody["initial_device_display_name"]?.jsonPrimitive?.content

            // Validate auth if provided
            if (auth != null) {
                val authType = auth["type"]?.jsonPrimitive?.content
                if (authType != "m.login.password" && authType != "m.login.dummy") {
                    call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                        put("errcode", "M_UNKNOWN")
                        put("error", "Unsupported authentication type")
                    })
                    return@post
                }
            }

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
                })
                return@post
            }

            // Validate username format
            if (!username.matches(Regex("^[a-zA-Z0-9._-]+$")) || username.length < 1 || username.length > 255) {
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

            // Generate device ID and create access token
            val deviceId = AuthUtils.generateDeviceId()
            val accessToken = AuthUtils.createAccessToken(userId, deviceId)

            call.respond(buildJsonObject {
                put("user_id", userId)
                put("access_token", accessToken)
                put("device_id", deviceId)
                put("home_server", config.federation.serverName)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error: ${e.message}")
            })
        }
    }

    // GET /capabilities - Server capabilities
    get("/capabilities") {
        call.respond(buildJsonObject {
            putJsonObject("capabilities") {
                putJsonObject("m.change_password") {
                    put("enabled", true)
                }
                putJsonObject("m.room_versions") {
                    put("default", "9")
                    putJsonObject("available") {
                        put("9", "stable")
                    }
                }
                putJsonObject("m.set_displayname") {
                    put("enabled", true)
                }
                putJsonObject("m.set_avatar_url") {
                    put("enabled", true)
                }
                putJsonObject("m.3pid_changes") {
                    put("enabled", true)
                }
            }
        })
    }

    // GET /register/available - Check username availability
    get("/register/available") {
        val username = call.request.queryParameters["username"]

        if (username == null) {
            call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
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
