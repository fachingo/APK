# SnapIMS Next 0.4.0 validation

| Check | Executed result |
|---|---|
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| PricingConformanceTest | 3 passed |
| AiProtocolTest | 5 passed |
| CameraLayoutTest | 1 passed |
| CameraUpdateTest | 6 passed |
| JournalIntegrationTest | 6 passed |
| NextIntegrationTest | 7 passed |
| RecognitionClientTest | 7 passed |
| RecognitionStorageTest | 7 passed |
| ShopifyUiTest | 1 passed |
| ShopifyWorkflowTest | 9 passed |
| SiwcProtocolTest | 6 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| WorkspaceUiTest | 1 passed |
| Android/core host tests | **89 passed, 0 failed, 0 skipped** |
| Broker mock/crypto/SQLite tests | **13 passed** |
| Total executed tests | **102 passed** |
| App and instrumentation APKs | Built; physical instrumentation execution UNRUN |
| Android lint | 0 errors; 57 warnings remain |
| Upgrade migrations | Explicit1→2→3→4→5, capture/files preserved; latest Room schema validated |
| Signing vs0.3.0 / 16KB alignment | Same signing identity; alignment passed |
| Native Shopify interruption | Synthetic timeout after server creation; stable request key, one product, no duplicate on retry |
| Native Shopify files/batches | Two photos, async processing stays WAITING_REMOTE, metadata/stock/IDs verified; success skips; separate live SUBMIT approval |
| Mutation safety | Changed local item rejected, unexpected stock protected, production guard, quantity1, closed owned-resource writes |
| Restore | Schema3/4 upgrades; Shopify upload secrets redacted; restored jobs PAUSED_RECONNECT; identity retained |
| Agent approval | DRAFT job absent before approval, one-time approval and job commit together, local status read, no implicit live publication |
| Broker security | P256 signed requests, body binding, replay/clock, one-use pairing, revocation, encrypted token renewal, foreign-resource and production denial |
| Broker creation recovery | Immutable request hashes, cached minimal result, uncertain create/file read-only reconciliation, no blind duplicate |
| GraphQL registry syntax | All19 operation documents parse; actual Shopify schema acceptance UNRUN |
| Container | Builds; non-root UID10001 startup/HTTP health and0600 state file verified; no Shopify requests |
| Shopify UI | Actual seeded Room inventory rendered, pairing/connection gates disabled until configured; PNG inspected |
| Retained A/C/D regression | Camera/layout/recognition, immutable inventory/photos,100×300 synthetic backup stress, source pricing/statistics, approvals, streaming/billing isolation passed |
| Physical Pixel / real Shopify authorization and development-store draft | **UNRUN**: no bound device/store/credentials; no broker deployed |
| SIWC physical Pixel sign-in/catalog/completed stream | **UNRUN**: no physical eligible account bound |
| Live provider calls / real sold dataset | **UNRUN** for this release's0.3 pricing fixtures; supplied Harvester CSV belongs to the next pricing extension |

No production Canada VHS data, real Shopify account or paid provider was mutated.
Host/mocked tests are not real API or hardware acceptance. A–D remains incomplete
until the documented external acceptance succeeds. Shopify server JSONL bulk and
unrelated existing-product linkage/update are not implemented; native batches use
per-item durable operations. SIWC never silently switches to billable API usage.

Capture test fixtures use real Linux directory fsync because Robolectric's stub is
unsupported. UI/test JVMs are isolated; assertions are enabled. Lint ran separately
after build/tests. Container startup caught and fixed copied-file ownership for its
non-root user. Earlier failed development logs remain retained, with no checks disabled.

APK SHA256: b48684209f2d5b6d42813373b4938d43f99177f015cd27cb201d134c02ae97f9

Certificate SHA256: b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae

Logs: /workspace/.setup/v040-final-validation.log, v040-final-lint.log,
v040-broker-final.log, v040-docker-final-build.log, v040-docker-final-smoke.log.
See next/MILESTONE_B.md, next/SHOPIFY_SETUP.md and next/FEATURE_MATRIX.md.
