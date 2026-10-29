# SnapIMS Next 0.8.1 validation

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
| Unique native/core host checks | **190 passed, 0 failed, 0 skipped** |
| Broker mock/crypto/SQLite/query checks | **21 passed** |
| Total unique validated checks | **211 passed** |
| Consolidated regression / app / instrumentation builds | All unique checks green; final UI/build invocation **BUILD SUCCESSFUL** |
| Android lint | **0 errors**, 63 warnings; no checks disabled |
| Package / signing | ca.canadavhs.snapmobile ·0.8.1 · versionCode11 · retained certificate ·16KB alignment verified |
| Batch deletion | Child records/photos disappear; recovery preserves IDs, leaves batch finished and does not start capture |
| Record deletion | Hidden from inventory/groups/photos; new captures advance to a new record; Undo does not reopen Trash |
| Photo deletion | Independent deletion, original/hash retained, exact seven-day recovery check, direct finished-batch UI button |
| Local drafts | Local receipt/job and product markers hide entries; original remote JSON/IDs unchanged; no remote delete call |
| Recovery | Independent child Trash survives parent recovery; blocked recovery rolls back; expired recovery rejected |
| Upgrade | Populated8→10 and legacy chains retain inventory/history;9→10 creates Trash and preserves previously removed-photo recovery |
| Snapshot | Schema10 includes Trash; legacy3–9 supported; usage JSON not overwritten for schema9 |
| Native UI | Batch delete/recover, finished-photo delete/recover and local draft/job deletion without pairing exercised |
| Agent compatibility | Existing compact registry, permission, approval, workflow, error/cost/idle-request tests pass; current/legacy reads exclude deleted inventory/batches |
| Imported market data | Native regression imports100,000 synthetic records plus75,054 supplied records, safe repeat and restart checked; private CSV excluded from release |
| Desktop preservation | No desktop repository changes made by this update |
| Physical Pixel installation/camera | **UNRUN**; no bound physical device |
| Eligible ChatGPT SIWC account | **UNRUN**; no real sign-in/model discovery/subscription stream claimed |
| Real Shopify development store | **UNRUN**; no deployed broker/account, no real product mutation/deletion |

The full regression ran against the delivered production source, with the owner's
CSV passed explicitly to the native dataset test. Its187 non-Trash-UI checks passed.
Two of three Trash UI cases failed during activity setup because a reused Android
ViewModel factory referenced a closed prior-test database. Those three cases were
split into separate process-isolated UI classes and all rerun successfully, with
their assertions preserved. The consolidated evidence contains190 unique checks,
not overlapping-run totals. Only test fixture organization changed after the full
run. Broker tests ran separately; lint ran after compilation/tests. Reactive UI
checks wait for actual Room/Compose state. WorkManager host teardown can emit
CloseGuard warnings; the consolidated suite has zero failures/errors/skips.

Deletion is local visibility plus a seven-day recovery window. Internal identity,
audit and original file bytes remain retained, including after recovery expires;
this is not secure erasure/storage reclamation. Historical exports and transcripts
are not rewritten. Local recovery never resumes external work automatically.
See [controls and exact limits](next/TRASH.md) and [release notes](next/RELEASE_0.8.1.md).

Evidence: /workspace/.setup/v081-validated-junit, v081-final-full-validation.log,
v081-ui-isolation-validation.log, v081-market-benchmark.json, v081-broker-tests.log,
v081-lint.log, v081-signing.txt and v081-alignment.txt in the cloud workspace.
The private CSV, runtime databases and signing key are excluded from public source.

APK SHA-256: `d79272d7b64072b74ccf325501fc22a99a111c45ae817a2369f8e9a227cc34d4`

Signing certificate SHA-256:
`b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the previous official app without uninstalling or clearing storage.
