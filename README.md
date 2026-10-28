# SnapIMS Mobile 0.1.1

**[Download the Android APK](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Mobile-0.1.1-debug.apk)** · [SHA-256](releases/SnapIMS-Mobile-0.1.1-debug.apk.sha256)

Native camera companion for Canada VHS, with durable offline sessions, explicit
Capture/Next grouping, barcode scanning, verified ZIP exports and reviewable recognition.
This is a debug-signed acceptance update for arm64 Google Pixel devices.

## Recognition

- **OpenAI:** enter your API key in Settings → Recognition, select OpenAI and a
  compatible vision model (default `gpt-4.1-mini`). Test key / refresh models is
  optional. In Batches, tap Recognize on a product or Recognize completed products.
  Requests use the Responses API and strict structured output; uploaded photos are
  small derivatives, while originals remain unchanged. API charges apply.
- **Offline OCR:** Tesseract 5.5.1 transcribes visible English cover text locally.
  ZXing decodes barcodes, and classical checks flag possible blur/lighting issues.
  Tap an OCR line and verify/edit the printed title. No cloud account is needed.
- **Model-free checks:** deterministic ZXing barcodes and classical pixel checks,
  with no trained models, OCR or network calls. This mode does not infer movie titles.
- **Off:** camera, grouping, exports and normal offline barcode scanning only.

Tesseract uses trained character data; it is not a generative AI service. OpenAI
and OCR results remain suggestions. Confirmed titles are capture metadata; SnapIMS
retains authority for catalog, inventory, pricing and Shopify. Nothing starts an
OpenAI request on each shutter. See [recognition details](docs/RECOGNITION_0.1.1.md).

## Update without losing captures

Install 0.1.1 **over** 0.1.0; do not uninstall or clear app storage. Application ID
and signing key are unchanged, versionCode increases to 2, and a tested Room 1→2
migration preserves the existing capture tables and files. Recognition defaults to
Off on upgrade. Keep verified ZIP backups before device resets or app removal.
Android private data is removed by uninstall/clear-storage; automatic backup is disabled.
The debug signing key must be retained for future updates; never commit signing keys.

## Build

JDK 21, Android SDK platform 37.0 / Build Tools 36.0.0. Gradle 9.8.2, AGP 9.4.1 and
Kotlin 2.3.21 are pinned. The app includes a verified, source-built Tesseract AAR and
English data, so normal builds need no JitPack access, NDK or runtime downloads.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# app/build/outputs/apk/debug/app-debug.apk
```

In the prepared cloud workspace first run `source /workspace/.tools/activate` and
`cd /workspace/APK`. Each cloud task already has isolated checkouts; do not create a
worktree unless explicitly requested. `scripts/setup-cloud.sh` reproduces the shared
Python/Android environment. `scripts/rebuild-tesseract.sh` rebuilds the pinned upstream
OCR library separately; see [build provenance](app/libs/tesseract-build-provenance.json).
Gradle verifies native/data SHA-256 before building. Room uses explicit migrations,
never destructive fallback. The legacy kapt DSL remains selected until a later AGP migration.

## Capture and handoff

Start a named batch and optional A1–J10/Q1 location. Capture front, back and extras;
Next is the only automatic grouping boundary. Undo Next, retake, remove/restore,
reorder, editable roles and moving photographs are in Capture/Batches. Rare/Review
and Damaged/Sealed/Unusual notes are native controls. Source bytes and EXIF stay intact.

Finish Batch or Finalize Part packages complete groups near a configurable 500 MB
target. Transfers → Save to Files verifies readback SHA-256; Share uses Android's
standard chooser. Originals are retained. Recognition ready before an archive
snapshot is included; later results can be shared separately without rewriting ZIPs.
See [manifest/manual import](docs/MOBILE_MANIFEST_V1.md).

CameraX requests maximum available JPEG resolution/quality by default, with autofocus,
tap focus, exposure, torch/flash, rear/front switch and available HDR. Settings can
request about 12 MP. Pixel Camera processing parity is not claimed.

## Validation and scope

[Current results](docs/TEST_RESULTS.md) · [known limits](docs/STATUS.md) ·
[Pixel acceptance](docs/PIXEL_ACCEPTANCE.md) · [architecture audit](docs/ARCHITECTURE_AUDIT.md) ·
[third-party notices](docs/THIRD_PARTY_NOTICES.md)

Physical Pixel sustained capture/quality, Android JNI OCR and actual hardware
Keystore checks remain device acceptance gates. Live OpenAI was not run in this
cloud environment because no API key was supplied. Wireless pairing/direct desktop
sync and chat remain separate work. Existing QR desktop import stays compatible.
