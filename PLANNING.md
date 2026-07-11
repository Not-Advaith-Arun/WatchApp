# WatchApp — Product and Engineering Plan

Status: implementation-ready planning baseline  
Prepared: 11 July 2026  
Target hardware: Galaxy Watch6 Classic Bluetooth, already initialized with Android  
Target audience: personal prototype in India/global regions

## 1. Executive summary

WatchApp will be a privacy-first, local-first companion system consisting of:

- A native Wear OS application on the Galaxy Watch6 Classic that captures supported health and fitness measurements.
- A native SwiftUI iPhone application with a restrained true-black OLED interface for current values, history, trends, recording sessions, device health, and Apple Health export.
- A versioned Bluetooth Low Energy protocol for enrollment, durable sample synchronization, live measurements, commands, and diagnostics.
- An isolated Notification Lab that investigates iPhone notification viewing and limited system-provided actions using ANCS. It must remain feature-flagged until it passes real-hardware reliability tests.

The dependable product promise is: **capture supported Watch6 measurements, present them beautifully on iPhone, retain them locally, and export compatible records to Apple Health.**

The following are explicitly not promised:

- Official Watch6 pairing, firmware updates, backups, calls, contacts, or Galaxy Wearable configuration from iPhone.
- Samsung Health history, Samsung sleep staging, Energy Score, stress interpretation, or other proprietary Samsung analytics.
- Universal typed replies to WhatsApp, iMessage, Instagram, Gmail, or arbitrary third-party notifications outside Apple’s eligible rich-accessory framework.
- Medical diagnosis, clinical alerts, or treatment recommendations.

## 2. Decisions and success criteria

### Locked decisions

| Decision | Selected direction |
|---|---|
| Release | Personal prototype first |
| Region | India/global |
| Phone dependency | No ongoing Android relay |
| Watch | Bluetooth Watch6 Classic, already initialized |
| Durable transport | Local-first BLE |
| Cloud/backend | Excluded from initial build |
| Apple Health | Export WatchApp data; do not merge external HealthKit sources into the dashboard |
| Sensor scope | Discover and expose every tracker actually supported by the Watch6 hardware, Health Platform, and granted permissions |
| Raw signals | User-started, time-bounded local sessions; never continuous background archival |
| Notifications | Experimental lab, independently gated from the health product |
| Minimum iOS | iOS 18 |
| UI technologies | SwiftUI and Compose for Wear OS |

### Acceptance outcomes

The prototype is successful when:

1. A real Watch6 Classic records passive heart rate and steps, survives offline periods, and synchronizes without gaps or duplicates when the iPhone returns.
2. The iOS dashboard presents current, stale, unavailable, and unsupported states honestly, including source and last-observed time.
3. Explicit measurement sessions for supported SpO2, skin temperature, body composition, ECG, PPG, and accelerometer data complete or return actionable capability errors.
4. Compatible records export idempotently to Apple Health after granular user authorization. ECG and raw PPG remain inside WatchApp because HealthKit ECG samples are read-only for third-party apps.
5. Default passive operation adds no more than 10% to a measured 24-hour Watch6 battery drain baseline. Active raw sessions are separately reported.
6. BLE reconnection and sync recover after phone lock, app suspension, watch reboot, radio interruption, and out-of-range periods without corrupting local history.
7. Notification functionality can be completely disabled without affecting health capture, sync, dashboard, or HealthKit export.

## 3. Feasibility and product boundaries

### Supported core

- Wear Health Services provides passive heart rate and steps, spot/live heart rate, and workout metrics. Passive delivery is power-efficient and may be batched rather than real-time.
- Samsung Health Sensor SDK provides runtime-discovered Galaxy trackers. On Watch6 this may include continuous heart rate/IBI, accelerometer, PPG, and skin temperature, plus on-demand BIA, ECG, PPG, skin temperature, and SpO2.
- Public distribution of Samsung sensor features requires Samsung partnership approval and package/signature registration. Personal development uses Health Platform developer mode only.
- Core Bluetooth supports an iPhone central connecting to a custom GATT service on the watch, with background event delivery and state restoration. iOS still does not permit unlimited background execution.
- HealthKit accepts appropriately mapped quantity/workout samples with per-type permission and sync metadata.

