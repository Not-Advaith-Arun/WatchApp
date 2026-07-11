# Platform interoperability audit — protocol v1

Audit date: 2026-07-11  
Reference: `ios/Sources/Infrastructure/ProtocolPayloads.swift` and current Wear sources. This is an audit only; platform code was not changed.

## Compatible pieces

- Both platforms use the same service/characteristic UUIDs, secure-frame integer keys, AES-GCM nonce construction, and six-byte fragment header.
- Both encode CBOR maps deterministically by ascending integer key.
- Swift's inner payload codec is now frozen in `contracts/payload-protocol-v1.md` and executable golden vectors.

## Resolved source-level mismatches

1. Wear now emits the frozen 10-key scalar layout with 16-byte UUID values inside an array batch.
2. Swift persists each batch before sending the frozen accepted-ID/high-water acknowledgement; Wear parses the acknowledgement and updates durable queue state.
3. Both sides implement the exact 151-byte `WAEN1`/`WAER1` exchange, X9.63 P-256 points, transcript SAS, directional HKDF keys, AES-GCM frames, replay counters, and fragment reassembly.
4. Wear implements measurement sequence requests, status, commands, raw manifests/chunks/missing requests/acknowledgements, and canonical raw rows. Swift performs bounded raw-DEFLATE inflation and verifies chunk/session SHA-256 before persistence and acknowledgement.
5. Fragment identifiers `0xA800...0xA8FF` are reserved to avoid ambiguity with a direct secure envelope.

## Additional risks

- Wear stores sequence in signed `Long`; protocol sequence is uint64. Define an exhaustion policy or unsigned representation before values exceed `Long.MAX_VALUE`.
- Swift's scalar model cannot represent compound workout/body-composition records defined by the JSON schema; these must remain unsent until an additive layout is implemented.
- Swift's status decoder currently ignores capabilities, consistent only with minor 0; UI capability discovery needs a separately versioned payload later.

## Remaining validation

Source-level layouts are converged and the Android test/build suite passes. The remaining work is empirical: build the Swift target on macOS, compare both platform implementations against the golden vectors in their native test runners, and execute enrollment, sync, raw resume, lock/reboot, Doze, and tamper scenarios on a physical iPhone and Watch6 Classic.
