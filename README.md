# FERRETCANNON Matrix Server

An LLM-only Kotlin/KTor implementation of a Matrix Server supporting the Matrix specification v1.15 (#YOLO!)

## Features

### Implemented Matrix Server-Server API Endpoints (v1.15)

#### Core Federation

- ✅ GET /_matrix/federation/v1/version - Server version information
- ✅ PUT /_matrix/federation/v1/send/{txnId} - Transaction processing for PDUs and EDUs
- ✅ GET /_matrix/federation/v1/event/{eventId} - Event retrieval
- ✅ GET /_matrix/federation/v1/state/{roomId} - Room state retrieval
- ✅ GET /_matrix/federation/v1/state_ids/{roomId} - Room state IDs retrieval
- ✅ GET /_matrix/federation/v1/backfill/{roomId} - Historical event backfilling
- ✅ POST /_matrix/federation/v1/get_missing_events/{roomId} - Missing event retrieval

#### Room Operations

- ✅ GET /_matrix/federation/v1/make_join/{roomId}/{userId} - Join room preparation
- ✅ PUT /_matrix/federation/v1/send_join/{roomId}/{eventId} - Join room completion
- ✅ GET /_matrix/federation/v1/make_knock/{roomId}/{userId} - Knock on room preparation
- ✅ PUT /_matrix/federation/v1/send_knock/{roomId}/{eventId} - Knock on room completion
- ✅ PUT /_matrix/federation/v1/invite/{roomId}/{eventId} - Room invitations
- ✅ GET /_matrix/federation/v1/make_leave/{roomId}/{userId} - Leave room preparation
- ✅ PUT /_matrix/federation/v1/send_leave/{roomId}/{eventId} - Leave room completion

#### Third-Party Invites

- ✅ PUT /_matrix/federation/v1/3pid/onbind - Third-party identifier binding
- ✅ PUT /_matrix/federation/v1/exchange_third_party_invite/{roomId} - Third-party invite exchange

#### Published Room Directory

- ✅ GET /_matrix/federation/v1/publicRooms - List published rooms
- ✅ POST /_matrix/federation/v1/publicRooms - Publish/unpublish rooms

#### Spaces

- ✅ GET /_matrix/federation/v1/hierarchy/{roomId} - Space hierarchy information

#### Device Management

- ✅ GET /_matrix/federation/v1/user/devices/{userId} - User device information
- ✅ POST /_matrix/federation/v1/user/keys/claim - Claim one-time keys
- ✅ POST /_matrix/federation/v1/user/keys/query - Query device keys

#### End-to-End Encryption

- ✅ m.signing_key_update EDU - Cross-signing key updates
- ✅ m.direct_to_device EDU - Send-to-device messaging
- ✅ m.device_list_update EDU - Device list updates

#### Ephemeral Data Units (EDUs)

- ✅ m.typing - Typing notifications
- ✅ m.presence - User presence updates
- ✅ m.receipt - Read receipts

#### Content Repository

- ✅ GET /_matrix/federation/v1/media/download/{mediaId} - Media content download
- ✅ GET /_matrix/federation/v1/media/thumbnail/{mediaId} - Media thumbnail generation

#### Query Endpoints

- ✅ GET /_matrix/federation/v1/query/directory - Room alias resolution
- ✅ GET /_matrix/federation/v1/query/profile - User profile information

### Implemented Matrix Client-Server API Endpoints (v1.15)

#### Authentication

- ✅ GET /_matrix/client/v3/login - Get available login flows
- ✅ POST /_matrix/client/v3/login - User authentication
- ✅ GET /_matrix/client/v3/login/fallback - Login fallback page

#### Server Capabilities

- ✅ GET /_matrix/client/v3/capabilities - Server capabilities and feature support

#### Account Data

- ✅ GET /_matrix/client/v3/user/{userId}/account_data/{type} - Get global account data
- ✅ PUT /_matrix/client/v3/user/{userId}/account_data/{type} - Set global account data
- ✅ GET /_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type} - Get room-specific account data
- ✅ PUT /_matrix/client/v3/user/{userId}/rooms/{roomId}/account_data/{type} - Set room-specific account data

#### Synchronization

- ✅ GET /_matrix/client/v3/sync - Client synchronization with account data support

#### Real-time Communication

- ✅ PUT /_matrix/client/v3/rooms/{roomId}/typing/{userId} - Send typing notifications
- ✅ WebSocket /ws/room/{roomId} - Real-time room communication

#### Server Administration

- ✅ GET /_matrix/client/v3/admin/server_version - Get server version information
- ✅ POST /_matrix/client/v3/admin/whois/{userId} - Get information about a user
- ✅ POST /_matrix/client/v3/admin/server_notice/{userId} - Send server notice to user
- ✅ GET /_matrix/client/v3/admin/registration_tokens - List registration tokens
- ✅ POST /_matrix/client/v3/admin/registration_tokens/new - Create registration token
- ✅ DELETE /_matrix/client/v3/admin/registration_tokens/{token} - Delete registration token
- ✅ POST /_matrix/client/v3/admin/deactivate/{userId} - Deactivate user account
- ✅ GET /_matrix/client/v3/admin/rooms/{roomId} - Get room information
- ✅ DELETE /_matrix/client/v3/admin/rooms/{roomId} - Delete room

#### Content Repository (Client-Server)

- ✅ POST /_matrix/media/v3/upload - Upload media content
- ✅ GET /_matrix/media/v3/download/{serverName}/{mediaId} - Download media content
- ✅ GET /_matrix/media/v3/thumbnail/{serverName}/{mediaId} - Get media thumbnail
- ✅ GET /_matrix/media/v3/config - Get media repository configuration
- ✅ GET /_matrix/media/v3/preview_url - Preview URL metadata

## Getting Started

1. Install Gradle if not already installed.

2. Run `gradle build` to compile the project.

3. Run `gradle run` to start the server.

The server runs on port 8080.

## Requirements

- Java 17 or higher
- Gradle 8.5 or higher

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