### Conditional or experimental

- Custom GATT advertising while the watch is backgrounded or in Doze must be proven on the exact Watch6 firmware.
- ANCS notification viewing requires the watch to act as an authorized GATT client to the iPhone’s service. Pairing, simultaneous BLE roles, reconnection, battery use, and action semantics need a hardware spike.
- Classic ANCS exposes only iOS-selected positive/negative actions. The UI must display the action title received from the platform and never infer that an action means “Reply.”
- Apple’s richer Accessory Notifications framework supports text responses but is customer-available only for eligible EU devices/accounts and requires restricted accessory entitlements. It is excluded from this India/global prototype.

### Unsupported

- Wear OS Data Layer communication with iPhone.
- Reading the Android-only Samsung Health Data SDK store without an Android relay.
- Writing Galaxy ECG waveforms to HealthKit; `HKElectrocardiogram` is read-only.
- Continuous SpO2/BIA/ECG capture where Samsung specifies an on-demand foreground tracker.

## 4. Complete user experience

### 4.1 iPhone onboarding

1. Launch into a black wordmark screen, then a concise compatibility disclosure.
2. Confirm that the watch is already initialized and can install a Wear OS application.
3. Explain the local-only architecture: data remains on the watch, iPhone, and optionally Apple Health.
4. Request Bluetooth only when the user taps **Pair Watch**.
5. Scan only for the WatchApp service UUID. Show model, watch-generated alias, signal strength, and a six-digit verification code on both devices.
6. The user confirms matching codes. Both devices persist device identities and session keys.
7. Run capability discovery and present available, unavailable, permission-blocked, and approval-blocked sensors.
8. Ask for watch sensor/activity permissions contextually on the watch, not as a wall of prompts on first launch.
9. Offer Apple Health export after the first successful synchronized measurement. Denial never blocks WatchApp.
10. Land on Today with a real value, a syncing state, or an explicit no-data explanation—never placeholder health values.

### 4.2 Navigation

Use four bottom destinations:

- **Today:** current state, latest metrics, daily movement, and watch/sync status.
- **Trends:** metric picker, 1D/7D/30D ranges, charts, sessions, and export status.
- **Watch:** device state, battery if available, capabilities, measurements, recordings, sync queue, and Notification Lab.
- **Settings:** Apple Health, units, retention, exports, privacy, diagnostics, and reset/unpair.

### 4.3 Today

- A compact connection capsule shows `Live`, `Nearby`, `Syncing`, `Offline`, or `Needs attention`, plus last successful sync.
- Heart rate is the visual anchor: large tabular number, BPM, timestamp, freshness, and a restrained sparkline.
- Secondary cards appear only for supported metrics: steps, workout activity, oxygen saturation, skin temperature, body composition, HRV/IBI summary, and last recording.
- Missing values display an em dash plus a reason such as `Not measured`, `Watch offline`, `Permission needed`, or `Unsupported on this watch`.
- A single primary action opens the contextually best next measurement. It never claims that an on-demand Galaxy sensor can be initiated invisibly; the watch must be foregrounded and guide required contact/positioning.

### 4.4 Metric detail

- Header: metric name, current value, unit, source, observed time, and Apple Health export state.
- Range control: 1D, 7D, 30D. Raw recording metrics use Sessions instead.
- Chart: scrub-enabled thin line or bars, with min/max/average and no fabricated interpolation across missing intervals.
- History: chronological measurements with quality flags and deletion/export controls.
- Info sheet: plain-language meaning, sensor limitations, canonical unit, HealthKit mapping, and a wellness-only disclaimer.

### 4.5 Spot measurements and raw recordings

