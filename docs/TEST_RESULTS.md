# SnapIMS Next 0.6.0 validation

| Check | Result |
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
| ShopifyUiTest | 1 passed |
| ShopifyWorkflowTest | 10 passed |
| SiwcProtocolTest | 6 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| WorkspaceUiTest | 1 passed |
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| MarketCsvTest | 2 passed |
| PricingConformanceTest | 3 passed |
| Unique native/core host checks | **120 passed, 0 failed, 0 skipped** |
| Broker mock/crypto/SQLite checks | **13 passed** |
| Total unique validated tests | **133 passed** |
| App and instrumentation APKs | Built; physical instrumentation execution UNRUN |
| Android lint | **0 errors**, 62 warnings remain; no checks disabled |
| Version / signing / alignment | 0.6.0, versionCode8; retained certificate;16KB alignment verified |
| Explicit upgrade |1→2→3→4→5→6→7 and populated6→7 retain originals/inventory; old unbound API attempts stop for manual retry |
| Native approval UI | Exact SKU/source-price review, failed approval stays open, manual/historical approval, portrait↔landscape with visible buttons, outcome task and Inventory navigation |
| Native task selection UI | Select actual local inventory, require nonempty selection, create offline review task; no AI/network call |
| Approved recognition | Single-use receipt, unchanged-job reuse, current-photo title confirmation, no silent title application; changed revision/photos/settings/credentials rejected |
| API billing isolation | ChatGPT-plan chat cannot implicitly approve separately billed API-key recognition; consent required; removed/rotated encrypted key slot fails before network request |
| Scheduling / cancel | Durable approved outbox reuses owning jobs on recovery; partial results retained; completed Shopify items survive cancellation |
| Atomic market pricing | Real source cohort only; manual/historical consent; operator-only FX/date; changed second cohort rolls back earlier price and audit writes |
| Shopify batch approvals | Exact multi-copy draft-only job; server partial success tracked separately; cancellation retains verified success; one-use approval |
| Backups | Schema7 tasks/recognition bindings plus photos and streamed market SQLite; schemas3–6 accepted; unfinished restored tasks pause and no external request restarts |
| Actual supplied CSV | All75,054 records imported natively with source SHA unchanged; repeated import adds0; private source data is excluded from APK/source ZIP |
| Dataset restart |100,000 generated +75,054 actual =175,054 current observations retained after persistent SQLite restart |
| Host indexed benchmark | FTS4, median1ms; range1–14ms; physical Pixel timing UNRUN |
| Preserved desktop sources | SnapIMS baseline5c790dc and Pricing_module baseline64c472c unchanged; pre-existing untracked desktop adapters retained |
| Physical Pixel / live SIWC | **UNRUN**: no attached Pixel/eligible account; no sign-in/model/stream success claimed |
| Real Shopify authorization / development store | **UNRUN**; no broker deployed or real-store acceptance claimed; no production mutations |

The full regression covered120 unique host tests and identified a native item-selection
layout issue. After its bounded-layout fix, the final sequential rerun passed
AgentTasksUiTest, AgentWorkflowTest and ShopifyUiTest (13 overlapping tests), and rebuilt
both APKs. Latest per-class JUnit results replace prior results in the consolidated
set; overlapping tests are counted once. All other regression source paths remain
unchanged after their passing run. Broker tests passed separately. Lint ran after
all compilation/test tasks finished. Two earlier overlapping Gradle runs collided
in generated result files; their incomplete reports are excluded from this evidence.

Evidence: /workspace/.setup/v060-validated-junit,
v060-full-validation.log, v060-final-focused-build.log, v060-final-lint.log,
v060-broker-tests.log, v060-market-benchmark.json and v060-signing.txt.
Native UI screenshots are retained in app/build/fixture-output/workflow-*.png;
portrait and landscape approval screens and Tasks were visually inspected.

APK SHA-256: `790efe09f7098812ad08215623aae2cef3b726851b14fe0474205936c8e6f553`

Signing certificate SHA-256:
`b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the existing app; do not uninstall or clear private storage.
The A–D program's external acceptance remains incomplete. Conventional API-key
support remains independent; SIWC errors never switch to separately billed API usage.
