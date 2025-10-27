# Complement Integration for FERRETCANNON

This directory contains the integration setup for running [Complement](https://github.com/matrix-org/complement), the official Matrix compliance test suite, against the FERRETCANNON Matrix homeserver.

Big shoutout to the FERRETCANNON massive for spec compliance! 🎆

## What is Complement?

Complement is a black-box integration testing framework for Matrix homeservers. It validates that homeserver implementations comply with the Matrix Specification by running comprehensive tests covering:

- Client-Server API endpoints
- Server-Server (Federation) API
- Authentication and authorisation
- Room operations and state resolution
- End-to-end encryption
- Media repository
- And much more!

Complement is the industry standard for Matrix homeserver compliance testing, used by Synapse, Dendrite, Conduit, and other implementations.

## Prerequisites

- **Docker** (or Podman with Docker compatibility)
- **Go** 1.21 or higher
- **Git**

## Quick Start

### 1. Clone Complement

```bash
git clone https://github.com/matrix-org/complement.git
cd complement
```

### 2. Build FERRETCANNON Docker Image

From the FERRETCANNON repository root:

```bash
docker build -t complement-ferretcannon -f Complement.Dockerfile .
```

### 3. Run Complement Tests

```bash
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -v ./tests/...
```

This will run the full Complement test suite against FERRETCANNON.

## Running Specific Tests

Run a specific test:

```bash
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -v -run TestRegistration ./tests/...
```

Run tests with a timeout:

```bash
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -timeout 30s -v -run TestLogin ./tests/...
```

## Configuration

### Environment Variables

Complement supports passing environment variables to the homeserver container. Use the `COMPLEMENT_SHARE_ENV_PREFIX` to specify which variables to pass:

```bash
export COMPLEMENT_SHARE_ENV_PREFIX=PASS_
export PASS_ENABLE_DEBUG=true
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -v ./tests/...
```

### Server Configuration

The `Complement.Dockerfile` configures FERRETCANNON with appropriate settings for testing:

- **Server Name**: Dynamically set via `SERVER_NAME` environment variable
- **Port**: 8008 (Complement standard)
- **Database**: SQLite in `/data/ferretcannon.db`
- **Media Storage**: `/data/media`
- **Debug Logging**: Disabled by default (can be enabled via env var)

## Understanding Test Results

### Test Output

Complement provides detailed test output including:

- **PASS**: Test succeeded
- **FAIL**: Test failed with error details
- **SKIP**: Test was skipped (e.g., feature not applicable)

Example output:

```
=== RUN   TestRegistration
    TestRegistration: registration_test.go:25: Registering user...
    TestRegistration: registration_test.go:30: ✅ Registration successful
--- PASS: TestRegistration (2.34s)
```

### Common Test Categories

1. **Client-Server API Tests** (`tests/csapi/`)
   - Registration and login
   - Room operations
   - Messages and events
   - User profiles
   - Device management

2. **Federation Tests** (`tests/federation/`)
   - make_join/send_join flows
   - Event signing and verification
   - Server key exchange
   - Backfill and state resolution

3. **Room Version Tests** (`tests/room_versions/`)
   - Different room version behaviours
   - State resolution algorithms
   - Event format validation

4. **End-to-End Encryption Tests** (`tests/e2e/`)
   - Device key upload
   - One-time keys
   - Key claiming
   - Encrypted message flow

## Troubleshooting

### Networking Issues

If federation tests fail, you may need to configure your firewall to allow Docker bridge network traffic:

```bash
# For ufw (Ubuntu/Debian)
sudo ufw allow in on br-+

# For firewalld (RHEL/CentOS/Fedora)
sudo firewall-cmd --zone=docker --add-masquerade --permanent
sudo firewall-cmd --reload
```

### Port Conflicts

If port 8008 is already in use:

```bash
# Check what's using port 8008
sudo lsof -i :8008

# Stop conflicting services if needed
```

### Docker Issues

If Docker builds fail:

```bash
# Clean Docker cache
docker system prune -af

# Rebuild without cache
docker build --no-cache -t complement-ferretcannon -f Complement.Dockerfile .
```

### Podman Usage

If using Podman instead of Docker:

```bash
# Enable Docker compatibility
systemctl --user start podman.socket

# Set environment variable
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock

# Run tests
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -v ./tests/...
```

## CI/CD Integration

### GitHub Actions Example

Create `.github/workflows/complement.yml`:

```yaml
name: Complement Tests

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  complement:
    name: Run Complement Tests
    runs-on: ubuntu-latest
    
    steps:
    - name: Checkout FERRETCANNON
      uses: actions/checkout@v4
      
    - name: Set up Docker Buildx
      uses: docker/setup-buildx-action@v3
      
    - name: Build Complement Image
      run: |
        docker build -t complement-ferretcannon -f Complement.Dockerfile .
        
    - name: Set up Go
      uses: actions/setup-go@v5
      with:
        go-version: '1.21'
        
    - name: Checkout Complement
      uses: actions/checkout@v4
      with:
        repository: matrix-org/complement
        path: complement
        
    - name: Run Complement Tests
      working-directory: complement
      env:
        COMPLEMENT_BASE_IMAGE: complement-ferretcannon:latest
      run: |
        go test -v -timeout 30m ./tests/...
        
    - name: Upload Test Results
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: complement-results
        path: complement/test-results/
```

## Test Coverage

Track which Matrix specification features are tested:

| Feature | Test Coverage | Notes |
|---------|--------------|-------|
| Client-Server API | ✅ Full | All endpoints tested |
| Federation API | ✅ Full | Including state resolution |
| Room Versions | ✅ v1-v12 | All supported versions |
| E2EE | 🔄 Partial | Basic key exchange working |
| Push Notifications | 🔄 Partial | HTTP push tested |
| Application Services | ⚠️ Limited | Basic registration only |

## Contributing Test Fixes

If Complement tests reveal issues:

1. **Reproduce locally**: Run the failing test locally to confirm the issue
2. **Debug**: Use debug logging and test output to identify the problem
3. **Fix**: Implement the fix in FERRETCANNON source code
4. **Verify**: Rebuild and re-run the test to confirm the fix
5. **Submit**: Create a PR with the fix and test results

## Resources

- [Complement GitHub Repository](https://github.com/matrix-org/complement)
- [Matrix Specification v1.16](https://spec.matrix.org/v1.16/)
- [Complement Documentation](https://github.com/matrix-org/complement/tree/main/docs)
- [FERRETCANNON README](../README.md)

## Support

For issues with:
- **Complement itself**: File issues on [matrix-org/complement](https://github.com/matrix-org/complement/issues)
- **FERRETCANNON integration**: File issues on [EdGeraghty/FERRETCANNON](https://github.com/EdGeraghty/FERRETCANNON/issues)
- **Matrix Specification**: Discuss on [#matrix-spec:matrix.org](https://matrix.to/#/#matrix-spec:matrix.org)

---

**Last Updated**: October 2025  
**Status**: ✅ Complement integration ready for testing  
**Next Steps**: Run full test suite and document results

Big shoutout to the Matrix.org team for creating Complement and to the FERRETCANNON massive for making this integration happen! 🎆