1. The iPhone requests a measurement type over BLE.
2. The watch receives the request and opens the appropriate foreground screen; it does not begin restricted trackers silently.
3. The watch verifies permission, SDK policy, tracker availability, on-body state, and whether another on-demand tracker is active.
4. The watch shows preparation instructions, progress, cancellation, and measurement-specific errors.
5. Scalar results enter the durable measurement queue. Raw sessions are compressed into chunks with metadata and a checksum.
6. The iPhone shows the result or waveform, quality/status, duration, source, and whether Apple Health export is available.

Raw ECG, PPG, and accelerometer sessions default to 30 seconds, are explicitly started, and are saved locally. The user may export an individual session as JSON plus CSV samples. Configurable continuous background recording is not provided.

### 4.6 Apple Health flow

- Present a WatchApp explanation screen listing only requested write types.
- Invoke the native HealthKit authorization sheet.
- Export each enabled type independently and show `Enabled`, `Not requested`, `Needs settings`, or `Not supported`.
- Use stable sync identifiers and versions so retries update rather than duplicate samples.
- Queue writes when temporarily unavailable. Do not interpret an empty HealthKit query as proof that read access was denied; v1 does not request read access.
- Provide a manual **Retry pending exports** action and a link to Health settings.

### 4.7 Notification Lab

Notification Lab is hidden behind Settings > Labs and an explicit warning that support is experimental.

1. Explain that notifications may be view-only and that arbitrary typed replies are unavailable globally.
2. Run a compatibility check for BLE bonding, ANCS presence, and watch service readiness.
3. Guide the user through iOS Bluetooth notification-sharing authorization when exposed by the OS.
4. Send a local test notification and confirm arrival, update, removal, reconnection, and privacy redaction behavior.
5. Show a watch inbox containing only the current ANCS session. Clear it on service reset or invalid identifiers.
6. Present only actions actually supplied by iOS, using their supplied labels. Do not show a generic reply composer.
7. Provide a global kill switch and a one-tap **Forget notification access** action.

Failure of any Notification Lab gate removes or disables the lab; it never delays the health core.

### 4.8 Privacy and recovery

- Settings provides local data export, per-session deletion, delete-all, watch unpair, and key reset.
- Unpairing revokes keys on both devices but does not silently delete measurements; the user chooses whether to retain or erase each local copy.
- No accounts, analytics SDKs, advertising identifiers, remote logs containing health values, or cloud backups are part of v1.
- Diagnostics are generated locally, redact metric values and notification content, and require explicit export.

## 5. Minimal OLED design language

### Visual principles

- True black is functional negative space, not decoration.
- Health values dominate; chrome, borders, and labels recede.
- One accent color appears per metric context. Never create a rainbow dashboard.
- Avoid glassmorphism, large gradients, bloom/glow, thick neon outlines, permanent bright panels, and ornamental animation.
- Every state is communicated by text/iconography as well as color.

### Color tokens

| Token | Value | Use |
|---|---:|---|
| `canvas` | `#000000` | App and watch background |
| `surface` | `#0A0A0A` | Primary cards |
| `surfaceRaised` | `#111113` | Sheets and selected controls |
| `separator` | `#242428` | Hairlines and chart guides |
| `textPrimary` | `#F5F5F7` | Values and primary labels |
| `textSecondary` | `#A1A1AA` | Metadata |
| `textTertiary` | `#6B6B73` | Disabled/supporting content |
| `heart` | `#FF453A` | Heart rate/IBI only |
| `oxygen` | `#64D2FF` | Oxygen/respiratory only |
| `temperature` | `#FF9F0A` | Temperature only |
| `activity` | `#30D158` | Steps/workouts/success |
| `warning` | `#FFD60A` | Attention, never decorative |
| `critical` | `#FF453A` | Operational errors, not medical alerts |

### Typography and layout

