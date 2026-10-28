# CODEX MASTER BUILD DIRECTIVE
## SnapIMS Next — Standalone Android Inventory & Business Management Application

### 1. Primary objective

Transform SnapIMS into a complete, high-quality, standalone Android application for managing Canada VHS, with the Google Pixel as the primary target device.

The Android app must operate independently of any desktop computer or home server.

**A desktop computer must NOT be required for normal inventory capture, recognition, database management, pricing, Shopify listing creation, image upload, bulk publishing, or business management.**

All major core workflows must be executable on the phone itself.

Internet connectivity is permitted when communicating directly with Shopify, OpenAI, other AI providers, or optional external services.

Offline-first capture and inventory functionality are mandatory.

The original desktop SnapIMS application must remain functional and must not be broken or replaced.

Source repository:
https://github.com/fachingo/SnapIMS

Treat this as a production software development project, not a UI demonstration.

### 2. Inspect everything before coding

Fully investigate the existing SnapIMS codebase, including:

- Current FastAPI application and browser interface.
- SQLite inventory schema and migrations.
- Image capture/import conventions and QR command vocabulary.
- Batch management and unique item IDs.
- Recognition providers and existing OpenAI integration.
- Pricing engine and VHS Market Harvester database integration.
- Shopify authentication, GraphQL services, bulk operations, durable jobs, and reconciliation.
- Existing validation, confirmation, exception handling, and recovery.
- Relevant project documentation and test fixtures.

Create an architecture and compatibility report before implementing major components.

Identify which existing rules, schemas, workflows, and algorithms can be ported to Android and which can be reused through shared specifications or fixtures.

Do not create an unrelated inventory system.

The existing SnapIMS product and batch formats must remain compatible wherever practical.

Do not modify production inventory or Shopify listings during development without an approved test procedure.

### 3. Technology and application architecture

Preferred stack:

- Kotlin
- Jetpack Compose
- Material 3
- CameraX
- Room and SQLite
- Kotlin Coroutines / Flow
- Retrofit or Ktor for network operations
- Android Keystore for credential protection
- WorkManager and appropriate Android transfer/job APIs
- Proven OCR and barcode libraries

Use supported stable versions and follow modern Android architecture guidance.

Build a self-contained native Android APK.

Use local repositories and a modular architecture separating:

1. Camera and capture
2. Inventory and batches
3. Image recognition
4. Pricing
5. Shopify
6. AI workspace
7. Tasks and calendar
8. Communications
9. Settings, security, and backup

The phone's inventory database must be usable without a cloud server.

Do not require a local HTTP server, desktop process, LAN connection, or external SnapIMS backend for core functions.

Avoid fragile schemes that attempt to run the entire existing Python/FastAPI desktop application inside Android.

### 4. Native photography and inventory capture

Build a full-screen, premium-quality camera interface optimized for rapid VHS inventory photography.

The essential user workflow:

1. Photograph the front of the tape.
2. Photograph the back.
3. Optionally photograph additional details.
4. Press NEXT.
5. Begin photographing the next physical tape.

NEXT is the authoritative product boundary.

Requirements:

- Highest practical quality on the Pixel.
- Full-resolution originals retained.
- Proper autofocus, exposure, flash, lens, and orientation controls where supported.
- Instant capture feedback.
- No unnecessary compression or destructive processing.
- Reliable capture with weak or absent internet.
- Unlimited additional photographs per item subject to available storage.
- Undo Next, retake, delete, reorder and regroup.
- Automatic capture timestamps.
- Barcode recognition on the second photo.
- Persistent unique item and batch identifiers.
- Optional locations, Rare/Review flags, and QR-card compatibility.
- Automatic crash recovery.
- Clear storage usage and low-storage warnings.

Original images must remain linked to their inventory records.

Never use a UPC as the sole unique item identifier.

Provide complete batch review and correction screens.

### 5. Offline inventory database

Implement a complete local inventory database, not merely a temporary capture queue.

