# SnapIMS Next 0.4.0 — Shopify publishing preview

Current owner-supplied API contract: Shopify GraphQL Admin2026-10, reviewed2026-10-28.
Android remains a standalone Kotlin inventory application. The optional hosted
broker holds confidential Shopify credentials and minimal operational state only;
no desktop connection, inventory database or original photo server is required.
Apache-2.0 covers this repository's original code; desktop repositories are unchanged.

## Delivered

- Settings → Shopify: HTTPS broker pairing, browser authorization, actual Shopify
  connection checks, CAD development-store identity/scopes/locations/publications.
- Review and approve selected ready copies (up to100), exact immutable SKU/Item ID,
  source-compatible integer-cents discounts, condition/tags/HTML description,
  default250g/configured weight, stock1, front-first source filenames and media markers.
- Room5 jobs and immutable reviewed snapshots; WorkManager network constraints,
  bounded attempts, separate item outcomes, checkpoints and manual reconciliation.
- DRAFT productCreate + initial variant, metadata/SKU/shipping/tracking, stock CAS
  with changeFromQuantity and @idempotent, staged derived JPEG uploads, asynchronous
  fileCreate, fileUpdate attachment, media order/server-job polling and final checks.
- Exact owned product/variant/item/image IDs and full product fields verified
  before UPLOADED_AS_DRAFT. Remote IDs persist locally even during partial failure.
- Cached immutable operations; uncertain creation only reconciles and never blindly
  repeats. Existing exact-SKU/foreign resources are protected. No productSet/deletes.
- Separate live publication approval `SUBMIT`; broker disabled by default; fresh
  full draft validation, ACTIVE + publication status confirmation before success.
- Pause/cancel retain remote resources. Restored backups redact staging credentials,
  preserve identity/checkpoints and require manual pairing/reconciliation.
- Chat tools can read saved job state and propose a DRAFT upload. Approval binds the
  local revision/photos and paired connection. Job creation and one-time approval
  commit together; scheduling follows the commit. No chat-driven live publication.
- Credential broker signed P-256 requests, timestamp/nonce replay rejection, encrypted
  rotating tokens/results, narrow operation registry, rate limits, audit metadata,
  revocation and master-key rotation procedure. Non-root Docker startup verified.

## Evidence and limits

See ../TEST_RESULTS.md for executed tests and artifact hashes. Tests use explicit
synthetic inventory/photos and mock Shopify HTTP. They do not establish real
Shopify OAuth compatibility, field/schema acceptance or successful dev-store writes.
A physical Pixel, eligible ChatGPT account and Shopify development-store credentials
are not attached. SIWC sign-in → discovery → completed stream remains **UNRUN**.
API-key support is retained; no subscription failure triggers billable fallback.

No hosted broker has been deployed or real Shopify authorization performed. Setup:
SHOPIFY_SETUP.md and ../../broker/README.md. The mandatory acceptance sequence is
one actual development-store draft with all metadata/images, interrupted resume/no
duplicate, then a multi-item batch. Production Canada VHS data has not been mutated.

This preview implements native batches as durable per-item requests, not Shopify's
server JSONL bulk-operation API. Server bulk uploads and automatic linkage/updates
of unrelated preexisting Shopify products are not implemented. Review those records
manually; do not reset operation IDs/ledgers to force a second creation.

Capture/OCR/barcodes/pricing/inventory/backups retain their existing functionality.
Physical lower-resolution barcode reliability and shutter timing still require
measurement on the target Pixel; synthetic tests are not hardware performance proof.