- Use SF Pro through SwiftUI Dynamic Type; use system fonts on Wear OS.
- Metric hero: 48–56 pt with tabular digits. Screen title: 22 pt. Body: 17 pt. Metadata: 13 pt.
- Use an 8 pt spacing rhythm with 4 pt alignment increments: 8, 12, 16, 24, 32.
- iOS cards use a 20 pt radius; sheets use 28 pt; minimum tap target is 44×44 pt.
- Content respects safe areas and readable width. One prominent action per screen.
- Charts use 1–2 pt strokes, low-opacity fills, sparse gridlines, and explicit gaps for absent data.

### Motion and haptics

- Use short opacity/position transitions; no perpetual pulse around health values.
- Haptics occur only for successful measurement, explicit confirmation, or operational failure.
- Reduce Motion replaces chart morphing and progress motion with fades/static progress.
- Watch ambient mode removes seconds, animation, waveform movement, and nonessential accents.

### Accessibility

- Meet WCAG AA contrast for essential text and controls.
- Support all Dynamic Type accessibility sizes without truncating values or units.
- VoiceOver reads metric, value, unit, observed time, trend, source, and quality in that order.
- Use shapes, symbols, labels, or patterns in addition to color.
- Respect Reduce Transparency, Bold Text, Increased Contrast, and 12/24-hour preferences.
- Localize units, decimals, dates, and time zones; store canonical values independently of display formatting.

### OLED burn-in discipline

- Avoid static full-width white regions and fixed neon edges.
- Permit subtle position variation for long-lived watch status elements.
- Dim nonessential content in ambient mode and redraw only when required.

## 6. Technical architecture

### Repository layout

```text
WatchApp/
├── README.md
├── PLANNING.md
├── docs/
│   ├── adr/
│   ├── protocols/
│   ├── privacy/
│   └── test-matrix/
├── contracts/
│   ├── measurement.schema.json
│   ├── ble-protocol.md
│   └── fixtures/
├── ios/
│   ├── WatchApp.xcodeproj
│   └── WatchApp/
├── wear/
│   ├── build.gradle.kts
│   └── app/
└── tools/
    └── protocol-validator/
```

Only `README.md` and `PLANNING.md` exist at planning time. Implementation creates the remaining paths as their owning workstreams begin.

### Wear OS application

- Kotlin, Compose for Wear OS, coroutines/Flow, Room, WorkManager, and Android Keystore.
- A `SensorProvider` boundary isolates Wear Health Services from `SamsungSensorProvider` so standard metrics work without Samsung SDK approval.
- Runtime capability discovery is the source of truth. UI and protocol never infer support from model name alone.
- `PassiveMonitoringClient`: passive heart rate and steps.
- `MeasureClient`: short foreground live/spot heart-rate sessions.
- `ExerciseClient`: workout sessions and supported metrics such as heart rate, distance, pace, calories, and steps.
- Samsung SDK continuous and on-demand trackers are serialized through a session coordinator. Only one on-demand tracker runs at once.
- A foreground service is used only for user-visible active measurement/recording sessions where platform policy requires it.
- A private Room queue persists normalized measurements and raw-session manifests before transport.
- Raw samples are chunked, compressed, checksummed, and capped by time/session limits. Automatic retention defaults to 30 days for raw sessions and unlimited for compact scalar history until the user deletes it.
- BLE operates as the custom health GATT server/peripheral while ANCS experiments use a separate client component. Both are independently feature-flagged.

### iOS application

- Swift 6, SwiftUI, Observation, Swift Concurrency, Charts, CoreBluetooth, HealthKit, and Keychain.
- Minimum deployment target iOS 18; the code must avoid requiring the EU-only notification framework.
- Feature modules: Onboarding, Today, Trends, Watch, Measurements, Recordings, HealthExport, Settings, and NotificationLab.
- A repository layer reconciles BLE records into the protected local database before publishing UI updates.
- Use GRDB for deterministic migrations and SQLite access; set the database to `NSFileProtectionCompleteUntilFirstUserAuthentication`. Store identity and session secrets in Keychain with `ThisDeviceOnly` accessibility.
- `BluetoothTransport` owns discovery, enrollment, state restoration, subscriptions, MTU-aware transfer, acknowledgements, and reconnect state.
- `HealthKitExporter` maps only semantically equivalent records, applies sync identifiers/versions, and records per-sample export results.
- Notification Lab is compiled behind a module boundary and controlled by a local feature flag and runtime capability checks.

