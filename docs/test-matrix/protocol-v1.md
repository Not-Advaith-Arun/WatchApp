# Protocol v1 test matrix

| Area | Automated contract test | Swift test | Kotlin test | Physical devices |
|---|---|---|---|---|
| JSON records/capabilities | valid, invalid, unknown fields, semantic invariants | Codable round trip | serialization round trip | — |
| Deterministic CBOR | golden header/payload | exact bytes | exact bytes | — |
| Enrollment | HKDF/SAS golden vector | CryptoKit derivation, bad P-256 point | JCA derivation, bad P-256 point | matching/mismatching SAS, timeout |
| Frame protection | nonce vector | AES-GCM/AAD/tamper/replay | AES-GCM/AAD/tamper/replay | bonded reconnect |
| Fragmentation | golden fragments | 23/185/517, expiry, malformed order | 23/185/517, expiry, malformed order | radio interruption |
| Durable sync | cross-fixture raw integrity | duplicate/conflict/resume/ack-after-save | queue retry/delete-after-ack | phone lock, watch reboot, out of range |
| Versions/rekey | schema limits | higher minor/major, counter loss | higher minor/major, counter loss | 30-day rekey simulation |
| Privacy | — | redacted diagnostics/key erasure | redacted diagnostics/key erasure | unpair/reset |

Release requires all applicable automated columns plus physical Watch6/iPhone rows. Notification Lab has a separate experimental gate and cannot waive any health-core result.
