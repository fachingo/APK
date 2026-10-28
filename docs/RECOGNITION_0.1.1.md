# Recognition in 0.1.1

Settings → Recognition provides four explicit choices. Capture, grouping, original
storage and exports work without a key or network connection in every mode.
Recognition only starts when you tap Recognize in Batches or confirm a batch run;
there is no per-shutter OpenAI request.

| Mode | What runs | Network / trained data |
| --- | --- | --- |
| Off | Automatic back-photo barcode scan only | ZXing, deterministic; no trained model |
| Model-free checks | ZXing barcodes plus classical brightness/clipping/blur statistics | No network, OCR or trained model |
| Offline OCR | Tesseract 5.5.1 English OCR, ZXing and classical checks | Fully offline; bundled trained **character** data, not generative AI |
| OpenAI | Vision recognition through the OpenAI Responses API with strict structured output | Uses your API key and uploads up to 3 small JPEG derivatives when requested |

No model-free algorithm can reliably infer arbitrary movie titles from cover art.
The model-free mode decodes product barcodes and reports pixel measurements. OCR
transcribes visible words; it does not build a movie catalog or infer editions.
Tap an OCR text line, edit it if necessary, and confirm the printed title. Pixel
statistics are clearly labelled heuristics, never reasons to discard a photograph.

## OpenAI setup

1. Select OpenAI in Settings and use Add API key. Enter an OpenAI **API** key;
   ChatGPT subscription credentials are not API credentials. Never post keys in GitHub.
2. The key is encrypted using AES-GCM and a wrapping key in Android Keystore.
   The key dialog blocks screenshots. Plaintext keys are not stored in Room,
   exports, logs, build configuration or source. Remove key deletes its encrypted
   binding and Keystore alias and stops pending API recognition.
3. The default model is `gpt-4.1-mini`, matching the existing desktop default.
   Test key / refresh models reads available model IDs. The Models API does not
   expose vision/structured-output capabilities; recognition checks actual support.
   You can enter a different compatible vision model ID manually. Unsupported model,
   permission, authentication, quota and connection errors are explicit.
4. In Batches, open a product's Recognize dialog and tap Run OpenAI, or confirm
   Recognize completed products. API billing belongs to your account. Batch runs
   skip completed results for the same photos/provider; individual reruns are explicit.

Requests use fixed `https://api.openai.com/v1/responses`, Bearer authentication,
`store:false`, strict JSON schema and bounded output. Redirects and automatic HTTP
retries are disabled. Downscaled, orientation-correct JPEG derivatives strip EXIF;
original bytes remain intact. No inventory IDs, pricing requests or owner notes
are sent. The returned metadata remains a suggestion until confirmed. No price
field exists in the mobile schema, which reuses the non-pricing fields of SnapIMS's
existing recognition schema. No inventory/catalog/Shopify data is changed.

Offline API jobs wait for network. An interrupted request with uncertain outcome
is never silently billed again; it requires a manual retry. Changing recognition
mode cancels unfinished jobs from the old provider. Capture never waits for OCR or
API jobs. Inputs are fingerprinted: edited/reordered/moved/removed photographs make
older results stale, and stale suggestions cannot be confirmed or exported as current.

## Results, exports and desktop handoff

Every attempt is retained with provider/model, input photo IDs/hashes, timestamps,
state, result/provenance and optional user-confirmed title. A confirmed title is
capture metadata, not a replacement catalog record. Recognition errors live in
this journal and do not turn valid camera photographs into failed captures.

New archive manifests include the latest successful **current** result for each
group when it is available before the archive snapshot. Existing ZIPs stay immutable
so reimports remain idempotent. Share results JSON sends current recognition results
separately. The supplementary `snapims.mobile.recognition` JSON is for inspection
and future adapters; this release does not automatically apply it to desktop titles.
The existing mobile ZIP importer accepts the enriched v1 ZIP and preserves the full
manifest provenance. Continue desktop Recognition/Review before catalog/publishing.

## Open-source engines and verification

- ZXing Core 3.5.4 is the deterministic barcode engine, replacing the former ML Kit
  decoder. Checksums, raw format, GTIN normalization and ambiguity are preserved.
- Tesseract4Android 4.9.0 wraps Tesseract 5.5.1 / Leptonica 1.85.0. Its official source
  tag/commit was built locally for arm64 Pixel devices with NDK 27.2.12479018 and
  16 KB-compatible ELF alignment. See app/libs/tesseract-build-provenance.json.
- English `tessdata_fast` 4.1.0 is bundled for offline availability. SHA-256 is verified
  before building and when extracting it on-device; interrupted copies are repaired.
- The vendored AAR has a checked-in SHA-256 and is checked by Gradle preBuild.
  `scripts/rebuild-tesseract.sh` reproduces the source build into build/vendor-tesseract
  without rewriting the verified app dependency. SDK platform 35, NDK and CMake are
  needed only to rebuild that library; ordinary app builds use the included AAR.
- Full original licenses and attribution are included in the APK/source.

These are mature open-source engines, not a measured guarantee on every VHS cover.
A real Pixel/VHS fixture benchmark, Android JNI OCR run and real Android Keystore
round-trip remain device checks. Host testing covers deterministic image decoding,
OCR language data, API protocol/error handling, encrypted storage, job safety and
Room migration. Live OpenAI completion was not run here: no API key was supplied,
and this cloud's API destination is blocked. Enter and test your key on the Pixel.

## Updating 0.1.0

Install 0.1.1 **over** 0.1.0; do not uninstall or clear storage. Application ID and
signing key are unchanged, versionCode increments from 1 to 2, and Room migration
1→2 adds only the recognition journal. Existing sessions, groups, photos, hashes,
archives and preferences remain. Recognition defaults to Off on upgrade. Back up
exported captures as usual; uninstalling still removes app-private photographs.
