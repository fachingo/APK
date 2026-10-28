# SnapIMS Next 0.3.0 validation

| Check | Actual result |
| --- | --- |
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
| SiwcProtocolTest | 6 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| WorkspaceUiTest | 1 passed |
| Unique host tests | **79 passed, 0 failed, 0 skipped** |
| App / instrumentation APK | Built; physical instrumentation execution unrun |
| Android lint | Passed, 0 errors; warnings remain |
| Signing identity vs published 0.2.0 | Matches |
| 16 KB APK alignment | Passed |
| Upgrade | Explicit 1→2→3→4 migration validates latest Room schema and preserves captures/files |
| Existing inventory/camera/backup regression | Retained checks passed, including synthetic 100-item/300-photo round trip |
| Real desktop pricing functions | Generated synthetic vectors match exact title filters and Decimal HALF_UP statistics |
| Actual source-created pricing SQLite | Native read-only import, source bytes unchanged, same dataset imports zero duplicates |
| Reviewed pricing | Manual-price override, exact evidence/title/expiry, stale title, optimistic revision and batch rollback checks |
| Agent tools | No changes before approval; single-use/revision binding; invented prices and unknown tools rejected; confirmed recognition application |
| Current backups | Evidence/chat/private attachment bytes included; stopped messages INTERRUPTED, restored approvals EXPIRED; original identities retained |
| Legacy imports | Existing schema3 desktop/native packages still accepted; conflicting records/originals rejected |
| SIWC protocol | RFC7636 PKCE, loopback-only redirect, state/duplicate/denial rejection; real RSA/ES256 signed-token identity/expiry/nonce/azp rejection checks |
| Token storage / lifecycle contract | AES-GCM envelope rotation/tamper rejection, scope rejection and identity preservation in mock fixtures |
| SIWC Responses | Allowed fields only; local context; response.completed required; partial stream errors and strict subscription catalog parsing |
| Billing isolation | Mock subscription HTTP429 causes one request and no API-key fallback |
| Compatible completion stream | Tool-call delta assembly plus required DONE/finish reason |
| Native chat UI | Seeded local history/Markdown/inert HTML/connections rendered with no provider request; PNG inspected |
| Apache-2.0 | Owner explicitly selected; LICENSE/NOTICE added; third-party notices retained |
| Physical Pixel SIWC sign-in/catalog/stream | **UNRUN**: no physical device or eligible account attached |
| Real OpenAI/Gemini/Groq/OpenRouter/local-model calls | **UNRUN**: no provider credentials/server bound |
| Shopify live draft/media/batch/reconciliation | **UNRUN / not implemented**: official API/auth and real dev-store gates remain |
| Real owner's sold-evidence dataset | **UNRUN**: no dataset supplied; test evidence is explicitly synthetic |
| Hosted GitHub Actions execution | Workflow supplied; local checks are not a claim of hosted CI execution |

The app remains a preview of C/D after the published A milestone; A–D as a whole
is incomplete. B currently supplies only local listing preparation. SIWC implements
the owner's externally verified official preview contract, but has no physical-device
acceptance evidence. No mock login or stream is labeled a real eligible-account pass.

The separate desktop repositories are unchanged. No production inventory, Shopify
listing or paid provider account was accessed. No credentials are bundled or exported.
ChatGPT and API-key modes are independent, with no automatic billing fallback.

Robolectric capture fixtures use a real Linux FileChannel directory fsync in place
of Robolectric's unsupported directory-fsync stub. Separate test JVMs isolate global
WorkManager/activity teardown state; UI scenarios use separate classes. Assertions
remain enabled. Test/build and lint execute sequentially after kapt completes.
The synthetic images do not measure physical shutter latency or barcode accuracy.

APK SHA-256: 1081202faf3a30b3a60b9c0323aaea2a452911ee7a7f191cfa1b50dcc72c4b28

Signing certificate SHA-256: b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae

Logs: /workspace/.setup/v030-final-validation.log.
JUnit XML: core/build/test-results/test and app/build/test-results/testDebugUnitTest.
Details: next/MILESTONE_CD.md, next/FEATURE_MATRIX.md, next/SIWC_INVESTIGATION.md,
next/SHOPIFY_SETUP.md. Historical A validation: TEST_RESULTS_0.2.0.md.
