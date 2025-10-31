# Synapse Test Suite Integration Plan

Big shoutout to the FERRETCANNON massive for considering Synapse's testing suite! Let's break down what hooking this up would look like. 🎆

## Executive Summary

**The Question:** Can we use Synapse's test suite (`@element-hq/synapse/tests`) directly against FERRETCANNON for 100% Synapse compliance?

**The Short Answer:** Not directly as-is, but we can extract valuable test vectors and create interoperability tests. Here's why and what we should do instead.

## Current State: FERRETCANNON Compliance Testing

### What We Already Have ✅

FERRETCANNON already has a working compliance test suite that's Kotlin/KTor native:

1. **Test Endpoints** (`src/main/kotlin/routes/TestEndpoints.kt`)
   - `POST /_matrix/test/canonical-json` - Canonicalise JSON
   - `POST /_matrix/test/compute-hash` - Event hash computation
   - Test endpoints expose FERRETCANNON's internal implementations for verification

2. **Test Runner** (`compliance-tests/run-compliance-tests.ps1`)
   - PowerShell-based test automation
   - Runs against live FERRETCANNON server
   - Compares actual vs expected outputs
   - Coloured output with pass/fail reporting

3. **Test Data** (`compliance-tests/test-data/`)
   - JSON-based test vectors
   - Canonical JSON tests (11 passing)
   - Event hashing tests (2 passing)
   - Easy to add new test cases

4. **Kotlin Unit Tests** (`test/kotlin/MatrixAuthHashTest.kt`)
   - JUnit tests for core functionality
   - Direct testing without HTTP overhead
   - Integrated with Gradle build

### Test Results

- ✅ **11/11 Canonical JSON tests passing**
- ✅ **2/2 Event hashing tests passing**
- ⏳ **Event signing tests** - Need test vectors with known keys
- 📋 **Federation tests** - Documentation complete, implementation pending

## Synapse Test Suite Structure

### What Synapse Has

Synapse's test suite is a comprehensive **Python/pytest-based** test infrastructure:

```
element-hq/synapse/tests/
├── crypto/
│   ├── test_event_signing.py    # Ed25519 signature tests
│   └── test_keyring.py           # Key verification tests
├── federation/                   # Federation protocol tests
├── rest/                         # Client-Server API tests
├── handlers/                     # Business logic tests
├── storage/                      # Database layer tests
├── unittest.py                   # Test utilities and base classes
└── server.py                     # Test server infrastructure
```

### Key Characteristics

1. **Python-Specific Infrastructure**
   - Uses Twisted async framework
   - Deep integration with Synapse's internal Python APIs
   - Mock objects and fixtures specific to Synapse's architecture
   - pytest test discovery and execution

2. **Unit Tests, Not Compliance Tests**
   - Tests Synapse's **internal implementation details**
   - Not designed to test other Matrix servers
   - Assumes access to Synapse's Python code and database
   - Tests business logic, not just API compliance

3. **Valuable Test Vectors**
   - Known Ed25519 keys with expected signatures
   - Canonical JSON examples with expected outputs
   - Event structures with known hashes
   - These are **extractable and reusable**

## Integration Approaches

### ❌ Approach 1: Direct Integration (Not Feasible)

**Idea:** Run Synapse's Python tests directly against FERRETCANNON

**Why It Won't Work:**
- Synapse tests import Synapse Python modules (`from synapse.crypto import ...`)
- Tests call internal Synapse methods, not HTTP endpoints
- Tests use Twisted-specific async patterns
- Tests access Synapse's PostgreSQL database directly
- Tests rely on Synapse-specific mock objects

**Example from `test_event_signing.py`:**
```python
from synapse.crypto.event_signing import add_hashes_and_signatures
from synapse.events import make_event_from_dict

# This calls Synapse's internal Python functions
add_hashes_and_signatures(
    RoomVersions.V1, event_dict, HOSTNAME, self.signing_key
)
```

This is **not** calling HTTP endpoints - it's calling Python functions inside Synapse's codebase. FERRETCANNON is Kotlin/KTor and has completely different internal APIs.

**Verdict:** ❌ Cannot use Synapse's test suite as-is.

### ✅ Approach 2: Extract Test Vectors (Recommended)

**Idea:** Mine Synapse's tests for **test vectors** (known inputs → expected outputs) and add them to FERRETCANNON's test suite

