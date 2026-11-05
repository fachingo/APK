# Guided VHS workflow · 0.10.0

The production path is **Capture → Identify → Price → Review Listings → Create Drafts**.
Open **Inventory / Batches** to use it. The previous workspace, AI budgets, pricing
rules, channel configuration, publication reviews and recovery controls remain in
**Advanced Tools**. Shopify browsing and all other main destinations remain available.

## What changed

| Before | After |
|---|---|
| Close a copy, then separately queue recognition, apply a title, run matching, generate descriptions and inspect jobs | Closing a physical copy starts the persisted offline workflow; reliable local identities, pricing recommendations and factual descriptions advance automatically |
| USD records silently excluded from calculations when a manual rate was absent | An explicitly started pricing workflow retrieves Bank of Canada FXUSDCAD, validates and caches it, or reports a precise FX blocker |
| Three sold rows qualified as high confidence | Explainable 0–100 evidence-quality heuristic; a reliable single sale can support individual review, never bulk approval |
| Market, inventory and Shopify screens needed to coordinate a batch | Review prices, correct matches, defer exceptions, approve listings and submit drafts in one batch screen |
| Publish compared six blank product IDs as duplicate identities | Blank IDs are ignored by duplicate-link detection; publication separately requires verified unchanged drafts |
| Old publication error stayed visible while a new draft action was running | Action messages reset immediately and errors name their originating operation |

## Ordinary operation

1. **Capture:** create a batch, photograph the front/back/additional views of one
   physical tape, then tap **Next product** for the next tape. A card with six
   photographs represents one copy and one immutable SKU, not six products.
   Photos/retakes/grouping open the existing original-photo editor. Closing a copy
   or finishing the batch starts offline processing. Finish opens the guided batch
   review directly. For existing batches, use
   **Identify & price N copies** once.
2. **Identify:** Tesseract/ZXing run on the phone. A sufficiently clear OCR title
   must agree with local catalog evidence; ambiguous text, editions and years are
   reviewed. Unique exact barcode matches can identify a title. Missing condition,
   edition or year is not invented. Optional paid recognition remains in Advanced
   Tools with its existing explicit consent; the guided path does not call AI.
3. **Price:** compatible independent sold records yield CAD recommendations. Tap
   **Approve all eligible prices** for the selected strong evidence. Open a copy
   for scored factors, ranked candidate searches, excluded-source reasons, paged
   sale dates/prices and currency conversion provenance. Confirm corrections,
   approve an individual recommendation, save a protected manual price, retry or
   defer. A recommendation is not an applied price.
4. **Review Listings:** inspect covers, printed titles, known release/edition data,
   condition, unique SKU, CAD price, weight and descriptions/tags. Templates fill
   only missing text using recorded facts and existing defaults. Existing metadata
   and manual prices are protected. Correcting an identity refreshes only text still
   matching the workflow’s saved generated-description hash; operator edits are
   retained. Approve the valid selected copies together.
5. **Create Drafts:** **Create Shopify Drafts (N)** authorizes only the approved,
   selected eligible copies and their original photo order. Existing native
   Shopify services preserve operation keys, ownership checks, durable photo
   checkpoints and remote verification. An unresolved copy does not block others.
   Failed operations can be reconciled individually using their original IDs.
   No success is claimed until remote product/media verification completes.

Live publication is a separate reviewed action in Advanced Tools, with the
existing exact confirmation and channel selection. Draft creation never turns a
product active and never replaces another product.

## Currency policy

Source: `https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?recent=10`.
The latest valid published observation is used; missing weekends/holidays are not
fabricated. HTTPS, the series identifier, decimal bounds and the effective date
are checked before saving the cache. The cache survives restarts and APK updates.
The job uses one resolved rate for prices and shipping, with its source, retrieval
time and effective date recorded in the price provenance.

An offline cached rate up to 30 days old can produce a recommendation. Its age is
visible; after seven days, USD-based prices require individual review. After
30 days or without a verified rate, USD evidence is blocked with `FX_STALE` or
`FX_MISSING`. CAD evidence is never converted at an invented rate. A partial
CAD-only recommendation with excluded compatible USD sales also requires review.
Advanced pricing rules retain an explicitly dated manual override.

