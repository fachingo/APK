# SnapIMS Next 0.5.0 validation

| Check | Executed result |
|---|---|
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
| ShopifyWorkflowTest | 9 passed |
| SiwcProtocolTest | 6 passed |
| StandaloneInventoryTest | 9 passed |
| StandaloneUiTest | 1 passed |
| WorkspaceUiTest | 1 passed |
| CaptureRulesTest | 10 passed |
| DesktopConformanceTest | 4 passed |
| LocalRecognitionTest | 6 passed |
| MarketCsvTest | 2 passed |
| PricingConformanceTest | 3 passed |
| Unique native/core host tests | **107 passed, 0 failed, 0 skipped** |
| Broker mock/crypto/SQLite tests retained from0.4.0 | **13 passed**; broker source unchanged |
| Total unique executed checks | **120 tests passed** |
| App / instrumentation APK | Built; physical instrumentation execution UNRUN |
| Android lint | **0 errors**, 62 warnings remain |
| Source upgrade | Explicit1→2→3→4→5→6; existing captures/files/inventory retained; latest Room migration validates |
| Signing /16KB alignment | Same certificate as0.4.0; alignment passed |
| Actual supplied CSV | All75,054 records imported through native Android code; all USD; source hash unchanged; repeated import added0 |
| Combined dataset restart |100,000 synthetic +75,054 actual =175,054 current observations retained after closing/reopening persistent SQLite |
| Generic titles | Four real format-only/generic titles preserved, searchable and excluded from automatic movie-price inference |
| 100k host query benchmark | FTS4; owner-import run median3ms, range2–21ms; final synthetic run median4ms |
| Runtime search fallback | FTS5 probe, FTS4 multiword/prefix behavior, forced indexed fallback, typo/barcode/year/currency/price filters, bounded pages and rebuild |
| Import integrity | Whole-row validation, source schema/SQLite integrity, stable source dedup, preserved versions, conflicting-source quarantine, malformed/tampered input rolls back |
| Decimal/currency | Original sold + shipping, source rounding conformance, no silent FX, explicit dated USD→CAD rate, manual-price protection |
| Historical reuse | Old sold data retained; historical approval/policy; price history stored separately and never becomes a fictitious sale |
| Matching safeguards | Bundles/sequels protected; edition/year constraints; remake-year ambiguity requires review; premium/edition markers excluded from ordinary item pricing |
| Price approval freshness | Added matching evidence invalidates an older review; title/edition/year/barcode edits invalidate incompatible associations |
| Data types | Imported sold observations separate from manual/approved prices; typed asking-price/AI data rejected at sold-import boundary |
| Price knowledge | Manual decisions search local evidence; approved source IDs/hash/method/FX retained; prior asking-price reuse requires opt-in/approval |
| Backup/restore | Market evidence streams to SQLite member, decisions/photos metadata preserved; atomic Room merge; schema3/4/5 accepted |
| Native Market UI | Real seeded Room source records, search/results, source details, histogram and distinct operator-history view; no network request; screenshot inspected |
| Retained A/B/C/D | Camera/landscape/offline recognition, inventory identity/photo corrections,100×300 synthetic backup, pricing/agent approvals, AI streaming/billing isolation, Shopify jobs passed |
| Physical Pixel import / offline search timing / shutter accuracy | **UNRUN**: no physical Pixel attached; host timings are not device timings |
| Original Harvester production SQLite migration | **UNRUN**: only the actual uploaded CSV export was supplied; no claim that an unavailable production DB was migrated |
| Shopify live auth/dev-store acceptance | **UNRUN**; no broker deployed or real-store credential bound |
| SIWC physical sign-in/catalog/completed stream | **UNRUN**; no eligible physical-device account bound |

The full final regression passed107 unique host tests. A final targeted rerun of
MarketDatabaseTest after a year-filter predicate correction passed its14 tests and
rebuilt the APK. Those14 overlap the full suite and are not counted twice. The
consolidated per-class JUnit evidence is retained at
/workspace/.setup/v050-validated-junit. The native owner-data import/restart run is
separate from the final100k synthetic regression; both used the same source importer.

No supplied sold records are bundled in the APK or public source. Install and
import your CSV in Market database. No production Shopify listing, paid provider
account or original desktop database was mutated. Source repositories outside APK
remain unchanged. A–D external acceptance and physical SIWC proof are incomplete.

Logs: /workspace/.setup/v050-full-validation1.log (actual CSV + full suite),
v050-final-validation.log (final full regression), v050-final-year-filter.log,
v050-final-lint.log. Owner-only raw data remains outside the repository.
Historical validation: TEST_RESULTS_0.4.0.md. Usage/contracts: next/MARKET_DATABASE.md.

APK SHA256: dee599d3682766d9719aab641927db961c1271711206e5b003afd3bb2d1d50f4

Certificate SHA256: b262fd08c7a58f338027260d47a94a33e5c939d72752034dddb2f4f6bf73faae
