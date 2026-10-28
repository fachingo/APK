# Move an existing desktop batch to SnapIMS Next

`export_standalone.py` reads an explicitly selected SnapIMS 0.15 inventory database
using SQLite mode=ro and query_only. It never edits inventory or calls Shopify.
It preserves Item ID/SKU, batch identity, metadata, remote IDs and original-photo
hashes. Mobile internal UUIDs are deterministic mappings of desktop identities.

```bash
python export_standalone.py \
  --database /path/to/SnapIMS-data/database/inventory.sqlite3 \
  --batch-id YOUR-BATCH-ID \
  --output /path/to/new-phone-inventory.zip
```

Select the ZIP in the phone's Settings → Backup & import → Import inventory/capture
ZIP. Imports preflight all file hashes and records. Repeating an identical import
reuses records; changed or conflicting IDs are rejected without overwriting data.
The output is a plaintext photo-inclusive interchange package. Store it privately;
use the phone's protected backup for confidential archival storage.

The default media root is the database's grandparent (the normal SnapIMS-data
layout); use --media-root for another approved source directory. Missing/corrupt
originals are rejected. For legacy timestamps without an offset, supply
--source-timezone with the verified capture timezone (for example America/Edmonton).
Ambiguous/nonexistent clock-change timestamps require source correction. The existing desktop
250 g setting is the configurable starting weight (--default-weight-grams).
Historical pricing is labelled IMPORTED_DESKTOP, not claimed as fresh sold evidence.
Only desktop records already READY and review_status DONE import as listing-ready.
No publication or automatic desktop/mobile synchronization occurs.

Old native capture ZIPs still import through the original mobile_import.py adapter;
that historical adapter mints desktop identities and does not adopt phone Item IDs.
Use this format for identity-preserving movement of existing desktop inventory.
