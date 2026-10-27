# SnapIMS Mobile — Android Companion Application

## Mission

Develop a beautiful, professional, offline-first Android application for my Google Pixel, primarily intended to capture VHS inventory photographs for Canada VHS and transfer them into the existing SnapIMS workflow.

The application must be production-quality, reliable, fast, visually polished, and installable as an APK.

**This is a companion to SnapIMS, not a replacement.**

Existing repository: https://github.com/fachingo/SnapIMS

Inspect the repository and existing documentation before implementing anything. Also reconcile the repository with newer local SnapIMS releases or code that may not yet be committed.

Existing systems include SnapIMS, the Canada VHS Shopify workflow, and the VHS Market Harvester SQLite sold-comparables database.

Do not recreate existing inventory, pricing, recognition orchestration, Shopify, or catalog logic. Reuse or extend existing systems wherever possible.

## 1. Mandatory architecture audit

Before implementation:

- Identify the current SnapIMS database schema, immutable IDs, batch importer, image processor, naming protocol, manifest writer, recognition providers, and Shopify workflows.
- Document the components that can be reused unchanged.
- Identify what requires a small integration adapter.
- Identify what must be implemented natively on Android.
- Avoid duplicated business logic or a competing inventory database.
- Preserve compatibility with existing QR-based batches.
- Do not overwrite or modify production inventory data during development.
- Record the architecture and implementation decisions in the repository.

The mobile application owns capture sessions, photo grouping, temporary local metadata, and transfer state. SnapIMS remains authoritative for inventory, catalog, pricing, and Shopify operations.

## 2. Android technology

Preferred stack:

- Native Kotlin
- Jetpack Compose and Material 3
- CameraX for photography
- CameraX Extensions where supported
- Room for the local capture/transfer journal
- Coroutines and Flow for asynchronous work
- A suitable Android background-transfer framework
- Android Keystore-backed secret storage
- Proven barcode and OCR libraries

Use current stable dependencies and standard Android architecture patterns.

Do not build a website wrapped in an Android WebView.

## 3. Photography and capture quality

The application must prioritize high-quality product photography.

Requirements:

- Use the highest practical resolution available from the selected camera.
- Default to quality-optimized capture rather than low-resolution fast capture.
- Preserve original full-resolution images.
- Support autofocus, tap-to-focus, exposure adjustment, flash/torch, lens selection, and optional gridlines where the hardware permits.
- Support device HDR extensions where available.
- Correctly preserve orientation and EXIF metadata.
- Default to the rear primary camera.
- Support optional volume-button shutter control.
- Provide instant visual shutter feedback and optional haptic feedback.
- Support rapid consecutive captures without UI freezes.
- Run barcode/OCR processing asynchronously without delaying photography.
- Avoid unnecessary recompression, resizing, filtering, or enhancement of originals.
- Use separate derivative images for OCR, AI, previews, and Shopify when needed.

The image quality should be benchmarked against the native Google Pixel Camera using the same objects and lighting. Do not claim feature parity without testing.

## 4. Capture workflow — essential

The primary capture interface revolves around two actions:

**CAPTURE** and **NEXT**.

A new photo session starts with a batch name, optional location, and an automatically generated session ID.

The workflow:

1. Press Capture to photograph the front of a VHS tape.
2. Press Capture again to photograph the back.
3. Capture any additional photos of the spine, cassette, stickers, damage, or special edition details.
4. Press Next to finalize the current product group.
5. The next captured photo begins a new product.
6. Repeat indefinitely until the operator completes the session.

Every photo between two Next events belongs to exactly one product.

The first image defaults to FRONT, the second to BACK, and subsequent images to ADDITIONAL, with editable roles.

Each group gets a persistent unique capture-group ID.

Important:

- Never infer a group boundary solely from time between photos.
- Next is the authoritative grouping action.
- Do not require photographing physical QR command cards.
- Provide Undo Next, retake last photo, delete photo, reorder photos, and move a photo between neighboring groups.
- Undo must not destroy original files.
- Warn about missing front/back images but permit incomplete products.
- Preserve unfinished groups when the application closes unexpectedly.
- On reopening, return to the exact session and unfinished product.

Provide a Finish Batch control that safely finalizes any open product and ends the session.

## 5. Automatic barcode recognition

The second image in a product group is the primary barcode source.

After capturing the back photograph:

- Automatically scan for UPC-A, UPC-E, EAN-13, EAN-8, and other relevant product barcode formats.
- Use a proven on-device decoder.
- Evaluate ZXing and Google's bundled ML Kit barcode scanner and choose based on measured reliability.
- Prefer offline functionality.
- Validate checksums for barcode types that support them.
- Preserve the raw detected barcode, barcode format, and normalized representation.
- Handle UPC/EAN leading-zero normalization correctly.
- Display the detected barcode immediately.
- Allow manual correction and a targeted barcode rescan.
- If multiple barcodes appear, identify ambiguity instead of arbitrarily choosing one.
- If no barcode exists, mark the group as NO_BARCODE and proceed.

Do not require a barcode to create an item.

Do not treat a barcode as a unique physical inventory identifier.

