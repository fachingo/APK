# SNAPIMS ANDROID — ADDITIONAL REQUIREMENT: FULL OFFLINE VHS MARKET DATABASE

Extend the existing implementation plan to include a complete, permanently stored, searchable, offline pricing database on Android.

This is a core requirement of the standalone SnapIMS application, not a future optional feature.

Do not restart or discard completed development work. Incorporate this into the current pricing/database milestone.

## 1. Reuse existing systems

Inspect:

- `snapims/pricing/database.py`
- `snapims/pricing/normalization.py`
- `snapims/pricing/integration.py`
- `snapims/pricing/workflow.py`
- Existing pricing migrations and fixtures
- Existing VHS Market Harvester SQLite database and export formats, where available

The VHS Market Harvester has previously collected tens of thousands of eBay sold-listing comparables.

The Android application must support importing the actual existing database, preserving historical records, rather than starting a new empty collection.

Do not assume the SnapIMS pricing workbench schema and VHS Market Harvester schema are identical.

If the Harvester database is unavailable in the repository, create an import adapter with a documented input contract and test fixtures. Do not fabricate sold records.

## 2. Fully offline storage

Use SQLite on Android with appropriate Room integration.

The database must remain available without internet, OpenAI, Shopify, desktop computers, or any external server.

Support:

- Full historical comparable-sales records.
- Titles and normalized title keys.
- UPC/EAN identifiers where available.
- Editions and release details where supported by evidence.
- Sold prices and shipping charges.
- Currency.
- Sale dates.
- Original listing URLs and source identifiers.
- Date collected and evidence provenance.
- Pricing statistics.
- Operator pricing decisions.
- Database version and last import time.

The database must survive application restarts, updates, and offline operation.

Keep sold-market evidence distinct from operator pricing decisions and inventory records.

## 3. Excellent offline search

Implement a dedicated Market Database screen.

Use SQLite FTS5 where supported on the target Android SQLite runtime, with indexed fallback where required.

Provide:

- Instant search-as-you-type.
- Exact and partial title matching.
- Normalized title matching.
- Typo-tolerant matching.
- Barcode lookup.
- Edition/year filtering.
- Price and sale-date filtering.
- Sorting by relevance, price, date, and sample size.
- Results grouped by normalized movie identity where reliable.
- Ability to examine individual source listings.
- Direct access from the inventory editor and pricing review screen.

Use the existing conservative matching rules to avoid confusing sequels, remakes, bundles, different editions, and unrelated titles.

Search results may include approximate matches, but they must be clearly labeled and must not automatically become accepted price evidence.

Use bounded pagination or lazy loading; do not load the entire database into memory.

Target responsive searches under 200 ms for common indexed queries against at least 100,000 sold records on the target device, subject to hardware benchmarking.

## 4. Automatic enrichment during pricing

Whenever an inventory item is priced:

1. Search existing local comparable-sales evidence.
2. Calculate a recommendation using the ported deterministic pricing rules.
3. Allow the operator to approve or adjust the recommendation.
4. Persist the approved price, title identity, item ID, edition, timestamp, evidence references, and pricing method.
5. Make the approved pricing history immediately searchable and reusable offline.

Do not create a fictional eBay sale when the operator prices an item.

Maintain separate data types:

- VERIFIED_SOLD_COMP
- IMPORTED_SOLD_COMP
- OPERATOR_APPROVED_PRICE
- MANUAL_ESTIMATE
- AI_SUGGESTION

Only genuine sold-market records may contribute to statistics presented as historical sold prices.

Operator-approved prices can inform future recommendations under a clearly identified, configurable policy.

AI suggestions must not silently contaminate the sold-comparables evidence database.

## 5. Database import and updates

Implement a reliable mechanism for loading existing sold-comparables data from a SQLite database or supported export package.

Requirements:

- Android file picker for selecting database packages.
- Schema/version validation.
- Import preview with counts and warnings.
- Transaction-safe import.
- Stable source-based deduplication.
- Efficient incremental updates.
- No accidental deletion of existing evidence.
- Corruption detection and rollback.
- Rebuildable search indexes.
- Database integrity verification.
- Accurate import progress.

An optional online updater may be added later using lawful, supported data acquisition methods.

Internet connectivity must never be required to use already imported data.

## 6. Offline pricing behavior

If no network is available, SnapIMS must still:

- Recognize barcodes and perform local OCR.
- Search locally stored market evidence.
- Retrieve historical pricing information.
- Calculate evidence-based recommended prices.
- Display historical evidence age.
- Allow manual pricing.
- Record the operator's decision.
- Prepare inventory for later Shopify synchronization.

Do not mark stored sold evidence unusable merely because its normal online cache expiration has passed.

Instead, label older evidence as stale and allow configurable reuse with an appropriate warning.

Do not invent missing data or fall back to unsupported AI price guesses.

Use precise integer-cent or decimal arithmetic, preserving existing SnapIMS rounding policies.

## 7. Search and pricing interface

Create a beautiful Material 3 database browser with:

- Prominent search field.
- Recent searches.
- Saved searches or favorites.
- Filtering and sorting.
- Individual listing details.
- Price distributions and comparison summaries.
- Historical evidence versus approved-price distinction.
- Database record counts and freshness indicators.
- One-tap application of a suggested price to an inventory item, subject to approval.
- A shortcut to search a captured item's recognized title.

The search experience should feel like a polished dedicated database application, not a generic SQLite viewer.

## 8. Validation

Test with at least 100,000 representative records.

Verify:

- Full offline search.
- Accurate title matching.
- Duplicate sold-record detection.
- Correct currency and shipping handling.
- Preservation of original evidence.
- Correct manual-price protection.
- Title changes invalidating inappropriate evidence associations.
- Pricing history surviving app restarts.
- Import and incremental update reliability.
- No loss of inventory or sold evidence during upgrades.
- Acceptable performance on a physical Pixel.

Do not claim production data was migrated unless the actual Harvester database has been imported and validated.

## Definition of success

I can install SnapIMS on my Pixel, import my complete existing eBay sold-comparables database, disconnect from the internet, and still search every historical record, price hundreds of tapes, save all my pricing decisions, and reuse that knowledge for future inventory.

The system must improve its internal pricing knowledge as I work, without confusing my asking prices with actual completed market sales.

Continue the existing milestone plan and incorporate these requirements into the standalone pricing milestone.