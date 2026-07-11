# WatchApp BLE protocol v1

Status: implementation contract. Protocol major `1` is frozen; compatible additions increment the minor version.

## Roles and identifiers

The Watch6 is the BLE peripheral/GATT server and the iPhone is the central/GATT client. All characteristics require an encrypted, bonded link after enrollment. Advertising contains only the service UUID, protocol major, enrollment availability, and a rotating random nonce—never identity or health data.

| Item | UUID | Properties |
|---|---|---|
| WatchApp service | `8f6d0000-7a5b-4c31-9e21-6d4f57415443` | Primary service |
| Control | `8f6d0001-7a5b-4c31-9e21-6d4f57415443` | Write with response, indicate |
| Manifest | `8f6d0002-7a5b-4c31-9e21-6d4f57415443` | Write with response, indicate |
| Data | `8f6d0003-7a5b-4c31-9e21-6d4f57415443` | Write without response, notify |
| Live | `8f6d0004-7a5b-4c31-9e21-6d4f57415443` | Notify |
| Status | `8f6d0005-7a5b-4c31-9e21-6d4f57415443` | Read, notify |

The suffix encodes ASCII `mOWATC` only as a namespace marker; these UUIDs are private and must be used consistently by both clients.

## Encoding and envelope

Messages are deterministic CBOR (RFC 8949 §4.2) with definite lengths, shortest integer encodings, map keys in deterministic order, and no floats except payload measurement values. Text is UTF-8 NFC. UUIDs are 16-byte byte strings. UTC timestamps are signed integer epoch milliseconds. CBOR sequences are unsigned 64-bit integers; JSON fixtures represent them as decimal strings.

Every post-enrollment application frame is:

| Integer key | Name | Type | Meaning |
|---:|---|---|---|
| `0` | protocolMajor | uint | Must equal `1` |
| `1` | protocolMinor | uint | Sender-supported minor version |
| `2` | messageType | uint | From the table below |
| `3` | requestId | uint | Nonzero for request/response; response echoes it; zero for events |
| `4` | keyEpoch | uint32 | Current session-key generation |
| `5` | frameCounter | uint64 | Strictly increasing per sender and epoch |
| `6` | ciphertext | bstr | AES-256-GCM encrypted deterministic-CBOR payload |
| `7` | tag | bstr(16) | GCM authentication tag |

The canonical CBOR encoding of keys `0`–`5` is additional authenticated data. The 96-bit GCM nonce is `keyEpoch` (big-endian uint32) concatenated with `frameCounter` (big-endian uint64). Counters are persisted before use; a key is never reused after counter-state loss. Receivers reject a counter not greater than the persisted high-water mark. Frames are limited to 8 MiB after reassembly and 64 KiB for non-raw messages.

### Message types

| Value | Name | Characteristic | Direction |
|---:|---|---|---|
| `1` | HELLO | Control | Phone → watch |
| `2` | HELLO_RESULT | Control | Watch → phone |
| `3` | COMMAND | Control | Phone → watch |
| `4` | COMMAND_RESULT | Control | Watch → phone |
| `10` | SYNC_OFFER | Manifest | Watch → phone |
| `11` | SYNC_REQUEST | Manifest | Phone → watch |
| `12` | SYNC_ACK | Manifest | Phone → watch |
| `20` | DATA_FRAGMENT | Data | Either direction |
| `21` | LIVE_VALUE | Live | Watch → phone |
| `30` | STATUS | Status | Watch → phone |
| `40` | REKEY_REQUEST | Control | Either direction |
| `41` | REKEY_RESULT | Control | Peer → requester |
| `255` | ERROR | Same as request | Either direction |

Unknown message types are answered with `unsupported_message` when a request ID is present and otherwise ignored. A higher major ends sync with `incompatible_protocol`; a higher minor is accepted and unknown optional payload keys are ignored. Required additions need a new major.

## Enrollment and session keys

1. When explicitly placed in enrollment mode, the watch generates a non-exportable P-256 identity key and a fresh P-256 ephemeral key in Android Keystore, then advertises a 128-bit random enrollment nonce for five minutes.
2. The phone generates a Secure Enclave/Keychain-backed P-256 identity key plus an ephemeral P-256 key and writes an unencrypted enrollment hello containing protocol version, both phone public keys, the observed nonce, and a 128-bit phone nonce.
3. The watch indicates its identity and ephemeral public keys plus both nonces. Each peer validates P-256 points and derives `shared = ECDH(ephemeralLocal, ephemeralRemote)`.
4. HKDF-SHA256 uses `salt = SHA256(watchNonce || phoneNonce)` and `info = "WatchApp enrollment v1" || orderedIdentityPublicKeys` to derive 96 bytes: phone-to-watch AES key, watch-to-phone AES key, and rekey secret.
5. Both screens display `uint32(HMAC-SHA256(rekeySecret, "SAS" || transcriptHash)[0..3]) mod 1000000`, zero-padded to six digits. Neither side persists the relationship until the user confirms the match on both devices.
6. Confirmation binds peer identity keys, initializes epoch `1` and counters `0`, and requires OS-level BLE bonding. Cancellation/timeout destroys derived material. A changed identity key requires full re-enrollment.