### Local-first data flow

```text
Watch sensor
  → normalize and persist to Room
  → BLE manifest and chunk negotiation
  → encrypted/authenticated transfer
  → iPhone verifies, persists, and acknowledges
  → watch marks delivered but retains according to policy
  → SwiftUI repository refresh
  → optional idempotent HealthKit export
```

BLE live values are ephemeral presentation updates. A value becomes historical only after the durable record is received and acknowledged. This avoids split-brain history when a live connection drops.

## 7. Public contracts and data model

### Measurement record

Every durable scalar or aggregate uses this logical contract:

| Field | Requirement |
|---|---|
| `id` | UUID generated once on watch |
| `schemaVersion` | Unsigned protocol schema version |
| `deviceId` | Stable pseudonymous watch identity |
| `sequence` | Monotonic per-device 64-bit sequence |
| `metricType` | Versioned enum |
| `observedAt` | UTC instant |
| `timezoneOffsetMinutes` | Offset at observation |
| `durationMillis` | Optional interval duration |
| `value/components` | Canonical scalar or typed component map |
| `unit` | Canonical UCUM-compatible unit identifier |
| `source` | `wear_health_services` or `samsung_sensor_sdk` |
| `quality` | `good`, `uncertain`, `poor`, or `unknown` |
| `statusFlags` | Off-body, incomplete, interrupted, capability-specific flags |
| `sessionId` | Optional workout/raw recording relation |
| `createdAt` | Watch persistence time |

Metric types initially include heart rate, IBI, steps, distance, active energy, workout summary, oxygen saturation, skin temperature, BIA components, and capability-supported scalar Samsung results. Raw ECG, PPG, and accelerometer samples use a separate session/chunk contract rather than bloating measurement records.

### Raw session contract

- Session header: UUID, sensor kind, sampling rate, channels, canonical units, start/end instants, device/source, quality, and chunk count.
- Chunk: session UUID, zero-based index, sample start offset, encoding version, compressed payload, uncompressed length, and SHA-256 checksum.
- Transfer supports resume from the first missing chunk and verifies the complete session digest.
- CSV/JSON export includes metadata and an explicit `fitness/wellness only` disclaimer.

### BLE service

Use one reserved 128-bit WatchApp service UUID with these conceptual characteristics:

- `control`: authenticated commands and responses.
- `manifest`: record/session availability and cursor exchange.
- `data`: MTU-aware durable chunks.
- `live`: lossy live metric notifications.
- `status`: capabilities, battery/sync state, and protocol version.

Protocol messages use deterministic CBOR with an outer envelope containing protocol version, message type, request ID, sequence, payload length, and authentication tag. Unknown optional fields are ignored; incompatible major versions stop sync and show an upgrade message.

### Enrollment and security

1. The watch generates a device-bound P-256 key in Android Keystore and advertises an enrollment nonce.
2. The iPhone creates a device-bound key in Keychain and initiates ephemeral ECDH.
3. Both screens derive and display the same six-digit short authentication string.
4. User confirmation binds public identities and derives separate encryption, authentication, and rekey material using HKDF-SHA256.
5. Application messages use AES-GCM with monotonic nonces and replay protection in addition to BLE link encryption.
6. Keys rotate after a configurable message/time threshold and are destroyed on confirmed unpair.

No health or notification content appears in OS logs, crash events, Bluetooth advertisements, screenshots used for diagnostics, or notification payloads.

## 8. Apple Health mappings

Map only when meaning and units match:

