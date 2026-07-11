# WatchApp iOS

Native Swift 6 / iOS 18 companion with true-black SwiftUI screens, durable file-protected local storage, Core Bluetooth restoration/enrollment seams, HealthKit export, trends, measurements, raw-session export, and a gated Notification Lab.

## Build

Requires macOS, Xcode 16+, XcodeGen, an Apple development team, iPhone with iOS 18, and the Wear OS companion on a Watch6.

```sh
brew install xcodegen
xcodegen generate
open WatchApp.xcodeproj
```

Set `DEVELOPMENT_TEAM` for the WatchApp target, keep HealthKit enabled, and run on a physical iPhone for Bluetooth/HealthKit. Run tests with:

```sh
xcodebuild -scheme WatchApp -destination 'platform=iOS Simulator,name=iPhone 16' test
```

### Unsigned IPA without a local Mac

The private GitHub Actions workflow **Build unsigned iOS IPA** can be run manually
from the Actions tab. It compiles the physical-device application on a hosted macOS
runner and uploads `WatchApp-unsigned.ipa` as a seven-day artifact. The IPA must still
be signed for the target iPhone before installation; Sideloadly can perform that step.

Free Apple Account signing generally expires after seven days. Re-signing may omit
HealthKit if the provisioning profile does not authorize its entitlement, so verify
Apple Health export separately from the core Bluetooth and local-storage flows.

## Implementation notes

### Frozen v1 payload CBOR keys

All maps use unsigned integer keys in ascending deterministic order. Measurement: `0 id`, `1 deviceId`, `2 sequence`, `3 metricType`, `4 value`, `5 unit`, `6 observedAt epoch-ms`, `7 source`, `8 quality`, `9 statusFlags`. Status: `0 battery/null`, `1 pending measurements`, `2 pending raw sessions`, `3 last-sync epoch-ms/null`. Command: `0 command name`, `1 typed argument map`. Scalar sync request: `0 last contiguous sequence`; scalar ACK: `0 accepted UUID array`, `1 highest contiguous sequence`. Raw manifest: `0 "raw_manifest"`, `1 sessionId`, `2 deviceId`, `3 kind`, `4 start epoch-ms`, `5 end epoch-ms`, `6 sample rate`, `7 channels`, `8 chunk count`, `9 session SHA-256`. Raw chunk: `0 "raw_chunk"`, `1 sessionId`, `2 chunk index`, `3 uncompressed chunk SHA-256`, `4 raw-DEFLATE bytes`, `5 declared uncompressed length`. Raw missing request: `0 "raw_missing"`, `1 sessionId`, `2 missing-index array`. Raw ACK: `0 "raw_ack"`, `1 sessionId`, `2 session digest`.

- `ProtectedStore` is a native, actor-isolated JSON document store using atomic writes and iOS file protection. Records are idempotent by `(deviceID,id)` and conflicting duplicates fail closed.
- `CoreBluetoothTransport` performs service-filtered discovery/restoration, an explicit P-256 enrollment exchange, transcript-derived SAS confirmation, and encrypted command/sync writes plus authenticated notification decoding. `ProtocolSecurity` implements P-256 ECDH, HKDF-SHA256 key separation, deterministic minimal CBOR, AES-256-GCM nonce construction, authenticated headers, and replay rejection.
- Keychain enrollment records are device-only. Complete physical-device validation, bonded reconnect/session re-key, and durable counter persistence are required before distribution; reconnect currently performs a fresh authenticated enrollment.
- `HealthKitService` requests write-only authorization and uses stable sync identifiers so retry is idempotent. Percent values are converted to HealthKit fractions.
- Notification Lab deliberately exposes no universal reply UI. ANCS transport belongs to the watch and remains gated until physical-device testing passes.

## Hardware acceptance

Validate enrollment transcript, encrypted CBOR golden vectors, reconnect after lock/reboot/out-of-range, state restoration, sync duplicate/gap handling, Health authorization denial/retry, Dynamic Type/VoiceOver, and JSON/CSV export before distribution.
