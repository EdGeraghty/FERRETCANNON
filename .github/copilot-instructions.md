# Progress Tracking

- [x] Verify that the - [x] Implement Content Repository
	<!-- GET /_matrix/federation/v1/media/download/{mediaId} and GET /_matrix/federation/v1/media/thumbnail/{mediaId} endpoints implemented according to Matrix Server-Server API v1.15 specification with proper authentication, validation, and error handling. Project compiles successfully. -->opilot-instructions.md file in the .github directory is created.

- [x] Clarify Project Requirements

- [x] Scaffold the Project

- [x] Customize the Project
	<!-- Basic Matrix server setup with versions endpoint added. -->

- [x] Install Required Extensions

- [x] Compile the Project
	<!-- Dependencies added to build.gradle.kts. Project compiles successfully without errors. -->

- [x] Create and Run Task
	<!-- Created and ran gradle run task successfully. Server is running in background. -->

- [x] Launch the Project
	<!-- Server launched successfully and is running on port 8080. Ready for testing federation and client endpoints. -->

- [x] Ensure Documentation is Complete

- [x] Implement Third-Party Invites
	<!-- Third-party invite endpoints (/3pid/onbind and /exchange_third_party_invite) implemented with proper authentication, validation, and Matrix spec compliance. Project compiles successfully. -->

- [x] Implement Published Room Directory
	<!-- GET and POST /publicRooms endpoints implemented according to Matrix specification with proper authentication, pagination, and room information extraction. Project compiles successfully. -->

- [x] Implement Spaces
	<!-- GET /hierarchy/{roomId} and GET /query/directory endpoints implemented according to Matrix specification with space hierarchy support, room alias resolution, and proper error handling. Project compiles successfully. -->

- [x] Implement Typing Notifications
	<!-- Enhanced m.typing EDU processing with timestamp-based tracking, automatic cleanup of expired notifications (30 seconds), comprehensive validation, and broadcasting of current typing status to all room clients. Project compiles successfully. -->

- [x] Implement Presence
	<!-- Enhanced m.presence EDU processing with comprehensive presence state validation, status messages, activity tracking, and broadcasting of presence updates to all clients. Project compiles successfully. -->

- [x] Implement Device Management
	<!-- GET /user/devices/{userId}, POST /user/keys/claim, POST /user/keys/query endpoints implemented according to Matrix Server-Server API v1.15 specification with proper authentication, validation, and device list update EDU processing. Project compiles successfully. -->

- [x] Implement End-to-End Encryption
	<!-- Implemented m.signing_key_update and m.direct_to_device EDUs for cross-signing key updates and send-to-device messaging. Enhanced m.direct_to_device with message ID validation, wildcard device support (*), and improved compliance with Matrix Server-Server API v1.15 specification. Project compiles successfully. -->

- [x] Implement Server Access Control Lists (ACLs)
	<!-- Server ACL checking implemented according to Matrix Server-Server API v1.15 specification. ACL validation added to all required federation endpoints (/make_join, /send_join, /make_leave, /send_leave, /invite, /make_knock, /send_knock, /state, /state_ids, /backfill, /event_auth, /get_missing_events) and transaction processing for PDUs and EDUs. Project compiles successfully. -->

- [x] Implement Comprehensive Client Authentication
	<!-- Complete client authentication implementation including login/logout, token refresh, sync endpoint, account management (password change, deactivation), device management, and profile management endpoints according to Matrix Client-Server API v1.15 specification. Enhanced with multiple authentication flows (password, token, SSO, application service), login token generation, SSO redirect support, and server capabilities endpoint. All endpoints include proper authentication, validation, and Matrix-compliant error handling. Project compiles successfully. -->

- [x] Implement Access Token Usage
	<!-- Enhanced access token implementation according to Matrix Client-Server API v1.15 specification. Added comprehensive middleware supporting both query parameter and Authorization header token inclusion, proper token validation, Matrix-compliant error responses (M_MISSING_TOKEN, M_UNKNOWN_TOKEN), helper functions for consistent token handling, and a token validation endpoint for testing. Project compiles successfully. -->

- [x] Implement Account Registration
	<!-- Complete account registration implementation including user registration, username availability checking, email validation token requests, and phone number validation token requests according to Matrix Client-Server API v1.15 specification. All endpoints include proper validation, Matrix-compliant error handling, and support for User-Interactive Authentication (UIA) flows. Project compiles successfully. -->

