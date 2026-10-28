# Milestone A: standalone local inventory

The Android application is now an independent source of inventory. It runs without
FastAPI, Python, desktop SnapIMS, a LAN connection or any hosted inventory server.
The desktop source/database remains unchanged.

Start a batch, photograph front/back/details, press NEXT, and finish. Open Inventory
to search physical copies and edit title, edition, year, condition/damage, location,
barcode, price/discount, quantity, weight, vendor/type/tags/description and readiness.
Item ID/SKU is immutable and never equals a UPC. Two copies of the same movie retain
separate identities and condition/price records. Every edit has a revision check and
a durable audit entry. Recognition is optional; only explicitly confirmed metadata
can be applied. Manual price changes are labelled manual, not AI or sold evidence.

Manage original photos from a saved inventory item: add/retake, remove/restore,
reorder, change roles and move between items in the same batch. Retake retains the
old original until the replacement is saved. Corrections invalidate readiness and
never rewrite an already exported ZIP. Old capture snapshots remain historical;
new portable packages carry the current inventory and photograph assignments.

The primary camera retains selectable resolution, zoom, fast/quality modes and
landscape support. QR command capture uses the established CVHS1 vocabulary,
excludes command cards from product photo counts, records a command-photo hash,
ignores unrelated consumer QR URLs, treats CONT as the existing compatibility no-op,
and defers a QR location change inside an item until NEXT. END still asks to finish.

## Protected backup and restore

Settings → Backup & import → Create protected backup. Use a passphrase of at least
12 characters and save/share the generated .snapbak file outside app-private storage.
The format uses PBKDF2-HMAC-SHA256 (310,000 iterations), a random salt and AES-256-GCM
with a random nonce and authenticated versioned header. The ZIP payload streams
inventory, captures, original files, archives, recognition metadata and audit history.
API keys and Keystore credentials are excluded and must be reconnected on a new phone.
Keep the passphrase separately: it is not stored and cannot be recovered.

Restore authenticates the entire encrypted file, stages/checks all JPEG hashes,
checks every record, then merges new non-conflicting data. Identical records/files
are reused. Different existing IDs, changed records, unsafe paths and corrupt files
stop the import without overwriting current data. This is a protected merge restore,
not a silent rollback of newer edits. Restore into a new/empty app for a full snapshot;
existing changed records require manual resolution. Interrupted provider requests
require manual retry to avoid unexpected charges. No work is labelled running after
the app has stopped it. Keep the app open during foreground transfers; if Android
kills the process, reopen and retry the interrupted operation.

Original files are installed before the database commit. An interruption can leave
verified orphan files, which a retry recognizes by hash; it cannot create committed
references to unfinished files. Rollback never deletes or replaces an existing original.

Portable ZIP exports are unencrypted photo-inclusive interchange packages. CSV uses
existing SnapIMS column names and stable Item IDs; the historical desktop CSV importer
updates already-known IDs rather than creating new inventory. The included read-only
export_standalone.py moves existing desktop batches to the phone with identities intact.
Native snapims.mobile v1 capture ZIPs also import. Historical desktop mobile-import
receipts still mint their own IDs and are not silently linked or synchronized.

## Upgrade and limits

Install the higher-version APK over 0.1.x; do not uninstall or clear storage. Same
application ID/signing identity, versionCode 4, and explicit Room 1→2→3 migrations.
Existing sessions, UUIDs, photos, archives and API-key storage remain intact. Legacy
photographed groups receive inventory identities; historical desktop receipt mappings
are not guessed. Capture resolution preferences are retained.

Imports/backups enforce 32 MB metadata, 100,000 files and 100 GB package limits, plus
available private staging space. Original import JPEGs are limited to 250 MB and
20,000 pixels per edge. Larger collections can be transferred in batches. No hard
photo-per-item limit is imposed during capture. Backups need private temporary space
and must wait for active captures/archive creation to finish. Source loss/corruption
is reported rather than presented as a complete successful backup.

Host synthetic 100-item/300-photo, restore, migration and source-conformance checks
are distinct from the required real physical Pixel capture/force-close/storage tests.
No actual device, production inventory, live Shopify transaction or paid API request
was used to create this milestone. See TEST_RESULTS and the completion matrix.
