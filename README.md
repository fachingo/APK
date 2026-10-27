# SnapIMS Mobile

**[Download the Android APK](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Mobile-0.1.0-debug.apk)** · [SHA-256](releases/SnapIMS-Mobile-0.1.0-debug.apk.sha256)

Debug-signed acceptance build; physical Pixel validation remains required.

Native Android capture companion for Canada VHS. Capture photographs of one tape,
press **Next**, then capture the next tape. SnapIMS stays authoritative for inventory,
pricing, catalog recognition and Shopify. The app keeps an offline capture/transfer
journal, not another inventory database.

This is a **Milestone 1 acceptance build**, not a production release. Camera/Pixel
acceptance is pending; later milestones are gated on it. See [requirements](docs/REQUIREMENTS.md),
[architecture audit](docs/ARCHITECTURE_AUDIT.md), [protocol/import instructions](docs/MOBILE_MANIFEST_V1.md)
and [acceptance checklist](docs/PIXEL_ACCEPTANCE.md).

## Build

Use JDK 21, Android SDK platform 37.0 and Build Tools 36.0.0. Gradle 9.8.2 is pinned
and checksum verified by the wrapper. AGP 9.4.1 / Kotlin 2.3.21 are pinned: Room's
current compiler does not support Kotlin 2.4 metadata. The legacy Kotlin/kapt DSL is
explicitly selected while using Room; migrate before AGP 10.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

For this prepared cloud environment:

```bash
source /workspace/.tools/activate
cd /workspace/APK
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug
```

`scripts/setup-cloud.sh` reproduces dependencies in /workspace/.tools using verified
artifacts and the managed proxy/CA. It uses development data in /workspace/.data,
leaves SnapIMS/Pricing source and lockfiles intact, and needs no OpenAI/Shopify key.
Do not use a new Git worktree: cloud tasks already have isolated checkouts.

## On your Pixel

Install the debug APK using Android's package installer, or `adb install -r app-debug.apk`
from a workstation with USB debugging. Grant Camera access, start a named batch,
and optionally choose A1–J10/Q1. Capture front, back, and extras. Barcode decoding
runs after the back image; tap its result to correct, choose among candidates or
rescan. Tap Next explicitly for each new tape. Use Rare/Review and change location
when needed. Finish Batch ends capture and queues archives.

Batches provides roles, reorder arrows, removal/restoration, move to neighboring
group, photo preview and archive finalization. Undo Next reopens the prior group
while the new group is empty and that prior group is not reserved for export.
Retake keeps the removed original. Exported groups are locked; archives retain
original JPEGs. Transfers supports Share and Save to Files (with readback checksum).

Default camera capture requests highest available resolution, quality priority,
100% JPEG quality, rear camera, autofocus and optional HDR extension. Settings can
request approximately 12 MP instead. Tap the viewfinder to focus, use the exposure
slider, torch/flash and front/rear switch. Device-supported CameraX JPEGs may differ
from Google's proprietary Pixel Camera processing; no feature-parity claim is made.

## Updates and data safety

Keep the application ID and signing certificate unchanged. Install updates with
`adb install -r` or the Android package installer; do not uninstall first. The debug
signing key lives under ANDROID_USER_HOME (normally ~/.android); back it up privately
if you intend to update this acceptance installation. A stable private release key
and a tested migration/recovery release process are required before production.
Never commit release signing keys. Future Room schema changes must use explicit
migrations; destructive fallback is prohibited.

Save/verify every ZIP on another device before uninstalling, clearing app storage,
changing signing keys, factory resetting, or replacing the Pixel. Android app-private
photos are removed by uninstall/clear-data. Cloud backup is disabled to avoid partial
restoration of a journal without its source images. No automatic source cleanup is
implemented. A successful save shows a verified result; a shutter animation alone
is not a durable-save receipt.

Manual import uses the new `snapims.mobile_import` module in the sibling SnapIMS
checkout; use the documented CLI with a disposable data root first. There is no
new unauthenticated endpoint. Wireless pairing, OCR/OpenAI modes, model selection,
quality heuristics, full cleanup controls and optional chat are listed explicitly
in [status and limitations](docs/STATUS.md); do not mistake them for implemented features.
