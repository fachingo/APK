# Shopify milestone B — 0.4.0 preview

The owner supplied externally verified official authentication and GraphQL2026-10
requirements on2026-10-28. That documentation gate is resolved for implementation.
The cloud still cannot independently fetch shopify.dev. API field selections and
native authorization remain unverified until exercised on a real development store.

Android Settings → **Shopify** provides hosted broker pairing, system-browser
Shopify authorization, an actual connection test, location selection, reviewed
DRAFT uploads, per-item progress/errors/remote IDs, pause/cancel and reconciliation.
Live publication is a separate `SUBMIT` approval and disabled at the broker by default.
No credentials are bundled. Capture, OCR/barcodes, pricing, SQLite and backups remain
independent of the hosted broker. No home computer is required.

The broker holds confidential credentials and performs Shopify's supported
server-side token exchange; Android signs narrow requests with its Keystore key.
No native PKCE-only Admin API grant, embedded client secret, static-token fallback
or App Bridge bypass is assumed. Photos transfer from the phone directly to
Shopify staging. Deployment/pairing/revocation/rotation instructions:
[broker/README.md](../../broker/README.md).

Existing desktop source supplied exact SKU identity, quantity1, CAD decimal price,
250g default, ordered filename and `SUBMIT` approval semantics. Native mutable job
and photo checkpoints are stored in Room schema5. Checkpoints survive normal process
restarts. Backups redact upload credentials and pause restored jobs until explicit
pairing and reconciliation. Existing product data is protected from blind overwrite.

Implemented: bounded native batches through per-item operations, productCreate,
variant/inventory metadata, compare-and-set stock, staged JPEG/fileCreate processing,
fileUpdate attachment, media order/server-job polling and final verification.
Not implemented: Shopify server JSONL bulk-operation path, automatic linkage or
updates of unrelated preexisting products. Both remain explicit preview limitations.

Required live acceptance, **UNRUN**:

1. Deploy the reviewable broker to a chosen HTTPS host and install/configure the
   appropriate Shopify app on an owned CAD development store; keep secrets off chat.
2. Pair a physical Pixel, authorize in system browser and check the actual store,
   scopes, location, health and actionable errors.
3. Approve one draft. Verify title, description, price, immutable SKU, weight,
   stock1, all ordered photographs, DRAFT status and IDs persisted after restart.
4. Interrupt a transfer/response; resume and prove no duplicate product or file.
5. Repeat with multiple items; verify partial success and completed-item skips.
6. Only after the draft tests, explicitly approve a separate test publication.

No broker is deployed by this build, no real Shopify authorization has been tested,
and no production Canada VHS mutation has been made. Mock tests establish local
logic, not live API compatibility. SIWC physical sign-in/catalog/stream proof is also
still unrun; ordinary API-key functionality remains available with no automatic
subscription-to-billable fallback.
