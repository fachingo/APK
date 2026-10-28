# Version 0.1.2 scope

Camera update: selectable 1.2/3/6/12 MP and maximum resolution, pinch/slider zoom,
fast/ZSL capture with background verification, original-detail tiled barcode scanning,
and adaptive landscape layout without Activity recreation on rotation.
See CAMERA_0.1.2.md. Real shutter timing and optical quality remain unmeasured here.

Implemented: native durable capture/grouping, original preservation, source checksums,
ZIP exports, model-free ZXing barcodes, classical image statistics, offline Tesseract
English OCR, opt-in OpenAI Responses/structured output, secure Keystore API-key storage,
model-ID selection, persistent recognition queues/results, explicit title confirmation,
current-result manifest provenance and separate results sharing. Room migration 1→2
preserves existing data. Recognition defaults to Off and never delays the shutter.

Model-free checks do not infer movie titles. OCR uses trained character data and
transcribes words; no local catalog/pricing engine is created. OpenAI returns suggestions
only. Confirmed capture metadata never overwrites existing desktop inventory or pricing.

Pending: physical Pixel camera/quality/sustained capture acceptance, real Android JNI
OCR and hardware Keystore tests, live OpenAI account completion, wireless authenticated
pairing/import receipts, native physical QR command scanning, physical telephoto/ultrawide
lens selection, French OCR data, advanced retention controls and chat workspace.
Local quality warnings are uncalibrated heuristics. Full desktop auto-application of
separate recognition JSON is not implemented; continue SnapIMS review.

Exported/finished groups remain locked against capture edits. Existing ZIPs are immutable;
results completed later are shared separately. No automatic source deletion. This build
targets arm64 Pixel devices, not x86 Android emulators. It remains a debug acceptance
build; private production signing and physical-device validation are still required.

See RECOGNITION_0.1.1.md for modes, API privacy/costs, result states, cancellation and
manual retry behavior, and TEST_RESULTS.md for executed versus unrun checks.