Store:

- Batches
- Individual physical items
- Images and their roles
- Barcode identifiers
- Title and edition metadata
- Condition and damage notes
- Product location
- Pricing information and provenance
- Shopify product/variant/inventory identifiers
- Publishing state
- Recognition status
- Task/job state
- Audit history

Use durable transactions, schema migrations, and backup/restore.

Avoid storing original image blobs inside SQLite; store durable file references and integrity hashes.

Preserve the ability to export SnapIMS-compatible manifests and backups for desktop use.

Do not invent new business rules where existing SnapIMS rules can be verified and ported.

### 6. Recognition system

Provide configurable recognition modes:

**No AI:** Barcode scanning and standard offline OCR.

**Local intelligent processing:** OCR, basic title extraction, barcode matching, image-quality warnings, and metadata matching using available local resources.

**Cloud AI:** Optional OpenAI, Gemini, Groq, OpenRouter, and other supported providers.

Use proven recognition components rather than writing new barcode/OCR engines unnecessarily.

Allow different model choices for different tasks.

Display suggestions and uncertainties before committing ambiguous metadata.

Recognition failures must not prevent photography, grouping, or inventory creation.

### 7. Existing VHS pricing database

Preserve the current sold-comparables-based pricing methodology.

Investigate the existing VHS Market Harvester and SnapIMS pricing integration.

Create a portable local SQLite pricing database or other appropriate offline-compatible representation.

Provide:

- Local title/barcode searches.
- Sold-comparable lookup.
- Existing pricing rules.
- Database freshness indicators.
- Versioned pricing-data imports/updates.
- Price explanation and source evidence.
- Missing-comparable and manual-review states.

Do not substitute AI-generated prices for database-backed prices without explicit operator permission.

Use existing Canada VHS settings for default weight, special collections, and pricing/discount rules. These settings must remain configurable rather than hardcoded.

### 8. Shopify integration — critical requirement

The phone must be capable of directly preparing, uploading, and managing Shopify inventory without contacting a desktop computer.

Read and follow the latest supported Shopify Admin GraphQL and authentication documentation.

Relevant official documentation:

https://shopify.dev/docs/apps/build/authentication-authorization

https://shopify.dev/docs/api/admin-graphql/2026-10

https://shopify.dev/docs/apps/build/apis/graphql-admin/bulk-operations/imports

Investigate the existing SnapIMS Shopify client-credentials implementation before designing mobile authentication.

**Do not assume the old static Shopify Admin API token approach remains appropriate.**

Security requirements:

- Never embed Client Secrets in APK source, resources, or build outputs.
- Prefer an officially supported secure authentication flow.
- If Shopify requires a confidential client, design an optional minimal remote authentication broker that holds only credentials, not inventory data or processing logic.
- Investigate whether a private, user-configured direct-device mode is technically possible; document the security and support tradeoffs.
- Do not misrepresent a phone-stored secret as equivalent to server-side secret storage.
- Use least-privilege Shopify scopes and protected credential storage.
- The core Android app must not depend on a home server.

Shopify features:

- Store connection and permissions testing.
- Browse existing listings.
- Search for duplicate products.
- Read draft/active/archive status.
- Read and update supported product information.
- Create individual draft products.
- Prepare and bulk-create drafts.
- Upload photographs directly from the phone.
- Set prices, weights, stock, tags, descriptions, and inventory details.
- Preserve existing SKU conventions.
- Support Shopify collections and product organization.
- Display API errors clearly.
- Retain remote product and variant IDs.
- Reconcile interrupted publishing operations.
- Retry safely without creating duplicate listings.
- Show job progress and individual failures.
- Allow controlled publication after review.

Evaluate the current `productCreate`, `productSet`, `productUpdate`, `stagedUploadsCreate`, and `bulkOperationRunMutation` APIs.

Use the appropriate APIs rather than assuming that one mutation covers every media, variant, and inventory requirement.

