# Additive desktop mobile-import adapter

For the inspected SnapIMS v0.15.0 baseline, copy mobile_import.py into snapims/ and
test_mobile_import.py into tests/ in that checkout. Do not replace existing files
without comparing any local changes. Install the existing SnapIMS dependencies.

Use a disposable data root for validation:

    python -m snapims.mobile_import /path/to/part.zip --data-dir /path/to/test-data

Use --location Q1 (or A1–J10) only as a fallback for unlocated groups. The adapter
reuses SnapIMS's processor/ID allocation and durable import journal. It validates
images, hashes, grouping and order, returns actual IDs, and deduplicates reimports.
Full enriched manifests, including recognition provenance, are preserved. It does
not overwrite catalog titles/prices or automatically apply separate results JSON.

See docs/MOBILE_MANIFEST_V1.md and docs/RECOGNITION_0.1.1.md in the Android project.
The adapter's existing 21 tests are copied unchanged from the validated cloud source.
