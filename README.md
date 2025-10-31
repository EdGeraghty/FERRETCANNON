# FERRETCANNON Matrix Server

[![Complement Tests](https://img.shields.io/github/actions/workflow/status/EdGeraghty/FERRETCANNON/complement-parallel.yml?branch=main&label=complement%20tests&logo=matrix)](https://github.com/EdGeraghty/FERRETCANNON/actions/workflows/complement-parallel.yml)

A Kotlin/KTor implementation of a Matrix homeserver focused on spec compliance and federation correctness.

## Complement Test Coverage

The following badges show the pass rate percentage for each Complement test suite in the parallel workflow:

| Test Suite | Pass Rate | Status |
|------------|-----------|--------|
| **Overall** | ![Overall](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-overall.json&query=$.message&label=complement%20tests&color=blue) | [![Run Tests](https://img.shields.io/github/actions/workflow/status/EdGeraghty/FERRETCANNON/complement-parallel.yml?label=run)](https://github.com/EdGeraghty/FERRETCANNON/actions/workflows/complement-parallel.yml) |
| Authentication | ![Auth](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-authentication.json&query=$.message&label=pass%20rate&color=blue) | Tests for login, registration, password changes |
| Rooms | ![Rooms](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-rooms.json&query=$.message&label=pass%20rate&color=blue) | Room operations, profiles, device management |
| Sync | ![Sync](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-sync.json&query=$.message&label=pass%20rate&color=blue) | Sync, presence, to-device, account data |
| Federation | ![Federation](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-federation.json&query=$.message&label=pass%20rate&color=blue) | Server-server federation tests |
| Media | ![Media](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-media.json&query=$.message&label=pass%20rate&color=blue) | Content upload, download, thumbnails |
| Keys & Crypto | ![Keys](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-keys.json&query=$.message&label=pass%20rate&color=blue) | E2E encryption, key uploads, backups |
| Knocking & Restricted | ![Knocking](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-knocking-restricted.json&query=$.message&label=pass%20rate&color=blue) | Knocking, restricted rooms, spaces |
| Relations & Threads | ![Relations](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-relations-threads.json&query=$.message&label=pass%20rate&color=blue) | Event relations, threads |
| Moderation | ![Moderation](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-moderation.json&query=$.message&label=pass%20rate&color=blue) | Ban, kick, invite, leave operations |
| Additional Features | ![Additional](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-additional.json&query=$.message&label=pass%20rate&color=blue) | Typing, filters, search, ACLs, receipts |
| Events & History | ![Events](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-events-history.json&query=$.message&label=pass%20rate&color=blue) | Event operations, history |
| Join & Membership | ![Join](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-join-membership.json&query=$.message&label=pass%20rate&color=blue) | Join operations, membership management |
| Edge Cases | ![Edge](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-edge-cases.json&query=$.message&label=pass%20rate&color=blue) | Edge cases, validation tests |
| MSC Experimental | ![MSC](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-msc-experimental.json&query=$.message&label=pass%20rate&color=blue) | Matrix Spec Change proposals |
| Miscellaneous | ![Misc](https://img.shields.io/badge/dynamic/json?url=https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/badge-miscellaneous.json&query=$.message&label=pass%20rate&color=blue) | Other tests |

> **Note**: Badges are updated automatically after each workflow run. The badge JSONs are stored in the `complement-badges` branch and updated by the workflow.

## Current Implementation Status

**Real Talk**: This is an active development project implementing the Matrix spec v1.16. Most core functionality is in place, but there are still rough edges, particularly around edge cases and some advanced features. This README reflects what's actually implemented, not aspirational goals.

### ✅ **IMPLEMENTED & WORKING** (Core Functionality)

#### **Authentication & Registration**
- ✅ `GET /_matrix/client/v3/login` - Returns supported login flows ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3login))
- ✅ `POST /_matrix/client/v3/login` - User authentication ([spec](https://spec.matrix.org/v1.16/client-server-api/#post_matrixclientv3login))
- ✅ `GET /_matrix/client/v3/register` - Returns supported registration flows ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3register))
- ✅ `POST /_matrix/client/v3/register` - User registration ([spec](https://spec.matrix.org/v1.16/client-server-api/#post_matrixclientv3register))
- ✅ `GET /_matrix/client/v3/register/available` - Username availability checking ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3registeravailable))

#### **User Management**
- ✅ `GET /_matrix/client/v3/devices` - List user devices ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3devices))
- ✅ `GET /_matrix/client/v3/profile/{userId}` - Get user profile ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3profileuserid))
- ✅ `PUT /_matrix/client/v3/profile/{userId}/displayname` - Set display name ([spec](https://spec.matrix.org/v1.16/client-server-api/#put_matrixclientv3profileuseridisplayname))
- ✅ `PUT /_matrix/client/v3/profile/{userId}/avatar_url` - Set avatar URL ([spec](https://spec.matrix.org/v1.16/client-server-api/#put_matrixclientv3profileuseridavatar_url))

#### **Room Operations**
- ✅ `POST /_matrix/client/v3/createRoom` - Create new room ([spec](https://spec.matrix.org/v1.16/client-server-api/#post_matrixclientv3createroom))
- ✅ `PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}` - Send messages/events ([spec](https://spec.matrix.org/v1.16/client-server-api/#put_matrixclientv3roomsroomidsendeventtypetxnid))
- ✅ `GET /_matrix/client/v3/sync` - Client synchronization ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3sync))
- ✅ `POST /_matrix/client/v3/rooms/{roomId}/join` - Join a room ([spec](https://spec.matrix.org/v1.16/client-server-api/#post_matrixclientv3roomsroomidjoin))
- ✅ `POST /_matrix/client/v3/rooms/{roomId}/leave` - Leave a room ([spec](https://spec.matrix.org/v1.16/client-server-api/#post_matrixclientv3roomsroomidleave))
- ✅ `GET /_matrix/client/v3/rooms/{roomId}/members` - Get room members ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3roomsroomidmembers))
- ✅ `GET /_matrix/client/v3/rooms/{roomId}/messages` - Room message history with pagination ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3roomsroomidmessages))
- ✅ `POST /_matrix/client/v3/rooms/{roomId}/read_markers` - Set read markers ([spec](https://spec.matrix.org/v1.16/client-server-api/#post_matrixclientv3roomsroomidread_markers))

#### **Push Notifications**
- ✅ `GET /_matrix/client/v3/pushrules/` - Get push rules ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3pushrules))

#### **Server Administration**
- ✅ `GET /_matrix/client/v3/server_version` - Server version information ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3adminserver_version))

#### **Capabilities**
- ✅ `GET /_matrix/client/v3/capabilities` - Server capabilities ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv3capabilities))

#### **Federation**
- ✅ `GET /_matrix/federation/v1/version` - Federation version info ([spec](https://spec.matrix.org/v1.16/server-server-api/#get_matrixfederationv1version))

#### **Well-Known**
- ✅ `GET /.well-known/matrix/client` - Client discovery ([spec](https://spec.matrix.org/v1.16/client-server-api/#get_well-knownmatrixclient))
- ✅ `GET /.well-known/matrix/server` - Server discovery ([spec](https://spec.matrix.org/v1.16/server-server-api/#get_well-knownmatrixserver))

### ⚠️ **KNOWN LIMITATIONS & ROUGH EDGES**

#### **Federation**
- ✅ Federation basics are working! Successfully federating with Synapse
- ✅ Event hash verification fixed and working correctly
- ✅ Invite flow, make_join, and send_join working per Matrix spec v1.16
- 🔄 Some advanced federation endpoints have placeholder implementations (third-party lookups, etc.)

#### **Content & Media**
- ✅ `POST /_matrix/media/v3/upload` - File upload works
- ✅ `GET /_matrix/media/v3/download/{serverName}/{mediaId}` - File download works
- ✅ `GET /_matrix/media/v3/thumbnail/{serverName}/{mediaId}` - Thumbnail generation works

#### **OAuth 2.0**
- ✅ OAuth 2.0 authorization flow implemented
- ⚠️ OAuth endpoints work but may not cover all edge cases

#### **Other Areas**
- 🔄 VoIP/TURN endpoints return configured server info but actual TURN integration is external
- ⚠️ Some advanced features like spaces, third-party lookups, and dehydrated devices are implemented but may have edge cases

### 📊 **REALISTIC COMPLIANCE ASSESSMENT**

**Estimated Spec Compliance: ~85-90%**

- ✅ **Core Authentication**: Solid (login, register, tokens, logout)
- ✅ **Basic Room Operations**: Working well (create, join, leave, send messages, sync)
- ✅ **User Profiles**: Working (display name, avatar, presence)
- ✅ **Device Management**: Working (list devices, device keys)
- ✅ **Federation**: Working! (Event signing, hash verification, make_join/send_join flows)
- ✅ **Discovery**: Working (well-known endpoints)
- ✅ **Content Upload/Download**: Working
- ✅ **Room Directory**: Working (alias resolution, room search)
- ⚠️ **Advanced Features**: Many implemented but need more testing
- 🔄 **Edge Cases**: Some rough edges remain

### 🎯 **CURRENT PRIORITIES**

1. ✅ **Priority 1**: Federation now working! Successfully joining rooms cross-server
2. 🧪 **Priority 2**: Continue testing edge cases and advanced features
3. 📝 **Priority 3**: Document known limitations and workarounds
4. 🔄 **Priority 4**: Replace remaining placeholder implementations
5. ✅ **Priority 5**: Improve test coverage

## Getting Started

### Quick Start

1. **Start the server** using the provided scripts:

   ```powershell
   # Windows PowerShell
   .\start-server.ps1
   
   # Or Windows Command Prompt
   start-server.bat
   
   # Or Linux/Mac
   ./start-server.sh
   ```

2. **VS Code Integration**: Use the pre-configured tasks:
   - Press `Ctrl+Shift+P` → "Tasks: Run Task" → "start-server"
   - Or use `Ctrl+Shift+B` to run the default build task

3. **Test the server**:

   ```powershell
   # Check if server is running
   curl http://localhost:8080
   
   # Or use the test scripts
   .\test-ed-message.ps1
   ```

### Manual Start

If you prefer to run manually:

1. Install Java 17 or higher and Gradle 8.5 or higher

2. Build the project:
   ```powershell
   gradle build
   ```

3. Run the server:
   ```powershell
   gradle run
   ```

The server runs on port 8080 by default (configurable in `config.yml`).

## Development Workflow

The project includes some conveniences to reduce friction during development:

### VS Code Settings

- Auto-save enabled (1 second delay)
- Reduced confirmation dialogs for common operations
- Pre-configured tasks for building and running

### Automated Scripts

- `start-server.ps1`: Stops old server processes and starts fresh
- `start-server.bat`: Windows batch version
- `start-server.sh`: Linux/Mac shell version
- Various test scripts (`test-*.ps1`) for debugging specific features

### VS Code Tasks

- `start-server`: Runs the server in background mode
- `build`: Compiles the project
- `clean`: Cleans build artifacts
- `run`: Basic server run task

### Typical Workflow

1. Open the project in VS Code
2. Press `Ctrl+Shift+P` → "Tasks: Run Task" → "start-server"
3. The server starts in the background
4. Make changes to code (auto-save handles saving)
5. Restart the server to pick up changes
6. Use test scripts to verify functionality

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

This implementation adheres quite well to the Matrix Server-Server API v1.16 specification. Core functionality is solid, with federation working correctly against Synapse. Some advanced features may have edge cases that need addressing.

### API Standards

- ✅ Server-server communication over HTTPS (configurable for production)
- ✅ Proper error responses for unsupported endpoints (404 M_UNRECOGNIZED)
- ✅ JSON content with UTF-8 encoding and application/json Content-Type
- ✅ Support for required HTTP methods and status codes

### Server Discovery

- ✅ `/.well-known/matrix/server` endpoint for server delegation
- ✅ `/_matrix/key/v2/server` endpoint for publishing server keys
- ✅ `/_matrix/key/v2/query` endpoint for querying keys from other servers
- ✅ Key signing and validation as per specification

### Authentication

- ✅ **Request Authentication**: Federation requests authenticated using X-Matrix authorization headers
  - Authorization header parsing and validation
  - Ed25519 signature verification for incoming requests
  - Public key fetching and caching from remote servers
  - Origin and destination validation
- ⚠️ **Response Authentication**: TLS server certificate authentication (HTTPS not yet enabled for production)
- ❌ **Client TLS Certificates**: Not implemented
- ✅ **Event Signing**: Event signing and signature validation working correctly
  - SHA-256 content hashing
  - Ed25519 signature generation and verification
  - Canonical JSON serialization
  - ✅ Hash verification working correctly (fixed October 2025)
- ✅ **Server ACLs**: Room-based server access control implemented

### Transactions

- ✅ **Transaction Processing**: Implementation of `PUT /_matrix/federation/v1/send/{txnId}`
  - Transaction ID validation
  - Origin server authentication
  - Transaction size limits
  - PDU and EDU processing
- ✅ **PDU Processing**: Persistent data unit handling
  - Server ACL validation per PDU
  - Individual PDU processing results
  - Transaction continues despite individual PDU failures
- ✅ **EDU Processing**: Ephemeral data unit support
  - Support for common EDU types (m.typing, m.presence, m.receipt, etc.)
  - Room-specific ACL checking
  - Timestamp-based tracking and cleanup
- ✅ **Transaction Response**: Specification-compliant response format
  - 200 OK for successful processing
  - Error handling for oversized/invalid transactions
  - PDU processing results in response body

## Compliance Testing

FERRETCANNON supports comprehensive Matrix Specification v1.16 compliance testing using the industry-standard Complement test suite.

### Official Complement Integration

**Complement** is the official Matrix compliance test suite - a black-box integration testing framework that validates homeserver implementations against the Matrix Specification. It's used by Synapse, Dendrite, Conduit, and other Matrix implementations.

#### Quick Start

```bash
# From the FERRETCANNON repository root
cd complement
./run-complement.sh
```

This will:
- ✅ Build the Complement Docker image
- ✅ Clone the Complement test suite
- ✅ Run all compliance tests

Note: The `TestInboundFederationKeys` Complement test has been validated locally and passes with the current
`complement-ferretcannon:latest` image. A unit test `ServerKeysTest` was also added to validate server key
signing and verification logic during CI/local development.

#### What Gets Tested

- ✅ **Client-Server API** - Registration, authentication, rooms, messages, profiles, devices
- ✅ **Server-Server API** - Federation, event distribution, state resolution, backfill
- ✅ **Security & Crypto** - Event signing, key exchange, content hashing
- ✅ **Room Versions** - Support for room versions v1-v12

#### Documentation

See [`complement/README.md`](complement/README.md) for full documentation and [`complement/QUICK_START.md`](complement/QUICK_START.md) for a 5-minute quick start guide.

### Internal Compliance Tests

FERRETCANNON also includes internal compliance tests for development and debugging:

```powershell
# Start the server
.\start-server.ps1

# Run internal compliance tests
cd compliance-tests
.\run-compliance-tests.ps1
```

These internal tests include:

- ✅ **Canonical JSON Tests** - Verifies JSON serialisation per Matrix spec
- ✅ **Event Hashing Tests** - Validates SHA-256 content hash computation
- 🔄 **Event Signing Tests** - Ed25519 signature generation and verification
- 📋 **Federation Protocol Tests** - Server-server API format documentation

See [`compliance-tests/QUICK_START.md`](compliance-tests/QUICK_START.md) for internal test documentation.

### Why This Matters

Compliance testing helps:

- ✅ Verify implementation correctness against Matrix specification
- ✅ Ensure interoperability with other Matrix implementations
- ✅ Debug federation issues systematically
- ✅ Prevent regressions with automated testing
- ✅ Document expected behaviour with executable specifications

Big shoutout to the Matrix.org team for creating Complement! 🎆

## License

[#YOLO Public License (YPL) v0.12.34-hunter.2](https://github.com/YOLOSecFW/YoloSec-Framework/blob/master/YOLO%20Public%20License)
