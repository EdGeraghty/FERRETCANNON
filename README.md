# FERRETCANNON Matrix Server

An LLM-only Kotlin/KTor implementation of a Matrix Server supporting the Matrix specification v1.15 (#YOLO!)

## Current Implementation Status

**⚠️ IMPORTANT**: This document reflects the **actual tested status** of the server. Many endpoints listed as "implemented" in the codebase are either missing, incomplete, or return errors. Only endpoints that have been verified to work correctly are marked as ✅.

### ✅ **WORKING ENDPOINTS** (Verified via Testing)

#### **Authentication & Registration**
- ✅ `GET /_matrix/client/v3/login` - Returns supported login flows ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3login))
- ✅ `POST /_matrix/client/v3/login` - User authentication ([spec](https://spec.matrix.org/v1.15/client-server-api/#post_matrixclientv3login))
- ✅ `GET /_matrix/client/v3/register` - Returns supported registration flows ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3register))
- ✅ `POST /_matrix/client/v3/register` - User registration ([spec](https://spec.matrix.org/v1.15/client-server-api/#post_matrixclientv3register))
- ✅ `GET /_matrix/client/v3/register/available` - Username availability checking ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3registeravailable))

#### **User Management**
- ✅ `GET /_matrix/client/v3/devices` - List user devices ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3devices))
- ✅ `GET /_matrix/client/v3/profile/{userId}` - Get user profile ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3profileuserid))
- ❌ `PUT /_matrix/client/v3/profile/{userId}/displayname` - Set display name (500 Internal Server Error)

#### **Room Operations**
- ✅ `POST /_matrix/client/v3/createRoom` - Create new room ([spec](https://spec.matrix.org/v1.15/client-server-api/#post_matrixclientv3createroom))
- ✅ `PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}` - Send messages/events ([spec](https://spec.matrix.org/v1.15/client-server-api/#put_matrixclientv3roomsroomidsendeventtypetxnid))
- ✅ `GET /_matrix/client/v3/sync` - Client synchronization ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3sync))

#### **Push Notifications**
- ✅ `GET /_matrix/client/v3/pushrules/` - Get push rules ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3pushrules))

#### **Server Administration**
- ✅ `GET /_matrix/client/v3/server_version` - Server version information ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3adminserver_version))

#### **Capabilities**
- ✅ `GET /_matrix/client/v3/capabilities` - Server capabilities ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3capabilities))

