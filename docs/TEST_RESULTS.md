# SnapIMS Next 0.8.0 validation

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
| JournalIntegrationTest | 6 passed |
| MarketDatabaseTest | 14 passed |
| MarketDatasetValidationTest | 1 passed |
| MarketUiTest | 1 passed |
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
| WorkspaceUiTest | 1 passed |
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| MarketCsvTest | 2 passed |
| PricingConformanceTest | 3 passed |
| Unique native/core host checks | **177 passed, 0 failed, 0 skipped** |
| Broker mock/crypto/SQLite/query checks | **21 passed** |
| Total unique validated checks | **198 passed** |
| Regression and APK builds | **BUILD SUCCESSFUL**; app and instrumentation APKs built |
| Android lint | **0 errors**, 63 warnings; no checks disabled |
| APK compatibility | Version0.8.0 / versionCode10 / same package and retained certificate /16KB alignment verified |
| Populated migration |8→9 retains physical IDs, photo hashes, chat/usage and reviews;1→9 and populated6→9 chains also validate |
| Compact registry |5 root tools in Full Agent,4 in Read Access,0 in Chat Only; every resource/action schema and every entity's actual Room columns checked |
| Permission enforcement | Fresh stored modes reject stale caller copies; Chat Only blocks reads; Read Access blocks proposals; downgrade cancels the response and expires pending reviews |
| Actual local reads | Full inventory fields, custom projection, local filters, latest-batch unpriced, cross-batch duplicates, complete-scope currency ranking and cache invalidation |
| Mixed approved workflow | Corrected-title evidence, metadata/prices/readiness/local plans commit atomically; changed second cohort rolls back metadata, prices, audit and task rows |
| Rejection and stale reviews | Rejected/stale selections make no inventory changes or recognition jobs; one-use approvals cannot replay |
| Explicit paid/destructive/financial/live controls | Reviewed prices/archive/paid recognition require their consent; live publication requires SUBMIT before native job creation |
| Existing services | Batch/task/settings controls, actual archive worker/hash verification, native Shopify management review and owning jobs reused |
| Remote completion | Queued recognition/Shopify remains incomplete; mocked verified native ownership/link outcome controls task success |
| On-demand-only behavior | Initialization, inventory edits, history, mode/budget changes and photo staging make0 mocked model requests; Send runs bounded tool rounds then idles |
| Error recovery | Bad typed query returns an actionable error and can recover next round; provider failures keep partial output with no retry/fallback |
| Context/cost bounds | Complete current tool results and pagination retained; older previews/context are bounded; older and subsequent-tool-round attachments are omitted |
| Usage/cost units | Token/cache counts recorded; known USD costs labelled reported, catalog USD rates estimated; unknown units/plan credits never labelled dollars |
| Photo staging | Explicit derivative≤960px/500KB, unchanged original/hash; staging sends0 requests and Chat Only cannot stage app photos |
| Native UI | Named permissions, review expiry, actual record shortcut, before/after preview, financial consent and portrait/landscape approval controls |
| Backup/restore | Schema9 policies/traces/usage retained; legacy3–8 supported; unfinished restored tasks pause and RUNNING traces become INTERRUPTED |
| Actual supplied CSV | All75,054 USD records plus100,000 synthetic records imported natively; private source bytes unchanged; repeat adds0 |
| Full library restart |175,054 current observations preserved after reopening persistent SQLite |
| Agent against actual dataset | Native market search and overview passed; largest observed tested result 9897 characters≤10,000; no provider contacted |
| Host indexed benchmark |FTS4 median2ms; range1–11ms; physical Pixel timing UNRUN |
| Desktop preservation | SnapIMS5c790dc and Pricing_module64c472c untouched; pre-existing untracked desktop adapters retained |
| Physical Pixel / eligible SIWC account | **UNRUN**; no device/account bound; no real sign-in, model discovery or subscription stream success claimed |
| Real Shopify authorization / development store | **UNRUN**; no deployed broker/account bound; no production mutation |

The final full regression passed176 host checks in one invocation, including the
actual supplied CSV import, complete current-context fix, agent reads and restart.
The final USD-unit parser change then passed all7 protocol and10 on-demand checks
and rebuilt both APKs. The final evidence set replaces those class reports and
contains177 unique host checks, not the sum of overlapping runs. Broker21 checks
passed separately. Lint ran after test/compilation completed. Native UI screenshots
were visually inspected. Earlier focused UI checks were corrected to wait for
reactive Room/Compose state and isolate each activity fixture; no assertions or
lint checks were disabled. WorkManager host-test teardown emitted CloseGuard
warnings; the test suite has0 failures/errors/skips.

Evidence: /workspace/.setup/v080-validated-junit,
v080-final-full-validation.log, v080-final-cost-validation.log,
v080-final-lint.log, v080-broker-tests.log, v080-market-benchmark.json,
v080-signing.txt and v080-alignment.txt. Native generated UI image:
app/build/fixture-output/agent-mixed-review-landscape.png. The private market CSV,
credentials, signing key, runtime databases and generated build/test directories
are excluded from public APK/source.

Read [agent usage and exact limits](agent/APPLICATION_AGENT.md),
[release notes](agent/RELEASE_0.8.0.md) and [baseline audit](agent/INTEGRATION_AUDIT.md).
The agent uses on-demand typed services and human approvals. Unsupported native
operations are not invented: photo editing/SAF file selection/credentials remain
human workflows, there is no SQL/shell/tap tool or continuous AI observation, and
not every native error retains a failure timestamp/history. Shopify still blocks
production writes, unrelated-product adoption and unsupported existing-product
price/stock/media editing. Actual model reasoning quality and physical/store/account
acceptance are not established by mocked transport or host UI tests.

APK SHA-256: `8656bfe9ea021b610286bbd71688ddc7340f6283adde98d57416668a62c48af5`

Signing certificate SHA-256:
`b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the previous official app; do not uninstall or clear private storage.
Conventional API-key support remains independent; subscription errors never switch
to separate API billing. Approved remote resources/consumed usage cannot be undone.
