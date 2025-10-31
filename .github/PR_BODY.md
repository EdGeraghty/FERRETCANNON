Branch: complement/homerunner-portbinding

Summary
-------
This branch contains Matrix Specification compliance improvements for federation endpoints, comprehensive unit tests, CI/CD enhancements, and diagnostic documentation for Complement testing.

What this branch contains
------------------------

### Source Code Changes
- **Federation v1 endpoints**: Fixed `get_missing_events` response format to correctly return `pdus` and `origin` fields (commit bf04631)
- **Federation v2 endpoints**: Enhanced auth chain endpoint with proper Matrix-spec-compliant responses (commit 19c5d39)
- **Federation user query**: Added profile query endpoint implementation (commit 19c5d39)
- **Client-server room routes**: Added state event handling and room state endpoints (commit 19c5d39)
- **MatrixAuth utilities**: Refactored authentication and authorisation logic for better spec compliance (commit 19c5d39)

### Test Coverage (merged from PR #10)
- `FederationProfileQueryTest.kt`: Unit tests for federation profile queries
- `FederationV2AuthChainTest.kt`: Comprehensive tests for auth chain endpoint
- `StateEventRoutesTest.kt`: Tests for client-server state event routes
- `MissingEventsTest.kt`: Unit tests for the missing events endpoint (A→B→C chain validation)

### CI/CD Improvements
- Updated `Complement.Dockerfile` to use Debian-slim builder, resolving TLS issues during Gradle dependency fetch (commit 2646d00)

### Documentation
- `COMPLEMENT_LOCAL_RUN.md`: Comprehensive guide for running Complement tests locally using Docker and WSL
- Diagnostic test log: `complement-test-output.txt` (captured output from Complement run for reference)

Context and Reasoning
---------------------
This branch evolved from an initial investigation into Complement/Homerunner port binding issues. During diagnostics, several Matrix Specification compliance gaps were identified and fixed. PR #10 was opened to deliver the test coverage for these fixes and was subsequently merged back into this branch.

The changes ensure:
- Proper federation endpoint responses matching Matrix Specification v1.16
- Complete test coverage for federation and client-server endpoints
- Improved CI/CD reliability for Complement testing
- Clear documentation for local Complement test workflows

Testing
-------
- All new unit tests pass locally
- Federation endpoints return spec-compliant responses
- Complement Docker image builds successfully with the updated Dockerfile

Signed-off-by: GitHub Copilot