**What We Can Extract:**

1. **Ed25519 Test Vectors** from `tests/crypto/test_event_signing.py`:
   - Known signing key: `YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1`
   - Event structures with expected hashes
   - Expected signatures for specific events
   - **This is pure data** - language agnostic

2. **Canonical JSON Examples**:
   - Input objects and expected canonical output
   - Edge cases (Unicode, large numbers, nested structures)
   - Special character handling

3. **Federation Protocol Examples**:
   - make_join request/response pairs
   - send_join request/response pairs
   - X-Matrix authorization header examples

**Implementation Plan:**

1. **Create `compliance-tests/test-data/synapse-vectors.json`**:
   ```json
   {
     "description": "Test vectors extracted from Synapse",
     "source": "element-hq/synapse/tests/crypto/test_event_signing.py",
     "signing_key": "YJDBA9Xnr2sVqXD9Vj7XVUnmFZcZrlw8Md7kMW+3XA1",
     "tests": [
       {
         "name": "Minimal event signing",
         "event": {
           "event_id": "$0:domain",
           "origin_server_ts": 1000000,
           "type": "X",
           "signatures": {}
         },
         "expected_hash": "A6Nco6sqoy18PPfPDVdYvoowfc0PVBk9g9OiyT3ncRM",
         "expected_signature": "PBc48yDVszWB9TRaB/+CZC1B+pDAC10F8zll006j+NN..."
       }
     ]
   }
   ```

2. **Add Test Endpoint for Signing**:
   ```kotlin
   // In TestEndpoints.kt
   post("/_matrix/test/sign-event") {
       val event = call.receive<JsonObject>()
       val signingKey = MatrixAuth.parseSigningKey(...)
       val signature = MatrixAuth.signEvent(event, signingKey)
       call.respond(mapOf("signature" to signature))
   }
   ```

3. **Update Test Runner**:
   ```powershell
   # In run-compliance-tests.ps1
   function Test-SynapseVectors {
       $testData = Get-Content ".\test-data\synapse-vectors.json" | ConvertFrom-Json
       foreach ($test in $testData.tests) {
           # Test hash computation
           # Test signature generation
           # Compare against Synapse's expected values
       }
   }
   ```

**Benefits:**
- ✅ Language agnostic - just data
- ✅ Directly validates against Synapse's behaviour
- ✅ Easy to maintain and extend
- ✅ No dependency on Python/Twisted
- ✅ Fits existing FERRETCANNON test infrastructure

**Verdict:** ✅ **Recommended approach** - Extract test vectors from Synapse and add to FERRETCANNON's test suite.

### ⚠️ Approach 3: Sytest-Style Integration Tests (Complex but Thorough)

**Idea:** Create integration tests that run FERRETCANNON and Synapse side-by-side, testing interoperability

**What is Sytest?**
- Matrix's official homeserver compliance test suite
- Runs actual homeserver instances
- Tests via HTTP APIs (not internal code)
- Tests federation between servers
- Written in Perl (yes, really!)

**Implementation Approach:**

1. **Setup Script** (`compliance-tests/synapse-interop-test.ps1`):
   ```powershell
   # Start FERRETCANNON on port 8080
   Start-Process -FilePath ".\start-server.ps1" -ArgumentList "-NoPrompt"
   
   # Start Synapse in Docker on port 8008
   docker run -d -p 8008:8008 matrixdotorg/synapse:latest
   
   # Run interoperability tests
   Test-FederationJoin      # FERRETCANNON joins Synapse room
   Test-FederationInvite    # Synapse invites FERRETCANNON user
   Test-MessageDelivery     # Messages flow both directions
   ```

2. **Interop Tests**:
   - Test actual federation between FERRETCANNON ↔ Synapse
   - Verify events are accepted by both servers
   - Check that signatures verify on both sides
   - Test edge cases and error handling

**Benefits:**
- ✅ Tests real-world interoperability
- ✅ Catches integration issues
- ✅ Validates complete federation flow
- ✅ Similar to how Matrix.org tests implementations

**Drawbacks:**
- ⚠️ Complex setup (requires Docker, multiple servers)
- ⚠️ Slow to run (full server startup)
- ⚠️ Harder to debug failures
- ⚠️ More moving parts to maintain

**Verdict:** ⚠️ **Good long-term goal**, but start with test vector extraction first.

### 📋 Approach 4: Matrix Spec Compliance Suite (Future)

