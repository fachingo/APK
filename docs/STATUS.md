# Delivery scope and acceptance status

The source implements a native offline capture application, not a WebView or a
visual-only prototype. It is an initial Milestone 1 acceptance build. Production
quality is not established without the physical tests in PIXEL_ACCEPTANCE.md.

| Requirement area | Implemented / limit |
| --- | --- |
| Capture | CameraX, maximum available JPEG/quality priority, rear default, front/rear switch, tap focus, exposure, torch/flash, available HDR, grid, volume shutter, save haptic/tone |
| Camera selection | Front/rear logical cameras; individual telephoto/ultrawide/physical lens selection is not implemented |
| Sessions | Room/WAL/FULL synchronous journal, reserved writes, fsync/rename, startup recovery, explicit Next, durable UUIDs and timezone timestamps |
| Editing | Undo Next, retake (keeps original), remove/restore, reorder, editable roles, neighboring-group move, Rare/Review, persistent A1-J10/Q1 location |
| Edit restrictions | No boundary change while a shutter is pending; export-reserved and finished groups are locked. Location changes affect the open and following products |
| Barcode | Bundled offline ML Kit, checksum/GTIN normalization, raw candidates/provenance, ambiguity, no-barcode, manual correction and back-photo rescan |
| Decoder acceptance | ML Kit vs ZXing reliability comparison and live targeted camera rescan need physical validation; current rescan analyzes the stored back image |
| Archives | Whole-product planner, default decimal 500 MB, oversized groups, background WorkManager retries, source hashes, readback verification, snapshot manifests, .pending output, atomic finalization |
| Manual transfer | Android Files and Share, checksum-verified save, no source deletion. No automatic knowledge of desktop import status |
| Desktop import | New SnapIMS adapter, original processor/IDs/derivatives, role/barcode/flag provenance, receipts, repeated/repacked import deduplication and verified interruption bridge |
| Cleanup | No automatic source deletion. Full retention/cleanup controls require a validated backup/receipt policy |
| Recognition | Off beyond barcode. Selectable OCR/OpenAI modes, model selection and Keystore credential management await milestone 2; no credentials are stored or required now |
| QR cards | Existing desktop CVHS1 import unchanged and tested. Optional native physical-card scanning is not implemented |
| Quality aids | Incomplete front/back and storage warnings; blur/glare/lighting/clipping/duplicate-image heuristics are not implemented |
| Synchronization | Wireless discovery, pairing, authenticated/resumable upload, Android receipts/imported state and transfer recovery await milestone 2; no network import endpoint exists |
| Extra flags | Rare and Review exist. Native notes dialog offers Damaged/Sealed/Unusual shortcuts and free text; these transfer to desktop condition notes |
| Chat workspace | Milestone 3; not implemented, as required by the capture-first priority |
| Security | App has no Internet permission, archives shared via restricted FileProvider, cleartext disabled, Android backup disabled, no API/signing secrets committed |
| Production signing | Debug acceptance key only. Stable private release signing and tested updates/migrations are still required |

Milestone 1 is **not signed off** until the Pixel hardware/quality/sustained capture
checks pass and remaining required acceptance issues are addressed. Milestones 2
and 3 have deliberately not begun; the requirements mandate completing and validating
each milestone before expansion. Do not deploy against production inventory yet.