Rates are fetched only during requested/capture-started production work, with a
15-minute failure/retry throttle. There is no periodic rate polling or background
model monitoring. The Bank of Canada endpoint is blocked in the build environment;
a live device fetch remains an acceptance check, not a claimed test result.

## Matching and evidence policy

Retrieval combines indexed exact UPC/EAN, normalized/cleaned title, SQLite FTS,
token prefixes, lexicon trigrams and bounded Apache Commons Text edit distance.
It tries OCR corrections, common aliases, alternate word order and partial titles.
Candidate retrieval is bounded; it never loads the entire sales library into RAM.
Strict title compatibility is evaluated on indexed title representatives before
fetching their complete sold histories. Other candidates contribute bounded
diagnostic examples only, never a partial-sample price. Exclusion counts describe
those inspected examples, not the entire library.
More than 5,000 potential pricing observations produces an explicit narrowing
request rather than a partial-sample price. Candidate similarity is separate from
evidence quality.

Hard validation excludes other formats, sets/lots, conflicting UPCs, numbered
sequels, incompatible editions, known condition differences, superseded evidence,
unsupported currencies and invalid/out-of-window dates. Multiple plausible release
years require a confirmed year. Similar spelling alone never becomes sold evidence.
Duplicate source listing IDs count once, including observations with differing dates.

Quality factors include strict film identity, VHS/edition agreement, known year,
barcode, condition, independent sale count, newest-sale age, traceable source
metadata and price dispersion. Default policy is 85+ for bulk eligibility and
65–84 for individual review; lower-quality evidence withholds the asking-price
recommendation. A single sale is capped below bulk eligibility. These scores are
transparent starting heuristics, not calibrated probabilities. Advanced rules can
raise approval thresholds. Median, IQR outliers, shipping choice, floors, ceilings,
adjustments, rounding and manual protection remain configurable.

## Root causes and diagnostic limits

* `ShopifyPublicationBatch.kt` checked distinct product-ID count before rejecting
  absent drafts. Repeated empty strings therefore triggered the duplicate warning.
  That code is used by **Publish**, not ordinary **Create Drafts**. Draft creation
  already accepted empty IDs, validated immutable SKUs, and refused replacement.
  `ProductionModel.action` retained the previous error during the next request,
  which could also misattribute a publication failure to a draft action. Both paths
  are now operation-specific and covered by regression checks.
* The supplied source has **75,054 USD observations and zero CAD observations**.
  `buildAutoPrice` previously skipped the USD loop if `usdCad` was blank. With an
  unset rate, that excluded the entire supplied library. The old three-comparable
  threshold and manual application step were additional independent gates.
* Source conditions such as `used` were compared literally with inventory grades
  such as `Good`, excluding otherwise compatible used sales. Broad used-condition
  evidence now supports used inventory without asserting a grade or condition
  premium. Known grade differences and sealed/damaged conflicts remain excluded.
* Fuzzy search was already present but pricing used a separate strict all-token
  retrieval path. Broader indexed retrieval now feeds explicit strict evidence
  checks, with per-source reasons. It does not turn fuzzy matches into facts.
* The six inventory records and saved jobs on the physical phone are not available
  in this workspace. The included source-data regression uses six named
  representative title/year examples and labels them as such. It does not claim to
  have diagnosed each of those unavailable phone records. On-device results now
  distinguish not-started, identifying, candidates/review, FX blocked, recommended,
  approved, listing ready, queued, verified draft and published.

## Data and recovery

No schema migration or database reset is required: orchestration reuses existing
Room production job/item tables and snapshot-compatible JSON payloads. Existing
version-11 inventory, market evidence, photos, credentials and history remain.
Recovery resumes deterministic jobs and offline recognition; uncertain paid
requests retain the existing no-auto-retry safeguards. Restored backups pause work
for review. Shopify retries retain creation keys and remote checkpoints.

Installation and executed validation are in [release notes](RELEASE_0.10.0.md)
and [test results](../TEST_RESULTS.md). Physical-device and production-store tests
must be distinguished from host-side native Android and intercepted-HTTP tests.
