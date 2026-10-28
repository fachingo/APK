# Sign in with ChatGPT: verification status and implementation gate

The owner added SIWC and requested milestones A–D on 2026-10-28. This is distinct
from conventional paid OpenAI API keys. Never silently fall back from subscription
usage to API billing; connection/authentication mode must be explicit per task/chat.

Required official source:
https://developers.openai.com/siwc/token-sharing-open-source

## Observed blocker

A request to that exact official URL on 2026-10-28 failed at the managed network
proxy with CONNECT HTTP 403. developers.openai.com is absent from the enforced
allowlist. No browser/search connector is installed. The current official SIWC
eligibility, native-client registration/redirect/PKCE requirements, request headers,
Responses restrictions, model discovery and usage endpoints cannot be verified in
this environment. This is **not** evidence that Android is officially unsupported.
No eligible account or connected physical Android device is available for the
required sign-in → model discovery → streamed-response physical proof of concept.

Do not invent OAuth endpoints, reuse a Codex/ChatGPT first-party client ID, repurpose
ChatGPT cookies, scrape a browser, simulate input, or label a mock login successful.
Conventional API-key functionality remains independent and available.

## Required next verification

1. Read the current official page with network access to developers.openai.com.
2. Verify eligibility of this open-source, locally running Android app and any
   registration requirements. Record the exact supported issuer/client/scopes and
   Responses/model/usage endpoints plus current capability restrictions.
3. Validate native Kotlin compatibility: system browser/Custom Tabs, PKCE S256,
   state/nonce, external user agent, approved claimed HTTPS/app redirect or an
   officially permitted native callback. No embedded WebView login or client secret.
4. Store permitted refresh/access credentials with Android Keystore wrapping;
   handle expiry, refresh rotation, logout/revocation and canceled callbacks.
5. Execute the owner's required physical-device proof with an eligible account:
   successful sign-in, actual model discovery, one actual streamed response.
   Record protocol/usage evidence without exposing tokens.
6. Enable only verified SIWC capabilities. Gate tools, file/image inputs and models
   against the official contract. Display actual provider usage/limits/health if
   exposed; otherwise label usage unavailable, never invent plan allowance.
7. Keep API-key, free-tier-provider and optional phone-local-model modes explicit.
   Unsupported SIWC features may offer a user-selected API-key task; never auto-switch.

Until those checks pass, a connection option must clearly state SIWC is unavailable
in this build and explain the verification/device gate. It must not initiate an
invented OAuth flow or imply that subscription inference has been implemented.
The blocker does not require inserting a home-server dependency into the app.

No repository-level open-source licence was found in the inspected checkouts. A
public repository alone does not establish open-source licensing; eligibility also
needs an owner-approved compatible licence. This update does not silently relicense
the owner's existing work or reuse a first-party OAuth client registration.