| WatchApp data | HealthKit target | Policy |
|---|---|---|
| Heart rate | `heartRate` | Export valid discrete samples |
| Steps | `stepCount` | Export non-overlapping intervals |
| Walking/running distance | `distanceWalkingRunning` | Export workout-linked intervals |
| Active energy | `activeEnergyBurned` | Export supported workout/passive totals |
| Workout | `HKWorkout` plus associated samples | Export completed workouts only |
| Oxygen saturation | `oxygenSaturation` | Export valid spot readings as fractions |
| Body fat percentage | `bodyFatPercentage` | Export BIA result when supplied |
| Lean body mass | `leanBodyMass` | Export only when SDK semantics match |
| Body mass | `bodyMass` | Export only if returned/measured by BIA flow |
| Skin temperature | No automatic body-temperature mapping | Retain in WatchApp unless documented semantics justify a HealthKit type |
| IBI | Derive no HRV sample by default | Avoid substituting a non-equivalent statistic |
| ECG waveform | None | HealthKit ECG is read-only |
| Raw PPG/accelerometer | None | Retain/export as WatchApp session files |

Each exported object uses `HKMetadataKeySyncIdentifier = measurement.id` and increments `HKMetadataKeySyncVersion` only when the source record is legitimately revised.

## 9. Reliability, privacy, and failure behavior

### Synchronization

- Exchange highest contiguous sequence and missing ranges before data transfer.
- Records are idempotent by device ID and UUID; sequence detects gaps but is not the sole identity.
- Use bounded batches, acknowledgement windows, exponential retry, and resumable raw chunks.
- Detect clock skew and retain both observed and received times; never silently rewrite the source timestamp.
- If the iPhone force-quits the app, show that automatic BLE reconnection may remain unavailable until the app is reopened.

### Storage and retention

- Scalar history remains until user deletion for the personal prototype.
- Raw sessions default to automatic deletion after 30 days, configurable to 7/30/90 days or keep until deleted.
- Notification content exists only in volatile/session storage and is cleared on disconnect, reboot, or service invalidation.
- iCloud document/database backup is disabled for personal health data.

### Error taxonomy

Errors are typed as permission, capability, SDK policy, off-body/contact, busy/conflict, Bluetooth, protocol/version, storage, HealthKit, or unknown. Each UI error provides one safe next action. Sensor errors never become medical interpretations.

### Privacy posture

- No cloud, account, ads, analytics, profiling, or data sale.
- Permissions are requested just in time and can be revoked without losing unrelated app functions.
- App copy calls measurements fitness/wellness information and never diagnostic results.
- Before any public release, add formal privacy policy, App Privacy disclosures, Samsung partnership approval, and Apple review notes describing the supported physical accessory.

## 10. Feasibility gates

### Gate 0 — toolchain and hardware readiness

- Obtain a Mac capable of the current Xcode release, a physical iPhone running iOS 18+, an Apple Developer account when entitlements/signing require it, the Watch6 Classic, and temporary Android access for ADB/watch application installation.
- Install Android Studio/SDK/ADB and a supported Gradle JDK. The current Linux host has Java 25 but lacks Android SDK, Gradle, ADB, Swift, and Xcode.
- Go when signed apps run on both physical devices. Until Mac access exists, iOS implementation and integration testing are blocked; contracts, Wear OS work, and design specifications may proceed.

### Gate 1 — sensors and battery

- Prove Wear Health Services passive HR/steps, a workout, Samsung developer-mode capability discovery, one scalar on-demand sensor, and one raw session.
- Record a 24-hour baseline and passive-feature battery delta.
- Go if durable capture survives watch reboot and default battery regression is at or below 10%.

### Gate 2 — custom BLE transport

- Prove foreground enrollment, encrypted GATT sync, 1,000 scalar records, a resumed raw session, background event delivery, state restoration, out-of-range recovery, and watch Doze behavior.
- Go if there is no data loss/duplication and typical nearby reconnection is acceptable; record measured targets rather than inventing them before the spike.

### Gate 3 — HealthKit

- Prove granular authorization, locked-phone behavior, idempotent retries, partial denial, and sample provenance on a physical iPhone.
- Go if every mapped type is semantically accurate and duplicate-free.

### Gate 4 — Notification Lab

