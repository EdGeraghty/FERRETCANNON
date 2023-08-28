package utils

import io.ktor.server.websocket.DefaultWebSocketServerSession

// Connected clients for broadcasting
val connectedClients = mutableMapOf<String, MutableList<DefaultWebSocketServerSession>>() // roomId to list of sessions

// In-memory storage for EDUs
val presenceMap = mutableMapOf<String, Map<String, Any?>>() // userId to presence data
val receiptsMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (eventId to ts)
val typingMap = mutableMapOf<String, MutableMap<String, Long>>() // roomId to (userId to timestamp)

// Device management storage
val deviceKeys = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>() // userId -> deviceId -> device info
val oneTimeKeys = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>() // userId -> keyId -> key data
val crossSigningKeys = mutableMapOf<String, Map<String, Any?>>() // userId -> cross-signing key data
val deviceListStreamIds = mutableMapOf<String, Long>() // userId -> stream_id for device list updates

// Simple in-memory user store for demo purposes
val users = mutableMapOf<String, String>() // userId to accessToken
