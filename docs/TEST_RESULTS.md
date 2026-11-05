# SnapIMS Next 0.10.0 executed checks

**263 unique Android/core host checks passed: zero failures, errors or skipped tests.**
The complete suite ran with the supplied 75,054-row source file. Final focused
reruns cover the guided UI, orchestration, workflow visibility after more than 100
child jobs and safely refreshing generated descriptions after identity corrections. Final reports replace earlier
results; reruns are not added to the count. No test or lint check was disabled.

Application and instrumentation APKs build. Lint: **0 errors / 105 warnings**.
The instrumentation APK was built, not executed on a physical device.
An additional **21 unchanged legacy broker compatibility checks** passed; no broker
was implemented, changed or deployed. Direct Shopify mode remains independent.

## Acceptance evidence

| Requested scenario | Executed evidence |
|---|---|
| Six blank Shopify identities | Six drafts queue with unique immutable SKUs/creation keys; publication correctly requests verified drafts instead of reporting duplicate blanks |
| Stale Publish error vs Create Drafts | Actual Advanced Tools UI displays the publication-specific error; subsequent draft action clears it and queues only the seven selected copies from 50 |
| Recognition against populated local database | Supplied CSV imported unchanged alongside 175,000 synthetic rows; six explicitly labeled representative title/year cases inspected below |
| Automatic USD/CAD retrieval | Actual OkHttp/Valet parser exercised with intercepted HTTP, persisted dated provenance, weekends, invalid/future values, failure throttle and offline cache |
| Cached offline conversion | Sold amount and shipping converted using a verified-cache fixture; missing and expired FX distinguished from identity/no-match outcomes; rate age rechecked on approval |
| Single reliable sale | Produces individual-review recommendation even with legacy minimum=100; bulk approval refused |
| Incompatible sales | DVD, lots, editions, sequels, years, barcode and condition conflicts excluded; broad used evidence never invents a grade; new/sealed premiums protected |
| Fuzzy retrieval | OCR misspellings, alternate word order, partial titles and normalized UPC padding retrieve ranked candidates; similarity alone does not establish evidence |
| Independent evidence | Repeated source listing IDs across dates count once; median/IQR, price range and traceable references retained |
| Bulk approval / exceptions | Real guided UI approves four strong prices, retains one manual price and leaves one unresolved item; five eligible copies advance independently |
| Generated metadata | Confirmed title corrections update only descriptions whose stored hash still matches the workflow-generated text; operator descriptions and prices remain protected |
| Manual prices | Existing manual price survives orchestration; confirming the same amount explicitly records manual protection/history |
| Draft submission | One reviewed UI action queues exactly five eligible selected copies; real Kotlin native pipeline verifies five distinct draft IDs and 14 ordered photo uploads through intercepted HTTP |
| Interrupted Shopify writes | Existing lost-response/native reconciliation tests retained; targeted retry resumes one copy, pauses unrelated copies and preserves creation keys/checkpoints |
| Existing remote identity | Nonblank duplicates, unrelated ownership and unverified/changed draft publication remain blocked; ordinary creation cannot overwrite existing products |
| Restart / selection / progress | Actual Activity recreation retains stage and selection; file-backed Room close/reopen/recovery retains guide, child results and deferral without duplicate jobs; history beyond 100 jobs stays visible |
| Guided navigation / responsive UI | Actual MainActivity Capture→Identify→Price→Review Listings→Create Drafts flow, in-place inspector search/deferral, landscape and 1.6× text; eight native rendered screenshots |
| Automatic safe processing | Next product creates one durable guide and only offline OCR even with optional OpenAI recognition selected; printed edition ambiguity requires review |
| Idle / costs / permissions | No model calls in the 100-copy deterministic run or guided UI; opening the workflow sends no Shopify mutations/uploads; existing agent permission/approval/rejection/idle checks retained |
| Existing local data | 250,054 observations after restart, source hash unchanged, repeat import adds zero, SQLite integrity checks pass; original photo/Trash/backup/credential regression retained |
| Update artifact | Same application ID, version code16, Room/snapshot schema11 unchanged, retained certificate and 16KB alignment verified; no uninstall/reset/migration required |