**The Real Goal:** Industry-wide Matrix specification compliance test suite

**Current State:**
- Matrix.org maintains **Sytest** (Perl-based, aging)
- Some discussion of creating modern compliance suite
- No official JSON test vector repository (yet!)

**What FERRETCANNON Could Do:**

1. **Publish Our Test Vectors**:
   - Share `compliance-tests/test-data/*.json` with Matrix community
   - Contribute to official test vector repository
   - Help other implementations (Dendrite, Conduit, etc.)

2. **Create Specification Test Tool**:
   - HTTP-only testing (no internal API access)
   - Language-agnostic test vectors
   - Easy for any implementation to adopt

3. **Become Reference Implementation** for compliance testing patterns

**Verdict:** 📋 **Long-term community contribution** - This is where the Matrix ecosystem should head.

## Recommended Implementation Plan

### Phase 1: Extract Synapse Test Vectors (1-2 days)

1. **Manual Extraction**:
   - Read `tests/crypto/test_event_signing.py`
   - Extract Ed25519 keys and expected signatures
   - Create `synapse-vectors.json` with test cases

2. **Add Test Endpoint**:
   - Implement `POST /_matrix/test/sign-event`
   - Use FERRETCANNON's existing `MatrixAuth.signEvent()`
   - Return signature in same format as Synapse

3. **Update Test Runner**:
   - Add `Test-EventSigning` function
   - Run tests against test vectors
   - Report pass/fail for each vector

4. **Validate**:
   - Run tests: `.\run-compliance-tests.ps1 -Suite event-signing`
   - Fix any failures
   - Document results

**Expected Outcome:** FERRETCANNON can sign events exactly like Synapse does.

### Phase 2: Federation Interoperability Tests (3-5 days)

1. **Setup Docker Synapse**:
   - Create `docker-compose.yml` for test Synapse instance
   - Configure federation between FERRETCANNON ↔ Synapse
   - Document setup process

2. **Create Interop Test Script**:
   ```powershell
   # compliance-tests/federation-interop-tests.ps1
   Test-MakeJoin             # FERRETCANNON → Synapse
   Test-SendJoin             # FERRETCANNON → Synapse
   Test-ReceiveJoin          # Synapse → FERRETCANNON
   Test-MessageDelivery      # Both directions
   ```

3. **Automated Testing**:
   - Script server startup/shutdown
   - Run tests in clean environment
   - Capture detailed logs

**Expected Outcome:** Verified federation compatibility with Synapse.

### Phase 3: Expand Test Coverage (Ongoing)

1. **More Test Vectors**:
   - Room versions (v1-v12)
   - State resolution edge cases
   - Auth rule scenarios
   - Redaction behaviour

2. **Additional Endpoints**:
   - Key query testing
   - Event authorization validation
   - State resolution testing

3. **Community Contribution**:
   - Share test vectors with Matrix community
   - Document patterns for other implementations
   - Contribute to spec clarity

## Technical Considerations

### Why Kotlin/KTor is Actually Better for This

1. **Type Safety**: Kotlin's type system catches issues at compile time
2. **Coroutines**: Native async/await, simpler than Twisted
3. **Null Safety**: Prevents whole classes of bugs
4. **Modern Language**: Better tooling and ecosystem than Python 2.x era code

### Differences from Synapse

| Aspect | Synapse | FERRETCANNON |
|--------|---------|--------------|
| Language | Python | Kotlin |
| Framework | Twisted | KTor |
| Database | PostgreSQL | SQLite (H2 compatible) |
| Testing | pytest | JUnit + PowerShell |
| Async | Twisted Deferreds | Kotlin Coroutines |

**Implication:** We can't run Synapse's tests directly, but we can verify against same test vectors.

### Compliance vs. Implementation Testing

**Synapse's tests are implementation tests:**
- Test internal Python methods
- Mock database calls
- Test business logic

**FERRETCANNON needs compliance tests:**
- Test HTTP API endpoints
- Verify Matrix spec adherence
- Test interoperability

**Different goals, different approaches.**

## Cost-Benefit Analysis

### Option 1: Extract Test Vectors
- **Effort:** Low (1-2 days)
- **Benefit:** High (immediate validation)
- **Maintenance:** Low (just data files)
- **Verdict:** ✅ **Do this first**

