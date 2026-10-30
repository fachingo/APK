# SnapIMS Next 0.8.4 executed checks

| Host check | Result |
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
| NativeShopifyIntegrationTest | 13 passed |
| NativeShopifyOwnerUiTest | 1 passed |
| NextIntegrationTest | 7 passed |
| ProductionBatchUiTest | 1 passed |
| ProductionWorkflowTest | 21 passed |
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

**236 unique Android/core checks passed, 0 failures, 0 errors, 0 skipped.**
The full suite passed; final focused reruns verify the final code and replace the
same class reports, rather than inflating the unique count. Application and
instrumentation APKs build successfully. Lint: **0 errors / 90 warnings**;
no lint checks were disabled. Instrumentation APK creation is not physical-device
execution. Core/main camera, inventory, imports, backup/restore, Trash, agent
permission/approval/idle behavior and native Shopify regressions remain covered.

| Acceptance area | Executed evidence |
| --- | --- |
| Partial selection | Native HTTP pipeline creates seven unique drafts with seven uploaded photographs from a 50-copy local batch; unselected records stay unchanged |
| Fast draft submission | 50 copies queued by one operator action; no per-item reviews or second approval; invalid/published copies skipped |
| Selection UI | Seven checkboxes, navigation away/back, exact seven-item draft queue, no approval dialog; channel override survives connection refresh and is saved with drafts |
| Offline mixed batch | 100 selected synthetic copies against the same 175,054-record SQLite library; 50 high-confidence prices applied, questionable copies remain unpriced; **0 model requests** |
| Financial validation | Median/average/trimmed mean, IQR exclusions, dated explicit FX, shipping policy, floors/ceilings, rounding and protected manual prices; stale inventory/source evidence cannot apply |
| Compatible identity | Separate editions, remakes/years, sequels, bundles, formats and sealed/graded conditions protected; exact UPC only corroborates compatible minor OCR errors |
| Hybrid search | Exact and approximate candidates coexist; prefix/token/spelling/trigram/Jaro-Winkler paths retain complete group counts; fallback, barcode, filters and pagination exercised |
| Independent AI tasks | Persisted separate overrides, model capability rejection, explicit AI consent/budget limits, selected request models, bounded candidates, actual usage when returned, no automatic pricing/fallback/retry |
| Native channels | One SUBMIT binds product/channel snapshots; multiple publication inputs; per-channel verified receipts; partial retry sends only missing destination; rejection sends no changes |
| Recovery / integrity | Existing lost product/file-response reconciliation; saved workflow results, interrupted-AI pause, delete cancellation, migration/legacy import and non-secret preference round trip |
| Large data | 100,000 synthetic plus 75,054 supplied source rows; 175,054 retained after restart; source CSV unchanged, repeat import adds zero |
| Cost / data bounds | Native agent reads the real local library with no provider requests; tested compact result 9816 characters; optional AI sends no whole database/photos |
| Host performance | Fuzzy median 26 ms; 100-copy mixed matching/application 4244 ms; physical Pixel timing UNRUN |
| Update compatibility | Same package/certificate, version code 14 / 0.8.4, additive Room/snapshot 11, 16KB native alignment verified |
| Public artifact privacy | Owner CSV, live databases, runtime operation journals, credentials, tokens and retained signing key excluded |

The benchmark FX rate is synthetic test configuration, not a fetched quote.
A similarity/confidence category is not evidence of an observed current market price.
Actual source references and amounts remain in the immutable local evidence library;
results use compact IDs/hashes and load sold records in local pages.

The owner reported working Canada VHS connection and a real draft on v0.8.3.
**New v0.8.4 physical Pixel/live Shopify/provider/website acceptance: UNRUN here.**
The new native tests execute real Kotlin gateway/pipeline code through intercepted
HTTP; they cannot establish live schema/scopes, actual model quality, physical camera
behavior or real website availability. No real store product was changed. No broker
app was created or deployed, and no new broker dependency was introduced.

Initial regression found and corrected a generic-format search regression, the
mobile-import snapshot version, and tests needing updated selectors/async UI waits.
The final full run has no failures. The final focused run additionally covers compact
paged evidence, per-batch channel preservation and public task-setting integration.
The unchanged legacy Python broker was not modified or deployed in this cycle.

Read [release notes](next/RELEASE_0.8.4.md),
[workflow contract and limits](next/PRODUCTION_WORKFLOW.md), and
[direct Shopify setup](next/DIRECT_SHOPIFY.md).

Evidence: `/workspace/.setup/v084-validated-junit`, `v084-final-full-validation.log`,
`v084-final-focused.log`, `v084-market-benchmark.json`, `v084-final-lint.log`,
`v084-signing.txt`, `v084-alignment.txt`, `v084-badging.txt`.
The host `production-batch-selection.png` screenshot was visually inspected.

APK SHA256: `75e7a9a553ede0b21f50cee424d2a7d4c62f4e14474ec3cff0af16d68bd7d562`

Signing certificate SHA256: `b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the existing official app without uninstalling or clearing private data.