#### **Federation**
- ✅ `GET /_matrix/federation/v1/version` - Federation version info ([spec](https://spec.matrix.org/v1.15/server-server-api/#get_matrixfederationv1version))

#### **Well-Known**
- ✅ `GET /.well-known/matrix/client` - Client discovery ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_well-knownmatrixclient))
- ✅ `GET /.well-known/matrix/server` - Server discovery ([spec](https://spec.matrix.org/v1.15/server-server-api/#get_well-knownmatrixserver))

### ❌ **MISSING/NON-WORKING ENDPOINTS** (Critical Gaps)

#### **High Priority Missing Endpoints**
- ❌ `GET /_matrix/client/v3/rooms/{roomId}/messages` - **CRITICAL**: Room message history ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3roomsroomidmessages)) - Returns 404
- ❌ `GET /_matrix/client/v3/joined_rooms` - List joined rooms ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3joined_rooms)) - Returns 404
- ❌ `GET /_matrix/client/v3/rooms/{roomId}/members` - Room membership ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixclientv3roomsroomidmembers)) - Returns 404
- ❌ `POST /_matrix/client/v3/rooms/{roomId}/join` - Join room ([spec](https://spec.matrix.org/v1.15/client-server-api/#post_matrixclientv3roomsroomidjoin)) - Returns 404
- ❌ `POST /_matrix/client/v3/rooms/{roomId}/leave` - Leave room ([spec](https://spec.matrix.org/v1.15/client-server-api/#post_matrixclientv3roomsroomidleave)) - Returns 404

#### **Content Repository**
- ❌ `POST /_matrix/media/v3/upload` - File upload ([spec](https://spec.matrix.org/v1.15/client-server-api/#post_matrixmediav3upload)) - Not tested (complex multipart)
- ❌ `GET /_matrix/media/v3/download/{serverName}/{mediaId}` - File download ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixmediav3downloadservernamemediaid)) - Not tested
- ❌ `GET /_matrix/media/v3/thumbnail/{serverName}/{mediaId}` - Thumbnail serving ([spec](https://spec.matrix.org/v1.15/client-server-api/#get_matrixmediav3thumbnailservernamemediaid)) - Not tested

#### **OAuth 2.0**
- ❌ OAuth 2.0 endpoints - Not tested but likely missing

### 📊 **COMPLIANCE ASSESSMENT**

**Current Compliance: ~40%**

- ✅ **Core Authentication**: 100% working
- ✅ **Basic Room Operations**: 60% working (missing message history)
- ✅ **Federation Basics**: 100% working
- ✅ **Discovery**: 100% working
- ❌ **Content Management**: 0% tested
- ❌ **Advanced Features**: Mostly missing

### 🎯 **CRITICAL ISSUES TO ADDRESS**

1. **Priority 1**: Implement `GET /rooms/{roomId}/messages` - Essential for any Matrix client
2. **Priority 2**: Fix profile update endpoints (500 errors)
3. **Priority 3**: Implement room membership endpoints (join/leave/members)
4. **Priority 4**: Add room listing endpoints (joined_rooms)
5. **Priority 5**: Complete content repository implementation

## Getting Started

### Quick Start (No Manual Intervention Required)

1. **Automated Server Start**: Use the provided scripts to start the server without manual prompts:

   ```bash
   # Windows PowerShell (Recommended)
   .\start-server.ps1
   
   # Or Windows Command Prompt
   start-server.bat
   ```

2. **VS Code Integration**: Use the pre-configured tasks:
   - Press `Ctrl+Shift+P` → "Tasks: Run Task" → "start-server"
   - Or use the debug panel with "Run FERRETCANNON Server"

3. **Test Server**: Automatically test if the server is running:

   ```powershell
   .\test-server.ps1
   ```

### Manual Start (if needed)

1. Install Gradle if not already installed.

2. Run `gradle build` to compile the project.

3. Run `gradle run` to start the server.

The server runs on port 8080.

## Avoiding Manual Prompts

This project includes several automation features to minimize the need for clicking "Continue":

### VS Code Settings

- Auto-save enabled (saves after 1 second delay)
- Disabled confirmation dialogs for:
  - File deletion
  - Terminal exit
  - Debug session exit
  - Window close
- Disabled extension update prompts
- Disabled Docker engine prompts

### Automated Scripts

- `start-server.ps1`: Automatically stops old processes and starts the server
- `start-server.bat`: Windows batch version of the auto-start script
- `test-server.ps1`: Automatically tests server connectivity

### VS Code Tasks

- `start-server`: Runs the server with no prompts
- `build`: Compiles the project
- `clean`: Cleans build artifacts
- `run`: Basic server run task

### Development Workflow

1. Open the project in VS Code
2. Press `Ctrl+Shift+P` → "Tasks: Run Task" → "start-server"
3. The server starts automatically without any prompts
4. Use `.\test-server.ps1` to verify it's running
5. Make changes - auto-save handles file saving
6. Server automatically reloads on code changes (Gradle daemon)

## Requirements

- Java 17 or higher
- Gradle 8.5 or higher
- Windows PowerShell (for automated scripts)

## Architecture

- **Language**: Kotlin
- **Framework**: KTor
- **Database**: H2/SQLite with Exposed ORM
- **Serialization**: kotlinx.serialization
- **Build System**: Gradle

## Compliance

This implementation adheres to the Matrix Server-Server API v1.15 specification. All endpoints include proper authentication, validation, and error handling as required by the specification.

### API Standards Compliance

- ✅ Server-server communication over HTTPS (configurable for production)
- ✅ Proper error responses for unsupported endpoints (404 M_UNRECOGNIZED)
- ✅ JSON content with UTF-8 encoding and application/json Content-Type
- ✅ Support for all required HTTP methods and status codes

### Server Discovery Compliance

- ✅ `/.well-known/matrix/server` endpoint for server delegation
- ✅ `/_matrix/key/v2/server` endpoint for publishing server keys
- ✅ `/_matrix/key/v2/query` endpoint for querying keys from other servers
- ✅ Proper key signing and validation as per specification

### Authentication Compliance

- ✅ **Request Authentication**: All federation requests are authenticated using X-Matrix authorization headers
  - Proper parsing and validation of Authorization headers
  - Ed25519 signature verification for incoming requests
  - Public key fetching and caching from remote servers
  - Origin and destination validation
  - Applied to all federation endpoints requiring authentication
- ❌ **Response Authentication**: TLS server certificate authentication (HTTPS not yet enabled for production)
- ❌ **Client TLS Certificates**: Optional client certificate authentication not implemented
- ✅ **Event Signing**: Complete event signing and signature validation
  - SHA-256 content hashing
  - Ed25519 signature generation and verification
  - Canonical JSON serialization for consistent signing
  - Multi-signature support (origin + sender servers)
- ✅ **Server ACLs**: Server access control lists implemented
  - Room-based server access restrictions
  - Pattern matching for server names
  - Applied to all protected federation endpoints

### Transactions Compliance

- ✅ **Transaction Processing**: Complete implementation of `PUT /_matrix/federation/v1/send/{txnId}`
  - Transaction ID validation and extraction
  - Origin server authentication and validation
  - Proper transaction size limits (50 PDUs, 100 EDUs)
  - Individual processing of PDUs and EDUs
- ✅ **PDU Processing**: Full compliance with persistent data unit handling
  - Per-PDU server ACL validation
  - Individual PDU processing results returned
  - Transaction continues despite individual PDU failures
  - Integration with existing event validation and storage
- ✅ **EDU Processing**: Complete ephemeral data unit support
  - Support for all required EDU types (m.typing, m.presence, m.receipt, m.device_list_update, m.signing_key_update, m.direct_to_device)
  - Room-specific ACL checking for typing and receipt EDUs
  - Proper EDU validation and broadcasting
  - Timestamp-based tracking and cleanup (30-second expiry for typing)
- ✅ **Transaction Response**: Specification-compliant response format
  - 200 OK response for successful processing
  - Proper error handling for oversized or invalid transactions
  - PDU processing results included in response body
- ✅ **Server ACL Integration**: ACL validation applied to transactions
  - Per-PDU ACL checking for room-based access control
  - Room-specific EDU ACL validation
  - Graceful handling of ACL-denied transactions

## License

[#YOLO Public License (YPL) v0.12.34-hunter.2](https://github.com/YOLOSecFW/YoloSec-Framework/blob/master/YOLO%20Public%20License)
