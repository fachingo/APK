# Mobile ZIP / manifest protocol, version 1

Each portable ZIP contains exactly `manifest.json` plus the original JPEG files
listed there. Members are flat, safe ASCII names, unique, unencrypted, and not
symlinks. Android uses ZIP STORED to avoid recompressing camera JPEGs. JPEG bytes,
EXIF and orientation are preserved; desktop derivatives remain separate.

```json
{
  "format": "snapims.mobile", "version": 1,
  "archive_id": "b60f877f-8bd3-43a3-bdfa-6f8e7cf0c857", "part": 1,
  "session": {"id": "84eb89bb-70d7-411c-aa96-a9049ea59764", "name": "Shelf intake", "created_at": "2026-10-27T14:00:00-06:00"},
  "groups": [{
    "id": "68dbe459-76b1-4dc2-aec6-b0ab6ccbe3f6", "number": 1,
    "captured_at": "2026-10-27T14:00:00-06:00", "location": "A1",
    "rare": false, "review": false, "note": "",
    "barcode": {"status": "NO_BARCODE", "selected": null, "candidates": []},
    "photos": [{
      "id": "2ea42f45-94b7-4ce0-9222-28f2bdd75c9f", "order": 1,
      "role": "FRONT", "captured_at": "2026-10-27T14:00:05-06:00",
      "filename": "example_F_1.jpg", "bytes": 123456,
      "sha256": "replace with the actual 64-character lowercase SHA-256"
    }]
  }],
  "events": []
}
```

This is a structural example, not an importable fixture. Every UUID is canonical;
timestamps must include their timezone. Product numbers are positive, unique in
a part, and session-wide. The groups array defines item order; the photos array
and contiguous 1-based `order` define photo order. Valid roles are FRONT, BACK,
ADDITIONAL. Repeated roles are permitted and filenames include ordinals so they
cannot collide. Filenames never determine identity or grouping.

Barcode candidates preserve `raw`, `format`, canonical GTIN-14 `normalized` when
applicable, `valid` checksum status, provider and source `photo_id`. UPC-E expands
to UPC-A before GTIN normalization; leading zeroes are not discarded from raw
values. Multiple different valid candidates are AMBIGUOUS. No match is NO_BARCODE;
scanner errors are distinct. A manual correction is MANUAL. Copies with identical
UPCs remain separate capture groups and separate physical SnapIMS items.

Events retain ID, optional group_id, timezone-aware created_at and action, using
CVHS1 BATCH START/END, ITEM NEXT and LOC where applicable, plus edit/recovery
actions. Groups/roles/flags in the final manifest are authoritative. Existing
photographed QR import remains unchanged on desktop.

Archive target is decimal MB (default 500,000,000 bytes of source photos). ZIP
headers and manifest add overhead. Split only between complete groups; an oversized
single group is exported together. A trailing partial-size part remains available
for more groups on automatic rollover. Manual Finalize Part flushes closed groups;
Finish Batch closes the open group and flushes everything. Reserved archive groups
are locked against edits, including Undo Next, to preserve repeat-import identity.

The journal stores the archive's final SHA-256, file size, part number, UUID and
state. Every photo is verified before ZIP writing and read back from the ZIP before
atomic finalization. Originals are never deleted. A .pending archive is rebuilt
after interruption; an already-renamed ZIP is verified and reused.

## Desktop validation and receipts

From the SnapIMS checkout with its dependencies installed:

```bash
python -m snapims.mobile_import /path/to/part.zip --data-dir /path/to/SnapIMS-data
# For deliberately unlocated groups only:
python -m snapims.mobile_import /path/to/part.zip --data-dir /path/to/SnapIMS-data --location Q1
```

First use a disposable data root, not production inventory. Run once per part.
The importer validates all files, sizes, hashes, JPEG decoding, paths, unique IDs,
roles and timezone-aware timestamps before touching inventory. Limits: 5,000
photographs and 10 GB uncompressed per archive; 5 MB manifest. Unlocated groups
require an explicit fallback. Capture group metadata is mapped through SnapIMS's
existing processor, which mints its own Batch/Item IDs and preserves source bytes.
Actual IDs are returned in a `snapims.mobile.receipt` v1 JSON result. No recognition,
pricing, Shopify publication or external API request is triggered.

A separate `mobile-imports/receipts.sqlite3` under the chosen data root maps capture
IDs to authoritative inventory IDs. It is an import journal, not another inventory.
Same group + same contents returns existing IDs even in a repacked archive; changed
groups/archive IDs are refused. Keep this journal and the inventory/originals in the
same backup. Do not delete the receipt journal to retry an import. Original barcode
provenance and roles are stored in existing photos.capture_metadata_json/image_role;
manual notes populate condition_notes only on fresh, unedited imports. Full mobile
manifest/receipt provenance is retained in mobile-imports/<archive UUID>.json.

An interruption after originals finalization is recovered only after checking the
journal's exact paths, all original hashes and every staged derivative. Other
ambiguous existing SnapIMS quarantines remain operator-visible. The CLI requires
Linux file locking; Windows support has not been validated.

## Wireless / pairing

There is no new network import endpoint in this milestone. Manual transfer uses
Android's Files/Share facilities. Milestone 2 must add authenticated TLS pairing,
short-lived codes, revocable device credentials and persistent transfer receipts
before enabling wireless imports. Never open SQLite over the network or expose an
unauthenticated import route. No API secret is needed for this milestone.
