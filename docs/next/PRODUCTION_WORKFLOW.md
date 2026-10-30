# Production batch workflow — v0.8.4

Start in **Batches**. The batch dashboard keeps one persistent checkbox selection
for recognition, matching/pricing, metadata, draft creation, and publication.
Inventory → **Bulk workflow · all eligible inventory** provides the same controls
across batches. Select All, Deselect All, Invert, ready, unpriced, and unlisted
selectors operate on the current batch/scope. Filters do not silently change the
checked selection; the count states how many checked copies an action will use.
Returning from photograph history retains the selection. Individual items and
original photograph/capture history remain accessible.

## Draft creation

1. Refresh the saved Shopify connection and choose the stock location.
2. Check the desired copies.
3. Tap **Create Shopify Drafts**.

That deliberate tap authorizes selected listing/photo uploads and creating drafts.
There is no second dialog, per-item approval, consent checkbox, or SUBMIT for this
ordinary operator action. Each copy is validated independently. Invalid/deleted,
already linked, unfinished-job, ambiguous barcode, missing-photo, unpriced, or
invalid-SKU records are skipped with reasons. Valid copies are validated for
readiness automatically; the existing pipeline retains its immutable SKU,
checkpoint, resource ownership, file verification, stock, and reconciliation
rules. An optional listing-review path remains in Shopify settings. AI-proposed
changes continue to require human review; the new operator shortcut is not an AI
permission bypass.

Up to 2,000 selected records can be submitted per operator draft action. They are
saved as existing durable jobs of up to 100 copies each. A queued job is not a
successful remote draft. Open the saved job results to inspect errors and resume
an existing job instead of submitting duplicate work. Draft creation never updates
an existing linked product and never activates or publishes a product.

## Offline Auto Match & Price

**This engine works without OpenAI, an API key, a network connection, or any model
response. It makes zero model requests.**

Choose pricing rules, select copies, and tap **Auto Match & Price**. Every copy gets
a durable result: High confidence, Needs review, No reliable match, or an actionable
error. Apply all selected high-confidence recommendations in one action, or enable
automatic high-confidence application in the rules before starting a job. Other
copies remain unpriced; approximate or AI scores never manufacture sold evidence.

Candidate retrieval extends existing SQLite FTS5/FTS4/fallback indexes, the trigram
lexicon, Apache Commons Text Levenshtein and Jaro-Winkler ranking. A derived indexed
catalog of distinct titles avoids repeatedly grouping every sold record for fuzzy
retrieval. Exact, corrected, prefix/partial, token/word-order and small documented
abbreviation retrieval paths run together. Relevance pages reserve space for Exact,
Likely, and Possible matches when available, including when exact hits exist. Scores
are deterministic heuristics, not calibrated probabilities. Explicit year/sequel,
edition, volume, format and bundle conflicts reduce relevance. Complete sale groups
are aggregated after bounded title candidate ranking; individual groups are not
priced from arbitrary sampled sales. Broad browsing is bounded to candidate windows;
refine the query to explore further candidates. Barcode browsing is exact.

Pricing uses the existing conservative comparable matcher, agreeing known identity
metadata, current eligible source observations, and additional format/edition/condition
checks. An exact source barcode plus an agreeing explicit release year can corroborate
a minor OCR spelling error; the barcode cannot override conflicting edition or format.
A fuzzy title match alone cannot confirm a comparable. Conflicting/remake years,
sequels, unsupported formats, lots/bundles, unrequested editions and sealed/graded
conditions are excluded. Ambiguous identities go to review rather than automatic
film-title correction. In particular, missing edition information is not permission
to mix a standard release and a collector's edition.

Rules include:

- Median, average, or trimmed mean. Trimmed mean removes 10% from each tail when
  at least ten observations remain.
- Minimum evidence count and maximum age; future/undated observations cannot support
  the automatic recommendation. Historical evidence remains historical, not a fresh
  market quote.
- Optional IQR outlier exclusion (1.5× fence, at least four observations), retained
  excluded source references, optional CAD floor/ceiling, basis-point adjustment,
  and rounding increment.
- Include or exclude recorded shipping from comparison totals. Zero shipping is used
  as recorded and disclosed. No missing shipping charge is invented.
- Manual/operator-price protection on by default.
- Automatic high-confidence application off by default; it acts only within an
  explicitly started job, never by continuously watching inventory.
- CAD evidence as recorded. USD evidence is excluded until an explicit positive
  USD→CAD rate and non-future effective date are configured. There is no fabricated,
  hardcoded business FX quote or automatic currency mixing.

Known incompatible conditions are excluded. Missing source condition is disclosed;
there is no invented grade premium. No statistical estimate can establish the physical
condition of a tape. Source sales, asking-price decisions, and optional AI estimates
remain separate records. Results retain the selected rules, evidence IDs/hashes,
reference access to source URLs and original sold/shipping amounts, date range, counts, exclusions and
explanations. Applying a saved price rechecks the inventory revision and current
source evidence. Changed records or superseded evidence are skipped. Price changes
use audited local services and do not silently update Shopify.

