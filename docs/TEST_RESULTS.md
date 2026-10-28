# Version 0.1.2 validation

| Check | Actual outcome |
| --- | --- |
| CaptureRulesTest | 10 passed |
| LocalRecognitionTest | 6 passed |
| CameraLayoutTest | 1 passed |
| CameraUpdateTest | 6 passed |
| JournalIntegrationTest | 6 passed |
| RecognitionClientTest | 7 passed |
| RecognitionStorageTest | 7 passed |
| Total host tests | **43 passed, 0 failed, 0 skipped** |
| Debug APK and instrumentation APK | Built |
| Android lint | Passed; existing warnings remain |
| APK version | 0.1.2 / versionCode 3 / ca.canadavhs.snapmobile |
| Signing identity vs 0.1.1 | Matches |
| 16 KB ZIP alignment | Passed |
| Bundled Tesseract AAR and English data integrity | Verified by preBuild |
| Physical camera, exposure latency, live zoom and sensor-size selection | NOT RUN: no device attached |
| Native Android region decoder, landscape form windows, JNI OCR and hardware Keystore | NOT RUN: no device attached |
| Live OpenAI completion | NOT RUN: no key supplied; unchanged from 0.1.1 |

The new tests exercise three pending captures with distinct IDs/positions and correct
FRONT/BACK/ADDITIONAL roles, the pending-save bound, blocked Next/Finish, interrupted
save recovery, short camera reservations during slow filesystem work, immediate press
acknowledgement and accepting another shutter before verification finishes.

A real 4032×3024 JPEG fixture contains a small UPC near a corner. The old 2200-pixel
full-frame pass cannot decode it; overlapping native-detail tiles decode it correctly.
Robolectric stubs BitmapRegionDecoder with blank regions, so this test injects region
pixels from a real JPEG decoded by native BitmapFactory. It checks actual ZXing decode,
tiling and edge coverage, but does not claim physical Android region-decoder validation.

Compose host checks at 900×420 landscape and 420×900 portrait exercise visible shutter,
Next and resolution controls, persistence of a changed size through Settings, and screen
selection through simulated rotation with Activity orientation/size configuration flags.
Both layouts were rendered to PNG and visually inspected. These fixtures have camera
permission denied and therefore do not test a live camera. Text-input Dialog windows
are unreliable in this host simulator; a read-only landscape notes/scroll/cancel check
is included in the compiled device suite and remains unrun.

The prior Room migration, archive recovery/original preservation, OpenAI protocol,
secure wrapping-key storage, recognition queues and OCR-data tests still pass.
See TEST_RESULTS_0.1.1.md and TEST_RESULTS_0.1.0.md for earlier separate evidence.
No live inventory, API key or paid OpenAI request was used.

Build/tests and lint ran sequentially to avoid an AGP/kapt race in which lint can read
temporary Java stubs while test compilation regenerates them. No lint checks were
disabled. CameraX's experimental zero-shutter-lag API is explicitly opted into; hardware
support and real capture timing remain physical acceptance checks. Lower resolution,
fast mode, flash/HDR, storage and lighting affect speed. No instant-exposure claim is
made from host timing. See CAMERA_0.1.2.md and PIXEL_ACCEPTANCE.md.

APK SHA-256: bdcbc1b291cbd4bab15c675ccc6ad30b72b3ca50f664b9f8105f5d259dd9778c

Signing certificate SHA-256: b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae

Final build log: /workspace/.setup/v012-final-validation.log.
JUnit XML: core/build/test-results/test and app/build/test-results/testDebugUnitTest.
Rendered layouts: app/build/fixture-output/landscape-controls.png and portrait-controls.png.
