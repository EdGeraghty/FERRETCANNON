# Complement Quick Start Guide

Get up and running with Complement testing in 5 minutes! 🚀

Big shoutout to the FERRETCANNON massive! 🎆

## The Fastest Way

```bash
# From the FERRETCANNON repository root
cd complement
./run-complement.sh
```

That's it! The script will:
1. ✅ Check prerequisites (Docker, Go)
2. ✅ Build the Complement Docker image
3. ✅ Clone the Complement test suite
4. ✅ Run all compliance tests

## Run Specific Tests

```bash
# Test registration only
./run-complement.sh -t TestRegistration

# Test federation
./run-complement.sh -t TestFederation

# Test with longer timeout
./run-complement.sh -T 1h -t TestRoomVersions
```

## Manual Setup (If You Prefer)

### Step 1: Build Docker Image

```bash
cd /path/to/FERRETCANNON
docker build -t complement-ferretcannon -f Complement.Dockerfile .
```

### Step 2: Clone Complement

```bash
git clone https://github.com/matrix-org/complement.git
cd complement
```

### Step 3: Run Tests

```bash
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -v ./tests/...
```

## What Gets Tested?

Complement runs hundreds of tests covering:

✅ **Client-Server API**
- User registration and authentication
- Room creation and management
- Sending and receiving messages
- User profiles and presence
- Device management

✅ **Server-Server API (Federation)**
- Server discovery and verification
- Room joins across servers
- Event distribution and backfill
- State resolution

✅ **Security & Crypto**
- Event signing and verification
- Server key exchange
- Content hashing
- End-to-end encryption basics

✅ **Room Versions**
- Support for room versions v1-v12
- State resolution algorithms
- Event format validation

## Understanding Results

### Test Passed ✅

```
=== RUN   TestRegistration
--- PASS: TestRegistration (2.34s)
```

Your implementation is correct!

### Test Failed ❌

```
=== RUN   TestFederationJoin
    federation_test.go:45: Expected status 200, got 404
--- FAIL: TestFederationJoin (1.23s)
```

There's a bug to fix. Check the test output for details.

### Test Skipped ⏭️

```
=== RUN   TestAdvancedFeature
--- SKIP: TestAdvancedFeature (0.00s)
```

This test doesn't apply to your configuration.

## Common Issues

### "Docker image not found"

Build the image first:
```bash
docker build -t complement-ferretcannon -f Complement.Dockerfile .
```

### "Port 8008 already in use"

Stop any running Matrix servers:
```bash
docker ps  # Find the container ID
docker stop <container-id>
```

### "Go not found"

Install Go 1.21+:
```bash
# On Ubuntu/Debian
sudo apt install golang-go

# On macOS
brew install go

# Or download from https://go.dev/dl/
```

### Tests timeout

Increase timeout:
```bash
./run-complement.sh -T 1h
```

## Next Steps

1. **Run the full suite**: `./run-complement.sh`
2. **Fix any failures**: Check test output for error details
3. **Run specific tests**: Test individual features as you fix them
4. **Celebrate**: When all tests pass! 🎉

## Need Help?

- **Script options**: `./run-complement.sh --help`
- **Detailed docs**: See [README.md](README.md)
- **Troubleshooting**: See [README.md#troubleshooting](README.md#troubleshooting)

## Pro Tips

💡 **Run specific test packages**:
```bash
cd complement-checkout
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -v ./tests/csapi/...
```

💡 **See test names without running**:
```bash
cd complement-checkout
go test -list . ./tests/... | grep Test
```

💡 **Run with race detector**:
```bash
cd complement-checkout
COMPLEMENT_BASE_IMAGE=complement-ferretcannon:latest go test -race -v ./tests/...
```

💡 **Save test output**:
```bash
./run-complement.sh 2>&1 | tee test-results.log
```

---

**You're now ready to validate FERRETCANNON against the Matrix Specification!** 🚀

Big shoutout to the Matrix.org team for Complement and to the FERRETCANNON massive! 🎆
