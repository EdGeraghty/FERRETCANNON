# Quick Start: Testing FERRETCANNON with Synapse Test Vectors

Big shoutout to the FERRETCANNON massive! This guide shows how to validate FERRETCANNON against Synapse's test suite. 🎆

## What This Is

This demonstrates **test vector extraction** from Synapse - the recommended approach for validating FERRETCANNON's compliance with Synapse's behaviour without requiring Python or Synapse's codebase.

## Prerequisites

1. **FERRETCANNON server running locally**:
   ```powershell
   .\start-server.ps1 -NoPrompt
   ```

2. **Test endpoints enabled** (they are by default in dev mode)

## Running Synapse Test Vectors

### Step 1: Run the Example Script

```powershell
cd compliance-tests
.\run-synapse-vectors.ps1
```

**Expected Output:**
```
=====================================================
 Synapse Test Vector Runner
 Testing FERRETCANNON against Synapse's test vectors
=====================================================

✅ Server is running

Loading Synapse test vectors...
✅ Loaded 2 test vectors from Synapse

---------------------------------------------------
Test: Sign minimal event
Description: Minimal event structure with only required fields

Step 1: Testing hash computation...
  ✅ Hash matches Synapse: A6Nco6sqoy18PPfPDVdYvoowfc0PVBk9g9OiyT3ncRM

Step 2: Testing signature generation...
  ⏳ Signature endpoint not yet implemented

⚠️  PARTIAL: Sign minimal event (hash OK, signature not tested)
```

### Step 2: Understand the Results

- ✅ **Hash matches** = FERRETCANNON computes event hashes identically to Synapse
- ⏳ **Signature not tested** = Signature endpoint needs implementation (see below)

## What's Being Tested

### Current Implementation ✅

- **Event Content Hashing**: FERRETCANNON computes SHA-256 hashes matching Synapse exactly
- **Canonical JSON**: Proper field ordering and formatting
- **Field Exclusion**: Correctly removes `signatures`, `hashes`, and `unsigned` fields

### Not Yet Implemented ⏳

- **Event Signing Endpoint**: `POST /_matrix/test/sign-event` endpoint for testing signature generation
- **Test Key Loading**: Ability to load Synapse's test signing key for deterministic testing

## Next Steps: Full Synapse Compatibility

### 1. Implement Signature Testing Endpoint

Add to `src/main/kotlin/routes/TestEndpoints.kt`:

```kotlin
/**
 * Test endpoint for event signature generation.
 * POST /_matrix/test/sign-event
 * 
 * Takes an event and signing key seed, returns computed signature
 * for validation against Synapse's test vectors.
 */
post("/sign-event") {
    try {
        val request = call.receive<JsonObject>()
        val event = request["event"]?.jsonObject 
            ?: throw IllegalArgumentException("Missing event field")
        val keyData = request["signing_key"]?.jsonObject
            ?: throw IllegalArgumentException("Missing signing_key field")
        
        // TODO: Implement Ed25519 signing with provided key
        // For now, return placeholder
        call.respond(mapOf(
            "error" to "Not yet implemented",
            "note" to "Need to implement Ed25519 signing with test keys"
        ))
    } catch (e: Exception) {
        logger.error("Signature test failed", e)
        call.respond(HttpStatusCode.BadRequest, mapOf(
            "error" to e.message
        ))
    }
}
```

### 2. Add Ed25519 Test Key Support

Synapse's test key:
- **Algorithm**: Ed25519
- **Version**: 1
- **Seed**: `YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1`

This needs to be:
1. Decoded from Base64
2. Used to generate Ed25519 signing key
3. Used to sign events deterministically

### 3. Re-run Tests

```powershell
.\run-synapse-vectors.ps1
```

**Expected after implementation:**
```
✅ Hash matches Synapse: A6Nco6sqoy18PPfPDVdYvoowfc0PVBk9g9OiyT3ncRM
✅ Signature matches Synapse: PBc48yDVszWB9TRa...
✅ PASS: Sign minimal event
```

