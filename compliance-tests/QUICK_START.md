# FERRETCANNON Matrix Compliance Test Suite Quick Start

Big shoutout to the FERRETCANNON massive for making this happen! 🎆

## What is This?

A comprehensive compliance test suite for verifying Matrix homeserver implementations against the Matrix Specification v1.16. These tests help ensure that FERRETCANNON (and potentially other Matrix servers) correctly implement:

- **Canonical JSON** generation (sorted keys, proper number formatting, UTF-8 encoding)
- **Event content hashing** (SHA-256 with proper field exclusion)
- **Event signing** (Ed25519 signatures with correct format)
- **Federation protocol** (make_join, send_join, and other server-server APIs)
- **Room version** support (v1-v12 with version-specific behaviors)

## Quick Start

### 1. Start the Server

```powershell
# Start FERRETCANNON in background
.\start-server.ps1 -NoPrompt
```

### 2. Run the Tests

```powershell
# Run all compliance tests
cd compliance-tests
.\run-compliance-tests.ps1

# Run specific test suite
.\run-compliance-tests.ps1 -Suite canonical-json

# Run with verbose output
.\run-compliance-tests.ps1 -Verbose

# Output results as JSON
.\run-compliance-tests.ps1 -OutputFormat json > results.json
```

### 3. Review Results

The test runner will display:
- ✅ PASS for tests that match expected behaviour
- ❌ FAIL for tests that don't match, with expected vs actual values
- Summary with pass rate and total counts

## Test Categories

### Canonical JSON Tests (`canonical-json.json`)
Verifies that JSON objects are serialised according to Matrix spec requirements:
- Keys sorted lexicographically
- Numbers formatted correctly (integers as unquoted numbers)
- No unnecessary whitespace
- Proper string escaping

**Example test case:**
```json
{
  "name": "Simple object with sorted keys",
  "input": {"z": 1, "a": 2, "m": 3},
  "expected": "{\"a\":2,\"m\":3,\"z\":1}"
}
```

### Event Hashing Tests (`event-hashing.json`)
Verifies that Matrix event content hashes are computed correctly:
- Removes `signatures`, `hashes`, and `unsigned` fields before hashing
- Uses canonical JSON representation
- Computes SHA-256 hash
- Encodes as Base64URL (unpadded)

**Example test case:**
```json
{
  "name": "Join event with all fields",
  "event": {
    "type": "m.room.member",
    "room_id": "!test:example.com",
    "sender": "@user:example.com",
    "content": {"membership": "join"}
  },
  "expected_hash": "COMPUTED_SHA256_BASE64URL"
}
```

### Event Signing Tests (`event-signing.json`)
Verifies Ed25519 signature generation and verification:
- Uses correct signing input (with hashes, without signatures)
- Proper Ed25519 signature format
- Base64URL encoding
- Server name and key ID inclusion

**Status:** Test vectors coming soon!

### Federation Protocol Tests (`federation-protocol.json`)
Verifies federation endpoint request/response formats:
- make_join endpoint (event template generation)
- send_join endpoint (joining remote rooms)
- Server key queries
- X-Matrix Authorization headers

**Status:** Documentation complete, test runner integration coming soon!

## Test Data Format

Test data files are JSON documents with this structure:

```json
{
  "description": "What this test suite verifies",
  "tests": [
    {
      "name": "Test case name",
      "input": { },
      "expected": "expected output"
    }
  ]
}
```

## How It Works

1. **Test Runner** (`run-compliance-tests.ps1`) reads test data files
2. **Test Endpoints** (`/_matrix/test/*`) expose FERRETCANNON's implementation
3. **Comparison** checks actual output against expected output
4. **Reporting** shows passes, failures, and detailed diffs

## Test Endpoints

FERRETCANNON exposes these test endpoints (only available locally):

- `POST /_matrix/test/canonical-json` - Canonicalise JSON input
- `POST /_matrix/test/compute-hash` - Compute event content hash
- `GET /_matrix/test/server-info` - Verify server is running

## Adding New Tests

1. Create or edit a test data file in `test-data/`
2. Add your test case following the existing format
3. Run the tests to verify
4. Commit the new test case

**Example:**
```json
{
  "name": "My new test",
  "input": {"test": "data"},
  "expected": "{\"test\":\"data\"}"
}
```

## Integration with CI/CD

Coming soon: GitHub Actions workflow to run compliance tests on every commit!

## Why This Matters

Matrix specification compliance is critical for federation to work correctly. When different implementations (FERRETCANNON, Synapse, Dendrite, etc.) compute different hashes or signatures from the same event, federation fails.

This test suite helps:
- ✅ **Verify implementation correctness** against known test vectors
- ✅ **Debug issues systematically** by isolating specific components
- ✅ **Prevent regressions** by running tests on every change
- ✅ **Document behaviour** with executable specifications
- ✅ **Help the Matrix community** by sharing reusable test cases

## Troubleshooting

### Server not running
```
⚠️  Warning: Could not connect to server at http://localhost:8080
```
**Solution:** Run `.\start-server.ps1 -NoPrompt` before running tests

### Test failures
Tests may fail if:
- FERRETCANNON implementation doesn't match Matrix spec
- Test expectations are incorrect (report this!)
- Test endpoint not implemented yet

Check the detailed output for expected vs actual values.

## Contributing

Found a bug? Have a test case that should be included? Contributions welcome!

Big shoutout to the FERRETCANNON massive - you're all legends! 🎆

## Resources

- [Matrix Specification v1.16](https://spec.matrix.org/v1.16/)
- [Canonical JSON (Section 3.4.2)](https://spec.matrix.org/v1.16/#canonical-json)
- [Event Hashing (Section 6.1.3)](https://spec.matrix.org/v1.16/#event-hashes)
- [Event Signing (Section 6.1.4)](https://spec.matrix.org/v1.16/#signing-events)
- [Server-Server API](https://spec.matrix.org/v1.16/server-server-api/)