Each deterministic job caches up to 128 repeated identity calculations locally for
that job; it does not send inventory to a provider. Existing source records, hashes,
photographs and IDs are preserved. No model is needed to complete or recover a local
pricing job. Searches and statistics stay local.

## Metadata and recognition

**Generate missing metadata · offline** fills missing descriptions/tags with an
escaped template of existing inventory facts, using the validated inventory service.
It preserves existing descriptions/tags and invents no cast, plot, playback testing,
shipping promise, price or grading claim. An unidentified title remains an error for
review. Recognition can be queued for the checked subset in one batch action. API
photo recognition has one explicit batch-level cost/photo consent; offline OCR and
barcode modes retain their existing behavior. One title-review list applies shown
suggestions to blank titles while protecting operator titles, unknown suggestions,
and changed photographs. Recognition can still be reviewed individually.

## Independent optional OpenAI tasks

Settings → **AI Models & Tasks** saves a default plus independent overrides for photo
recognition, market reranking, edition identification, listing content, chat, and
agent conversations. Discovery and manual OpenAI model IDs are supported. Image,
text, structured-output, tool, and reasoning support is not assumed for unknown
models; explicit synthetic tests or documented capabilities establish compatibility.
Tool probes remain in AI workspace Connections. A synthetic probe itself requires
explicit API-charge authorization.

The central configuration is OpenAI-only; existing free/local/subscription connection
options remain in the AI workspace. New chat/agent conversations have shortcuts to
their separate saved models. Existing conversations retain their selected models.
Changing recognition does not change the market/content/edition/chat/agent override.
Reasoning effort is optional and requires a compatible model/test. Standard API keys
remain encrypted in the existing secure store.

Optional batch market, content, and metadata-based edition helpers use the selected
independent model. One reviewed authorization covers the exact subset, maximum
request count, output cap, and conservative estimated budget. Configure verified
per-million input/output tariffs to calculate that estimate. The input byte bound
and output cap limit each request; the reservation stops new calls when the configured
budget or request count is exhausted. Tariffs are operator settings, not fetched or
guaranteed provider billing. Provider-reported cost and actual token usage are saved
when available; unknown costs remain unknown. Candidate reranking sends at most
20 compact identities. These batch helpers send no photographs or complete database.
Edition suggestions based only on metadata stay unverified; they do not establish
visual authenticity. Description and edition suggestions remain reviewable.

API failure never falls back to another model/connection and never becomes a price
record. An interrupted billable request is uncertain and is not automatically repeated.
On app recovery, AI jobs pause for review; local deterministic jobs can safely resume
from saved per-item outcomes. No periodic model polling, inventory observer, screenshot
monitoring or automatic photo transmission was introduced.

## Multi-channel publication

Available publications are discovered from the connected store. Online Store is
preselected when present and no defaults have been explicitly saved. Check additional
compatible publications and **Save selected channels as defaults**. A batch can override
them; drafts save intended destinations without publishing.

Choose verified, marked, linked products and **Review batch publication**. One SUBMIT
review authorizes the exact product snapshots and all selected publication IDs. Up to
100 products and 50 actual publications are admitted per reviewed publication batch.
The existing owner-operated live controls and scopes are still required. A fresh
remote change or changed/deleted selected local copy stops the affected job.

Each product is activated as needed and sent to `publishablePublish` using multiple
publication inputs when appropriate. Actual `publishedOnPublication` reads establish
each channel's outcome. One rejected channel does not turn another channel's success
into a false failure; both outcomes are persisted and displayed. Resuming the same
job reads current statuses and sends only missing publications. A complete job requires
all selected channels to verify. Do not create a new product to recover publication.
Active status alone does not prove Online Store/website publication. Marketplace
integrations that expose no compatible Shopify publication are not invented or
silently called.

The dashboard filters show recognized, matched, priced, review, draft-ready, drafted,
active, published, and failed copies. Per-channel counts describe saved verified
receipts and their timestamps; changes performed outside SnapIMS require a fresh
Shopify read/reconciliation. They are not a continuous remote monitor. Workflow job
history and evidence remain stored, including partial and failed results. The normal
batch view lists recent jobs; application read tools and backups retain older rows.

## Data and acceptance

Room/snapshot schema 11 adds workflow/result/channel-state tables without rebuilding
inventory or modifying existing source sales. Schema 3–10 imports migrate forward.
Protected backups also carry explicitly allowlisted non-secret task, pricing and
channel/location preferences. They exclude credentials, owner-write/live enablement,
subscription tokens and temporary staging credentials. Restored external/AI jobs pause;
a backup cannot silently authorize a new model request or product write.

The release's host tests exercise native services, real SQLite/Room structures,
selection and operator UI, and intercepted Shopify HTTP. Live Shopify/physical Pixel
acceptance for the new changes must still be performed on the owner's device.
The owner reported successful real-store connection and a real draft on v0.8.3; that
working authentication and draft pipeline were extended, not rebuilt. No production
Canada VHS product was changed from this cloud environment. No broker app was created
or deployed, and direct mode requires no computer or hosted service.