## Adding More Test Vectors

### From Synapse's Test Suite

1. **Find test file**:
   ```
   https://github.com/element-hq/synapse/blob/develop/tests/crypto/test_event_signing.py
   ```

2. **Extract test data**:
   - Event structure
   - Expected hash (SHA-256 Base64url)
   - Expected signature (Ed25519 Base64)

3. **Add to `synapse-event-signing-vectors.json`**:
   ```json
   {
     "name": "New test case",
     "event": { ... },
     "expected_hash": { "sha256": "..." },
     "expected_signature": { "domain": { "ed25519:1": "..." } }
   }
   ```

4. **Run tests**: Script automatically picks up new test cases

### From Other Sources

- **Matrix Spec Examples**: Official specification includes test vectors
- **Dendrite Tests**: Dendrite (Go implementation) has additional test cases
- **Conduit Tests**: Conduit (Rust implementation) may have unique edge cases
- **Real Federation Events**: Capture events from production servers

## Comparison with Direct Integration

### ❌ What We're NOT Doing

Running Synapse's Python tests directly:
```python
# This won't work for FERRETCANNON
from synapse.crypto.event_signing import add_hashes_and_signatures
add_hashes_and_signatures(event_dict, ...)  # Calls Python functions!
```

### ✅ What We ARE Doing

Using test vectors (language-agnostic data):
```json
{
  "input": { "event_id": "$0:domain", ... },
  "expected_output": "A6Nco6sqoy18PPfPDVdYvoowfc0PVBk9g9OiyT3ncRM"
}
```

**Why this works:**
- No dependency on Python/Synapse codebase
- Tests actual behaviour, not implementation
- Easy to maintain and extend
- Validates spec compliance, not code compatibility

## Benefits of This Approach

1. **Validates Against Production Server**: If FERRETCANNON matches Synapse's outputs, federation will work
2. **Language Agnostic**: Test vectors are pure data, work with any language
3. **Maintainable**: Simple JSON files, no complex tooling
4. **Extensible**: Easy to add more test cases from any source
5. **Community Friendly**: Can share test vectors with other Matrix implementations

## Troubleshooting

### Test Vectors Not Loading

**Error**: `Cannot find path 'test-data\synapse-event-signing-vectors.json'`

**Solution**: Ensure you're running from `compliance-tests` directory:
```powershell
cd compliance-tests
.\run-synapse-vectors.ps1
```

### Server Not Running

**Error**: `❌ Server is not running!`

**Solution**: Start FERRETCANNON first:
```powershell
.\start-server.ps1 -NoPrompt
```

### Hash Mismatch

**Issue**: Hash doesn't match Synapse's expected value

**Debugging**:
1. Check canonical JSON generation (key ordering, number formatting)
2. Verify UTF-8 encoding
3. Ensure correct fields are excluded (signatures, hashes, unsigned)
4. Check Base64url encoding (unpadded, URL-safe alphabet)

### Signature Not Tested

**Issue**: `⏳ Signature endpoint not yet implemented`

**Solution**: This is expected! The signature testing endpoint needs to be implemented. See "Next Steps" above.

## More Information

- **[SYNAPSE_INTEGRATION.md](SYNAPSE_INTEGRATION.md)** - Comprehensive integration analysis
- **[QUICK_START.md](QUICK_START.md)** - General compliance testing guide
- **[STATUS.md](STATUS.md)** - Current implementation status
- **Matrix Spec**: https://spec.matrix.org/v1.16/

## Contributing

Found a mismatch between FERRETCANNON and Synapse? 

1. **Extract the test vector** from Synapse's test suite
2. **Add to test-data/** as a new JSON file or append to existing
3. **Document the issue** with expected vs actual output
4. **Fix FERRETCANNON's implementation** to match spec
5. **Verify fix** by running tests again

Big shoutout to the FERRETCANNON massive for making Matrix federation work correctly! 🎆
