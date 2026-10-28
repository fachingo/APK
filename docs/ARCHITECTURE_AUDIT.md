# SnapIMS Mobile architecture audit — 2026-10-27

## Inspected baseline

The available SnapIMS checkout identifies itself as 0.15.0, inventory schema 16.
`LOCAL_SOURCE_INVENTORY.md` records its 0.14 -> 0.15 reconciliation. The checkout
was clean at inspection. Pricing_module contains only Pricing-Workbench-v0.1.0.zip;
APK has an unborn main branch and no source. No newer local release or production
inventory was present under /workspace. Releases on the owner's workstation cannot
be inspected here; compare them before deployment. No production data is used.

## Reuse unchanged

- `snapims/db.py`, migrations: authoritative SQLite inventory and durable import journal.
- `models.py`: BatchRecord / ItemRecord / PhotoRecord integration boundary.
- `interpreter.py:make_batch_id`, `manifests.py:item_id`: desktop-minted immutable
  Batch/Item IDs; SKU remains Item ID. UPC is not physical-copy identity.
- `import_v0130.py`: current folder/NEXT importer and preview/commit UI.
- `protocol.py`, `interpreter.py`, `pipeline.py`: CVHS1 BATCH START/END,
  ITEM NEXT/CONT, FLAG RARE/REVIEW, LOC A1-J10/Q1 parsing. Leave QR import intact.
- `processor.py:process_batch(prepared_batch=...)`: staged originals, independent
  derivatives, import journals, recovery, fingerprint deduplication, CSV and manifests.
- `images.py`: immutable original copy, separate normalized/preview/recognition images.
- `recognition/providers.py`, `recognition/service.py`: desktop recognition providers
  and durable orchestration; catalog matching remains on desktop.
- `snapims/pricing/`: existing pricing integration; no Android pricing/catalog database.
- `shopify/auth.py`, client/service/jobs: existing draft-first Shopify workflow.

## Small integration adapter

A versioned mobile manifest supplies explicit groups and order, bypassing inference
from filenames, timestamps or photographed command cards. Validate every ZIP member,
size, SHA-256 and image before writing anything to inventory; reject traversal,
symlinks, duplicates and incompatible versions. Build existing model records and call
`process_batch(prepared_batch=...)`. The desktop still allocates IDs. Record mobile
session/group provenance and physical photo roles separately without changing schema.
Use deterministic session/group fingerprints and reject changed reimports of a group.
Repeated archives must return the original receipt rather than duplicate items.
CLI/manual import is the required first connection path. Authenticated pairing and
wireless transfer belong to milestone 2, after physical-device capture acceptance.
Do not expose a new unauthenticated network endpoint.

## Native Android responsibilities

Kotlin + Compose/Material 3; CameraX quality capture with original JPEGs/EXIF;
Room capture and archive journal; coroutines/Flow; WorkManager archive recovery;
Storage Access Framework and FileProvider for exports. UUIDs identify sessions,
groups and photos. These are temporary capture IDs, never inventory IDs.

Before CameraX writes, reserve a Room photo row pointing to a UUID .pending file.
On callback, fsync + atomic rename, hash, then commit READY. Startup reconciles
pending rows/files; damaged writes remain visible as errors, never silently deleted.
Serialize journal mutations, disable boundary edits while a shutter is pending,
and keep a single explicit active group. Next finalizes; time never creates a group.
Undo/delete are metadata operations and keep original files. Archive only closed
whole groups; lock exported groups to keep repeat import identity stable. Source
files survive exports and app updates; never use destructive Room migration.

## Dependency/recognition decisions

Bundled ML Kit barcode decoder is the initial offline implementation (no Play
Services model download). Preserve all candidates, symbology and checksum status;
EAN/UPC canonicalization preserves the raw value. ZXing vs ML Kit reliability is
not measured without a representative physical fixture set and Pixel. Do not claim
that comparison passed. Recognition Off performs barcode/optional CVHS QR only.
Local OCR, desktop recognition and OpenAI settings remain later milestone work;
no credentials are required by core capture/export.

## Validation gates

Host tests exercise the actual grouping, barcode normalization, archive planning,
checksums and import adapter including repeated imports and interruption recovery.
Build/installable debug APK is required. Pixel camera quality vs native Camera,
hardware controls, force-close timing, low-storage behavior and 100 physical tapes /
300 actual photographs require a device. A synthetic stress run is distinct from
that physical sustained capture acceptance. Do not advance to milestones 2/3 or
claim production quality until milestone 1 passes these hardware gates.


## 0.1.1 update

The owner requested OpenAI API-key recognition and offline open-source OCR/barcodes.
ZXing now handles deterministic barcode decoding; Tesseract provides separately
labelled offline OCR. The non-pricing Responses schema is derived from the existing
desktop schema. New recognition attempts are capture metadata in Room schema 2,
with an additive 1→2 migration, source fingerprints and explicit confirmation.
Existing desktop inventory, pricing and Shopify logic remains authoritative.
See RECOGNITION_0.1.1.md for the implementation, dependencies and remaining gates.
