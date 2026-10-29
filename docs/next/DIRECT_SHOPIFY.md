# Direct Shopify read-only preview — 0.8.2

This is the first direct-connection milestone, not completion of production writes.
The existing SnapIMS Dev Dashboard app is reused. No new Shopify app, broker,
hosting, desktop or home server is required for direct catalog reads.

Settings → Shopify → Direct Shopify Connection. Enter the store's canonical
`name.myshopify.com` domain, Client ID and Client Secret, then Save credentials &
connect directly. A successful connection loads the actual shop identity, scopes,
locations and first catalog page, then opens Listings & saved reviews. Search by
title or SKU, filter All/Draft/Active/Archived and use Next 20 listings. Select an
inventory location before inspecting an existing listing. Test connection checks
store data without creating products. Clear direct credentials disconnects locally.

The credentials/token envelope uses a separate AES-GCM Android Keystore key and
app-private preferences. Ordinary updates preserve it. Android backup is disabled;
SnapIMS database snapshots/export do not include the credential envelope. The
settings screen retains its secure-window flag. Client secrets are not embedded
in APK/source, saved in Compose instance state, or returned by AI tools. Enter
credentials on the phone, not in chat. The existing encrypted ChatGPT credential
namespace and key alias are unchanged.

The native gateway performs client_credentials form exchange at the selected
shop's `/admin/oauth/access_token`, caches its token/scope/expiry encrypted and
renews on demand before expiry. GraphQL uses API2026-10 and
X-Shopify-Access-Token. Redirects are disabled; domains must be canonical Shopify
subdomains. Authentication failures do not fall back to a broker or another grant.
The app remains idle until an operator/agent explicitly requests reads.

Existing AI shopify.catalog/product/connection reads select this native gateway
when direct mode is saved. Results remain subject to the existing compact tool
output limit. No credential values enter tool outputs. Dedicated count/missing-image
queries and full direct write workflows are not included in this preview.

## Acceptance status

Host tests exercise encryption with an injected test key, persistence across gateway
instances, token reuse/renewal, disconnect, domain validation and rejection of
write operations before network access. These are mocked tests, not proof that the
Canada VHS account connected. The system documentation request returned HTTP403
in this cloud environment; implementation uses the owner's previously verified
Shopify documentation excerpts and existing broker GraphQL contract.

**UNRUN:** real client-credentials exchange, Canada VHS shop identity/scopes,
actual production catalog/pagination, Android Keystore on the physical Pixel,
revoked credentials against Shopify, and physical-device update installation.
No real-store credentials or physical device are available to this task.

**Not implemented:** direct production writes, photo staging/uploads, draft creation,
existing-product linking/updates, direct durable write reconciliation and live/bulk
approval flows. Direct gateway writes/uploads reject locally. Existing broker write
behavior remains unchanged; direct connection preparation explicitly rejects writes.
Do not use this preview as evidence that production publishing is operational.

The requested order requires real-store read acceptance before adding production
writes. Install over the existing official app, enter credentials on the Pixel and
verify that the actual Canada VHS catalog loads. Keep credentials out of bug reports;
report only the displayed error/status when connection fails.
