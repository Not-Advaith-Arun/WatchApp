# ADR 0002: Enrollment and frame security

Status: accepted  
Date: 2026-07-11

## Context

BLE bonding alone does not provide application identity, durable replay protection, or a user-verifiable defense against pairing with the wrong nearby device.

## Decision

Enrollment is available only during an explicit five-minute watch flow. Peers exchange P-256 identity and ephemeral keys, derive directional AES-256-GCM keys with HKDF-SHA256, and require confirmation of a six-digit short authentication string on both screens. Identity keys live in Android Keystore and iOS Secure Enclave/Keychain facilities.

Post-enrollment frames authenticate their header, derive the 96-bit nonce from key epoch and persisted sender counter, and reject counters at or below the durable high-water mark. Rekey occurs within 30 days or before `2^32` frames. Counter-state loss invalidates the epoch; it never resets a counter with the same key.

## Consequences

- Enrollment transcript encoding and public-key ordering must be byte-identical across platforms.
- Authentication/replay failures disconnect without leaking health details.
- Key reset requires full re-enrollment. Unpair erases peer/key state but retains health records unless separately deleted.
- The golden vectors test encoding and derivation; platform tests must separately test P-256 point validation, Secure Enclave/Keystore behavior, AES-GCM tamper rejection, and key erasure.
