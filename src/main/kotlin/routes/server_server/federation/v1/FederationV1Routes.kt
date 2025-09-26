package routes.server_server.federation.v1

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import models.Users
import kotlinx.coroutines.runBlocking
import utils.connectedClients
import utils.PresenceStorage
import utils.ReceiptsStorage
import utils.ServerKeysStorage
import utils.typingMap
import utils.MediaStorage
import models.AccessTokens
import utils.StateResolver
import utils.MatrixAuth
import utils.ServerKeys
import models.Events
import models.Rooms

fun Application.federationV1Routes() {
    routing {
        route("/_matrix") {
            route("/federation") {
                route("/v1") {
                    get("/version") {
                        // For now, skip auth for version, as per spec it's public
                        call.respond(buildJsonObject {
                            putJsonObject("server") {
                                put("name", "FERRETCANNON")
                                put("version", "#YOLO🔥🔥🔥")
                            }
                            putJsonObject("spec_versions") {
                                put("federation", "v1.15")
                                put("client_server", "v1.15")
                            }
                        })
                    }
                    put("/send/{txnId}") {
                        try {
                            call.parameters["txnId"] ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                put("errcode", "M_INVALID_PARAM")
                                put("error", "Missing transaction ID")
                            })

                            // Validate content type
                            val contentType = call.request.contentType()
                            if (contentType != ContentType.Application.Json) {
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_NOT_JSON")
                                    put("error", "Content-Type must be application/json")
                                })
                                return@put
                            }

                            val body = call.receiveText()

                            // Validate request size
                            if (body.length > 1024 * 1024) { // 1MB limit
                                call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                    put("errcode", "M_TOO_LARGE")
                                    put("error", "Request too large")
                                })
                                return@put
                            }

                            val authHeader = call.request.headers["Authorization"]
                            if (authHeader == null || !MatrixAuth.verifyAuth(call, authHeader, body)) {
                                call.respond(HttpStatusCode.Unauthorized, buildJsonObject {
                                    put("errcode", "M_UNAUTHORIZED")
                                    put("error", "Invalid signature")
                                })
                                return@put
                            }

                            // Process transaction
                            val result = processTransaction(body)
                            if (result is JsonObject && result.containsKey("errcode")) {
                                call.respond(HttpStatusCode.BadRequest, result)
                            } else {
                                call.respond(HttpStatusCode.OK, result)
                            }
                        } catch (e: Exception) {
                            when (e) {
                                is kotlinx.serialization.SerializationException -> {
                                    call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                                        put("errcode", "M_BAD_JSON")
                                        put("error", "Invalid JSON")
                                    })
                                }
                                else -> {
                                    call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                        put("errcode", "M_UNKNOWN")
                                        put("error", "Internal server error")
                                    })
                                }
                            }
                        }
                    }
                    // Include all the route modules
                    federationV1Events()
                    federationV1State()
                    federationV1Membership()
                    federationV1ThirdParty()
                    federationV1PublicRooms()
                    federationV1Spaces()
                    federationV1UserQuery()
                    federationV1Devices()
                    federationV1Media()
                    federationV1ServerKeys()
                }
            }
        }
    }
}
