# SnapIMS Next offline market database — 0.5.0

The Market database is a core local feature. Its evidence, imports, decisions and
saved searches use the same persistent Room/SQLite journal as inventory (schema6).
It needs no network, OpenAI, Shopify, broker or desktop. No sold records are bundled
in the APK; import your own source data from Android Files.

## Use it

Settings → **Market database** → **Import market records** (or Filters & tools →
Import CSV / SQLite). Review counts, currencies, source warnings and preservation
policy before approving. Progress reports actual validated/imported row counts.
Cancellation or a bad record rolls back the permanent transaction. After process
interruption, reselect the same file; completed records deduplicate by source identity.

Search as you type, use a barcode, filter currency, edition, year/title-year,
price including shipping, and sale-date range, or sort by relevance, price, date or
sample count. Searches return bounded pages of40 groups and50 individual listings.
Saved/recent searches are local. FTS5 is probed on the actual runtime; FTS4 is the
first fallback and indexed word-prefix/barcode lookup works without either engine.
Typo candidates come from a bounded indexed term dictionary and are labeled possible
matches. They are never automatically accepted as price evidence.

Groups use the full normalized listing identity with explicit edition/year/currency;
no speculative film merge. Open a group for its individual original listings,
prices/shipping, source IDs, sale/collection/import dates and an eligible-price
histogram. Remote listing links explicitly require internet; no browsing/network
request is needed to read stored evidence. Source image URLs stay in original
metadata; thumbnails are not downloaded or falsely presented as cached images.

Inventory editor → **Search market & approved prices** searches the saved item.
Sold pricing also links to Market. Select a saved inventory item, then recommend
CAD or USD evidence. Recommendations use the ported conservative title matcher and
Decimal/HALF_UP statistics, include shipping, and keep currencies separate.
Bundles, sequels and incompatible years/editions are protected. Distinct explicit
release years require item-year review; premium/edition-specific listings do not
silently enter an ordinary unspecified-edition recommendation. A cohort with more
than5,000 candidates requires a narrower identity instead of a biased partial sample.

If evidence is USD and inventory is CAD, explicitly enter your CAD-per-USD rate and
its effective date. No exchange rate is fetched or inferred. Review median/average,
original currency and age, optionally adjust the CAD amount, approve replacement
of a manual price and historical reuse where needed, then approve the decision.
Older evidence is retained; the default warning threshold is180 days and configurable.
The source workbench's online cache expiry no longer deletes/disables stored history;
its review screen has an explicit historical-reuse policy.

Every manual price change records a separate MANUAL_ESTIMATE and performs a bounded
local market lookup for decision context. Reviewed recommendations record
OPERATOR_APPROVED_PRICE with item identity/revision, title, edition/year, timestamp,
method, source IDs/hashes, actual evidence references and any conversion/adjustment.
Title/edition/year/barcode changes invalidate inappropriate price associations.
History is separately searchable and does not become a fictional eBay sale.

Optional prior-approved asking-price hints are off by default. Enable that clearly
labeled policy to review and reuse compatible operator decisions. They remain asking
prices. MANUAL_ESTIMATE and AI_SUGGESTION are never included in historical sold-price
statistics. VERIFIED_SOLD_COMP is reserved for evidence with independent verification;
imports use IMPORTED_SOLD_COMP and are not promoted merely because they were loaded.

Chat tools can read bounded market search and conservative recommendations; they
cannot invent sold records or conversion rates or silently apply a price.

## Import contracts

The Harvester and SnapIMS pricing-workbench schemas are distinct. No original
Harvester SQLite file was present in the repositories. The supplied actual
`trawl_sales(3).csv` establishes the implemented Harvester export contract:

- UTF8 (optional BOM), RFC4180 quoting, quoted commas/newlines and escaped quotes.
- Required headers: `title,site,item_id,date_sold,sale_price,shipping_price,currency,url`.
- Original supplied headers also include `id,category_id,condition,image_url`;
  these and other bounded source columns are preserved verbatim in original metadata.
- Optional explicit fields: `barcode,edition,release_year,collected_at`. Missing
  values stay unknown; they are not inferred from price or import time.
- Prices/shipping are nonnegative original-currency decimals (up to1,000,000 each).
  Decimal text is retained; display totals use HALF_UP cents. No float-based FX.
- Sale/collection dates use ISO date or an offset-aware ISO timestamp. Source URLs
  use HTTP/HTTPS without embedded credentials. Unsupported/malformed values cause
  an actionable preview failure, without modifying permanent evidence.
- Blank/format-only normalized titles retain distinct source identities for browsing
  and are excluded from automatic pricing. No record is dropped to invent a film.
- Files up to2GB and up to2million observations per import; stage/merge on disk,
  never load the whole file into a list. Device free space remains the practical bound.

Supported SQLite inputs are read-only, integrity checked, and streamed:

1. `trawl_sales` or `sales` table with the above Harvester column contract.
2. SnapIMS `pricing_evidence` + `pricing_evidence_listings`, joined on evidence ID,
   using its separate source schema/collection provenance.
3. SnapIMS market archive v1: `market_format(version,format)` plus `market_records`
   with the documented source-code column contract and per-record content validation.

No generic SQLite schema is guessed. Export the supported CSV if your actual
Harvester schema differs; retain the original source. The original DB/file is never
opened for writing. Export sold evidence produces a normalized market SQLite file;
complete protected app backup also includes operator decisions, inventory and photos.

Stable source keys use source namespace + original listing ID + sale timestamp;
local CSV row numbers are provenance only. Exact payload repeats add no sale.
A changed payload keeps its earlier version and advances the current observation;
reimporting an old already-seen version does not overwrite a newer one. Conflicting
versions in one input are preserved and excluded from automatic statistics.
Only current eligible sold observations contribute to recommendations.

## Backups and reliability

Room5→6 migration is additive. Market evidence is streamed into a consistent SQLite
member inside the existing protected, photo-inclusive backup/export. This avoids
building a100k-row JSON document in memory. Inventory, decisions and market merges
commit in the same journal transaction on restore. Hashes, archive schema/content,
original files and record conflicts are verified before success; existing evidence
is never silently deleted or replaced. Schema3/4/5 snapshots remain accepted.
Search indexes are derived and rebuildable; integrity verification is available.

Import commit is exclusive maintenance; capture/navigation is gated rather than
accepting a shutter press that would wait behind the import's long write transaction.
Preview validation runs independently on a temporary file. Android process loss
rolls back an uncommitted import; reimport is safe.

## Acceptance evidence

See ../TEST_RESULTS.md for the current executed result and host benchmark.
The supplied CSV has75,054 rows, all USD, with sale dates2020-09-19 through2026-10-15.
Its collection timestamps, UPCs, explicit editions and release-year columns are
absent and remain unknown. Four generic/format-only titles remain stored and
browsable, rather than becoming guessed movie identities. The CSV itself is kept
outside the public repository/APK. Test fixtures are explicitly synthetic.

Native host validation loads100,000 synthetic observations and all75,054 supplied
rows, checks repeat import, original file preservation, search and persistent
restart. This is not a claim that an unavailable original Harvester production
SQLite database or the physical Pixel was migrated. Physical Pixel offline latency,
full device import and hardware acceptance remain UNRUN. The search target is common
indexed queries under200ms on the target device; host timings are separate evidence.
