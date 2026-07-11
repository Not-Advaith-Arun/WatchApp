# WatchApp protocol v1 inner payloads

Status: frozen interoperability contract. This document specifies the deterministic-CBOR plaintext encrypted inside the frame described by `ble-protocol.md`.

All maps use unsigned integer keys in ascending order, definite lengths, shortest integer encodings, UTF-8 NFC text, 16-byte RFC 4122 UUID byte strings, epoch milliseconds, and IEEE-754 binary64 values. Missing optional keys are omitted, never encoded as null. Receivers reject duplicate keys, wrong CBOR types, non-finite floats, invalid UTF-8, trailing bytes, and values outside the stated bounds.

## Commands

`COMMAND` is `{0: command tstr, 1: arguments map}`. The argument map is always present.

| Command | Arguments | Behavior |
|---|---|---|
| `start_measurement` | `0`: metric tstr | Opens the corresponding foreground spot flow. |
| `start_raw` | `0`: raw sensor kind tstr, `1`: duration seconds uint (1–300) | Opens an explicit recording flow. |
| `cancel_session` | `0`: session UUID bstr(16) | Cancels an active local session. |
| `request_status` | empty | Requests an immediate status event. |
| `unpair` | empty | Performs authenticated peer-key removal after local confirmation. |

Metric, source, quality, flag, and raw-kind strings use the enums in the JSON record schemas. Unknown commands or arguments return `unsupported_message`; arguments unknown to a recognized command are ignored only when negotiated minor-version rules permit them.

## Measurements and batches

A scalar measurement is:

| Key | Type | Field |
|---:|---|---|
| 0 | bstr(16) | measurement UUID |
| 1 | bstr(16) | device UUID |
| 2 | uint64 | sequence |
| 3 | tstr | metric type |
| 4 | binary64 | finite value |
| 5 | tstr | canonical unit |
| 6 | uint64 | observed epoch milliseconds |
| 7 | tstr | source |
| 8 | tstr | quality |
| 9 | array(tstr) | unique status flags |

`LIVE_VALUE` carries one measurement map. A measurement `DATA_FRAGMENT` carries an array of 1–256 measurement maps in ascending sequence order. This v1 wire layout is scalar-only; compound `workout_summary` and `body_composition` JSON records require a future additive payload layout and must not be sent using key 4.

## Synchronization

- Measurement `SYNC_REQUEST`: `{0: highestContiguousSequence uint64}`. Zero requests all retained measurements.
- Measurement `SYNC_ACK`: `{0: acceptedMeasurementIds array(bstr(16)), 1: highestContiguousSequence uint64}`. IDs are unique, at most 256, and the high-water mark advances only through records durably stored on iPhone.
- Raw `SYNC_OFFER`: the raw manifest map below.
- Raw missing `SYNC_REQUEST`: `{0: "raw_missing", 1: sessionId bstr(16), 2: missingIndexes array(uint)}`. Indexes are unique, ascending, and within the offered chunk count.
- Raw `SYNC_ACK`: `{0: "raw_ack", 1: sessionId bstr(16), 2: sessionSha256 tstr}`.

Protocol v1 does not currently define a measurement offer payload. After HELLO, the phone sends its sequence request and the watch streams available batches. Implementations must not invent a second measurement-offer shape under message type 10.

## Status

`STATUS` is `{0: batteryPercent uint?, 1: pendingMeasurements uint, 2: pendingRawSessions uint, 3: lastSuccessfulSyncEpochMs uint?}`. Battery is omitted when unavailable and otherwise 0–100. Last sync is omitted when none exists. Capability snapshots use `capability.schema.json` but are not embedded in the frozen minor-0 status payload.

## Raw sessions

Raw manifest (`SYNC_OFFER`):

`{0:"raw_manifest", 1:sessionId bstr(16), 2:deviceId bstr(16), 3:kind tstr, 4:startedMs uint, 5:endedMs uint, 6:samplingRateHz binary64, 7:channels array(tstr), 8:chunkCount uint, 9:sessionSha256 tstr}`.

Raw chunk (`DATA_FRAGMENT`):

`{0:"raw_chunk", 1:sessionId bstr(16), 2:chunkIndex uint, 3:uncompressedSha256 tstr, 4:deflateRawBytes bstr, 5:uncompressedLength uint}`.

After decompression, raw session content is deterministic CBOR `array(array(binary64))`: outer rows are samples, inner values follow manifest channel order. Every row has exactly the channel count. The digest is lowercase hexadecimal SHA-256 over those complete uncompressed CBOR bytes. Limits and retry rules remain those in `ble-protocol.md`.