Enrollment messages are the only plaintext application messages and are accepted only during the explicit five-minute enrollment window. Re-key uses fresh ephemeral ECDH authenticated by the current channel, increments the epoch, and resets counters. Rekey before `2^32` frames or 30 days, whichever occurs first.

## Payload contracts

Payload maps also use integer keys. Implementations maintain typed adapters rather than exposing raw maps to feature code.

- `HELLO`: supported minor range, phone instance ID, last contiguous measurement sequence, and feature bits.
- `HELLO_RESULT`: negotiated minor, watch device ID, current sequence, capability snapshot version, and server limits.
- `COMMAND`: command enum (`start_measurement`, `cancel_session`, `request_status`, `unpair`) plus typed arguments. Starting a restricted sensor only asks the watch to open its foreground flow.
- `COMMAND_RESULT`: result enum, optional session/request identifier, and typed error. It never reports medical conclusions.
- `SYNC_OFFER`: highest sequence, missing/deleted ranges still retained, measurement batch manifests, and raw-session manifests.
- `SYNC_REQUEST`: measurement UUIDs/ranges, raw session ID, and zero-based missing chunk indexes. Requests are bounded by the limits returned in HELLO_RESULT.
- `SYNC_ACK`: accepted measurement UUIDs, highest contiguous sequence, and accepted raw chunks/session digest. The watch marks data delivered only after this acknowledgement is authenticated and durably persisted on iPhone.
- `LIVE_VALUE`: metric, canonical value/unit, observed epoch milliseconds, and quality. It is lossy and never creates history.
- `STATUS`: negotiated protocol, connection/sync state, battery percentage when available, queue counts, capability snapshot, and typed attention reason.

Durable measurement payloads are the CBOR equivalents of `measurement.schema.json`. Raw manifests/chunks follow `raw-session.schema.json`. Component names remain text because they are versioned domain identifiers. `statusFlags` are transmitted as their string values in v1 for diagnosability.

## Fragmentation, flow control, and resume

GATT writes/notifications carry a 6-byte fragment header followed by frame bytes: message ID uint16, fragment index uint16, fragment count uint16, all big-endian. Fragment payload is at most `negotiatedATTMTU - 3 - 6`. Fragment count is 1–4096; all fragments for a message must agree and arrive in index order. A mismatch discards the assembly and returns `malformed_fragment` on a reliable characteristic.

Only DATA_FRAGMENT uses write-without-response/notify. The sender obeys CoreBluetooth/Android readiness callbacks and permits at most four unacknowledged complete data messages. Control, Manifest, and Status use indications or writes with response. An assembly expires after 30 seconds; durable content resumes by a new SYNC_REQUEST, never by assuming partial RAM survived.

On connection, peers exchange HELLO and then SYNC_OFFER/SYNC_REQUEST. Measurements are idempotent by `(deviceId,id)`; sequence locates gaps but does not replace identity. A conflicting duplicate ID is rejected as `record_conflict`. Raw chunks are idempotent by `(sessionId,chunkIndex)` and verified after raw DEFLATE decompression against length and SHA-256. After all chunks arrive, SHA-256 over uncompressed chunks in index order must equal `sessionSha256` before the session is committed and acknowledged.

## Errors and lifecycle

Errors contain stable code, retryable boolean, safe display category, and optional redacted detail. Codes are: `permission_denied`, `capability_unavailable`, `sdk_policy`, `off_body`, `poor_contact`, `busy`, `bluetooth_failure`, `incompatible_protocol`, `authentication_failed`, `replay_rejected`, `storage_failure`, `checksum_mismatch`, `malformed_message`, `malformed_fragment`, `record_conflict`, and `unknown`.

Authentication failures, replay, impossible lengths, or three malformed frames disconnect immediately without diagnostic payload details. Ordinary retryable errors keep the link. Confirmed unpair sends an authenticated command, erases peer keys and replay state on both sides, and returns to enrollment-only mode; local health records are retained unless separately erased by the user.

## Capability and privacy rules

Capabilities are runtime facts with state `available`, `permission_blocked`, `approval_blocked`, `temporarily_unavailable`, or `unsupported`, plus a snapshot version. Consumers never infer them from model name. Logs may include message type, byte count, duration, and redacted error code, but never payloads, identifiers, keys, notification content, or metric values.

## Conformance requirements

A v1 implementation must pass the JSON fixtures, deterministic-CBOR golden vectors once platform CBOR codecs are selected, enrollment transcript/SAS vectors, tamper and replay tests, MTU fragmentation at 23/185/517, duplicate/out-of-order sync, raw checksum/resume, version negotiation, key rotation, and counter-state-loss tests. The included Linux validator covers JSON contracts and raw fixture integrity; cryptographic/wire vectors are added before Gate 2 implementation freeze.