## 6. Automatic naming and manifests

Each capture group must have:

- Persistent capture-group ID
- Timestamp with timezone
- Sequential item number
- Batch ID/name
- Location
- Barcode, if found
- Ordered list of photos
- Photo roles
- Rare and Review flags
- SHA-256 hashes
- Recognition results and provenance, if available

Once a product is finalized, create a friendly filename stem using timestamp, barcode (or NOUPC), and a unique group identifier.

Assign unique photo suffixes such as `_F`, `_B`, `_03`, and `_04`.

The manifest is authoritative for grouping and order. Filenames are a convenience, not database identity.

Preserve original image bytes.

Export a versioned JSON manifest with all necessary metadata, including filenames, sizes, checksums, group membership, roles, and capture order.

Use an adapter that imports these mobile manifests into the existing SnapIMS import pipeline. SnapIMS should mint or reconcile its own immutable IDs.

Existing QR command parsing must remain functional.

## 7. Batch size and ZIP generation

Default maximum archive target: approximately 500 MB, configurable in Settings.

Requirements:

- Continuously calculate captured data size.
- Only split archives between complete product groups.
- Never split a product's photos across two ZIP archives during normal rollover.
- If a single product exceeds the threshold, allow an oversized archive and report it.
- Automatically finalize archive parts at product boundaries near the configured size limit.
- Generate portable ZIP archives containing photographs and the applicable manifest.
- Use date, batch name, and part number in archive filenames.
- Example: `CVHS_2026-10-27_BATCH1_PART001.zip`.
- Include hashes and counts so SnapIMS can validate imports.
- JPEG files generally benefit little from ZIP recompression; prioritize throughput and integrity.
- Archive processing must not block ongoing photography.
- The operator can also manually finalize an archive or batch.
- Never automatically delete source photographs after ZIP creation.

Use crash-safe temporary files and finalize archive names only after successful verification.

## 8. Offline-first reliability

**No photograph may be lost because the application crashes, loses internet access, or is interrupted.**

Every successful shutter event must result in a durable photograph and associated journal entry.

Requirements:

- Persistent local capture database.
- Atomic photo and metadata state transitions.
- Recovery of incomplete camera writes and interrupted exports.
- Safe handling of low storage.
- Warning thresholds for available storage.
- Restartable transfer queues.
- Checksum verification.
- Idempotent imports and uploads.
- Clear session states: Capturing, Archiving, Ready, Transferring, Imported, and Error.
- No unnecessary dependency on internet connectivity.
- All core camera, grouping, barcode, and export features must work without OpenAI credentials.

Implement robust diagnostics and actionable error messages.

## 9. SnapIMS synchronization

Support two transfer methods.

### Manual import — required

Allow the operator to export or share ZIP archives using Android's standard file access and sharing facilities.

The ZIP format must be importable into SnapIMS on the desktop through a documented workflow.

### Direct wireless synchronization — preferred

Add a minimal authenticated mobile-import interface to the existing SnapIMS FastAPI application.

Where practical:

- Discover the SnapIMS workstation on the local network.
- Pair the Pixel using a short-lived pairing code or QR code.
- Authenticate subsequent transfers securely.
- Display connection and server status.
- Send archives or resumable file parts.
- Verify file checksums.
- Return import receipts and actual SnapIMS batch/item IDs.
- Retry interrupted transfers without duplicating inventory.
- Never directly expose the SQLite database to Android.
- Never expose an unauthenticated SnapIMS import endpoint on the network.

If the desktop is offline, preserve pending transfers and allow later synchronization.

Existing SnapIMS import and validation logic must execute on the desktop rather than being duplicated on Android.

## 10. Recognition system

Provide three selectable recognition settings.

### Off

Only capture, group, scan barcodes, and export photographs.

No OCR or external model processing beyond necessary barcode/QR detection.

### Local recognition

Use existing proven libraries for offline OCR and barcode decoding.

Suggested components include ZXing, OpenCV, and optional bundled ML Kit text recognition.

Extract visible title text and other simple cover details when reliably possible.

Any title discovered locally is a suggestion until confirmed or reconciled with existing catalog evidence.

Use existing SnapIMS product/catalog lookups when connected. Avoid constructing a second movie catalog or pricing engine on Android.

### OpenAI-assisted recognition

Allow the operator to enable OpenAI image recognition explicitly.

Reuse the existing SnapIMS recognition provider wherever possible.

When standalone device-side inference is necessary, use the supported OpenAI API and structured outputs.

Allow selection among available compatible models.

Extract suggested title, edition, distributor, year, barcode candidates, confidence, and supporting evidence.

Do not overwrite trusted user-entered data with unverified AI suggestions.

Do not use AI-estimated prices to replace the existing VHS Market Harvester/database pricing system.

Show uncertainty clearly and route ambiguous results to Review.

Allow batch recognition after capture rather than requiring an API request on every photograph.

## 11. Recognition and image-quality assistance

Optional intelligent capture aids:

- Blur warning
- Excessive glare warning
- Poor lighting warning
- Edge clipping warning
- Suspected duplicate photograph
- Barcode-unreadable indicator
- Front/back role mismatch warning

