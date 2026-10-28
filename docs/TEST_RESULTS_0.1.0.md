# Validation results — 2026-10-27

This report distinguishes current cloud validation from physical Pixel acceptance.
The build is a Milestone 1 acceptance build. It is not certified for production use.

| Check | Actual outcome |
| --- | --- |
| Kotlin core rules | 10 passed, 0 skipped/failed |
| Android Room/filesystem integration (Robolectric API 35, native graphics) | 6 passed, 0 skipped/failed |
| SnapIMS selected current workflow + mobile adapter | 86 passed (includes 21 new adapter tests) |
| Synthetic throughput/import fixture | 100 capture groups / 300 generated JPEGs imported; repeat import kept exactly 100 items |
| Android → desktop interoperability | Actual ArchiveWorker ZIP imported via desktop CLI; 1 group / 2 photos, roles preserved, repeat receipt deduplicated |
| Actual CLI manual import | Two physical-copy groups imported, repeated command returned identical IDs and duplicate receipt |
| Pricing Workbench (Streamlit 1.50.0) | 61 passed, 1 browser test skipped |
| Pricing browser extraction/parser | Passed with installed system Chromium and a local HTTP fixture; no eBay request/data collection |
| Android debug app / instrumentation APK | Built; app package signature verified with apksigner (v2, one signer) |
| Android lint | Passed; dependency/legacy DSL deprecation warnings remain |
| Reusable install script | Executed end to end successfully, including native builds/tests and desktop/Pricing suites |
| Service startup | SnapIMS healthy schema-16 database, Import page rendered; Pricing server healthy and operator controls rendered |
| Repeated startup | Passed; healthy services reused |
| Existing QR behavior | Current protocol/import regression tests passed unchanged |
| Physical Pixel and device UI tests | NOT RUN; no device or /dev/kvm available |
| 100 real tapes / 300 actual camera photographs | NOT RUN; synthetic import is not a physical sustained-capture acceptance pass |
| Pixel Camera quality comparison / ML Kit vs ZXing benchmark | NOT RUN |
| Live Shopify/OpenAI | NOT RUN; no credentials needed for this offline milestone, no production writes |
| Default downloaded Playwright / live eBay | BLOCKED by current network policy; draft additions do not establish access |

Host integration tests exercise actual Room records, JPEG files, hashes and ZIPs:
explicit front/back/additional grouping, Next/Undo Next, session restoration,
pending-shutter boundary exclusion, interrupted/incomplete camera writes, photo
reordering/moves/tombstones, interrupted ZIP output/retry, ZIP manifest/original
byte verification, post-rename recovery and exported-group locking. Robolectric's
incomplete directory-fd emulation is replaced only at that platform boundary by a
real host directory FileChannel.force. Android POSIX directory fsync still needs
physical-device verification. Atomic file moves use Java NIO ATOMIC_MOVE on both.

Core tests cover UPC/EAN normalization, UPC-E expansion/checksum, missing/ambiguous
barcodes, duplicate UPCs across unique groups, 500 MB whole-product planning, exact
boundaries, oversized groups, unique friendly filenames and location vocabulary.

Adapter tests reject bad hashes/sizes, traversal/symlink/extra members, unsupported
versions, invalid roles/order/timezones, duplicate IDs and malformed manifests
before creating inventory. They cover incomplete/no-barcode groups, fallback
location, role/order/original-byte preservation, repeat/repacked imports, changed
group refusal and interruptions at four desktop journal/finalization boundaries.

## Existing SnapIMS full-suite limitation

Full-suite collection remains blocked by four pre-existing files that refer to
removed functions or fixtures: test_batch_recognition.py, test_interpreter.py,
test_recognition.py and test_sorter.py. Examples include list_results,
accept_result, read_photo_record and BASE_TIME. These are repository defects, not
missing packages. Existing source/tests have not been rewritten or disabled. Only
the selected 86 checks are claimed as passing; no full-suite pass is claimed.

Pricing's initial failures with newer Streamlit were traced to AppTest relative
path behavior. Streamlit 1.48.1 resolved that path but lacked the app's stretch-width
API; the supported 1.50.0 release satisfies both requirements and passes 61 checks.
This pin is in setup instructions, leaving the archived project unchanged.

## Evidence and remaining gates

Raw current-instance logs live in /workspace/.setup, including
reusable-setup-validation.log, android-tests.log, final-apk-build.log,
android-lint-final.log, startup-validation.log, startup-repeat-validation.log,
combined-tests.log, pricing-tests-compatible.log, android-desktop-interop.json and manual-import-*.json.
JUnit XML lives under core/build/test-results and app/build/test-results.

See PIXEL_ACCEPTANCE.md for device, storage exhaustion, sustained capture, update,
500 MB physical rollover and camera-quality gates. See STATUS.md for all pending
milestone 2/3 capabilities. Saving the environment draft is distinct from publishing
its filesystem snapshot and does not validate restoration into a new task.
