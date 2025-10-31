package routes.client_server.client.device

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.DehydratedDevices
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.*
import routes.client_server.client.common.*

fun Route.dehydratedDeviceRoutes() {
    // GET /dehydrated_device - Get dehydrated device
    get("/dehydrated_device") {
        try {
            val userId = call.validateAccessToken() ?: return@get

            val dehydratedDevice = transaction {
                DehydratedDevices.select { DehydratedDevices.userId eq userId }
                    .singleOrNull()
            }

            if (dehydratedDevice == null) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject {
                    put("errcode", "M_NOT_FOUND")
                    put("error", "Dehydrated device not found")
                })
                return@get
            }

            call.respond(buildJsonObject {
                put("device_id", dehydratedDevice[DehydratedDevices.deviceId])
                put("device_data", Json.parseToJsonElement(dehydratedDevice[DehydratedDevices.deviceData]).jsonObject)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }

    // PUT /dehydrated_device - Upload dehydrated device
    put("/dehydrated_device") {
        try {
            val userId = call.validateAccessToken() ?: return@put

            // Parse request body
            val requestBody = call.receiveText()
            val jsonBody = Json.parseToJsonElement(requestBody).jsonObject
            val deviceId = jsonBody["device_id"]?.jsonPrimitive?.content ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing device_id")
            })
            val deviceData = jsonBody["device_data"]?.jsonObject ?: return@put call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                put("errcode", "M_INVALID_PARAM")
                put("error", "Missing device_data")
            })

            transaction {
                // Delete any existing dehydrated device for this user
                DehydratedDevices.deleteWhere { DehydratedDevices.userId eq userId }

                // Insert the new one
                DehydratedDevices.insert {
                    it[DehydratedDevices.userId] = userId
                    it[DehydratedDevices.deviceId] = deviceId
                    it[DehydratedDevices.deviceData] = Json.encodeToString(JsonObject.serializer(), deviceData)
                    it[DehydratedDevices.createdAt] = System.currentTimeMillis()
                }
            }

            call.respond(buildJsonObject {
                put("device_id", deviceId)
            })

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("errcode", "M_UNKNOWN")
                put("error", "Internal server error")
            })
        }
    }
}