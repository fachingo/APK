# Shopify milestone B prerequisites and proposed secure design

Desktop SnapIMS 0.15.0 exchanges Client ID + Client Secret for expiring tokens using
client_credentials. Its default GraphQL version is 2026-07. This is inspected source,
not current mobile-auth guidance. The required official 2026-10 documentation was
blocked by this environment's network policy (CONNECT HTTP 403).

Before enabling real mobile writes:

1. Allow read access to shopify.dev and verify the requested authentication, GraphQL
   2026-10 and bulk-import documentation. Record actual supported field semantics.
2. Select/install the appropriate Shopify app in an owned development store and
   establish the required minimal scopes. Do not send secrets in chat or commit them.
3. Review a credential-only hosted broker design: server holds Client Secret and
   performs supported OAuth/token exchange; phone authenticates with a device-bound
   short-lived session, stores tokens with Keystore, and calls Shopify directly.
   The broker must not become an inventory/processing server or depend on a home PC.
4. Verify whether Shopify officially supports the intended native/public-client flow
   before using it. No default static-token or phone-stored Client Secret mode is
   assumed. A private direct-device alternative needs documented official support,
   threat model and explicit operator choice.
5. Approve a named development-store test procedure: one DRAFT with images, verify
   variant/stock/weight/price/SKU/media IDs, then a multi-item draft batch, interrupt
   and resume it, and prove no duplicates. Keep live publication off by default.

API design review must cover productCreate vs productSet list-replacement semantics,
productUpdate, current media/file creation/attachment, stagedUploadsCreate transfers,
productVariantsBulkUpdate, inventory compare-and-set and bulkOperationRunMutation.
Reconcile exact SKU and a stable operation marker after uncertain creation. Do not
blindly retry a non-idempotent mutation. Retain per-image and per-item checkpoints,
remote IDs, server-delegated work status, rate-limit backoff and explicit cancellation.

No Shopify production call has been made by this update. Until these gates pass,
local listing fields and readiness are useful inventory preparation only; they are
not evidence that phone-to-Shopify publishing works.
