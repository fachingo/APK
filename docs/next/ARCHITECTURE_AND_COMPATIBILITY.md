# SnapIMS Next architecture and compatibility audit — 2026-10-28

This report precedes the standalone implementation. Desktop baseline is SnapIMS
0.15.0 (schema 16, commit 5c790dce); Android baseline is 0.1.2 (Room 2, a2478a9).
Pricing_module contains Pricing-Workbench-v0.1.0.zip, extracted for inspection only.
Desktop source, production databases and Shopify listings are not changed by this work.

## Inspected boundaries

| Existing source | Verified contract and Android reuse |
| --- | --- |
| snapims/web/app.py, templates, static/app.js | FastAPI review/import/editor/publish/configuration routes; port workflows to Compose, not a bundled Python server |
| snapims/db.py and migrations 0007/0014/0015/0016 | Separate batches, physical items, file-backed photos, recognition results, inventory events, Shopify sync and durable jobs; additive Room migration with no destructive fallback |
| models.py, processor.py, images.py | Explicit item/photo ordering, original hashes, staged media and recovery; preserve CameraX originals and existing native recovery |
| interpreter.py, manifests.py | Batch ID date/time + optional name; Item ID and SKU are batch-location-sequence; photo suffix F then 02, 03; retain native UUID provenance separately |
| protocol.py, import_v0130.py, pipeline.py | CVHS1 BATCH START/END, ITEM NEXT/CONT, FLAG RARE/REVIEW, LOC Q1/A1–J10; NEXT is the boundary; never infer identity from UPC |
| inventory.py, money.py, tests/test_inventory.py | Exact condition vocabulary, quantity >=0, required title/positive price/photo, immutable SKU=Item ID; integer cents with Decimal ROUND_HALF_UP; optimistic revisions and audit trail |
| recognition/providers.py, recognition/service.py | Reviewable provider suggestions and uncertainties; current native Tesseract/ZXing/OpenAI implementation retained, confirmed metadata requires explicit application to inventory |
| pricing/{database,workflow,integration,statistics,normalization}.py | Immutable sold evidence + listing URLs, sold+shipping average/median/min/max, normalized title cache, manual-price protection, title-change staleness; no AI prices substituted |
| shopify/{auth,client,service,jobs}.py | Client credentials exchanged server-side with expiry/refresh; exact SKU duplicate guard, DRAFT default, per-stage checkpoints and uncertain-result reconciliation; explicit confirmation before live writes |
| tests/fixtures/import_v0130, migration and Shopify tests | Reuse source rules through generated conformance fixtures; synthetic 100-item/300-photo run is separate from real Pixel acceptance |

The desktop currently hardcodes 250 g in configure_variant; mobile should expose
250 g as a configurable starting value. Vendor Canada VHS and product type VHS Tape
are existing defaults. Existing conditions are Not Graded, Fair, Good, Very Good,
Like New, Sealed, Damaged and Mold Review. Rare initially selects UNIQUE pooling.
Current desktop GraphQL default is 2026-07, not proof of 2026-10 compatibility.

## Standalone model

Keep capture sessions/groups/photos and archives, preserving all 0.1.x UUIDs. Add a
batch identity table and physical inventory table keyed by capture group UUID, with
an immutable unique desktop-shaped Item ID and SKU. Mint the identity on first
capture in the same transaction, backfill existing photographed groups on upgrade,
and retain it when location, metadata or order changes. Imported desktop items keep
their supplied Item IDs. Empty next groups do not count as inventory.

Room owns editable title/edition/year/distributor, condition/notes, barcode override,
price/provenance/discount/quantity, vendor/type/tags/description, location and readiness,
remote Shopify identifiers/status, revision and audit events. Recognition suggestions
remain separate from saved fields. No server is needed to create, search, review,
edit, validate, export, back up or restore inventory.

Phone inventory is authoritative locally. Historical mobile ZIP imports minted new
IDs on desktop at import time; those historical receipts cannot be reconstructed on
the phone. They must never be silently linked or overwritten. The existing mobile
ZIP format remains accepted by the old adapter, which still mints desktop IDs.
A new identity-preserving portable inventory package and desktop batch import keep
original Item IDs; legacy adapter limitations must be explicit in the user docs.

## Security and recovery

Photo files stay outside SQLite. Every reference has a hash and byte count. Backups
use a versioned, password-derived authenticated-encryption container with all selected
originals, records and audit history. Keystore/API credentials are excluded. Restore
first authenticates and validates the entire package into private staging, rejects
unsafe paths and conflicting IDs, then merges new records transactionally without
replacing an existing database. Repeated identical imports are no-ops; changed records
require explicit resolution rather than silent overwrites. Original file installation
is staged and recoverable; never delete existing originals on rollback.

Normal inventories and backup jobs run through typed repositories, Room transactions,
coroutines and Android-supported work. The app reports bounded active work and
interrupted/failed jobs accurately; it does not promise indefinite execution.

## Shopify authentication decision and verification blocker

The current desktop client-credentials exchange uses a Client Secret and is a
confidential-client flow. Do not embed it, ask for it as a default phone setting,
or claim Keystore makes it equivalent to server-side storage. Recommended design:
an optional internet-hosted credential-only broker with authenticated, device-bound,
short-lived token delivery; inventory/recognition/pricing/uploads stay on the phone.
Broker deployment, OAuth redirect ownership, store/app installation and an approved
development-store procedure must be concrete before external writes.

On 2026-10-28, a direct request to the official authentication URL failed CONNECT
HTTP 403 under the managed network allowlist. shopify.dev and shop/store endpoints
are not allowed and no Shopify credentials or dev-store binding are configured.
Latest 2026-10 auth/schema/media/bulk behavior is therefore not verified here.
Do not present existing desktop mutations or mock responses as a verified mobile
integration. No live product creation/publication is authorized by a test fixture.
See SHOPIFY_SETUP.md for the exact next milestone gate.

## Module boundaries and milestone order

A: camera + local inventory + review/search + file references + import/export +
protected backup/restore + durable audit/migration + usable APK/CI and tests.
B: verified current Shopify auth/API contract, direct transfers, durable draft jobs,
reconciliation and explicitly approved real dev-store demonstration.
C: portable sold-evidence database/import + deterministic pricing + bulk review.
D: provider-capable persistent AI workspace with typed tools and approvals.
E: tasks/calendar/communications/notifications and optional sync.

Do not build placeholder chat, calendar or business metrics before A/B are usable.
A home inventory summary may use actual Room counts only. Existing recognition is
retained; it does not imply milestone C is complete. Physical 100-tape capture,
force-close/power/storage and actual camera behavior require an attached Pixel.
