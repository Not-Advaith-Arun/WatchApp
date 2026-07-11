# ADR 0001: Local-first BLE transport

Status: accepted  
Date: 2026-07-11

## Context

WatchApp must synchronize private health data between a Galaxy Watch6 and iPhone without a cloud account or Android relay. Wear OS Data Layer is unavailable to an iPhone and background execution is constrained on both platforms.

## Decision

The watch is a BLE GATT peripheral and durable source of truth until an authenticated iPhone acknowledgement. The phone is the central. Sync is pull/resume based, idempotent by stable record IDs, and independent of live lossy values. Both peers apply backpressure and persist content before acknowledgement.

Protocol v1 uses deterministic CBOR, explicit fragmentation, bounded messages, and runtime capability snapshots. BLE reconnect never implies delivery; only `SYNC_ACK` does.

## Consequences

- Offline capture and interrupted transfers recover without a server.
- iOS cannot guarantee immediate background sync; the UI must expose freshness.
- Real-device tests at MTU 23, 185, and 517 are release gates.
- A future cloud transport may reuse domain records but cannot silently change v1 delivery semantics.
