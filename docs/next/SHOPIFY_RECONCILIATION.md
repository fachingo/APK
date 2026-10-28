# Shopify milestone B continuation — 0.7.0 design

The next unfinished critical milestone is Shopify. This release completes additional
native listing inspection and review/reconciliation code before secondary business
modules. Real authorization/development-store and physical SIWC acceptance remain
UNRUN without the required account/device. No production write is authorized.

Scope:
- Bounded, cursor-paged Shopify listing search and draft/active/archive inspection.
- Explicit linking of an existing DRAFT with exact immutable SKU plus matching
  SnapIMS item/creation metafields. A matching title or UPC is never sufficient.
- Reviewed updates to selected title/description/vendor/product-type fields of a
  linked DRAFT. Price, inventory, media, tags, publication and collections are outside
  this update operation. Existing creation/publication pipelines remain available.
- Durable, one-use reviews for native uploads/publication and management. Bind store,
  device connection, local revisions/photos and reviewed remote version. Restore
  expires pending reviews. Worker jobs remain separate from approval receipts.

The hosted credential broker stays narrow: no inventory/photo storage. Browse reads
return current Shopify data; review content persists only on the phone. Claiming an
owned listing binds only remote resource IDs in the broker. An unrelated or multiply
variant product cannot be adopted. A foreign product needs manual Shopify migration;
this release never stamps ownership markers onto it automatically.

Metadata writes use a closed productUpdate field allowlist. The broker checks fresh
Shopify state against the reviewed version immediately before dispatch and rejects
stale/active/archived/foreign resources. Shopify productUpdate does not supply an
atomic compare-and-set contract here: a concurrent admin change between preflight
and mutation remains a documented limitation. Only explicitly selected scalar fields
are sent, and post-verification checks untouched fields. An uncertain HTTP result
is reconciled by reading the desired/protected-field hashes; it is never blindly
resent. Actual remote completion, not Android worker completion, determines success.

Tests will exercise typed queries, foreign/duplicate/stale rejection, one-use local
approval, exact-field mutation, uncertain reconciliation, cancellation, schema upgrade,
paused backup restore, UI review errors/rotation and preservation of existing capture,
recognition, pricing and draft pipelines. Every live external acceptance gate remains
explicit until real account and physical-device tests succeed.

## Use version 0.7.0

Update the optional hosted broker from this source release before using listing
management. Earlier brokers do not expose browse/claim/metadata-update operations
or the opaque reviewed version. Existing capture/inventory and independent API-key
recognition need no broker update or hosted service.

Open Settings → Shopify → Listings & saved reviews. Pair/test the connection on the
Shopify screen first. Search titles, filter All/Draft/Active/Archived, select an active
location, then Inspect listing. Pages contain at most20 products; Next20 retains the
server query/cursor. Results are a read snapshot with a fetch timestamp; preparing
any review reads the product again. Browser data lives in the current app session;
saved review snapshots and receipts live in SQLite and protected backups.

Find and select the physical copy by immutable Item ID. Preparing a link needs its
exact SKU, original `snapims-<creationKey>` marker tag and matching `snapims.item_id` /
`snapims.creation_key` metafields. The store/copy deterministic creation identity must
match the existing native publishing engine. Unmarked legacy desktop listings are
not adopted based on title, UPC or SKU alone. Restore missing ownership markers or
resolve duplicate listings manually in Shopify; the app does not stamp them for you.
Only single-variant drafts with a complete image list are manageable here.

Approve the saved link once, then check Uploads & connection → Publishing jobs for
verified completion. A link stores only returned product/variant/inventory IDs and
LINKED_DRAFT state; it does not copy remote metadata over local inventory. Price,
stock, weight, image identity and full publication readiness remain separate. This
state cannot bypass the existing verified-upload requirements for live publication.

After linking, edit inventory metadata locally, inspect the same listing, choose
which Title/Description/Vendor/Product-type fields to send, then Prepare metadata
review. Unchanged fields are omitted. Review before/after values and approve once.
Stock/price/media/tags/collections/status are not sent in this operation. Active and
archived products are inspectable but not editable through this path. A stale or
ambiguous review requires a fresh inspection rather than a blind update.

Native draft upload and live-publication buttons now prepare durable reviews instead
of immediately queuing a mutable selection. These show exact SKU, final CAD price,
weight, stock, description, tags, barcode and ordered photo previews. Publication
keeps its separate SUBMIT field and selected channel. Review failures remain visible;
changed local metadata/photos/barcode evidence expire the review with no new job.
Saved reviews remain accessible after closing the dialog or reopening the app.

## Upgrade and recovery

VersionCode9 and Room7→8 retain existing captures, photos, jobs and tasks. Pending
pre-upgrade AI proposals expire because approval binding now includes barcode scan
state/candidates and manual overrides. Approved owning jobs retain their receipts.

Protected snapshot schema8 adds native Shopify reviews; schemas3–7 remain supported.
Pending restored reviews expire. Restored unfinished management items become
NEEDS_REVIEW; cancel their remaining job and prepare a fresh inspection/review.
Existing creation jobs retain their reconnect/reconciliation flow. Restore does not
start external requests. Interrupted in-process jobs use saved operation IDs and
broker outcomes; an uncertain metadata write is read-reconciled without resending.
Cancellation retains partial server IDs/checkpoints and cannot become SUCCESS just
because a delayed response arrives. Inspect already-sent operations on the server.

## Remaining acceptance and limits

All transport responses used in automated management tests are explicit fixtures.
No real Shopify store was linked or modified. Test real authorization, one existing
marked development-store draft, edited-field preservation, independent admin changes,
disconnected response/reconciliation and then a multi-item draft batch before using
this integration operationally. Full existing-product price/stock/media editing,
foreign product adoption, server JSONL bulk operations and production writes remain
outside this release. Shopify provides no atomic scalar productUpdate compare-and-set
here; the preflight/write race with an independent admin remains a real limitation.

Calendar/communications have not been started while critical B acceptance remains
unverified. Physical Pixel/SIWC account proof also remains UNRUN. Conventional API-key
support, offline recognition, market pricing and standalone inventory are preserved.
