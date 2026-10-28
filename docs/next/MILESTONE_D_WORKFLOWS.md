# SnapIMS Next 0.6.0 — approved workflows and tasks

Settings → Tasks tracks actual approved recognition, market prices and Shopify
DRAFT jobs, and offline inventory review tasks. AI workspace also opens Tasks.
The phone retains its independent SQLite inventory, photographs and market library.
Desktop SnapIMS and Pricing_module are unchanged.

## Use the workflows

1. In Settings choose Classical (ZXing/pixel checks), Offline OCR (Tesseract plus
   barcodes), or OpenAI recognition. Save a conventional API key only if desired.
2. In AI workspace select an explicit provider/model with verified tool support.
   Enable local read tools and reviewed change proposals for that conversation.
   Read tools may send matching local records to the selected provider.
3. Ask for batch review, recognition, a local sold-evidence price, or Shopify drafts.
   `capture_batches` returns actual session IDs, `batch_review` pages 20 copies,
   and `inventory_search`/`inventory_get` expose immutable physical-copy identities.
4. Open **Review proposed change**. Check each selected SKU, photos, proposed prices
   or listing details. Nothing executes until **Approve once**. Reject or close to
   defer. Errors keep the review open so missing operator decisions can be supplied.
5. Open Tasks for actual completion, errors, partial results and item navigation.
   Cancelling stops remaining local work; completed outcomes and remote resources
   are retained. Already-sent external requests can still consume usage.

No AI connection is needed for **Tasks → New inventory review task**. Select up to
50 active copies. Review them in Inventory and explicitly mark each ready before
marking the review task complete. Readiness itself never uploads or publishes.

## Recognition

Single and exact batch selections support up to 50 copies. An approval binds each
inventory revision, active photograph identities/order/roles/hashes/state, and the
configured recognition provider/model and encrypted credential-slot identity.
Queued/running work for an unchanged selection is reused rather than duplicated.

**OpenAI recognition uses conventional, separately billed API keys.** Even a chat
using a ChatGPT plan needs its own explicit paid-recognition checkbox. There is no
subscription-to-API fallback. Choose Classical/OCR for offline processing without a
provider bill. Changed/removed credentials stop the worker before a request; an
uncertain interrupted API request requires a manual retry to avoid duplicate charges.

Recognition results remain suggestions. Open the item from Tasks, choose Recognition,
confirm the printed title and separately apply it to inventory. Confirmation never
silently applies a title. Changed photographs invalidate a completed title review.
Capture never automatically initiates these cloud requests.

## Market prices

Single or atomic batch pricing supports up to 20 copies per proposal. Prices come
from the permanent local sold-market library, preserving the existing conservative
matching, shipping-inclusive Decimal rounding, currency and provenance rules.
The model cannot supply a conversion rate or invented amount in a tool call.

Review sample count, sale dates, original-currency statistic and before/after CAD
base prices. USD evidence needs an operator-entered CAD-per-USD rate and effective
date. Historical reuse and replacing manual prices each require a checkbox. Single
copies optionally allow an operator-adjusted CAD price; batch proposals use the
reviewed deterministic statistic. Existing discounts remain visible and retained.
A price change clears listing readiness.

Every selected item and compatible evidence cohort is rechecked during approval.
Any failed check rolls back the whole batch, including earlier prices and audit
records. One-use receipts and completed task history record the resulting CAD prices.
Asking-price decisions remain separate from immutable sold observations.

## Shopify drafts

Single and exact batches support up to 50 ready physical copies. Pair/test the
optional hosted confidential-credential broker first. The proposal reads actual
health and active locations, requires a CAD development store, and displays listing
fields and ordered photographs for transfer. Approval binds the paired connection
and inventory/photo snapshots, then commits a durable existing Shopify batch job.

Tasks reports saved per-item server verification, partial success and errors;
Android WorkManager completion alone does not imply Shopify success. Open Shopify
jobs for its existing retry/reconciliation controls. Draft approval never invokes
live publication; publication retains its separate SUBMIT review. Already-completed
products are not recreated by retry. Production operations remain disabled.

The broker is **not deployed**. Real authorization, one development-store product,
network-interruption resumption and multi-item acceptance are **UNRUN**. Mock tests
exercise the pipeline and approval boundary; they do not establish Shopify acceptance.

## Persistence and bounds

Room migration 6→7 adds durable task receipts and encrypted-slot recognition bindings.
An upgrade stops old queued/running unbound OpenAI attempts for explicit manual retry.
The complete 1→7 migration retains existing capture rows and originals.

Task creation and owning job rows commit atomically. A scheduling outbox survives
process death; recovery schedules existing jobs and never creates a second product
or recognition row. Pending scheduling can be retried explicitly from Tasks.
Approved tasks survive conversation deletion. The board shows the latest 200 tasks;
older receipts remain in SQLite and backups. Each review payload caps at 900,000
characters; use a smaller selection if needed.

Protected backups now use snapshot schema7, include tasks and keep the streamed
market SQLite member. Schemas3–6 remain supported. Restored unfinished tasks pause
for fresh review, pending recognition requests become manual-retry errors, and
Shopify jobs require reconnection. Restore never starts billable work.

Provider chat remains a foreground-process stream, separate from durable recognition
and Shopify workers. SIWC retains the previous experimental native loopback OAuth
prototype. Physical Pixel sign-in → model discovery → completed subscription stream
is **UNRUN** without an attached Pixel and eligible account. No mandatory home server
has been introduced. Conventional API-key connections remain independent.
