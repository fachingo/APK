# SnapIMS Shopify credential broker (preview)

This optional service enables Shopify on the standalone Android app. It does not
store the inventory database or original photographs. The phone keeps those locally
and sends derived JPEGs directly to Shopify staging. No home PC is required.

**Not deployed or verified against a real Shopify store.** Tests use mock HTTP.
Shopify field/capability acceptance must pass on a development store before use.
The Android preview rejects production stores even if a server flag permits them.

## Configure a separately hosted instance

Use Python 3.13, persistent encrypted storage, and an HTTPS ingress. One process per
broker database; the container runs UID10001. Use a secrets manager for environment
values; never include them in an image, repository, APK, chat or screenshots.

Required settings:

| Setting | Meaning |
|---|---|
| `BROKER_PUBLIC_URL` | Canonical HTTPS origin, no path/trailing slash; signed request audience |
| `SHOPIFY_SHOP` | Exact owned development store `name.myshopify.com` |
| `SHOPIFY_CLIENT_ID`, `SHOPIFY_CLIENT_SECRET` | Confidential installed Shopify app credentials, server only |
| `BROKER_MASTER_KEY` | 32 random bytes, URL-safe Base64, managed as an encryption key |
| `BROKER_STATE_PATH` | Persistent file, default `/data/broker.sqlite`; parent owned by UID10001 |
| `SHOPIFY_AUTH_MODE` | `authorization_code` (default) or `client_credentials` |
| `SHOPIFY_SAME_ORGANIZATION` | Must be `true` for client credentials; only same-organization integrations |
| `ALLOW_PRODUCTION` | Leave unset/false; Android also independently blocks production writes |
| `ALLOW_PUBLISH` | Leave unset/false for draft acceptance; `true` explicitly enables separately approved publication |

Generate the master key with `python -m broker.manage new-master-key` directly into
the host secrets manager. Do not print it in a shared terminal or application logs.
Register the exact `BROKER_PUBLIC_URL/oauth/callback` in the Shopify app's allowed
redirects. The broker validates callback HMAC, timestamp, canonical shop, one-use
state and the paired device's revocation status before exchanging the code.

Requested scopes: `write_products`, `write_inventory`, `read_locations`,
`write_files`; publication mode additionally requests `read_publications` and
`write_publications`. Verify the actual granted installation scopes against current
Shopify requirements. No Shopify access/refresh token is returned to Android.

Client credentials renew before the approximately24-hour token expires. In code
mode, response-provided token lifetime/rotating refresh tokens are encrypted and
honored; if refresh is unavailable or authorization is revoked, the app must
reauthorize. No invented native PKCE grant or App Bridge shortcut is used.

Build from the repository root: `docker build -f broker/Dockerfile -t snapims-broker .`.
Run with a persistent `/data` volume and host-provided secrets. Bind port8080 only
to the HTTPS ingress/private network, not directly to the public internet. No
inventory volume is mounted. The deployment must preserve the operation ledger;
losing it requires operator reconciliation, not blind creation.

Disable query-string/access-body logging at **every** ingress, tracing and error
collection layer, particularly `/oauth/callback`. Uvicorn access logs are disabled.
Use ingress request size limits, TLS, a bounded upstream timeout and request rate
limits. `/healthz` is public and returns only a static service identity. There is no
public administrator endpoint. Do not cache any authenticated response.

## Pair and authorize

1. On the host, use `python -m broker.manage pair-code` to obtain a single-use,
   five-minute code through a private administration channel.
2. On Android: Settings → Shopify → HTTPS origin + pairing code → Pair.
   Android generates a non-exportable P-256 signing key in Android Keystore.
3. Choose Authorize in browser, complete the Shopify system-browser flow, return
   to SnapIMS, then Test connection. Same-organization client-credentials mode
   does not launch OAuth and obtains tokens server-side when tested.
4. Check the actual CAD development-store identity, scopes and location before
   approving any draft. The UI does not label a connection check a publishing pass.

Signed POSTs cover audience, path, timestamp, fresh nonce, exact body SHA256 and
device UUID. The server rejects expired signatures, replay and revoked devices.
No general GraphQL proxy, delete, productSet or arbitrary URL operation exists.
Staging targets must use HTTPS on explicitly allowed Shopify/cloud-storage hosts;
redirects are disabled and Shopify tokens never accompany photo transfers.

## Operations and recovery

Each write has an immutable UUID + request hash. The encrypted ledger stores only
minimal returned IDs/status/staging credentials, plus hashed item/filename/alt
identity for reconciliation. Input title/description/price and photo bodies are
not persisted. Audit records contain device/action/outcome/hash/time, not payloads.

Creation records are written before upstream dispatch. A timeout becomes UNCERTAIN.
A repeated creation reads an exact owned marker or cached result; it never blindly
creates again. File recovery can reconcile an existing pending operation after a
phone restore without retaining its expired staging secret. Ambiguous/no match
requires review. A crash before dispatch can conservatively leave UNCERTAIN; an
operator must inspect Shopify instead of deleting the ledger or changing UUIDs.

Quantity1 uses required `changeFromQuantity` and `@idempotent`. Unexpected stock is
reviewed. Only resource IDs bound to that creation key can be modified. Photo
attachment uses `fileUpdate.referencesToAdd`, then media ordering/server-job polling
and final verification. These API2026-10 field selections still require a real-store
acceptance pass. No deprecated `productCreateMedia` is used.

Android batches use per-item durable RPCs (up to100 reviewed copies). Server-side
JSONL `bulkOperationRunMutation` is deliberately not exposed in this preview:
its asynchronous bulk recovery path is **not implemented**, and a batch in the
UI must not be described as a completed Shopify server bulk operation.

A local pause/cancel stops further phone work and keeps any existing remote draft,
files and operation checkpoints. It does not claim to abort Shopify server work.
No automatic cleanup/deletion or existing-product overwrite is performed. Existing
foreign/exact-SKU products are blocked for manual review; automatic linking/import
of external Shopify listings is not implemented in this preview.

## Revoke and rotate

Use the app's Revoke device, or host `python -m broker.manage devices` and
`python -m broker.manage revoke --device <UUID>`. Forget locally removes the phone
key; it does not pretend the server was contacted. A revoked installation must
create a fresh device identity and pair again. Revoke the Shopify app in Shopify
admin to remove its installation grant.

For Shopify app-secret rotation, update the secret manager and restart; authorize
again as required by Shopify. For the encryption master key: stop the API, securely
snapshot the credential-only DB, set `BROKER_NEW_MASTER_KEY` through the secrets
manager, run `python -m broker.manage rotate-master-key`, replace
`BROKER_MASTER_KEY`, remove the temporary new-key variable, and restart. The CLI
re-encrypts credentials and cached results in one exclusive transaction. Preserve
old encrypted backups/keys according to your host retention and recovery policy.

## Validation

`python -m pip install -r broker/requirements-dev.txt`

`python -m pytest -q broker/tests`

These are local mock/crypto/SQLite tests. Real acceptance is one development-store
draft, all metadata/images/IDs, interruption/resume/no duplicate, then a multi-item
batch. Live publication needs its own explicit approval and test. No production
Canada VHS data may be used to satisfy acceptance.
