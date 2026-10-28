# Feature completion matrix — 0.4.0 preview

Status follows delivered code and executed evidence. The A–D program is not complete.

| Milestone / capability | Status |
| --- | --- |
| A: native camera/NEXT/zoom/resolution/landscape/original preservation | Published 0.2.0; retained in 0.4.0. Physical Pixel acceptance unrun |
| A: standalone inventory/search/editor/conditions/IDs/readiness/audit | Implemented; deterministic integration/UI tests |
| A: finished-item photo corrections/retake | Implemented; originals and prior ZIP bytes preserved |
| A: protected photo-inclusive backup/merge and duplicate-safe imports | Implemented; 0.4.0 includes chat/evidence/attachments and credential-redacted Shopify checkpoints; legacy schema3/4 supported |
| A: interoperability/CSV/read-only desktop export/CI workflow | Delivered; hosted CI execution not claimed |
| A: 100 real products / 300 physical photographs | Unrun; synthetic 100×300 stress test passed |
| B: current official Shopify auth/API review | Owner externally supplied current official requirements; implemented from that contract. Cloud independent fetch still blocked |
| B: real dev-store draft/images/batch/interruption/reconciliation | Native/broker implementation and mock tests delivered. **UNRUN** real authorization/dev-store acceptance; no host/store credentials bound |
| B: native Shopify pipeline | Implemented reviewed DRAFT creation, immutable SKU/CAD price/weight/stock1, staged JPEGs, async files, attachment/order verification, IDs, WorkManager retry/pause/cancel and explicit publication |
| B: hosted confidential credential broker | Source + non-root container startup tested; signed device pairing, OAuth/client credentials, revocation/rates/audits/rotation and narrow owned-resource operations. Not deployed |
| B: server JSONL bulk operations / unrelated existing-product linkage | Not implemented. Native per-item batches are available; foreign existing products remain protected for manual review |
| C: actual desktop sold-evidence SQLite schema / source pricing rules | Implemented native read-only import, immutable datasets, exact matches and Decimal statistics |
| C: individual/bulk reviewed pricing, manual override, expiry/provenance | Implemented; source-generated synthetic fixtures; real owner's dataset not supplied |
| C: offline Tesseract/ZXing and conventional API-key recognition | Retained; reviewed application to inventory; no automatic cloud request on shutter |
| C: complete inventory-to-Shopify workflow | Native implementation delivered; end-to-end live acceptance still unrun |
| D: persistent provider chat/streaming/history/search/export/attachments | Implemented preview; mock protocol + local integration/UI evidence, live provider calls unrun |
| D: OpenAI/Gemini/Groq/OpenRouter/phone-local compatible providers | Implemented connectors/discovery; availability, model capabilities and free quotas require provider validation |
| D: typed local tools and exact one-time approval | Inventory/evidence/recognition/status reads; metadata/confirmed-title/sold-price/local-plan and approved Shopify DRAFT-job proposals implemented; Shopify checkpoint reads. New recognition execution remains outside chat |
| D: SIWC native OAuth/model/Responses prototype | Owner-supplied official contract implemented separately; mock tests do not establish account/native acceptance |
| D: physical Pixel SIWC sign-in → catalog → completed stream | UNRUN: no attached Pixel or eligible account; Apache-2.0 selected by owner; actual account/native eligibility and issuer metadata still need live confirmation |
| E: calendar/communications | Outside latest A–D continuation |

Usage: MILESTONE_A.md and MILESTONE_CD.md. External gates: SHOPIFY_SETUP.md and
SIWC_INVESTIGATION.md. Validation evidence: ../TEST_RESULTS.md. API-key connections
remain independent; SIWC errors never select billable API credentials automatically.
