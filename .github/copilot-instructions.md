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
