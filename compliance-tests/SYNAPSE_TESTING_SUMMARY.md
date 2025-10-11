# Synapse Test Suite Integration - Summary

## TL;DR

**Question:** Can we hook up Synapse's testing suite (@element-hq/synapse/tests) for 100% Synapse compliance against FERRETCANNON?

**Answer:** Not directly (Python vs Kotlin), but we can extract test vectors for validation. This document explains the approach.

## What's Been Created

### 1. Analysis Document (`SYNAPSE_INTEGRATION.md`)

Comprehensive 16KB analysis covering:
- ✅ Why direct integration isn't feasible (Python/Twisted vs Kotlin/KTor)
- ✅ Four integration approaches with pros/cons
- ✅ Recommended: Extract test vectors from Synapse
- ✅ Implementation phases and timelines
- ✅ Alternative: Complement test suite
- ✅ Cost-benefit analysis

**Key Insight:** Synapse's tests call internal Python methods, not HTTP endpoints. We need test vectors (input → expected output), not test infrastructure.

### 2. Test Vector Example (`test-data/synapse-event-signing-vectors.json`)

Extracted test vectors from Synapse's `test_event_signing.py`:
- Known Ed25519 signing key for deterministic testing
- 2 test cases with expected hashes and signatures
- Matches Synapse's test suite exactly

**Format:**
```json
{
  "event": { "type": "X", "origin_server_ts": 1000000, ... },
  "expected_hash": { "sha256": "A6Nco6sqoy18PPfPDVdYvoowfc0PVBk9g9OiyT3ncRM" },
  "expected_signature": { "domain": { "ed25519:1": "PBc48yDV..." } }
}
```

### 3. Test Runner Script (`run-synapse-vectors.ps1`)

PowerShell script that:
- Loads Synapse test vectors
- Calls FERRETCANNON's test endpoints
- Compares actual vs expected outputs
- Reports pass/fail for each test

**Usage:**
```powershell
.\start-server.ps1 -NoPrompt
cd compliance-tests
.\run-synapse-vectors.ps1
```

**Current Status:**
- ✅ Hash computation tests work
- ⏳ Signature tests pending (endpoint not yet implemented)

### 4. Quick Start Guide (`SYNAPSE_TESTING_GUIDE.md`)

Practical guide covering:
- How to run the tests
- What's being validated
- How to add more test vectors
- Troubleshooting common issues
- Next steps for full implementation

## The Recommended Approach

### Phase 1: Extract Test Vectors (Immediate)

1. **Mine Synapse's tests** for known input/output pairs
2. **Create JSON files** with test vectors
3. **Run against FERRETCANNON** via test endpoints
4. **Validate outputs match** Synapse exactly

**Benefit:** Validates spec compliance without Python dependency.

### Phase 2: Expand Coverage (Short-term)

5. **Extract more vectors** from all Synapse test files
6. **Add edge cases** (Unicode, large numbers, etc.)
7. **Test all room versions** (v1-v12)
8. **Document any differences** found

**Benefit:** Comprehensive validation against production server.

### Phase 3: Federation Testing (Medium-term)

9. **Setup Docker Synapse** for interop testing
10. **Test real federation** (make_join, send_join, etc.)
11. **Verify bidirectional** message delivery
12. **Document federation compatibility**

**Benefit:** Proves production-ready federation.

### Phase 4: Community Contribution (Long-term)

13. **Share test vectors** with Matrix community
14. **Propose unified compliance suite** to Matrix.org
15. **Help other implementations** adopt testing patterns
16. **Establish FERRETCANNON** as testing reference

**Benefit:** Improves entire Matrix ecosystem.

## Why This Works

### Test Vectors are Language-Agnostic

```json
Input:  {"z": 1, "a": 2}
Output: "{\"a\":2,\"z\":1}"
```

This works for:
- ✅ Python (Synapse)
- ✅ Kotlin (FERRETCANNON)
- ✅ Go (Dendrite, Complement)
- ✅ Rust (Conduit)
- ✅ Any implementation

### Validates Behaviour, Not Implementation

- ❌ Don't care: How Synapse's Python code works internally
- ✅ Do care: That FERRETCANNON produces same outputs as Synapse

If both produce identical hashes and signatures, federation works.

### Easy to Maintain

- Test vectors are just JSON files
- No Python/Twisted/pytest dependency
- Add new tests by editing JSON
- Share with other Matrix implementations

