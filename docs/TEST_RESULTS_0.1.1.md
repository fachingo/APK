# Version 0.1.1 validation

| Check | Actual outcome |
| --- | --- |
| Core grouping/barcode rules | 10 passed |
| New deterministic barcode/image checks | 6 passed |
| Existing Room/filesystem/archive integration | 6 passed |
| OpenAI protocol, parsing, HTTPS/header and failure handling | 7 passed, using fake HTTP responses/interceptors |
| New key storage, migration, queues, OCR data and enriched ZIP checks | 7 passed |
| Total host/JVM tests | **36 passed, 0 failed, 0 skipped** |
| Debug APK / device-test APK | Built |
| Android lint | Passed; existing legacy-DSL/deprecation/metrics warnings remain |
| Original→updated APK signing certificate | Matches |
| Native Tesseract/Leptonica/jpeg/png ELF alignment | All four verified for 16 KB pages |
| Native/library and English data SHA-256 | Verified before build; data rechecked during extraction |
| Host OCR smoke | Three generated front/back/softened text images transcribed as expected using bundled English data |
| Android-produced enriched ZIP → actual desktop CLI | Passed; current recognition retained, duplicate import reused IDs, inventory title unchanged |
| Live OpenAI completion | NOT RUN: no API key supplied; cloud API destination blocked |
| Physical Pixel / real hardware Keystore / Android JNI OCR | NOT RUN: no device attached |
| Sustained 100 real tapes / Pixel Camera quality benchmark | NOT RUN; remains a physical acceptance gate |

The host OCR binary was Tesseract 5.5.0; the Android source build is Tesseract 5.5.1.
The three high-contrast generated samples are smoke checks, not a VHS accuracy benchmark.
Do not infer universal reliability or Pixel Camera feature parity from these results.

New tests exercise real ZXing image decoding (UPC-A, UPC-E, multiple/missing barcodes),
checksums/normalization, classical metrics, secure AES-GCM storage without plaintext,
fresh IVs, corrupt-key errors, migration from the checked-in v1 schema preserving
all five capture tables and existing files, duplicate-job avoidance, mode cancellation,
stale-input protection, explicit title confirmation, uncertain OpenAI restart protection,
corrupted/interrupted language-data recovery and actual recognition-bearing ZIPs.

OpenAI tests cover fixed HTTPS origin/Bearer header, Responses input images, strict
schema, store=false, bounded output parsing, mandatory review, refusal/incomplete
responses, invalid fields, model discovery and authentication/permission/quota/model/
server errors. They do not substitute for a paid live API request with the owner's key.
No real key is committed or requested in chat. The app uses Android Keystore; host
cipher tests inject a software wrapping key. A separate compiled device test exercises
an isolated real Keystore alias without changing the owner's saved API key.

The existing 0.1.0 QR/import evidence remains in TEST_RESULTS_0.1.0.md. The enriched
manifest was also imported and reimported through the real desktop CLI against
throwaway data, retaining current recognition while leaving inventory title authority
unchanged. Existing production inventory/source code was not modified by these checks.

Current logs: /workspace/.setup/v011-final-validation.log, v011-ocr-smoke.json,
v011-interop.json and tesseract-native-build.log. JUnit XML is under core/build/test-results
and app/build/test-results. Device test sources include read-only navigation, generated
OCR text and isolated hardware-key round-trip; those tests are compiled but unrun here.

Install over 0.1.0 without uninstalling/clearing storage. See RECOGNITION_0.1.1.md for
configuration, costs, recognition states, provider limits and update preservation.
