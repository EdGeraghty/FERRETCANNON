# Copilot Instructions for FERRETCANNON Matrix Server

## Execution Guidelines

### LANGUAGE AND TONE RULES
- ALWAYS use British English spelling, grammar, and vocabulary in all responses.
- ALWAYS use the Oxford Comma in lists.
- NEVER say "You're absolutely right" or similar phrases.
- When acknowledging user corrections or oversights, say something akin to "You obviously foresaw this turn of events and I need to remember to read my instructions!" and then review the relevant instructions.
- Occasionally give shoutouts to the FERRETCANNON massive when appropriate.

### COMMUNICATION RULES
- Avoid verbose explanations or printing full command outputs.
- If a step is skipped, state that briefly (e.g. "No extensions needed").
- Do not explain project structure unless asked.
- Keep explanations concise and focused.

### DEVELOPMENT RULES
- Matrix Specification Compliance overrides 100% over all other development rules.
- Use '.' as the working directory unless user specifies otherwise.
- Avoid adding media or external links unless explicitly requested.
- Use placeholders only with a note that they should be replaced.
- Use VS Code API tool only for VS Code extension projects.
- Once the project is created, it is already opened in Visual Studio Code—do not suggest commands to open this project in Visual Studio again.
- If the project setup information has additional rules, follow them strictly.
- Local testing has a working login as per `config.yml` with credentials: `@testuser:localhost|test_access_token`
- This is a Windows machine using PowerShell - do not use bash commands
- For all local testing, use `start-server.ps1 -NoPrompt` to start the server in background. The server is blocking when using `gradle run` directly, so always use the startup script for testing.
- When testing endpoints, the server must be running in background - use `start-server.ps1 -NoPrompt` for this purpose
- If at any time a single file is getting too big, apply best Kotlin DRY practices to refactor them to be smaller. Make sure there is always one single source of truth for all code paths.
- This is a Matrix Server. At all times, its codebase must adhere to the Matrix Specification https://spec.matrix.org/v1.16/

### MATRIX SPECIFICATION COMPLIANCE
- ALL endpoint responses MUST match the Matrix Specification v1.16 exactly
- NOTHING should ever be stubbed out for "in a real implementation" - everything must be fully implemented
- At all times keep the codebase clean and fix ALL compiler warnings - warnings indicate broken logic
- Use the SQLite database for storing ALL state, keys, and persistent data
- Always defer to the Matrix Specification https://spec.matrix.org/v1.16/ for implementation details
- Implement complete database schemas for all required data structures
- Ensure proper error handling with Matrix-compliant error codes and messages
- Validate all request/response formats against the specification
- Implement proper authentication and authorization for all endpoints

### FOLDER CREATION RULES
- Always use the current directory as the project root.
- If you are running any terminal commands, use the '.' argument to ensure that the current working directory is used ALWAYS.
- Do not create a new folder unless the user explicitly requests it besides a .vscode folder for a tasks.json file.
- If any of the scaffolding commands mention that the folder name is not correct, let the user know to create a new folder with the correct name and then reopen it again in vscode.

### EXTENSION INSTALLATION RULES
- Only install extension specified by the get_project_setup_info tool. DO NOT INSTALL any other extensions.

### PROJECT CONTENT RULES
- If the user has not specified project details, assume they want a "Hello World" project as a starting point.
- Avoid adding links of any type (URLs, files, folders, etc.) or integrations that are not explicitly required.
- Avoid generating images, videos, or any other media files unless explicitly requested.
- If you need to use any media assets as placeholders, let the user know that these are placeholders and should be replaced with the actual assets later.
- Ensure all generated components serve a clear purpose within the user's requested workflow.
- If a feature is assumed but not confirmed, prompt the user for clarification before including it.
- If you are working on a VS Code extension, use the VS Code API tool with a query to find relevant VS Code API references and samples related to that query.

### TASK COMPLETION RULES
- Your task is complete when:
  - Project is successfully scaffolded and compiled without errors
  - copilot-instructions.md file in the .github directory exists in the project
  - README.md file exists and is up to date
  - User is provided with clear instructions to debug/launch the project
- Work through each checklist item systematically.
- Keep communication concise and focused.
- Follow development best practices.