Be particularly careful that product synchronization does not unintentionally remove omitted list-valued fields.

Do not use deprecated product-media APIs when current alternatives exist.

### 9. Full mobile bulk-upload workflow

Implement this exact end-to-end operation:

1. Capture 100 or more tapes.
2. Finish the batch.
3. Run barcode and OCR recognition.
4. Apply optional AI recognition.
5. Match titles and editions.
6. Check the local inventory and Shopify for duplicates.
7. Price items using the existing comparable-sales database.
8. Generate listing metadata.
9. Display exceptions needing operator review.
10. Select eligible products.
11. Create Shopify drafts.
12. Upload all associated photographs.
13. Reconcile returned product, variant, and media IDs.
14. Display completed, failed, and pending items.
15. Permit explicit bulk publication of approved items.

The default action is ALWAYS draft creation.

Live publication requires explicit operator approval.

Maintain durable per-item checkpoints, idempotency and recovery.

If the network disconnects halfway through uploading 200 products, successfully uploaded items must remain recorded and must not be duplicated on retry.

### 10. AI assistant and agent workspace

Implement a polished AI interface inside the Android application, comparable in usability to leading AI chat applications.

Use a well-maintained open-source interface or native UI library after reviewing licences, maintenance, and compatibility.

Features:

- Multiple persistent conversations.
- Streaming responses.
- Markdown and code formatting.
- Image and file attachments.
- Conversation search.
- Model/provider selector.
- Custom system instructions.
- Optional saved user preferences.
- Cost and token reporting where available.
- Local conversation-history database.
- Export and deletion controls.
- Tool-calling and agent workflows.
- Task histories, cancellation, and retries.

Allow the assistant to use authorized SnapIMS tools for inventory search, recognition, pricing, batch review, listing preparation, and Shopify operations.

Use existing application services through typed interfaces; do not let the agent execute unrestricted SQL or arbitrary shell commands.

Support free-tier AI providers and local models where technically practical.

Discover provider models dynamically and enforce actual supported model capabilities.

An agent must never silently publish products, delete inventory, modify many prices, or perform irreversible operations without an appropriate approval workflow.

Background operations must follow real Android execution limits.

Do not promise indefinite background execution on a terminated or offline phone.

### 11. Dashboard and business management

Implement a clean, phone-first home dashboard showing:

- Current inventory count.
- Batch status.
- Items requiring attention.
- Draft and active Shopify products.
- Pending uploads.
- Current sales/order summaries where available.
- Scheduled tasks and reminders.
- Recognition and AI job status.

Reuse real local and Shopify data.

Do not display invented operational metrics.

### 12. Calendar and tasks

Implement a local operations calendar with:

- Tasks
- Follow-up reminders
- Shipping deadlines
- Inventory milestones
- Optional AI-generated task suggestions
- Device notifications
- Recurring tasks
- ICS import/export
- Optional Android calendar integration

Core calendar functionality must work without a remote server.

Make external calendar integration optional.

### 13. Customer communications

Add a Communications module that supports:

- Customer communication templates.
- Draft message generation.
- Order-related notes.
- Attachments.
- Android sharing intents.
- Opening compatible external mail applications.

Investigate Proton Mail compatibility before attempting a full email client.

Do not claim that unsupported Proton Mail APIs exist.

A native Proton inbox is not required for the initial release.

### 14. Design requirements

This application must look like a modern premium consumer product, not an internal ERP dashboard.

Use native Material 3 design with excellent attention to:

- Typography
- Visual hierarchy
- Spacing
- Color consistency
- Dark and light themes
- Touch targets
- Motion
- Navigation
- Loading states
- Empty states
- Error and recovery states
- Accessibility

Design for comfortable one-handed use on a Google Pixel.

Navigation should provide clear access to Home, Capture/Inventory, Shopify, AI, and Planner, with secondary features in appropriate screens.

Build a consistent reusable component library.