## What We CAN'T Do

### ❌ Run Synapse's Tests Directly

Synapse's tests do this:
```python
from synapse.crypto.event_signing import add_hashes_and_signatures
add_hashes_and_signatures(event, hostname, signing_key)
```

This is **calling Python functions**, not HTTP endpoints.

FERRETCANNON is Kotlin - it doesn't have these Python functions.

### ❌ Import Synapse's Test Infrastructure

- pytest fixtures
- Twisted async patterns
- Mock database objects
- Synapse-specific utilities

All of this is Python-specific and tightly coupled to Synapse's codebase.

## What We CAN Do

### ✅ Extract Known Test Vectors

From Synapse's tests, extract:
```
Input Event → Expected Hash → Expected Signature
```

Then validate FERRETCANNON produces same outputs.

### ✅ Test Federation Interoperability

Run Synapse and FERRETCANNON side-by-side:
- Test actual federation
- Verify events are accepted
- Check signatures validate
- Prove production readiness

### ✅ Contribute to Matrix Ecosystem

Share our test vectors and patterns:
- Help Dendrite, Conduit, others
- Improve Matrix specification
- Build unified compliance suite
- Make federation more reliable

## Current Status

### ✅ Implemented

- **Analysis complete**: `SYNAPSE_INTEGRATION.md` documents approach
- **Test vectors extracted**: 2 test cases from Synapse
- **Test runner created**: PowerShell script ready
- **Documentation complete**: Quick start guide available

### ⏳ Partially Implemented

- **Hash testing works**: FERRETCANNON's test endpoint operational
- **Signature testing pending**: Need to implement `POST /_matrix/test/sign-event`

### 📋 Next Steps

- **Extract more vectors**: Canonical JSON, various room versions
- **Implement signing endpoint**: Complete signature validation
- **Setup Docker Synapse**: For federation interop tests
- **Run full test suite**: Validate complete compliance

## Files Created

1. `compliance-tests/SYNAPSE_INTEGRATION.md` (16KB)
   - Comprehensive analysis and implementation plan

2. `compliance-tests/test-data/synapse-event-signing-vectors.json` (3KB)
   - Extracted test vectors from Synapse

3. `compliance-tests/run-synapse-vectors.ps1` (6KB)
   - Test runner script

4. `compliance-tests/SYNAPSE_TESTING_GUIDE.md` (7KB)
   - Quick start guide for users

5. `compliance-tests/SYNAPSE_TESTING_SUMMARY.md` (this file)
   - Executive summary

## How to Use This

### For Developers

1. Read `SYNAPSE_INTEGRATION.md` for full context
2. Review `synapse-event-signing-vectors.json` for example format
3. Extract more test vectors from Synapse's test suite
4. Add to test data files
5. Run `run-synapse-vectors.ps1` to validate

### For Users

1. Read `SYNAPSE_TESTING_GUIDE.md` for quick start
2. Start FERRETCANNON server
3. Run test script
4. Review results
5. Report any mismatches

### For Matrix Community

1. Review our test vector format
2. Consider adopting for your implementation
3. Share your test vectors back
4. Help build unified compliance suite

## Key Takeaways

1. **Can't use Synapse's Python tests directly** - Language and infrastructure mismatch
2. **Can extract test vectors** - Language-agnostic input/output pairs
3. **Test vectors validate spec compliance** - Same outputs = compatible
4. **Easy to maintain and extend** - Just edit JSON files
5. **Benefits entire Matrix ecosystem** - Shareable test cases

## Conclusion

**"What would hooking up Synapse's testing suite look like?"**

**Answer:** Extract test vectors from Synapse, validate FERRETCANNON produces identical outputs. This proves spec compliance without requiring Python/Twisted/Synapse codebase. Pragmatic, maintainable, and effective.

**Is it easy to plug in?** The test *vectors* are easy to extract and use. The test *infrastructure* isn't compatible (Python vs Kotlin).

**Will it work?** Yes! Test vectors validate behaviour, which is what matters for federation. If FERRETCANNON matches Synapse's outputs, federation works.

Big shoutout to the FERRETCANNON massive for thinking about rigorous compliance testing! 🎆

---

**Questions? See:**
- `SYNAPSE_INTEGRATION.md` for detailed analysis
- `SYNAPSE_TESTING_GUIDE.md` for practical guide
- `README.md` for overview of entire test suite
