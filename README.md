# SnapIMS Next0.8.3 — standalone Shopify and smart market matching

**[Download the Android APK](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Next-0.8.3-debug.apk)** · [Source](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Next-0.8.3-source.zip)

Direct Shopify authentication, reviewed draft creation, staged photo uploads,
SKU/price/weight/stock updates, compatible marked-product linking, protected edits,
owner-approved status changes and selected-channel publication run on the phone.
No broker, hosting or computer is required. Approved locally, Active and published
to Online Store are distinct states with separate reviews and verified outcomes.

Offline market lookup combines SQLite full-text/prefix/trigram indexes and Apache
Commons Text similarity. Optional AI ranking uses a separately selected model and
one explicit request approval; it never replaces the local evidence/pricing rules.

[Release notes](docs/next/RELEASE_0.8.3.md) · [Direct Shopify setup and exact safeguards](docs/next/DIRECT_SHOPIFY.md) ·
[Smart matching](docs/next/SMART_MARKET_MATCHING.md) · [Executed checks](docs/TEST_RESULTS.md).
Real Canada VHS/physical Pixel/API publication acceptance remains UNRUN; host/mock
tests are not real-store proof. No real store product was changed in this task.
Install over the existing signed app without uninstalling or clearing private data.

# SnapIMS Next 0.8.1

**[Download the Android APK](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Next-0.8.1-debug.apk)** · [SHA-256](releases/SnapIMS-Next-0.8.1-debug.apk.sha256) · [Source ZIP](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Next-0.8.1-source.zip)

Standalone native Android inventory for Canada VHS, built on the existing SnapIMS
rules and compatible capture formats. No desktop process, local HTTP server or LAN
connection is required for capture, inventory, search, review or backup.

This update adds **easy local deletion and seven-day Trash recovery**. Delete a
batch from Batches, a record from its inventory details, a photograph beside its
preview, or a local draft from Shopify screens. Deleted entries disappear from
ordinary app lists. Recover them from **Settings → Trash** within seven days.
**Deleting here never deletes a Shopify product.** Recovery does not restart work.
Original photo bytes, immutable exports and internal identity/audit records are
retained; this is not secure erasure or storage reclamation.
[Deletion and recovery details](docs/next/TRASH.md).

The existing **AI workspace remains an on-demand application agent**.
Choose Chat Only, Read Access or Full Agent. The agent can inspect non-secret app
records through compact local tools, cross-reference the offline market library,
and propose reviewed inventory/pricing/recognition/listing workflows. Batch, task,
public-settings and existing Shopify controls use validated native services.
Tool activity, change previews, task links and token/cost records stay in the workspace.
Nothing changes without human approval; there is no continuous AI monitoring,
model polling or automatic photo transmission. Economy/Standard budgets bound requests.
[Agent capabilities and limits](docs/agent/APPLICATION_AGENT.md) ·
[0.8.1 release notes](docs/next/RELEASE_0.8.1.md) ·
[Executed tests](docs/TEST_RESULTS.md) · [Feature matrix](docs/next/FEATURE_MATRIX.md).

The A–D program is not complete: Shopify authorization and development-store
acceptance are unverified, and no broker is deployed. **Continue with ChatGPT is an experimental native prototype;
physical Pixel sign-in, discovery and subscription streaming are still unverified.**
Its contract uses the owner's externally verified official excerpts, independently
of API-key mode, with no automatic switch to API billing.

- Market database imports the supplied Harvester CSV, supported Harvester SQLite,
  desktop workbench SQLite and native market archives. Historical records remain
  searchable offline with FTS5/FTS4/indexed fallbacks, typo suggestions, barcode and
  edition/year/date/price filters, source listings and price distributions.
- Native validation loaded all **75,054 supplied USD records** plus **100,000
  synthetic records**, confirmed safe repeat import and restart persistence. The
  uploaded data is kept out of the public APK/source. [Usage and import contract](docs/next/MARKET_DATABASE.md).
- Sold evidence and operator decisions stay separate. Pricing preserves currency,
  includes shipping, uses source-compatible Decimal rounding and requires review.
  Applying USD evidence to CAD inventory requires an explicitly entered rate/date.
  Older evidence is retained with configurable historical-reuse warnings.
- AI chat stores local history and supports explicit provider connections, dynamic
  models, streamed responses, compatible inline attachments and one-time reviewed
  local tools. Unknown capabilities can be checked with explicit synthetic probes.