Do not sacrifice functionality for visual effects.

### 15. Backup, migration, and interoperability

The phone must be able to function as an independent source of inventory data.

Provide:

- Encrypted or otherwise appropriately protected backups.
- Photo-inclusive export packages.
- SnapIMS-compatible manifests.
- Database migrations.
- Import from existing SnapIMS batch exports.
- Recovery after application updates.
- Clear handling of duplicate records and ID conflicts.
- Optional future synchronization with desktop SnapIMS.

Do not delete or replace the existing desktop SnapIMS production database.

Do not silently enable synchronization or overwrite records.

### 16. Background processing and reliability

Use durable job records, resumable processing, checkpoints, explicit cancellation, and Android-supported background execution.

Distinguish between:

- Work running on the phone.
- Work delegated to Shopify.
- Work delegated to an AI provider.
- Work requiring the application to reopen or reconnect.

Do not claim a task is still running locally when Android has stopped it.

Implement detailed error reporting, retry handling, rate-limit backoff, and recovery after force-close or reboot.

Provide visible notifications for lengthy uploads where supported.

### 17. Development milestones — mandatory

**Milestone A: Functional standalone inventory application**

Deliver a real APK with camera, photo grouping, barcode scanning, local SQLite inventory, offline support, export, backup, and a polished interface.

**Milestone B: Complete Shopify functionality**

Implement the approved authentication design, direct image transfer, real draft creation, product reconciliation, and bulk jobs.

Demonstrate one real end-to-end product draft with images, followed by a multi-item batch.

**Milestone C: Recognition and pricing**

Integrate offline recognition, optional AI, sold-comparable database, bulk review and complete inventory-to-Shopify workflow.

**Milestone D: AI workspace**

Add provider selection, persistent chats, attachments, tool-based agents, approval controls, and task management.

**Milestone E: Business modules**

Add dashboards, calendars, communication tools, notifications, and optional synchronization.

Every milestone must have a usable build, tests, and documented limitations.

Do not begin building elaborate secondary modules before the previous critical workflows work.

### 18. Acceptance tests

Test at minimum:

- 100 products, 300 photographs in a real capture session.
- Force-close during capture.
- Force-close during bulk upload.
- No-barcode and duplicate-barcode tapes.
- Two copies of one movie with different conditions.
- Failed Shopify authentication and token expiry.
- No internet during capture.
- Unreliable mobile data during upload.
- Incomplete recognition results.
- Correct pricing database lookup.
- Accurate Shopify variants, inventory, prices, weights and images.
- Duplicate-safe import and publishing.
- Restore inventory from backup.
- App upgrade without data loss.
- Device storage exhaustion.
- Restartable jobs and accurate progress indicators.

Provide deterministic automated tests for ported SnapIMS business rules using existing source fixtures.

Use real Shopify development-store testing where credentials are available.

Never represent mocked API responses as successful live integration tests.

### 19. Deliverables

- Complete Kotlin Android source.
- Buildable Gradle project.
- GitHub Actions APK build workflow.
- Installable APK artifact.
- Migration and compatibility documentation.
- Shopify setup instructions.
- Tested end-to-end workflow.
- Unit and integration tests.
- Third-party licence notices.
- Known limitations.
- Clear feature-completion matrix.

Provide a usable APK at the completion of each relevant milestone.

If a feature cannot be implemented without a server or unavailable credentials, document the exact blocker and the minimum necessary alternative. Do not quietly insert a desktop/server dependency.

### 20. Execution instructions

Begin by auditing the existing repository and creating the implementation plan.

Then implement Milestone A.

Continue through the milestones in order, validating each before expanding scope.

Do not ask repetitive architectural questions that the requirements already answer.

Favor working end-to-end functionality over broad superficial feature coverage.

**Definition of success: I can pick up my Google Pixel, photograph an entire collection of VHS tapes, identify them, price them using our database, prepare listings, upload photos and create Shopify products without needing to start my desktop computer.**