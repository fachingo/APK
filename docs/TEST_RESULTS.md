# SnapIMS Next0.8.3 validation

| Check | Executed result |
| --- | --- |
| AgentApplicationTest | 18 passed |
| AgentContextTest | 7 passed |
| AgentIntegrationUiTest | 1 passed |
| AgentOnDemandTest | 10 passed |
| AgentPermissionUiTest | 1 passed |
| AgentTasksUiTest | 1 passed |
| AgentWorkflowTest | 11 passed |
| AiProtocolTest | 7 passed |
| CameraLayoutTest | 1 passed |
| CameraUpdateTest | 6 passed |
| DirectShopifyModelTest | 1 passed |
| DirectShopifyTest | 4 passed |
| JournalIntegrationTest | 6 passed |
| MarketDatabaseTest | 14 passed |
| MarketDatasetValidationTest | 1 passed |
| MarketSmartMatchTest | 5 passed |
| MarketUiTest | 1 passed |
| NativeShopifyIntegrationTest | 10 passed |
| NativeShopifyOwnerUiTest | 1 passed |
| NextIntegrationTest | 7 passed |
| RecognitionClientTest | 7 passed |
| RecognitionStorageTest | 7 passed |
| ShopifyManagementTest | 15 passed |
| ShopifyManagementUiTest | 1 passed |
| ShopifyUiTest | 1 passed |
| ShopifyUploadReviewUiTest | 1 passed |
| ShopifyWorkflowTest | 11 passed |
| SiwcProtocolTest | 6 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| TrashDraftUiTest | 1 passed |
| TrashPhotoUiTest | 1 passed |
| TrashTest | 10 passed |
| TrashUiTest | 1 passed |
| WorkspaceUiTest | 1 passed |
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| MarketCsvTest | 2 passed |
| PricingConformanceTest | 3 passed |
| Unique native/core host checks | **211 passed,0 failed,0 skipped** in consolidated reports |
| Unchanged legacy compatibility checks | **21 passed**; no broker application created/deployed |
| Total unique checks | **232 passed** |
| Final tests / app and instrumentation APK builds | BUILD SUCCESSFUL |
| Lint |0 errors,78 warnings; no checks disabled |
| Update compatibility | VersionCode13 /0.8.3 / retained package and certificate /16KB alignment verified |
| Local data | Room/snapshot10 unchanged; existing migration/import/export/backup checks pass |
| Direct auth | Encrypted persistence, cached token reuse/renewal, safe rejected-org errors, disconnect and late-response credential resurrection prevention |
| Native draft pipeline | Real Kotlin gateway through mocked GraphQL/staging: two photographs, SKU, CAD decimal price, stock, weight, media and ownership verification |
| Product/file interruption | Lost accepted product and file responses reconcile from durable journal/markers without duplicate product/photo creation |
| Existing marked product | Exact SKU/ownership claim, selected metadata/tags/price/weight/stock updates; unselected barcode/tax/tracking/shipping/media protected |
| Concurrent external edit | An external price change stops the selected price update; external price is not overwritten |
| Native status UI | Separate local approval, Active and Online Store publication reviews; SUBMIT enforced; local inventory reaches verified PUBLISHED |
| Permissions / rejection / replay | Disabled owner writes and foreign resources send no mutations; rejected/pending/stale reviews cannot write; accepted approval cannot enqueue twice |
| Agent direct operations | Actual native catalog read, rejected/accepted typed status proposal, native approval handoff and verified1/1 task outcome |
| Smart local retrieval | SQLite indexed search, transposed OCR spelling, partial tokens, score/year separation and currency/GTIN safety |
| Independent optional match AI | Explicit consent, separate model, bounded text-only candidates, recorded usage/cost; invented IDs/prices/duplicates rejected; failure makes no fallback/evidence write |
| Idle behavior | Existing on-demand-only model tests remain green; local database retrieval does not call a model |
| Market dataset |75,054 supplied USD records plus100,000 synthetic records;175,054 observations retained after restart; repeat import adds0 |
| Agent against private dataset | Native market/overview reads pass; tested result 9621 chars, bounded below10,000; no provider contacted |
| Indexed host timing |FTS4 median3ms; physical Pixel performance UNRUN |
| Private files | Owner CSV, runtime databases, operation journals, credentials, tokens and signing key excluded from public APK/source |
| Physical Pixel / real Canada VHS / website | **UNRUN**; no real credentials/device bound and no real-store mutation |
| Real API/provider acceptance | **UNRUN**; mocked transport does not prove current Shopify schema/scopes or actual model quality |

The full regression exercised all test classes and the owner's CSV. Two existing
checks needed proper asynchronous UI/Room waits after the additional approval/
disconnect steps. Their assertions remain intact. Final focused runs replace
those class reports and include native status, financial-field protection, agent
status tasks and the final UI guards for connection changes/loading match scores.
The evidence above counts211 unique checks, not overlapping runs. The new native
protocol tests execute the Kotlin upload/status/ownership code against intercepted
HTTP responses; the UI test drives the native approval flow and explicitly runs
its durable jobs. No store credentials or actual product data are used in those
fixtures. Tests do not establish physical-device/live-store success.

Lint ran after compilation/tests. WorkManager host teardown can emit CloseGuard
warnings; the consolidated JUnit evidence has zero failures/errors/skips. The
market-browser host screenshot was visually inspected after waiting for actual
query completion; scores are labelled as heuristics and AI is OFF by default.

No new database tables or destructive migrations were required. Existing string-
kind review/job fields admit the new owner action, while snapshots preserve their
payloads and pause restored external work. Device-private encrypted operation
journals are deliberately outside backup/export roots. Restore/reconnect may
require fresh ownership/status review; ambiguous writes are never blindly created
again. Independent devices/desktop publishers do not share a global SKU lock.

Read [direct operations and exact limits](next/DIRECT_SHOPIFY.md) and
[matching and independent AI](next/SMART_MARKET_MATCHING.md). Credential entry and
owner-write/live permission switches remain human phone actions. No continuous
model monitoring, automatic image transmission or broker deployment was added.
The existing desktop repositories were not modified by this update.

Evidence: /workspace/.setup/v083-validated-junit, v083-full-validation.log,
v083-final-focused-validation.log, v083-final-ui-validation.log,
v083-market-benchmark.json, v083-broker-regression.log, v083-final-lint.log,
v083-signing.txt and v083-alignment.txt. Host screenshot:
app/build/fixture-output/market-browser.png.

APK SHA256: `78dcf44b180d56d5c2b7730e21b883dd881c061314a5eb8af55169b6c3b0c5ff`

Signing certificate SHA256:
`b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the existing official app; do not uninstall or clear private data.
