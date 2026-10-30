# SnapIMS Next 0.9.0 executed checks

| Android/core host check | Result |
|---|---|
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
| OperatorActionsUiTest | 1 passed |
| OperatorRecoveryUiTest | 1 passed |
| OperatorRecreationUiTest | 1 passed |
| OperatorScreensUiTest | 1 passed |
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

**240 unique Android/core checks passed after final reruns: 0 failures, 0 errors, 0 skipped.**
The complete regression suite was exercised with the supplied private CSV.
Final focused reruns verify the same tests after correcting the moved-toolbar
selector and synchronizing the approval test after rotation. All original
inventory/financial-consent assertions remain. An earlier environment interruption
disrupted result recording; the final reports below are complete. Counts are unique
tests with final reruns replacing their earlier results, not summed reruns. No test or lint check was disabled. Lint: **0 errors / 92 warnings**.
Application and instrumentation APKs build; instrumentation build is not device execution.
An additional **21 unchanged legacy broker compatibility tests** passed locally;
no broker code was changed or deployed and direct mode requires no broker.

| Area | Executed evidence |
|---|---|
| Feature preservation | 491 pre-edit source/control records, per-control reconciliation and 34 capability groups; existing regression coverage retained |
| Native rendered UI | 17 actual populated renders across all eight requested areas, plus pre-edit plans and baseline captures; light/dark, landscape and 1.6× text inspected |
| Selection / exact IDs | Seven selected from 50; capture-history return preserves seven; exact draft queue and channel overrides retained, no per-item approval dialog |
| One-action workflows | Native UI starts selected MATCH_PRICE and direct drafts without a separate health refresh; only selected record IDs queued; saved batch channels used |
| Work-state restoration | Unsaved title survives route return and real Activity recreation; saved database title remains unchanged; Save stays available in landscape/large text |
| Idle / approvals | Opening populated routes causes zero native mutations/uploads/recognition jobs; existing on-demand tests cover zero idle model calls and permission/approval/rejection safeguards |
| Recovery | Paused deterministic job resumes explicitly; AI, cancelled and changed-state recovery refused; original durable Shopify/recognition/archive recovery retained |
| Offline sold database | 75,054 owner source rows plus 100,000 synthetic, 175,054 after restart; repeat owner import adds zero and CSV is unchanged |
| Pricing / cost controls | 100-copy mixed batch, 50 eligible prices applied, questionable records protected, zero provider requests; manual prices, FX, outliers, identity and stale evidence regression retained |
| Host timing | Median fuzzy retrieval 35 ms; mixed offline batch 4256 ms; not physical Pixel measurements |
| Native Shopify | Existing intercepted-HTTP draft/photo, immutable SKU, pricing/weight/stock, linking/edits, lost-response reconciliation and exact multi-channel publication tests passed |
| Data / updates | Package unchanged, version code15, schema11 unchanged, retained certificate and 16KB native alignment verified; no migration/destructive reset introduced |
| Artifact privacy | Owner CSV, runtime databases/journals, API/Shopify credentials and retained signing key excluded |

The application services and workers remain authoritative. The redesign changes
presentation/navigation and adds only guarded local recovery plus saved-destination
resolution at an explicitly requested draft/publication action. It does not start
model requests from UI observation or silently fall back to billed API usage.
The source parity audit is not proof of exercising every possible remote action.

**Physical Pixel / real Canada VHS / real AI account acceptance for 0.9.0: UNRUN here.**
No production Shopify product was changed. Native HTTP fixtures do not establish
live account scopes/schema acceptance or website availability. The existing SIWC
prototype remains experimental; physical sign-in/discovery/stream acceptance is
still unverified. Camera captures show real UI controls but no physical camera.

[Release notes](next/RELEASE_0.9.0.md) ·
[Feature map](design/v0.9.0/FEATURE_PARITY.md) ·
[Actual screenshots](design/v0.9.0/SCREENSHOTS.md) ·
[Before / after workflows](design/v0.9.0/WORKFLOW_COMPARISON.md).

Evidence retained in `/workspace/.setup/v090-validated-junit`,
`v090-final-full-validation3.log`, `v090-final-focused.log`, `v090-final-approval.log`,
`v090-final-lint.log`, `v090-market-benchmark.json`, `v090-signing.txt`,
`v090-alignment.txt`, `v090-badging.txt` and `v090-legacy-compatibility.log`.

APK SHA256: `81c8f2282cec92c494ca5e8ad6cdf15d690c5c36489107ad85e303642f69617b`

Signing certificate SHA256: `b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the existing official app without uninstalling or clearing data.
