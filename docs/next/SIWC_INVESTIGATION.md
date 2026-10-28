# Official SIWC native proof: implemented prototype, physical acceptance UNRUN

The owner supplied externally verified excerpts from these official pages on
2026-10-28, after the cloud network denied the original URL with CONNECT HTTP 403:

- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/token-reference

Those excerpts enable this experimental implementation. The cloud has not
independently fetched the pages. This is not a claim that Android is unsupported,
or that an eligible account, physical callback, or subscription inference worked.
No physical Pixel or eligible Plus/Pro account is attached to this workspace.

## Implemented contract

Settings → AI workspace → Connections → ChatGPT → Continue with ChatGPT starts a
foreground data-sync service and system browser. A stable per-install
`ext_agent_host_id=urn:uuid:UUIDv4` is retained. Initial registration uses
`dynamic_agent_client` with `agent_name_hint=SnapIMS Next`; subsequent sign-ins use
the issued client ID. State, nonce and PKCE verifier are freshly generated with
SecureRandom. PKCE uses S256. There is no client secret or embedded WebView.

The authorize endpoint is https://auth.openai.com/api/accounts/authorize. Redirect
is HTTP 127.0.0.1:1455/auth/callback initially; subsequent attempts can bind another
loopback port. Host/path never change. The service only binds 127.0.0.1, caps requests
and attempts, checks state, rejects duplicate fields, expires after five minutes,
and supports notification cancellation. Browser responses contain no codes/tokens.
No custom-scheme fallback, cookies, scraping, HID, Selenium or first-party client
registration is used.

Scopes: `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`.
Resource: `https://api.openai.com/v1`. The initial callback must supply the issued
`client_id`; code exchange at https://auth.openai.com/api/accounts/oauth/token uses
that ID, verifier, exact redirect and resource. The initial token response must
explicitly grant direct plan scope. ID-token validation covers signature, issuer,
audience/authorized party, expiration/issued-at/not-before and nonce. RS256 and ES256
are supported; unrecognized algorithms/keys fail closed. OpenID discovery uses the
standard auth.openai.com/.well-known/openid-configuration, pins the issuer to
https://auth.openai.com and accepts only same-origin HTTPS JWKS. These metadata/key
assumptions must be confirmed against the actual issuer during the physical proof.
If they differ, show the observed failure and correct the official contract; do not
skip signature verification to get sign-in working.

Tokens are wrapped with AES-GCM and Android Keystore, in private preferences excluded
from app exports, encrypted inventory backups and Android Auto Backup. Refresh runs
under a mutex shortly before expiry; rotated refresh/access credentials are committed
before callers receive the new access token. A returned refreshed ID token is also
verified; account/client identity stays bound. Unusable/revoked/expired credentials
require sign-in again. Disconnect removes local credentials, preserving registration
and hashed identity; Forget account clears them. No documented remote revocation
endpoint was supplied, so this build does not claim server-side revocation on logout.
Do not share preference files or authorization URLs in diagnostic reports.

GET https://api.openai.com/v1/models uses the OAuth bearer and requires root `models[]`
with `slug` and `display_name`; API-key `data[]` is not accepted as a substitute.
The workspace uses POST https://api.openai.com/v1/responses with `store:false`,
`stream:true`, local context and optional `instructions`/custom functions. It sends
no system-role items and none of these unsupported parameters: background,
conversation, previous_response_id, max_output_tokens, max_tool_calls, metadata,
multi_agent, temperature, top_p, prompt, truncation or user. A stream succeeds only
after response.completed. Midstream failure/cancellation preserves partial text;
there is no automatic paid retry or switch to an API key.

Compatible image/file content can be inlined after documented capability metadata or
an explicit device probe. No Files upload API, hosted file search, Code Interpreter,
computer use, hosted MCP/connectors, tool_search, native image generation or
audio/video route is exposed. Custom local tools are a closed, opt-in registry;
changes require exact one-time operator approval. Shopify operations are not enabled
until milestone B's independent gates pass.

The connection screen shows health and sanitized proof stages. This excerpted
preview contract supplies no remaining-plan-allowance endpoint, so usage totals and
remaining limits are explicitly unavailable. HTTP 429 stops with an actionable plan
limit message. Separate API-key support remains available for a new operator-selected
conversation; it is never an automatic fallback.

## Physical Pixel procedure — not yet executed

1. Install the signed 0.3.0 APK over the existing app, preserving data. Use an eligible
   Plus/Pro account and a qualifying open-source locally running application. Keep
   normal device internet access; no home server is involved.
2. Open the ChatGPT connection and Continue with ChatGPT. The proof log must record
   LISTENER then SIGNED_IN after browser consent and verified ID token/direct scope.
   If callback or metadata verification fails, record the exact sanitized error.
   A cloud-network denial is not an Android compatibility diagnosis.
3. Choose Check connection & discover models. Require MODELS_DISCOVERED using the
   actual OAuth catalog. Reopen New chat, select ChatGPT, discover/select an eligible
   model. Press Test TEXT to send an explicit synthetic request against that plan,
   or create a conversation and send a normal text message.
4. Require real streamed text and response.completed, then STREAM_COMPLETED with the
   real response ID/model and a Pixel hardware flag. Confirm that the API key was
   neither requested nor read. The three stages must belong to the same sign-in
   session on the device; old successful logs do not prove a new attempt passed.
5. Test denial, cancellation, expiry/refresh rotation, ineligible account, unavailable
   model, plan limit and airplane mode. Confirm no silent API-key request. Verify
   reconnect after app termination and local credential removal on disconnect.

Host tests cover RFC7636 PKCE, callback state/duplicates/denial, signed-token rejection,
credential encryption/tampering/rotation, strict model parsing, allowed request fields,
stream terminal requirements and 429 billing isolation using mock HTTP only. They do
not establish physical-device OAuth or real subscription acceptance.

The owner explicitly selected Apache-2.0 on 2026-10-28. LICENSE and NOTICE now apply
to SnapIMS Next in this repository, while third-party notices remain intact and the
separate desktop repositories are unchanged. Actual account/app eligibility,
Android callback compatibility and current official preview registration still
require the physical proof.

Each sign-in attempt has a separate random, non-secret proof-attempt ID. Discovery
and streaming log entries carry that ID so the operator can avoid mixing historical
stages from different attempts. A failed attempt does not erase historical evidence.
