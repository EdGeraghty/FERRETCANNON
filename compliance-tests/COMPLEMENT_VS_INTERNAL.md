# Complement vs Internal Tests - Understanding the Difference

This document explains the difference between FERRETCANNON's two testing approaches and when to use each.

Big shoutout to the FERRETCANNON massive! 🎆

## Two Testing Approaches

FERRETCANNON now supports **two complementary** testing approaches:

### 1. Complement (Official Matrix Test Suite)
**Location**: `complement/`  
**Type**: Black-box integration testing  
**Scope**: Full Matrix Specification v1.16 compliance

### 2. Internal Compliance Tests
**Location**: `compliance-tests/`  
**Type**: White-box unit/integration testing  
**Scope**: Specific components and debugging

## When to Use Each

### Use Complement When:

✅ **Validating full specification compliance**
- Testing against official Matrix test suite
- Verifying interoperability with other servers
- Preparing for production deployment
- Certifying compliance claims

✅ **Integration testing**
- Testing complete workflows end-to-end
- Verifying federation scenarios
- Testing real client interactions
- Running CI/CD checks

✅ **Comparing with other implementations**
- Synapse, Dendrite, and Conduit use the same tests
- Apples-to-apples comparison
- Industry standard benchmarking

### Use Internal Tests When:

✅ **Debugging specific components**
- Testing canonical JSON implementation
- Verifying event hashing logic
- Validating signature generation
- Testing internal utilities

✅ **Rapid iteration during development**
- No Docker build required
- Faster test execution
- Direct access to internal methods
- Custom test vectors

✅ **Testing edge cases**
- Specific Synapse interop issues
- Custom test scenarios
- Regression tests for known issues
- Performance testing

## Feature Comparison

| Feature | Complement | Internal Tests |
|---------|-----------|----------------|
| **Setup Time** | ~5 minutes (Docker build) | ~30 seconds |
| **Test Scope** | Full Matrix spec | Specific components |
| **Test Type** | Black-box | White-box |
| **Dependencies** | Docker, Go | Just FERRETCANNON |
| **Test Count** | 500+ tests | ~20 tests |
| **Coverage** | Client & Server APIs | Core utilities |
| **Official** | ✅ Matrix.org | ❌ FERRETCANNON only |
| **CI Ready** | ✅ Yes | ✅ Yes |
| **Custom Tests** | ❌ No | ✅ Yes |

## Typical Workflow

### Development Workflow

1. **During active development**:
   ```bash
   # Quick iteration with internal tests
   cd compliance-tests
   ./run-compliance-tests.ps1
   ```

2. **Before committing**:
   ```bash
   # Validate with Complement
   cd complement
   ./run-complement.sh -t TestRegistration
   ```

3. **Before merging PR**:
   ```bash
   # Full Complement suite (CI does this automatically)
   cd complement
   ./run-complement.sh
   ```

### Debugging Workflow

1. **Identify failure in Complement**:
   ```
   --- FAIL: TestCanonicalJSON (2.34s)
       canonical_json_test.go:45: Hash mismatch
   ```

2. **Create internal test to reproduce**:
   ```kotlin
   @Test
   fun `reproduce complement canonical json issue`() {
       val input = """{"z":1,"a":2}"""
       val expected = """{"a":2,"z":1}"""
       assertEquals(expected, MatrixAuth.canonicalizeJson(input))
   }
   ```

3. **Fix issue with fast iteration**:
   ```bash
   # Run internal test repeatedly while fixing
   gradle test --tests CanonicalJsonTest
   ```

4. **Verify fix with Complement**:
   ```bash
   cd complement
   ./run-complement.sh -t TestCanonicalJSON
   ```

## Test Coverage

### Complement Coverage

**Client-Server API** (100+ tests):
- Registration, login, logout
- Room operations (create, join, leave, invite)
- Messages and events
- User profiles
- Device management
- Presence and typing
- Read receipts
- Push notifications
- Content repository
- Account data

**Server-Server API** (100+ tests):
- Server discovery
- Key exchange
- Event signing
- make_join/send_join
- make_leave/send_leave
- Invites
- Backfill
- State resolution
- Missing events

**Security** (50+ tests):
- Event hashing
- Signature verification
- Key rotation
- Canonical JSON

**Room Versions** (50+ tests):
- v1-v12 support
- State resolution
- Event format

### Internal Test Coverage

**Canonical JSON** (11 tests):
- Key sorting
- Number formatting
- String escaping
- Nested objects
- Arrays
- Boolean and null handling

**Event Hashing** (4 tests):
- Join events
- Hash computation
- Field removal (signatures, hashes, unsigned)
- Base64URL encoding

**Event Signing** (Template ready):
- Signature generation
- Signature verification
- Key pair handling

**Federation Protocol** (Documentation):
- make_join format
- send_join format
- Server keys
- Authorization headers

## Migration Path

### If You're Currently Using Internal Tests

✅ **Keep using internal tests for**:
- Unit testing individual components
- Debugging specific issues
- Custom test scenarios
- Performance testing

✅ **Add Complement for**:
- Pre-release validation
- CI/CD integration
- Compliance certification
- Production readiness

### Recommended Setup

1. **Local development**: Internal tests for rapid iteration
2. **Pre-commit**: Quick Complement smoke tests
3. **CI/CD**: Full Complement suite on PRs
4. **Releases**: Complete Complement validation

## Command Reference

### Complement Commands

```bash
# Run all tests
cd complement && ./run-complement.sh

# Run specific category
cd complement && ./run-complement.sh -t TestCSAPI

# Run with timeout
cd complement && ./run-complement.sh -T 1h

# Skip rebuild (faster iteration)
cd complement && ./run-complement.sh -n -N
```

### Internal Test Commands

```bash
# Run all internal tests
cd compliance-tests && ./run-compliance-tests.ps1

# Run specific suite
cd compliance-tests && ./run-compliance-tests.ps1 -Suite canonical-json

# Verbose output
cd compliance-tests && ./run-compliance-tests.ps1 -Verbose
```

## CI/CD Integration

### Complement CI (GitHub Actions)

File: `.github/workflows/complement.yml`

Runs on:
- Push to main/develop
- Pull requests
- Manual trigger

### Internal Tests CI

You can add a similar workflow for internal tests:

```yaml
name: Internal Compliance Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - name: Run Tests
      run: |
        cd compliance-tests
        ./run-compliance-tests.ps1
```

## Best Practices

### ✅ Do

- Run internal tests frequently during development
- Run Complement before submitting PRs
- Keep both test suites passing
- Add internal tests for specific bug reproductions
- Use Complement for compliance claims

### ❌ Don't

- Rely only on internal tests for production readiness
- Skip Complement tests because they're slower
- Duplicate test cases between suites
- Modify Complement tests (they're official)
- Ignore failing Complement tests

## Future Plans

### Internal Tests

- Add more event signing test vectors
- Expand federation protocol tests
- Add performance benchmarks
- Create stress tests

### Complement

- Track pass rate over time
- Add custom Complement tests (if applicable)
- Integrate with test reporting tools
- Document FERRETCANNON-specific quirks

## Summary

Both testing approaches are valuable:

**Complement** = Industry standard, comprehensive, official validation  
**Internal Tests** = Fast iteration, custom scenarios, debugging

Use both for best results! 🎆

---

For questions or issues:
- Complement: See [complement/README.md](../complement/README.md)
- Internal Tests: See [QUICK_START.md](QUICK_START.md)

Big shoutout to the FERRETCANNON massive for comprehensive testing! 🎆