- [x] Implement OAuth 2.0 API
	<!-- Complete OAuth 2.0 API implementation according to Matrix Client-Server API v1.15 specification. Includes OAuth 2.0 provider endpoints (/oauth2/authorize, /oauth2/token, /oauth2/userinfo, /oauth2/revoke, /oauth2/introspect), OAuth 2.0 client endpoints for UIA flows (/auth/{authType}/oauth2/*), server metadata (.well-known/oauth-authorization-server), JWKS endpoint, and OAuth 2.0 login flow support. All endpoints include proper validation, OAuth 2.0 compliant error handling, and Matrix spec compliance. Project compiles successfully. -->

- [x] Implement Capabilities Negotiation
	<!-- Enhanced GET /_matrix/client/v3/capabilities endpoint according to Matrix Client-Server API v1.15 specification. Includes all standard Matrix capabilities (m.change_password, m.room_versions, m.set_displayname, m.set_avatar_url, m.3pid_changes, m.get_login_token) with proper boolean and object structures. Project compiles successfully. -->

- [x] Implement Filtering
	<!-- Complete filtering system implementation according to Matrix Client-Server API v1.15 specification. Includes POST /user/{userId}/filter for creating filters, GET /user/{userId}/filter/{filterId} for retrieving filters, and filter support in /sync, /rooms/{roomId}/messages, /rooms/{roomId}/context/{eventId}, and /search endpoints. Supports EventFilter, RoomEventFilter, and RoomFilter structures with lazy-loading room members. Project compiles successfully. -->

- [x] Implement Device Management
	<!-- GET /user/devices, GET /user/devices/{deviceId}, PUT /user/devices/{deviceId}, DELETE /user/devices/{deviceId} endpoints implemented according to Matrix Client-Server API v1.15 specification with proper authentication, validation, and error handling. Project compiles successfully. -->

- [x] Implement Push Rules Management
	<!-- GET /pushrules/, GET /pushrules/global/{kind}/{ruleId}, PUT /pushrules/global/{kind}/{ruleId}, DELETE /pushrules/global/{kind}/{ruleId} endpoints implemented according to Matrix Client-Server API v1.15 specification with proper authentication, validation, and error handling. Project compiles successfully. -->

- [x] Implement Content Repository
	<!-- POST /upload, GET /download/{serverName}/{mediaId}, GET /thumbnail/{serverName}/{mediaId}, GET /config endpoints implemented according to Matrix Client-Server API v1.15 specification with proper authentication, validation, and error handling. Project compiles successfully. -->

- [x] Implement Server Administration
	<!-- GET /send_server_notice/{userId}, POST /send_server_notice/{userId}, GET /capabilities endpoints implemented according to Matrix Client-Server API v1.15 specification with proper authentication, validation, and error handling. Project compiles successfully. -->

- [x] Implement Matrix User Data
	<!-- Complete Matrix user data implementation according to Matrix Client-Server API v1.15 specification. Includes user profile management (GET/PUT /profile/{userId}, displayname, avatar_url), account data management (global and room-specific), user directory search, push notification management, third-party user lookups, OpenID token requests, user reporting, and device management endpoints. All endpoints include proper authentication, validation, and Matrix-compliant error handling. Project compiles successfully. -->

- [x] Implement Sync API
	<!-- Complete sync implementation according to Matrix Client-Server API v1.15 specification. Includes GET /sync endpoint with full state, incremental sync, room filtering, lazy-loading members, presence, account data, and proper since token handling. Project compiles successfully. -->

## Remaining Modules to Implement

- [x] Implement Receipts
	<!-- POST /rooms/{roomId}/receipt/{receiptType}/{eventId} endpoint for sending read receipts. Currently only handled in federation layer. -->

- [x] Implement Send-to-Device Messaging
	<!-- PUT /sendToDevice/{eventType}/{txnId} endpoint for sending direct messages to specific devices. Currently only handled in federation layer. -->

- [x] Implement Room History Visibility
	<!-- Client-side endpoints for GET/PUT room history visibility settings. Currently only checked in federation layer. -->

- [x] Implement Room Previews
	<!-- GET /events endpoint for room previews and related functionality for non-joined users. -->

- [x] Implement Client Config/Account Data
	<!-- GET/PUT /user/{userId}/account_data/{type} and /user/{userId}/rooms/{roomId}/account_data/{type} endpoints for storing custom client configuration. -->

- [x] Implement Event Context
	<!-- GET /rooms/{roomId}/context/{eventId} endpoint for retrieving events around a specific event. Partially implemented but may need completion. -->

- [x] Implement Direct Messaging
	<!-- Client-side support for direct messaging rooms (m.direct rooms). Currently only federation layer support. -->

- [x] Implement Rich Replies
	<!-- Enhanced rich reply support implemented according to Matrix Client-Server API v1.15 specification. Added proper validation for m.in_reply_to structure without rel_type, event ID format validation, and Matrix-compliant error handling. Project compiles successfully. -->

- [x] Implement VoIP/TURN Server
	<!-- GET /voip/turnServer endpoint for WebRTC TURN server credentials. Enhanced with comprehensive VoIP event validation for m.call.invite, m.call.candidates, m.call.answer, m.call.select_answer, m.call.negotiate, m.call.sdp_stream_metadata_changed, and m.call.hangup events in the send message endpoint according to Matrix Client-Server API v1.15 specification. Project compiles successfully. -->

- [x] Implement User Mentions
	<!-- Support for m.mentions in room messages for user and room mentions. -->

- [x] Implement Room Upgrades
	<!-- POST /rooms/{roomId}/upgrade endpoint for upgrading rooms to new room versions. -->

- [x] Implement Moderation Policy Lists
	<!-- Support for m.policy.rule.* events and related endpoints for moderation policies. -->

- [x] Implement Event Annotations/Reactions
	<!-- Support for m.annotation relation type for reactions to messages. -->

- [x] Implement Event Replacements
	<!-- Support for m.replace relation type for editing messages. -->

- [x] Implement Threading
	<!-- Support for m.thread relation type for threaded conversations. -->

- [x] Implement Sticker Messages
	<!-- Support for m.sticker message type for sending stickers. -->

- [x] Implement Third-party Networks
	<!-- GET /thirdparty/protocols, GET /thirdparty/protocol/{protocol}, GET /thirdparty/user/{protocol}, GET /thirdparty/location/{protocol} endpoints for third-party network integration. -->

- [x] Implement Read Markers
	<!-- POST /rooms/{roomId}/read_markers endpoint for setting read markers and fully read markers. -->

- [x] Implement Event Relations
	<!-- GET /rooms/{roomId}/relations/{eventId} and GET /rooms/{roomId}/relations/{eventId}/{relType} endpoints implemented according to Matrix Client-Server API v1.15 specification with proper authentication, validation, pagination, and error handling. Project compiles successfully. -->

- [x] Implement Event Redaction
	<!-- PUT /rooms/{roomId}/redact/{eventId}/{txnId} endpoint implemented according to Matrix Client-Server API v1.15 specification with proper authentication, validation, and error handling. Project compiles successfully. -->

## Execution Guidelines
PROGRESS TRACKING:
- If any tools are available to manage the above todo list, use it to track progress through this checklist.
- After completing each step, mark it complete and add a summary.
- Read current todo list status before starting each new step.

COMMUNICATION RULES:
- Avoid verbose explanations or printing full command outputs.
- If a step is skipped, state that briefly (e.g. "No extensions needed").
- Do not explain project structure unless asked.
- Keep explanations concise and focused.

DEVELOPMENT RULES:
- Use '.' as the working directory unless user specifies otherwise.
- Avoid adding media or external links unless explicitly requested.
- Use placeholders only with a note that they should be replaced.
- Use VS Code API tool only for VS Code extension projects.
- Once the project is created, it is already opened in Visual Studio Code—do not suggest commands to open this project in Visual Studio again.
- If the project setup information has additional rules, follow them strictly.
<!--Added by Ed-->
- This is a Matrix Server. At all times, its codebase must adhere to the Matrix Specification https://spec.matrix.org/v1.15/
<!--/Added by Ed-->

FOLDER CREATION RULES:
- Always use the current directory as the project root.
- If you are running any terminal commands, use the '.' argument to ensure that the current working directory is used ALWAYS.
- Do not create a new folder unless the user explicitly requests it besides a .vscode folder for a tasks.json file.
- If any of the scaffolding commands mention that the folder name is not correct, let the user know to create a new folder with the correct name and then reopen it again in vscode.

EXTENSION INSTALLATION RULES:
- Only install extension specified by the get_project_setup_info tool. DO NOT INSTALL any other extensions.

PROJECT CONTENT RULES:
- If the user has not specified project details, assume they want a "Hello World" project as a starting point.
- Avoid adding links of any type (URLs, files, folders, etc.) or integrations that are not explicitly required.
- Avoid generating images, videos, or any other media files unless explicitly requested.
- If you need to use any media assets as placeholders, let the user know that these are placeholders and should be replaced with the actual assets later.
- Ensure all generated components serve a clear purpose within the user's requested workflow.
- If a feature is assumed but not confirmed, prompt the user for clarification before including it.
- If you are working on a VS Code extension, use the VS Code API tool with a query to find relevant VS Code API references and samples related to that query.

TASK COMPLETION RULES:
- Your task is complete when:
  - Project is successfully scaffolded and compiled without errors
  - copilot-instructions.md file in the .github directory exists in the project
  - README.md file exists and is up to date
  - User is provided with clear instructions to debug/launch the project

Before starting a new task in the above plan, update progress in the plan.

- Work through each checklist item systematically.
- Keep communication concise and focused.
- Follow development best practices.