## Source-data diagnostics and limits

The supplied CSV has **75,054 USD observations, zero CAD**. Without FX, the old
pricing loop excluded every USD sale. It also compared source condition `used`
literally against inventory grades such as `Good`. Both were separate exclusion
causes. The false Shopify duplicate warning originated in publication's blank-ID
comparison; retained UI errors could misattribute that failure to the next action.

The six records and persisted jobs on the actual Pixel were **not available**.
These examples use representative titles and explicit years against the supplied
CSV. They do not establish why each of the user's six phone records failed.
The test rate **1.35 is a synthetic configuration, not a fetched current quote**.
Every withheld recommendation has an explicit validation reason in the app.

| Representative title / year | Without FX | With fixture FX | Evidence score | Compatible retained sales | Result |
|---|---|---|---:|---:|---|
| Beauty and the Beast (1992) | FX_MISSING | PRICE_RECOMMENDED | 85 | 43 | CAD 9.96 recommendation |
| Aladdin (1993) | INCOMPATIBLE_EVIDENCE | INCOMPATIBLE_EVIDENCE | 0 | 0 | Withheld |
| Toy Story (1996) | FX_MISSING | PRICE_REVIEW_REQUIRED | 75 | 1 | CAD 70.67 recommendation |
| The Lion King (1995) | INCOMPATIBLE_EVIDENCE | INCOMPATIBLE_EVIDENCE | 0 | 0 | Withheld |
| The Terminator (1984) | INCOMPATIBLE_EVIDENCE | INCOMPATIBLE_EVIDENCE | 0 | 0 | Withheld |
| Titanic (1998) | INCOMPATIBLE_EVIDENCE | INCOMPATIBLE_EVIDENCE | 0 | 0 | Withheld |

Diagnostic reasons:

- **Beauty and the Beast**: 85/100 evidence quality · 43 independent compatible sales. Eligible for bulk approval; not yet applied.
- **Aladdin**: Candidates found but none passed sold-evidence validation: IDENTITY_REVIEW (4), EDITION_CONFLICT (20), YEAR_UNVERIFIED (25), YEAR_CONFLICT (6), CONDITION_CONFLICT (4), MULTI_ITEM (1)
- **Toy Story**: 75/100 evidence quality · 1 independent compatible sales. Review this recommendation individually before applying.
- **The Lion King**: Candidates found but none passed sold-evidence validation: EDITION_CONFLICT (20), YEAR_UNVERIFIED (20), IDENTITY_REVIEW (6), CONDITION_CONFLICT (1), YEAR_CONFLICT (10), MULTI_ITEM (3)
- **The Terminator**: Candidates found but none passed sold-evidence validation: YEAR_UNVERIFIED (22), IDENTITY_REVIEW (13), YEAR_CONFLICT (15), MULTI_ITEM (6), EDITION_CONFLICT (3), SEQUEL_CONFLICT (2)
- **Titanic**: Candidates found but none passed sold-evidence validation: IDENTITY_REVIEW (4), MULTI_ITEM (3), YEAR_CONFLICT (17), YEAR_UNVERIFIED (13), EDITION_CONFLICT (1), CONDITION_CONFLICT (7), SEQUEL_CONFLICT (15)

Excluded-candidate counts describe inspected bounded examples, not the whole
library. Complete histories are fetched only for identity-compatible title keys;
more than 5,000 potential pricing observations requires narrowing instead of
silently sampling prices. All sales remain in the user's database.

Host benchmark: **43 ms median indexed retrieval** on the initial
175,000-row synthetic library; after importing the owner CSV, the 100-copy
mixed batch completed matching/approval in **10914 ms**, with 50 strong
recommendations applied and zero model requests. These are cloud-host timings,
not Pixel latency promises. Reopening retained **250,054** current observations.

## Unverified external acceptance