- The optional local model endpoint is on this phone; inventory needs no server.
- Shopify adds reviewed DRAFT creation, ordered images, SKU/price/weight/stock,
  durable per-item batch checkpoints and safe reconciliation through an optional
  hosted credential broker. Live publication requires a separate `SUBMIT` approval
  and is disabled by default at the broker. [Setup and limits](docs/next/SHOPIFY_SETUP.md).
- No Shopify client secret or token is embedded in the APK. Inventory needs no broker.

## Standalone inventory

- Permanent physical-copy Item ID / SKU, separate from barcode and capture UUID.
- Search/filter and full on-phone metadata, condition, location, price/discount,
  stock, weight and listing-readiness editing, with optimistic revisions and audit.
- Add/retake/reorder/regroup/remove/restore photographs on finished items; previous
  originals and historical ZIP exports remain intact.
- Passphrase-protected, photo-inclusive backup/merge restore; duplicate-safe portable
  and native capture ZIP imports, desktop-compatible CSV and read-only desktop export.
- Real local inventory counts and offline operation; no fake sales or pricing data.

Read [Milestone A usage and limits](docs/next/MILESTONE_A.md),
[architecture/compatibility](docs/next/ARCHITECTURE_AND_COMPATIBILITY.md) and
[build/CI instructions](docs/next/BUILD_AND_CI.md). GitHub Actions builds APKs/tests;
update-compatible CI signing needs the retained key configured as a repository secret.

## Camera update

- Resolution selector in Capture and Settings: about 1.2, 3, 6, 12 MP or maximum.
  New default: 3 MP; the preview shows the actual supported size.
- Pinch-to-zoom, a zoom slider and 1× reset; full-frame preview.
- Fast capture defaults to zero shutter lag where supported, JPEG 95%, immediate
  press feedback and background verification. Another photo can be taken while saving.
- Landscape places controls beside the camera and preserves the screen and capture
  through rotation.
- Barcode scans inspect overlapping regions at original detail, avoiding the
  previous full-image reduction that lost small barcode bars.

See [camera details and limits](docs/CAMERA_0.1.2.md). Flash, HDR, low light and phone
hardware affect shutter timing; near-instant exposure needs physical device measurement.

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

Install 0.7.0 **over** 0.1.x, 0.2.0, 0.3.0, 0.4.0, 0.5.0 or 0.6.0; do not uninstall or clear app storage.
Application ID and signing key are unchanged, versionCode increases to 9. Explicit
Room migrations 1→2→3→4→5→6→7→8 retain captures/archives and add inventory, permanent
market evidence/decision history, chat, Shopify jobs and approved task receipts.
Pending AI approvals from earlier versions expire for fresh barcode-aware review.
Restored unfinished tasks pause for fresh review; old unbound API recognition jobs
require an explicit manual retry after upgrade. Large market evidence
is streamed into consistent SQLite members of complete protected backups. Restored Shopify jobs require fresh pairing and manual
reconciliation; temporary upload credentials are excluded from backups.
Existing API-key credentials and camera preferences remain on the device. New
protected backups exclude API keys and OAuth credentials, which must be reconnected on a different phone.
Save and verify backups outside app-private storage before device reset/app removal.

## Build

JDK 21, Android SDK platform 37.0 / Build Tools 36.0.0. Gradle 9.8.2, AGP 9.4.1 and
Kotlin 2.3.21 are pinned. The app includes a verified, source-built Tesseract AAR and
English data, so normal builds need no JitPack access, NDK or runtime downloads.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:lintDebug
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

CameraX supports selectable JPEG resolution, fast/quality modes, autofocus, tap focus,
zoom, exposure, torch/flash, rear/front switching and available HDR. Pixel Camera
processing parity is not claimed.

## Validation and scope

[Current results](docs/TEST_RESULTS.md) · [known limits](docs/STATUS.md) ·
[Pixel acceptance](docs/PIXEL_ACCEPTANCE.md) · [architecture audit](docs/ARCHITECTURE_AUDIT.md) ·
[third-party notices](docs/THIRD_PARTY_NOTICES.md)

Physical Pixel sustained capture/quality, Android JNI OCR and actual hardware
Keystore checks remain device acceptance gates. Live OpenAI was not run in this
cloud environment because no API key was supplied. Wireless pairing/direct desktop
sync and chat remain separate work. Existing QR desktop import stays compatible.

## License

SnapIMS Next is licensed under [Apache-2.0](LICENSE), as selected by the owner. See [NOTICE](NOTICE) and bundled third-party notices. Separate desktop repositories are unchanged.
