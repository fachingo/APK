# SnapIMS Next0.8.2 direct read-only preview validation

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
| DirectShopifyTest | 3 passed |
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
| Unique host checks | **194 passed,0 failed,0 skipped** in consolidated reports |
| Broker regression | **21 passed** |
| Total unique checks | **215 passed** |
| Final focused tests / APK build | BUILD SUCCESSFUL |
| Lint |0 errors,69 warnings; no checks disabled |
| Update compatibility | VersionCode12, same package/certificate; Room10 unchanged;16KB alignment verified |
| Native direct transport | Client-credentials form, encrypted token persistence, reuse/renewal, domain checks and rejection of direct writes tested with mocked HTTP |
| Rejected grant | Actual organization error preserved with secret redaction; no fallback request |
| Disconnect | Direct credentials removed without a broker revocation request |
| Existing components | SIWC encrypted namespace and existing inventory/agent/Shopify regression retained |
| Real client-credentials exchange / Canada VHS catalog | **UNRUN**; no credentials or physical Pixel available |
| Direct production writes and photo upload | **NOT IMPLEMENTED**; gateway rejects writes/uploads before network |
| Independent current documentation fetch | HTTP403 from shopify.dev; uses owner's verified excerpts and existing broker GraphQL contract |

These are host/mocked checks, not real-store acceptance. The full regression had
one UI wait failure after adding the connection selector: a location was outside
the composed viewport. The test now scrolls to the actual location while waiting;
all existing assertions remain. The final Shopify UI/direct tests were rerun and
replace overlapping reports. The final direct tests additionally cover redacted
organization rejection and local disconnect; counts above are unique class reports.
Lint ran after compilation/tests. The market regression used100,000 synthetic
records this run; the supplied75,054-row CSV remains private and was not reimported
for this transport-only change. Historical real CSV validation remains in0.8.1.

This preview delivers the native read connection code and installable update. It
does not establish that the real Canada VHS catalog loaded successfully. The
requested order requires that physical-device read acceptance before production
write enablement. No real products were created, updated, uploaded or deleted.

Evidence: /workspace/.setup/v082-validated-junit, v082-full-validation.log,
v082-focused-validation.log, v082-final-direct-validation.log,
v082-final-validation.log, v082-broker-tests.log, v082-lint.log,
v082-signing.txt and v082-alignment.txt.

[Setup and exact acceptance limits](next/DIRECT_SHOPIFY.md).

APK SHA256: `af03678ca7ea74c6c7dc53a9a1d1af118ad471ee1d245c9f7dc5f49396446884`

Retained signing certificate SHA256:
`b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae`.
Install over the existing app; do not uninstall or clear private data.