**Physical Pixel capture/OCR accuracy, live Bank of Canada retrieval, and real
Canada VHS Shopify writes: UNRUN in this environment.** Bank of Canada network
access is blocked by the cloud allowlist. Test interceptors verify request/parser,
cache and failure behavior, not live service availability. No real Shopify product
was created or modified. A physical sign-in/discovery/streamed SIWC response also
remains unverified from the earlier prototype; this release does not claim it.

## Per-suite results

| Android/core suite | Passed |
|---|---:|
| AgentApplicationTest | 18 |
| AgentContextTest | 7 |
| AgentIntegrationUiTest | 1 |
| AgentOnDemandTest | 10 |
| AgentPermissionUiTest | 1 |
| AgentTasksUiTest | 1 |
| AgentWorkflowTest | 11 |
| AiProtocolTest | 7 |
| CameraLayoutTest | 1 |
| CameraUpdateTest | 6 |
| DirectShopifyModelTest | 1 |
| DirectShopifyTest | 4 |
| GuidedCaptureNavigationTest | 1 |
| GuidedInspectorUiTest | 1 |
| GuidedWorkflowTest | 20 |
| GuidedWorkflowUiTest | 1 |
| JournalIntegrationTest | 6 |
| MarketDatabaseTest | 14 |
| MarketDatasetValidationTest | 1 |
| MarketSmartMatchTest | 5 |
| MarketUiTest | 1 |
| NativeShopifyIntegrationTest | 13 |
| NativeShopifyOwnerUiTest | 1 |
| NextIntegrationTest | 7 |
| OperatorActionsUiTest | 1 |
| OperatorRecoveryUiTest | 1 |
| OperatorRecreationUiTest | 1 |
| OperatorScreensUiTest | 1 |
| ProductionBatchUiTest | 1 |
| ProductionWorkflowTest | 21 |
| RecognitionClientTest | 7 |
| RecognitionStorageTest | 7 |
| ShopifyManagementTest | 15 |
| ShopifyManagementUiTest | 1 |
| ShopifyUiTest | 1 |
| ShopifyUploadReviewUiTest | 1 |
| ShopifyWorkflowTest | 11 |
| SiwcProtocolTest | 6 |
| StandaloneInventoryTest | 9 |
| StandaloneUiTest | 1 |
| TrashDraftUiTest | 1 |
| TrashPhotoUiTest | 1 |
| TrashTest | 10 |
| TrashUiTest | 1 |
| WorkspaceUiTest | 1 |
| CaptureRulesTest | 10 |
| DesktopConformanceTest | 4 |
| LocalRecognitionTest | 6 |
| MarketCsvTest | 2 |
| PricingConformanceTest | 3 |

## Reproduce

Use JDK21, the pinned SDK/Gradle versions and repository setup instructions.
Run Gradle invocations sequentially:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
```

For the private source-data validation, append `-PmarketCsv=/absolute/path/to/trawl_sales.csv`
to the first invocation. The source file is never bundled. The ordinary test run
uses 175,000 generated observations without requiring the private CSV.
Install the retained-signer release APK over the existing app; **do not uninstall
or clear data**. See the [Pixel installation/checklist](next/RELEASE_0.10.0.md).

[Workflow / root causes](next/WORKFLOW_0.10.0.md) ·
[Native screenshots](design/v0.10.0/SCREENSHOTS.md) ·
[GitHub build workflow](https://github.com/fachingo/APK/actions/workflows/android.yml).

Local evidence: `/workspace/.setup/v010-validated-junit`, `v010-full2.log`,
`v010-final-focused.log`, `v010-final-guided.log`, `v010-final-lint.log`, `v010-market-benchmark.json`,
`v010-signing.txt`, `v010-alignment.txt`, `v010-badging.txt`,
`v010-legacy-compatibility.log`. Earlier failing runs were repaired and rerun.

APK SHA256: `bc0ef7627761766fc217284e3b5b269ddb9674d9979917ac1e54f48e3b2ede3b`

Signing certificate SHA256: `b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