These checks must not interrupt a successful capture.

Allow the operator to dismiss warnings or turn them off.

Where possible, run quality detection on lightweight preview images.

## 12. Inventory-specific controls

Support the established SnapIMS vocabulary:

- Batch Start/End
- Item Next
- Item Continue, if needed for compatibility
- Rare
- Review
- Locations A1–J10 and Q1

Implement these as native interface actions and manifest events.

Support scanning existing physical SnapIMS QR cards as an optional alternative.

Allow location to persist across multiple products until changed.

Provide a simple way to flag damaged, sealed, rare, unusual, or review-required tapes without creating a competing catalog.

## 13. User interface and visual design

The app must feel like a premium, modern camera utility—not a warehouse ERP.

Design direction:

- Clean, restrained, minimalist interface.
- Default dark theme with optional light theme.
- Rich graphite/near-black surfaces and carefully selected accents.
- Excellent typography and large readable numbers.
- Subtle animations and thoughtful haptics.
- Consistent spacing, corner radii, and iconography.
- Responsive layout for Google Pixel screen sizes.
- Accessible text contrast and touch-target sizes.
- No unnecessarily crowded menus, pop-ups, or modal dialogs.

Primary camera screen:

- Full-size camera viewfinder.
- Current batch and location at the top.
- Current product number and photo count.
- Large centrally located capture button.
- Next arrow directly beside the shutter.
- Undo and last-photo thumbnail readily accessible.
- Subtle Front/Back/Additional state indicator.
- Barcode confirmation immediately after the back photo.
- Small archive/synchronization status indicator.

Additional screens:

1. Capture
2. Batches and photo review
3. Transfers/synchronization
4. Settings
5. Optional AI workspace

Support one-handed usage wherever practical.

Build the UI as reusable Compose components with a coherent design system.

## 14. Settings

Include configurable controls for:

- Image quality and resolution
- Flash and focus preferences
- Barcode scanning
- OCR and recognition provider
- Optional OpenAI credentials
- OpenAI model selection
- ZIP size threshold
- Export destination
- Wi-Fi transfer preferences
- Default location
- Haptic and shutter sounds
- Image-quality warnings
- Data retention and cleanup
- Dark/light appearance

Credentials must be stored securely and never committed to source control.

## 15. Optional integrated OpenAI chat — secondary milestone

Design the app so a separate AI workspace can be added without affecting camera reliability.

Future capabilities:

- Chat interface using the OpenAI API
- Dynamic model selection from available models
- Streaming text responses
- Attaching photographs and supported files
- Persistent conversation history
- Background-capable API jobs, where supported
- Job progress, cancellation, and recovery
- Optional use of selected captured images in chat
- Token usage/cost visibility

Only expose features a selected model/API actually supports.

Use an appropriate server-side job orchestrator for persistent agent workflows where necessary. Do not assume an Android process can execute arbitrary agents indefinitely in the background.

Do not delay the core capture application to implement this section.

## 16. Validation and acceptance tests

Create automated unit, integration, and UI tests.

At minimum test:

- Front/back/additional-photo grouping
- Next, Undo Next, and photo reordering
- Missing barcode and ambiguous barcode scenarios
- Duplicate UPCs across physical copies
- Crash during capture, ZIP creation, and transfer
- 500 MB rollover between products
- Session restoration after force-close
- Offline capture with no API key
- ZIP contents, photo ordering, and hashes
- Import compatibility with the existing SnapIMS workflow
- Repeat import without duplicate items
- Camera resolution and quality on the target Pixel
- Recognition failures and recovery

Perform a realistic sustained capture test of at least 100 products and 300 photographs.

Validate the final Android APK on a physical Pixel where hardware access is available. Clearly identify any tests that cannot be completed without the device.

## 17. Delivery requirements

Deliver:

- Complete buildable Android source code.
- Installable APK.
- Reproducible Gradle build instructions.
- Necessary minimal SnapIMS import adapter.
- Versioned mobile ZIP/manifest specification.
- Configuration and pairing documentation.
- Test results and known limitations.
- Clear instructions for updating the app without losing captured data.
- A list of reused components and new components.
- All required licences and third-party notices.

Do not deliver a nonfunctional visual prototype in place of a working application.

Do not silently omit essential requirements.

## 18. Development priorities

**Milestone 1 — Production-quality capture**

Native camera, durable sessions, Next grouping, barcode recognition, naming, ZIP export, beautiful UI, and offline recovery.

**Milestone 2 — SnapIMS connectivity**

Authenticated desktop synchronization, manifest import adapter, import receipts, location and flag integration, optional OCR and OpenAI recognition, and duplicate warnings.

**Milestone 3 — Expanded intelligence**

Improved catalog matching, optional chat workspace, file attachments, model management, background API job coordination, and other secondary features.

Complete and validate each milestone before expanding scope.

**Primary success criterion: I should be able to process hundreds of physical VHS tapes using only my Pixel, with minimal manual interaction, and reliably hand the resulting inventory to SnapIMS without losing photographs, misgrouping tapes, or duplicating work.**