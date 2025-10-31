# Federation Join Signature Verification Issue

## Summary
FERRETCANNON can successfully perform `make_join` requests to Synapse, but when sending the signed event via `send_join`, Synapse recomputes a different event hash and rejects the signature.

## Evidence

### Our Canonical JSON (verified correct with PowerShell SHA-256 test):
```json
{"auth_events":["$amxREh8Yxdn7Ajg1isWwiE8vThUUrMTMeAn9vK4iANA","$sQzYfaDpsQJg93gkc-0KBVzcKgBTjuzPUQ31aGFxEj4"],"content":{"membership":"join"},"depth":1399,"origin_server_ts":1759753025984,"prev_events":["$jtmwoL0SodCgNmjPwlPdhUVsAIA-elkCs_FI6VO78dQ"],"room_id":"!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw","sender":"@ferretcannon:ferretcannon.roflcopter.wtf","state_key":"@ferretcannon:ferretcannon.roflcopter.wtf","type":"m.room.member"}
```

### Our Computed Hash:
`x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ`

### Synapse's Recomputed Hash:
`prTT9VPTdvEsf_iDhai0hAYpwSgp5ixhhwE8aXMkf18` (and different every time!)

### Event Sent to Synapse:
```json
{"auth_events":["$amxREh8Yxdn7Ajg1isWwiE8vThUUrMTMeAn9vK4iANA","$sQzYfaDpsQJg93gkc-0KBVzcKgBTjuzPUQ31aGFxEj4"],"content":{"membership":"join"},"depth":1399,"hashes":{"sha256":"x8vNFczuKAWlLMO-F7XWzAZRCS0zlplC6l7HcxihZfQ"},"origin_server_ts":1759753025984,"prev_events":["$jtmwoL0SodCgNmjPwlPdhUVsAIA-elkCs_FI6VO78dQ"],"room_id":"!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw","sender":"@ferretcannon:ferretcannon.roflcopter.wtf","signatures":{"ferretcannon.roflcopter.wtf":{"ed25519:YOLO420-1759713455":"LRLJOpeqy3KcH62tFERcuol57nP1GvBCCZtLPDxIs_XaHsj35baWXj5_dg5ipQSYvHogmm2OLMSLLpGwMN6eDQ"}},"state_key":"@ferretcannon:ferretcannon.roflcopter.wtf","type":"m.room.member"}
```

## What We've Verified

✅ **Canonical JSON Generation**: Produces Matrix-compliant canonical JSON with sorted keys
✅ **Number Type Handling**: `depth` and `origin_server_ts` are correctly typed as `Long`  
✅ **Hash Computation**: SHA-256 of our canonical JSON produces the exact hash we expect (verified with independent PowerShell script)
✅ **Base64 Encoding**: Using URL-safe Base64 without padding
✅ **Event Structure**: Correct format for Room Version 12 (no event_id in body, hash-derived event ID)
✅ **Signature Computation**: Signs event without `hashes` field, with placeholder `signatures` structure
✅ **UTF-8 Encoding**: Verified byte-for-byte UTF-8 encoding is correct

## What's Different

❌ **Synapse computes a DIFFERENT hash** from the same event every single time
❌ **The hash difference causes signature verification to fail**

## Possible Causes

1. **JSON Parsing Differences**: When Synapse (Python) parses our JSON string, it might create a slightly different internal representation that produces different canonical JSON
   
2. **Character Encoding**: Some subtle UTF-8 encoding difference (though we verified this is correct)

3. **Number Serialization**: Python's JSON handling might serialize large integers differently (though `1759753025984` is well within safe range)

4. **Whitespace/Escaping**: Some difference in how strings are escaped or whitespace is handled (though canonical JSON should eliminate this)

5. **Nested Object Handling**: The `content` object `{"membership":"join"}` might be serialized differently

## What We Need

1. **Synapse Logs**: Access to the target Synapse server logs to see what canonical JSON it's computing
   
2. **Synapse Source Review**: Compare our implementation with Synapse's exact canonical JSON implementation

3. **Test with Another Server**: Try the same join against a different Matrix server to see if it's Synapse-specific

## Implementation Details

- **Server**: ferretcannon.roflcopter.wtf
- **Target**: matrix.geraghty.family:8448 (Synapse)
- **Room Version**: 12
- **Room ID**: `!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw`
- **Ed25519 Key ID**: `ed25519:YOLO420-1759713455`
- **Canonical JSON Implementation**: Custom Kotlin implementation following Matrix Spec v1.16
- **JSON Library**: kotlinx.serialization

## Next Steps

1. Get Synapse logs from geraghty.family to see what it's receiving/computing
2. Compare byte-for-byte what we send vs what Synapse receives
3. Test with Python Matrix SDK to verify our event structure
4. Review Synapse source code for canonical JSON implementation
5. Consider using a Matrix compliance test suite

## Files to Review

- `src/main/kotlin/utils/MatrixAuth.kt` - Canonical JSON and hashing logic
- `src/main/kotlin/routes/client-server/client/room/FederationJoinHandler.kt` - Join flow implementation
- `test-hash.ps1` - Independent hash verification script (proves our hash is correct)
