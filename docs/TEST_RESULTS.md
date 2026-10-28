# SnapIMS Next 0.7.0 validation

| Check | Executed result |
|---|---|
| AgentTasksUiTest | 1 passed |
| AgentWorkflowTest | 11 passed |
| AiProtocolTest | 5 passed |
| CameraLayoutTest | 1 passed |
| CameraUpdateTest | 6 passed |
| JournalIntegrationTest | 6 passed |
| MarketDatabaseTest | 14 passed |
| MarketDatasetValidationTest | 1 passed |
| MarketUiTest | 1 passed |
| NextIntegrationTest | 7 passed |
| RecognitionClientTest | 7 passed |
| RecognitionStorageTest | 7 passed |
| ShopifyManagementTest | 13 passed |
| ShopifyManagementUiTest | 1 passed |
| ShopifyUiTest | 1 passed |
| ShopifyUploadReviewUiTest | 1 passed |
| ShopifyWorkflowTest | 11 passed |
| SiwcProtocolTest | 6 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| WorkspaceUiTest | 1 passed |
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| MarketCsvTest | 2 passed |
| PricingConformanceTest | 3 passed |
| Unique native/core host checks | **136 passed, 0 failed, 0 skipped** |
| Broker mock/crypto/SQLite/query checks | **21 passed** |
| Total unique validated tests | **157 passed** |
| Full regression and APK builds | **BUILD SUCCESSFUL**; both app and instrumentation APK built |
| Android lint | **0 errors**, 62 warnings remain; no checks disabled |
| Version/signing/alignment | 0.7.0, versionCode9; retained certificate;16KB alignment verified |
| Explicit upgrade |1→2→3→4→5→6→7→8 validates; populated6→8 preserves captures/IDs and expires old pending AI approvals |
| Native listing management | Cursor-paged title/status browse, exact-ID inspection, single-variant/ownership/SKU validation and scalar metadata review implemented |
| Broker safeguards |22 GraphQL document syntaxes parsed; literal search escaping, page/cursor bounds, ownership/duplicate/stale/production guards, exact field allowlist |
| Metadata uncertainty | Accepted response-loss case reconciles by read; desired/protected hashes preserve other fields; exactly one write; conflicting retry rejected |
| Native link/update outcomes | One-use approval, no change before approval, local/remote changes reject, exact metadata fields only, server mismatch never becomes success |
| Cancellation | Accepted create retains remote IDs and CANCELLED state; accepted metadata/cancel cannot become SUCCESS; no later steps dispatched |
| Native upload approvals | Durable reviewed snapshots bind selected item/photo/barcode evidence and connection; changes expire without a job; SUBMIT required for publication |
| Native UI | Catalog inspection, saved link review, stale rejection, queued-job navigation, portrait/landscape controls; native upload review includes exact SKU, price, weight, barcode and photo order |
| Backup/restore | Schema8 includes saved reviews; schemas3–7 supported; pending restored reviews expire; unfinished restored management items require fresh approval |
| Broker container | Built and starts UID10001 with read-only root and network disabled; current runtime source matches image; **not deployed** |
| Actual supplied CSV | All75,054 USD records imported natively; source SHA unchanged; repeated import adds0; private input excluded from APK/source |
| Dataset restart |100,000 generated +75,054 actual =175,054 current observations preserved after reopening persistent SQLite |
| Host indexed benchmark | FTS4 median2ms; range1–9ms; physical Pixel timing UNRUN |
| Desktop preservation | SnapIMS5c790dc and Pricing_module64c472c unchanged; pre-existing untracked desktop adapters retained |
| Physical Pixel / live SIWC | **UNRUN**: no bound Pixel/eligible account; no sign-in/model/stream success claimed |
| Real Shopify authorization / store acceptance | **UNRUN**; no deployed broker/account bound; no production mutation |

The final full regression passed136 unique host checks in one invocation, including
the actual owner-data import/restart case. Earlier focused UI failures involved
scrolling to virtualized controls; final tests scroll the native list before
interacting. The final full run includes those corrections and all cancellation,
barcode-binding and schema8 changes. There are no overlapping targeted runs added
to the test count. Broker21 checks passed separately. Lint ran after compilation
and test tasks finished. Native UI screenshots were visually inspected.

Evidence: /workspace/.setup/v070-validated-junit,
v070-full-validation.log, v070-final-lint.log, v070-broker-final.log,
v070-market-benchmark.json, v070-signing.txt, v070-docker-final-build.log and
v070-docker-smoke.log. UI images: app/build/fixture-output/shopify-link-review.png
and shopify-native-draft-review.png. Host mocks/container startup do not establish
Shopify schema, authorization or physical-device acceptance.

Current scope: listing inspection, explicit owned-DRAFT link and selected scalar
metadata updates, plus saved native upload/publication reviews. Existing-product
price/stock/media editing, foreign/legacy ownership stamping, server JSONL bulk
operations and production writes are not delivered. Shopify scalar productUpdate
has no atomic compare-and-set contract here: an independent admin write between
preflight and mutation remains a real limitation. Inspect post-verification errors.
The A–D program's external gates remain incomplete; business calendar/communications
have not started. See next/SHOPIFY_RECONCILIATION.md for usage and exact limits.

APK SHA-256: `17e47f64fe5a6ea9332c6e27c6cc6652994bdd49d10cf3936f4c3d5b89173dd9`

Signing certificate SHA-256:
`b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the existing app; do not uninstall or clear private storage. Conventional
API-key support remains independent; SIWC errors never switch to API billing.