- Prove ANCS authorization, add/update/remove events, content truncation, predefined actions, disconnect invalidation, watch reboot, and simultaneous health GATT operation.
- Go only if battery and reconnection are acceptable. Otherwise compile the module out or leave it disabled.
- Typed arbitrary replies are not a pass criterion because classic ANCS does not provide them.

## 11. Delivery phases and parallel work

Four concurrency slots are assumed, including one integration lead. Agents must own non-overlapping modules and coordinate through versioned contracts and fixtures.

### Phase A — discovery and contract lock

Run three workstreams in parallel:

1. **Platform spike agent:** Watch6 sensor capability harness, battery baseline, BLE role test, and ANCS proof-of-concept.
2. **Product/design agent:** OLED token catalog, complete iPhone/watch wireframes, copy, empty/error states, and accessibility annotations.
3. **Architecture/security agent:** measurement schema, raw-session format, BLE state machine, enrollment threat model, ADRs, and protocol fixtures.

The lead owns integration decisions, repository conventions, protocol versioning, design tokens, feature flags, and gate review. Phase A exits only when the measurement schema and BLE envelope are frozen at v1.

### Phase B — health core

After contract lock, run in parallel:

1. **Wear agent:** sensor abstraction, passive capture, workouts, Samsung tracker adapters, Room queue, and raw-session recorder.
2. **iOS app agent:** navigation shell, OLED components, local repository, Today, Trends, Watch, Settings, and mock transport.
3. **Transport agent:** Wear GATT server, iOS CoreBluetooth central, enrollment, encryption, durable sync, reconnect, and protocol conformance tests.

The lead integrates vertical slices in this order: pairing/status, heart rate, steps, workout, on-demand scalar, raw session. No new metric bypasses the shared contract.

### Phase C — platform integrations

Run in parallel after stable durable sync:

1. **HealthKit agent:** authorization UX, mapping adapters, export queue, idempotency, provenance, and settings.
2. **Advanced sensor agent:** BIA, SpO2, skin temperature, ECG, PPG, accelerometer UX, capability errors, and export files.
3. **Quality agent:** hardware matrix, permission/failure automation, accessibility, performance, battery scripts, and privacy/log audits.

### Phase D — Notification Lab

Keep this separate from release-critical work:

1. ANCS transport and authorization spike.
2. Watch notification inbox and lifecycle handling.
3. Predefined action round trip and safe labeling.
4. Simultaneous health sync, Doze, reboot, and battery validation.
5. Feature-flag decision: enabled for this device, retained as developer-only, or removed.

### Coordination rules

- One owner per module/directory; cross-module changes go through the lead.
- Contracts and fixtures merge before consumers; iOS and Wear use the same golden CBOR vectors.
- Each agent supplies unit tests, a demo artifact, known limitations, and an ADR for material deviations.
- Daily integration uses one small end-to-end scenario rather than merging large untested branches late.
- Notifications never edit or block the health capture and durable sync modules.

## 12. Test plan

### Unit and contract tests

- Canonical unit conversion, range validation without source-value mutation, time zones/DST, clock skew, quality flags, and capability mapping.
- CBOR golden vectors, protocol version negotiation, authentication failure, replay rejection, chunking at multiple MTUs, checksums, and raw-session resume.
- Duplicate/out-of-order records, missing sequences, database migrations, retention, export, deletion, and key rotation.
- HealthKit mapping, unsupported types, partial authorization, stable sync identifiers, revisions, and retry idempotency.

### Real-hardware matrix

- Watch6 Classic Bluetooth on current firmware; add both case sizes only if hardware becomes available.
- At least one physical iPhone on iOS 18 and one current iOS version before broader distribution.
- Phone locked/unlocked, app foreground/background/force-quit, Bluetooth toggled, airplane mode, watch reboot, iPhone reboot, out-of-range, low-power modes, and watch Doze.
- Sensor off-body, poor contact, user cancellation, conflicting tracker, expired permissions, old Health Platform, and Samsung SDK policy error.

