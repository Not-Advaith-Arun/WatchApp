# WatchApp

WatchApp is an implemented, pre-hardware-validation companion prototype for a Galaxy Watch6 Classic and iPhone. It pairs a custom Wear OS sensor application with a minimal OLED SwiftUI dashboard, local-first encrypted Bluetooth synchronization, Apple Health export, bounded raw recordings, and an experimental notification laboratory.

The product specification is in [PLANNING.md](./PLANNING.md), and current validation gates are tracked in [docs/IMPLEMENTATION_STATUS.md](./docs/IMPLEMENTATION_STATUS.md).

Important boundary: WatchApp does not replace Galaxy Wearable, cannot perform the official Watch6-to-iPhone setup, and does not promise universal notification replies.

## Build and inspect

```sh
git clone https://github.com/Not-Advaith-Arun/WatchApp.git
cd WatchApp
make contracts
```

For the Wear OS app, install the Android SDK and JDK, then run `make android`. For iOS, use macOS, Xcode, and XcodeGen; see [iOS setup](ios/README.md). The repository includes native build workflows and unsigned IPA packaging.

## Hardware status

Physical Watch6/iPhone pairing, BLE recovery, battery measurements, HealthKit behavior, and notification interactions still need device validation. A successful build is not evidence that these hardware journeys work. Samsung-specific sensor adapters require the relevant SDK access; the notification laboratory remains disabled by default.

The JSON/CBOR vectors use deliberately reproducible synthetic test material. They are not deployment credentials. Do not reuse test vectors as device keys.
