# Direct Shopify — v0.8.4

The v0.8.4 [production workflow](PRODUCTION_WORKFLOW.md) adds one-tap selected
drafts and a single reviewed multi-channel publication batch. Authentication and
the underlying draft/photo/reconciliation pipeline remain the established direct
implementation. The older review path below remains optional for ordinary drafts.

SnapIMS connects from the Android phone to Shopify using the existing installed
Dev Dashboard app. Direct authentication, catalog reads, product writes, photos,
status changes and publication do not require a broker, hosting or a computer.
The old optional connection remains for compatibility; no new broker application
is created or deployed by this update.

## Connect and enable reviewed operations

1. Open Settings → Shopify → Direct Shopify Connection.
2. Enter the canonical `name.myshopify.com` store domain, existing Client ID and
   Client Secret. Save credentials & connect directly. This only reads store data.
3. On later launches, use Open saved Shopify connection; credentials and cached
   tokens survive ordinary app restarts and signed APK updates.
4. Enable **writes to my Shopify store** to permit production write proposals.
   Deliberate Create Shopify Drafts authorizes selected draft uploads. Live/status,
   destructive and selected existing-product changes retain reviewed approval.
5. Enable **live publication controls** for status activation/publication. Grant
   the required scopes to the existing app, then test the connection again.

The native client uses `client_credentials`, encrypted token caching and on-demand
renewal before expiration. Organization/installation failures are displayed with
credential values redacted. There is no authentication or broker fallback. A
concurrent disconnect cannot resurrect credentials when an old token response
arrives. Disconnect pauses local direct jobs and clears the encrypted envelope.
Already dispatched remote operations can still complete; reconciliation checks
actual outcomes before another creation is attempted.

AES-GCM keys are protected by Android Keystore. Credentials/tokens are app-private,
excluded from SnapIMS backup/export/AI tools; Android app backup is disabled.
The settings window blocks screenshots. The separate ChatGPT credential namespace
is preserved. Do not uninstall or clear app storage to install this update.

## Photograph to draft

Capture photos as before; use offline OCR/barcodes or the explicitly selected photo
recognition model. Confirm recognition suggestions before applying their title.
In the Market database, search the title/barcode, inspect candidate identity and
sold evidence, and approve an evidence-based price. Edit remaining metadata and
mark the physical-copy inventory record ready.

Select copies in Shopify, select the inventory location, approve transmission of
the displayed data/photos and review the full draft selection. One approval may
cover the displayed batch, up to100 copies. The native WorkManager pipeline then:

- Creates DRAFT products using immutable copy IDs/SKUs and stable creation markers.
- Sets description, tags, exact decimal CAD price, barcode, SKU, weight and stock.
- Uploads compressed2048px JPEG derivatives via Shopify staged upload targets.
- Waits for file processing, attaches/reorders images and verifies their identities.
- Records returned product/variant/inventory/file IDs and actual verified outcomes.

One physical copy remains one SKU; new drafts start with stock1. Original photo
bytes and historical exports remain unchanged. External uploads never start merely
because a connection is tested or a photograph is captured.

The existing app needs appropriate product/inventory/file/location scopes, such as
`write_products`, `write_inventory`, `write_files`, and `read_locations`. The
connection displays actual granted scopes. Missing scopes stop the affected
operation with an actionable error rather than silently switching authentication.

## Existing products and status workflow

Search real products by title or SKU, filter Draft/Active/Archived, page through the
catalog and inspect a product after selecting a location. Listings are read
snapshots; use Refresh this listing for current server data.

**Approved locally** is a SnapIMS review receipt, not a Shopify product status.
Shopify statuses are DRAFT, ACTIVE and ARCHIVED. Each status/channel change has a
fresh product review and requires SUBMIT, except local-only approval:

- Review local approval: records approval after checking the current product;
  makes no Shopify mutation.
- Set Draft / Set Active / Archive on Shopify: shows the exact product and target
  state. Active can be visible on channels where publication already exists.
- Review publication to selected channel: choose **Online Store** for the website;
  activation and publication are separate API operations. Success requires the
  actual selected-channel publication flag, not just Active status or Android job
  completion. Grant `write_publications` and the corresponding read access.

Status-only results do not claim publication was checked. Verified status changes
update related visible local inventory status and audit history without changing
its metadata or immutable IDs. Local deletion/Trash still never deletes Shopify
products. Remote archiving is a separate, explicitly reviewed Shopify action.

Compatible existing products can be linked when exact immutable SKU and both
SnapIMS ownership markers match, with one variant and no ambiguous duplicates.
An unrelated listing is never adopted merely because its title/barcode matches.
For a linked draft or active product, select title, description, tags, vendor,
product type, price, weight or stock fields to update. SKU remains immutable;
stock updates allow0 or1 for a physical copy. Reviews show the selected values.
Financial changes and updates to active listings require SUBMIT. Unselected
barcode, taxability, tracking/shipping settings, IDs and media remain protected.
Changed remote values stop the affected update rather than being blindly replaced.

General owner-selected status/publication reviews can operate on a store product
without creating a local inventory link. They do not claim physical-copy ownership
or modify its price/stock/images. All operations remain bound to the authenticated
store and exact reviewed product.

## Interrupted operations and approvals

Phone jobs, revisions, audit rows and per-item checkpoints use the existing Room
services. No database tables are removed; Room/snapshot schema10 is preserved.
A separate device-encrypted, atomic operation/resource journal in no-backup storage
binds request IDs to approved inputs and records dispatched/confirmed outcomes.
Product/file creation with a lost response searches the saved identity markers
before retrying; ambiguous outcomes stop for review and never blindly create again.
Inventory changes use Shopify idempotency keys and compare-from quantities.
Price/weight changes are checked again immediately before their selected write.
Shopify does not provide an atomic transaction over every product/media/stock
step: partial successes remain visible and retries reconcile saved identities.

Do not publish the same copy concurrently from independent devices/desktop jobs:
Shopify does not enforce global SKU uniqueness. The phone serializes its own
operations and refuses ambiguous SKU/marker searches. Cross-device simultaneous
creation has no shared transaction/lock in this standalone design.

Restoring inventory backups does not restore credentials or private operation
journals. Restored external jobs pause for review. Reconnect, inspect saved IDs and
prepare fresh ownership/status approvals when required. Owner status approvals
expire after24 hours. Cancelling/rejecting a review does not undo remote work
already completed under an earlier approval.

## AI workspace

Existing compact native reads and reviewed draft/management/live workflows use the
direct gateway. A typed `propose_shopify_status` action adds exact product/channel
status proposals. Full Agent and operator approval remain required. The model
cannot enable owner-write/live permission settings or access stored credentials.
No continuous AI monitoring, automatic photograph transmission or model polling
was added. Deterministic SQLite retrieval uses no model tokens.

## Acceptance status

Native API protocol tests and host UI tests cover staged photos, draft creation,
SKU/price/weight/stock, ownership, protected existing updates, status/publication,
rejected approvals, changed remote data and lost product/file responses. These use
mocked Shopify responses. They do **not** establish real Canada VHS acceptance.

**UNRUN:** real client-credentials exchange, real product/media/stock/publication
mutations, actual Shopify API schema acceptance, physical Pixel Keystore/update
installation and website availability. No real credentials/device are bound to
this cloud task, and no real product was changed. The official documentation host
is blocked here; operations build on the owner's verified2026-10 excerpts and the
existing publishing contract. Test one explicitly approved real draft before a
bulk upload; live publication requires its own approval.
