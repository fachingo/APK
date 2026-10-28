# SnapIMS Next 0.2.0 validation

| Check | Actual outcome |
| --- | --- |
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| CameraLayoutTest | 1 passed |
| CameraUpdateTest | 6 passed |
| JournalIntegrationTest | 6 passed |
| RecognitionClientTest | 7 passed |
| RecognitionStorageTest | 7 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| Unique host tests | **57 passed, 0 failed, 0 skipped** |
| APK / instrumentation APK | Built |
| Android lint | Passed, 0 errors; existing warnings remain |
| Signing identity vs 0.1.1 / 0.1.2 | Matches |
| 16 KB APK alignment | Passed |
| Desktop → native inventory package | Actual desktop processor fixture; original Item IDs and all 4 photos preserved, repeated import adds 0 records |
| Desktop exporter read-only proof | Original source SQLite bytes unchanged before/after export |
| 100 items / 300 photos | Actual Room/filesystem/archive/export/restore synthetic run; all records and photos restored, repeated import adds 0 |
| Protected backups | Real AES-GCM round trip, wrong-password and altered-tag rejection, original bytes restored |
| Conflict / low-storage / stopped-job checks | Passed without replacing inventory/originals; stopped transfer marked INTERRUPTED |
| Finished-item correction / retake | New photo retained, old original retained, earlier ZIP checksum unchanged |
| Standalone UI | Real local record edited/marked ready; Home/Inventory/Backup and portrait/landscape navigation exercised; rendered PNGs inspected |
| Source business-rule conformance | Generated desktop fixtures: money HALF_UP, discounts, IDs/photo naming, conditions and QR vocabulary; real QR raster decoding |
| Upgrade | Existing v1 captures/tables/files preserved through explicit 1→2→3 migrations |
| GitHub Actions hosted execution | Workflow supplied; local success is not a claimed hosted CI run |
| Physical Pixel / real 100-tape session / force-close hardware proof | NOT RUN: no attached device |
| Live Shopify / SIWC / paid AI account calls | NOT RUN: no dev-store/eligible account binding; required official docs denied by network policy |

The synthetic photographs are generated JPEGs and do not measure camera quality,
shutter latency or real VHS accuracy. Live API tests are not replaced by mock claims.
API-key recognition remains optional and separate from unverified SIWC subscription
usage. No credentials, production inventory or Shopify listings were modified.

Robolectric's directory-fsync stub is replaced in host fixtures with a real Linux
FileChannel directory fsync. App test classes use separate JVMs to prevent process-
global WorkManager teardown callbacks from crossing test-runtime boundaries; checks
are not disabled. Test/build and lint run sequentially to avoid the legacy kapt/lint
stub-regeneration race. Device tests compile but are unrun here.

The read-only desktop export utility requires an explicit verified source timezone
for old naive timestamps. Repeated native/desktop imports retain immutable physical
identities. SHA-256 and JPEG preflight happen before transactional record insertion;
conflicts reject rather than overwrite current records.

APK SHA-256: 19eb01fee8380648d13db73661b4d0a0d6ec08bcb661d6b1d4bbec9af21fa376

Signing certificate SHA-256: b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae

Logs: /workspace/.setup/v020-final-validation.log and v020-standalone-validation.log.
JUnit XML: core/build/test-results/test and app/build/test-results/testDebugUnitTest.
Earlier camera/recognition evidence: TEST_RESULTS_0.1.2.md / TEST_RESULTS_0.1.1.md.
Current limitations and A–D progress: next/FEATURE_MATRIX.md, next/MILESTONE_A.md,
next/SHOPIFY_SETUP.md and next/SIWC_INVESTIGATION.md.
