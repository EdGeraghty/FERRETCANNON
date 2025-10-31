# FERRETCANNON Matrix Compliance Test Suite

This directory contains compliance tests to verify FERRETCANNON's implementation against the Matrix Specification v1.16.

Big shoutout to the FERRETCANNON massive for making this happen! 🎆

## Test Categories

1. **Canonical JSON Tests** - Verify our canonical JSON implementation matches the spec
2. **Event Hashing Tests** - Test event content hash computation
3. **Event Signing Tests** - Test Ed25519 signature generation and verification
4. **Federation Tests** - Test federation endpoints and protocols
5. **Room Version Tests** - Test different room version implementations

## Running Tests

### All Tests
```powershell
.\run-compliance-tests.ps1
```

### Specific Test Suite
```powershell
.\run-compliance-tests.ps1 -Suite canonical-json
.\run-compliance-tests.ps1 -Suite event-hashing
.\run-compliance-tests.ps1 -Suite event-signing
.\run-compliance-tests.ps1 -Suite federation
```

### Verbose Output
```powershell
.\run-compliance-tests.ps1 -Verbose
```

## Test Data

Test data is based on:
- Matrix Specification v1.16 examples
- Real-world events from production Matrix servers
- Edge cases and corner cases

## CI Integration

These tests can be run in CI/CD pipelines to ensure compliance on every commit.

## Synapse Test Suite Integration

Curious about using Synapse's test suite for compliance testing? See:
- **[SYNAPSE_INTEGRATION.md](SYNAPSE_INTEGRATION.md)** - Comprehensive analysis and implementation plan
- **[run-synapse-vectors.ps1](run-synapse-vectors.ps1)** - Example script for running Synapse test vectors
- **[test-data/synapse-event-signing-vectors.json](test-data/synapse-event-signing-vectors.json)** - Extracted test vectors from Synapse

Big shoutout to the Synapse team for their comprehensive test suite! 🎆

## Contributing

To add new compliance tests:
1. Add test data to the appropriate JSON file in `test-data/`
2. Update the test runner to include your new test
3. Document the test case and expected behavior
4. Consider extracting test vectors from other Matrix implementations (Synapse, Dendrite, Conduit)
5. Shoutout to the FERRETCANNON massive!