### Option 2: Interop Tests
- **Effort:** Medium (3-5 days)
- **Benefit:** High (real federation testing)
- **Maintenance:** Medium (Docker, scripts)
- **Verdict:** ✅ **Do this second**

### Option 3: Port Synapse Tests
- **Effort:** Very High (weeks/months)
- **Benefit:** Low (tests wrong thing)
- **Maintenance:** High (keep in sync with Synapse)
- **Verdict:** ❌ **Don't do this**

### Option 4: Community Compliance Suite
- **Effort:** High (coordination with Matrix.org)
- **Benefit:** Very High (whole ecosystem)
- **Maintenance:** Shared (community effort)
- **Verdict:** 📋 **Long-term goal**

## Concrete Next Steps

### Immediate (This Week)

1. **Extract 5-10 test vectors** from Synapse's `test_event_signing.py`
2. **Add to `synapse-vectors.json`** in FERRETCANNON
3. **Implement `POST /_matrix/test/sign-event`** endpoint
4. **Run tests** and document results

### Short Term (This Month)

5. **Extract more test vectors** (canonical JSON, hashing, etc.)
6. **Setup Docker Synapse** for interop testing
7. **Create federation interop tests** script
8. **Run and document** federation testing results

### Medium Term (This Quarter)

9. **Expand test coverage** to all room versions
10. **Test against Dendrite** and **Conduit** too
11. **Document testing patterns** for Matrix community
12. **Share test vectors** with Matrix.org

### Long Term (This Year)

13. **Propose unified compliance suite** to Matrix spec team
14. **Contribute test infrastructure** to ecosystem
15. **Help other implementations** adopt testing patterns
16. **Establish FERRETCANNON** as reference for testing practices

## Alternative: Use Complement Instead

**Complement** is Matrix.org's newer Go-based test suite for homeservers:

- GitHub: https://github.com/matrix-org/complement
- **Language:** Go
- **Approach:** Runs servers in Docker containers
- **Tests:** HTTP API endpoints (not internal code!)
- **Coverage:** Federation, client-server API, edge cases

**Pros:**
- ✅ Designed for testing different implementations
- ✅ Language agnostic (HTTP only)
- ✅ Actively maintained by Matrix.org
- ✅ Used to test Synapse, Dendrite, others

**Cons:**
- ⚠️ Requires Docker infrastructure
- ⚠️ Go-based runner (but tests are HTTP)
- ⚠️ More complex than simple test vectors

**Verdict:** ⚠️ Worth exploring, but **test vector extraction is still simpler** to start.

## Conclusion

**What hooking up Synapse's test suite looks like:**

### ❌ What We CAN'T Do:
- Run Synapse's Python tests directly against FERRETCANNON
- Import Synapse's test infrastructure
- Reuse Synapse's mock objects and fixtures

### ✅ What We CAN Do:
- Extract test vectors from Synapse's tests
- Validate FERRETCANNON produces same outputs as Synapse
- Test federation interoperability with Synapse
- Contribute to Matrix ecosystem testing patterns

### 🎯 Recommended Approach:

1. **Start simple:** Extract test vectors from Synapse
2. **Add to existing infrastructure:** Use FERRETCANNON's test suite
3. **Validate outputs:** Ensure same behaviour as Synapse
4. **Test federation:** Run interop tests with real Synapse instance
5. **Share knowledge:** Contribute to Matrix community

**Is it easy to plug in?** Not directly, but extracting test vectors is straightforward and gives us the validation we need.

**Will it give 100% Synapse compliance?** If we produce the same outputs for the same inputs as Synapse, we're spec-compliant. That's what matters for federation.

**Big shoutout to the FERRETCANNON massive for thinking about rigorous testing!** This is exactly the kind of quality engineering that makes a homeserver production-ready. 🎆

## Questions?

- **"Why not just run Sytest?"** - Sytest is Perl-based and aging. Test vector extraction is simpler and more maintainable.
- **"Why not use Complement?"** - Worth exploring! But test vectors are simpler to start with.
- **"Can we test against Dendrite/Conduit too?"** - Absolutely! Same approach works for any Matrix server.
- **"Who maintains this long-term?"** - FERRETCANNON maintains its own tests, but test vectors are shareable with whole community.

**Bottom line:** Extract test vectors from Synapse, add to FERRETCANNON's test suite, validate compatibility. That's the pragmatic path to Synapse compliance. 🚀