### UX and accessibility

- No-data, stale, syncing, unsupported, permission-denied, storage-full, protocol-mismatch, and partial-sync snapshots.
- VoiceOver order/labels, all Dynamic Type sizes, Increased Contrast, Reduce Motion, Reduce Transparency, color-blind differentiation, 12/24-hour clock, and locale-aware units.
- Verify true black, restrained accents, and absence of clipping on supported iPhone and round-watch sizes.

### Performance and battery

- Time to first dashboard, chart rendering with large histories, database growth, scalar sync throughput, raw-session throughput, reconnect time, and memory pressure.
- 24-hour passive baseline versus enabled build; separate active HR, workout, ECG, PPG, accelerometer, BLE sync, and ANCS drains.

### Security and privacy

- Threat-model review, pairing MITM attempt, replay/tamper tests, key deletion/re-enrollment, unauthorized GATT access, malformed payload fuzzing, and protocol downgrade rejection.
- Scan logs, diagnostics, exports, screenshots, backups, and crash reports for health/notification leakage.
- Verify delete-all and raw-retention jobs independently on watch and iPhone.

## 13. Milestones and definition of done

1. **M0 — Environment and spikes:** toolchains documented; sensor, BLE, HealthKit, and ANCS feasibility results recorded with go/no-go decisions.
2. **M1 — Heart core:** secure pairing, passive/live HR, durable history, Today/Trends, offline recovery, and tests.
3. **M2 — Activity:** steps, workouts, distance/energy where supported, charts, and HealthKit export.
4. **M3 — Galaxy sensors:** all runtime-supported scalar spot checks plus bounded raw sessions and local exports.
5. **M4 — Product hardening:** accessibility, retention/deletion, diagnostics, battery targets, failure recovery, and privacy audit.
6. **M5 — Notification decision:** ship the proven Lab for this personal device or disable it without affecting M1–M4.

The personal prototype is done when M1–M4 meet their acceptance criteria on physical hardware. M5 is optional and cannot redefine completion.

## 14. Documentation and source references

Implementation decisions should be revalidated against current primary documentation at each gate:

- Samsung Watch/phone compatibility: https://www.samsung.com/us/support/answer/ANS10004668/
- Samsung Health Sensor SDK: https://developer.samsung.com/health/sensor/guide/introduction.html
- Samsung sensor specifications: https://developer.samsung.com/health/sensor/guide/data-specifications.html
- Samsung app verification: https://developer.samsung.com/health/sensor/guide/app-verification.html
- Wear Health Services: https://developer.android.com/health-and-fitness/health-services
- Wear passive monitoring: https://developer.android.com/health-and-fitness/health-services/monitor-background
- Wear OS direct networking/Data Layer limitation: https://developer.android.com/training/wearables/data/overview
- Apple Core Bluetooth background behavior: https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html
- Apple ANCS: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/
- Apple ANCS actions: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html
- Apple Accessory Notifications regional limits: https://developer.apple.com/documentation/accessorynotifications
- HealthKit authorization: https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data
- HealthKit privacy: https://developer.apple.com/documentation/healthkit/protecting-user-privacy
- HealthKit ECG write limitation: https://developer.apple.com/documentation/healthkit/hkelectrocardiogramtype
- App Review Guidelines: https://developer.apple.com/app-store/review/guidelines/

## 15. Assumptions requiring revalidation

- The watch remains initialized and capable of installing the custom Wear OS app after Android is removed from the daily workflow.
- Exact Samsung tracker availability is determined at runtime, not assumed from marketing specifications.
- Personal development mode is acceptable for initial Samsung SDK testing; a future public build requires Samsung approval.
- A Mac, physical iPhone, and Apple signing access will become available before iOS implementation and Gate 3.
- Direct BLE remains the only durable transport in v1; adding cloud sync requires a separate privacy, account, security, API, and deletion design.
- Raw recording sessions default to 30 seconds and 30-day retention.
- Notification viewing/actions are experimental; universal typed replies are excluded for India/global.
