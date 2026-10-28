# Shopify milestone B implementation design — 2026-10-28

The owner supplied externally verified current official Shopify documentation on
2026-10-28. This resolves the prior documentation gate for planning and code. These
pages were not independently fetched through the cloud's blocked shopify.dev proxy.

Sources: /docs/apps/build/authentication-authorization (including client-credentials-
grant, authenticate-standalone-apps and access-tokens) and GraphQL Admin API2026-10
productCreate/productSet/productVariantsBulkUpdate/inventoryItemUpdate/
inventorySetQuantities/inventoryAdjustQuantities/stagedUploadsCreate/fileCreate/
bulkOperationRunMutation/publishablePublish on https://shopify.dev.

## Credential boundary

Shopify's authorization-code exchange requires a client secret; a native PKCE-only
Admin API flow is not established. Client credentials are server-side for stores in
the same Shopify organization and return ~86,399-second tokens. App Bridge Direct
API is not a general standalone Android route. No Shopify client secret enters the
APK, local inventory database, backups or GitHub.

Implement a minimal separately hosted credential/API broker with TLS ingress. The
broker holds confidential credentials and encrypted access tokens, pairs approved
phone public keys, validates signed timestamp/nonce/body requests, rate-limits them,
and executes only a closed registry of Shopify operations. Its SQLite state contains
credentials, device public keys, nonce/rate/audit metadata and minimal operation-ID/
remote-ID checkpoints; no inventory database or original photos. Photos go directly
from the phone to authenticated Shopify staging targets. No home computer is required.

The broker is an optional Shopify integration dependency. Capture, recognition,
pricing, local inventory and backups remain independent. Source/Docker/deployment
instructions are reviewable before any deployment. No hosted account is configured
or production store called by this build. Real auth/dev-store acceptance stays UNRUN.

## Preserve source semantics

Port the existing desktop exact-SKU guard, immutable Item IDs, integer cents/HALF_UP
discounts, condition/default/weight rules, explicit approval, DRAFT default, stage
checkpoints, media identities and per-item batch outcomes. Initial listings represent
one physical copy with stock1. Reject stock>1 instead of silently changing copy identity.
Never infer physical identity from UPC. Existing remotely linked records need an
explicit reconciliation path; no blind overwrite, productSet list replacement or
destructive mutation is exposed.

Native Room stores durable reviewed jobs and per-photo checkpoints. WorkManager
executes bounded work, records server WAITING/READY separately from Android work
completion, supports pause/cancel/retry, and skips verified successes. Each operation
has an immutable request hash and key. A broker/HTTP timeout after non-idempotent
creation becomes UNCERTAIN; repeated delivery reads the cached minimal result or
requires reconciliation, never blindly creates a second product. Stable item markers
support exact remote matching. Inventory uses changeFromQuantity and @idempotent.

Stage JPEGs, create drafts, configure the initial variant/SKU/price/weight, compare-and-
set stock, create files asynchronously, attach only the intended file IDs, and verify
server media completion plus final product fields before marking UPLOADED_AS_DRAFT.
Live publication is a separate operator-approved action, disabled by default at the
broker. Development-store-only writes are the default server policy. Production
Canada VHS data is never used for acceptance tests.

Native batches run durable per-item steps. Large Shopify server JSONL bulk operations
must preserve the same identity/results/reconciliation semantics; they cannot be
called complete merely because WorkManager returned success. No fake live proof.

## Required acceptance

Execute real authorization, then one development-store draft with all fields/photos,
remote IDs, interruption/resume and duplicate prevention, followed by a multi-item
batch. Mock transport and host crypto/job tests are separate evidence. Keep the app's
Shopify connection marked UNVERIFIED until actual authorization and real-store test.
