# Supplied Harvester export validation

The uploaded trawl_sales(3).csv contains **75,054 actual source rows**, all USD,
with source sale dates2020-09-19 through2026-10-15. It has no duplicate local IDs,
no duplicate site/item/date sale keys, no conflicting source-sale keys, and no
invalid monetary/date values in the aggregate profile. Four titles normalize only
to format words; those records are kept and not guessed into movie identities.

The native importer loaded every supplied row, verified the original file remained
unchanged, added zero records on repeat, and reopened a persistent database holding
all175,054 current observations after the100k explicitly synthetic validation load.
This is host Android-runtime validation of the supplied CSV, not a claim about a
physical Pixel or an unavailable original Harvester production SQLite database.

The CSV has no structured UPC, edition, release-year or collection timestamp columns.
Those fields remain unknown; import observation time is separate provenance. Original
sale prices/shipping stay USD. No CAD exchange rate is invented. Full row metadata,
source identifiers, original listing/image URLs and historical dates are retained.

Host indexed query timing at100k rows: median3ms in the
owner-import run (range2–21ms);
final standard regression median4ms. FTS4 was available
in this host runtime; FTS5 uses a runtime probe and indexed fallback is separately
tested. Physical Pixel performance/offline import remains UNRUN.

Reproducible profile: dev/profile_market_csv.py (aggregate output only).
Native acceptance: MarketDatasetValidationTest with the private -PmarketCsv input.
Sanitized aggregate evidence: MARKET_VALIDATION.json. No raw sold records are
included in GitHub artifacts. Source SHA256: 1f26549c6cbdd87e22911e1f48c4decea740fde0ada138768bce7c0c17e20e09.
