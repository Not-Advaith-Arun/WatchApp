# Implementation status

This file tracks the contract-first prototype against the gates in `PLANNING.md`.

## Current milestone

Source implementation complete; physical integration and approval gates remain.

## Implemented

- Shared JSON/CBOR contracts, secure-frame and payload golden vectors, enrollment/security ADRs, cross-document validation, and interoperability test tooling.
- Wear OS OLED application, Room persistence and retry queue, passive/active Health Services, workouts, bounded raw sessions, encrypted GATT enrollment/sync, raw resume, and disabled-by-default ANCS laboratory.
- SwiftUI iOS application with onboarding, Today/Trends/Watch/Settings flows, protected persistence, encrypted Core Bluetooth enrollment and resumable sync, bounded raw-DEFLATE verification/export, and write-only HealthKit export.
- Android CI/local build configuration and a checked-in Gradle 9.4.1 wrapper.

## Remaining external validation

- Build/sign/run the iOS project on macOS with Xcode and a physical iPhone.
- Install the debug Wear APK on the initialized Watch6 Classic and run the hardware protocol matrix.
- Measure 24-hour battery impact and BLE/Doze/reboot recovery.
- Obtain Samsung SDK approval/AAR before enabling Galaxy-only sensor adapters; the default build reports those trackers unavailable.
- Keep ANCS disabled unless physical notification lifecycle/action tests pass.

## Environment constraints

- This Linux host has Java 25, Android API 36/build-tools, platform tools, and Gradle 9.4.1.
- The iOS target therefore requires later generation/build/signing on macOS with Xcode and a physical iPhone.
- Samsung Health Sensor SDK binaries and partnership authorization are not available and are intentionally not committed.

## Automated evidence

- Shared contract suite: 14/14 fixtures, cross-document invariants, and all deterministic protocol vectors pass.
- Wear OS: `testDebugUnitTest assembleDebug` passes with 18 unit tests and produces a debug APK.
- Static JSON/XML/plist parsing and `git diff --check` pass.
- iOS source and tests are present, but cannot be compiled on this non-macOS host.

## Gate status

| Gate | State | Exit evidence |
|---|---|---|
| G0 Toolchain/hardware | Partial | Wear debug build passes; signed iOS and physical-device runs remain |
| G1 Sensors/battery | Awaiting hardware | Sensor matrix and 24-hour battery report |
| G2 BLE transport | Awaiting hardware | Encrypted, resumable real-device sync report |
| G3 HealthKit | Awaiting macOS/iPhone | Physical-iPhone mapping and idempotency tests |
| G4 Notification Lab | Disabled pending hardware | ANCS lifecycle/action hardware report |

No gate is considered passed by mocks, simulator results, or source compilation alone.
