# WatchApp Wear OS

Production-shaped Wear OS client for the WatchApp local-first companion. It includes a true-black OLED Compose UI, Room-backed measurement history and durable retry queue, Wear Health Services passive heart-rate/steps capture, bounded raw-session storage, a versioned BLE GATT transport, and Android Keystore enrollment.

`SamsungTrackerBridge` is the license-safe integration boundary. An approved build supplies the separately obtained Samsung Health Sensor SDK adapter; the default bridge reports unavailable and never fabricates sensor data. Do not commit Samsung's proprietary AAR.

## Runtime behavior

Grant body-sensor, activity-recognition, Bluetooth connect, and advertise permissions on first launch. Passive readings are persisted before being queued. The encrypted GATT service advertises only while the app process is active; a phone acknowledges each message by UUID, and unacknowledged records use bounded exponential retry and dead-letter after eight attempts.

Raw PPG/ECG/accelerometer sessions are explicitly user initiated, capped at five minutes, chunked at 512 samples, encoded as deterministic CBOR sample rows, raw-DEFLATE compressed, and verified with per-chunk uncompressed SHA-256 plus a whole-session uncompressed SHA-256. Public Samsung-enabled builds still require Samsung approval and an actual bridge implementation.

### Measurement CBOR payload

Durable measurement DATA_FRAGMENT plaintext is an array of maps using the frozen v1 keys: `0=id bstr(16)`, `1=deviceId bstr(16)`, `2=sequence`, `3=metricType`, `4=value`, `5=unit`, `6=observedAtEpochMs`, `7=source`, `8=quality`, `9=statusFlags`. This is encrypted inside the protocol-v1 CBOR secure envelope; JSON is never transported over GATT. Fragment message IDs `0xA800...0xA8FF` are reserved so they cannot be confused with a direct `0xA8` secure envelope.

## Build

Use JDK 17, Android SDK 36, and Gradle 9.4.1 or newer compatible with Android Gradle Plugin 9.2.0:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The checked-in Gradle wrapper pins Gradle 9.4.1. The Linux validation build uses Android API 36/build-tools 36.0.0 and produces `app/build/outputs/apk/debug/app-debug.apk`.
