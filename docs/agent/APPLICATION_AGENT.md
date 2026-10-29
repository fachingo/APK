# SnapIMS Next 0.8.3 application agent

This extends the 0.7.0 workspace and native services. It does not replace inventory,
recognition, market pricing, Shopify jobs or the task outbox. Start with Settings →
AI workspace, choose your provider and model, then choose a permission mode.

| Mode | Enabled behavior |
| --- | --- |
| Chat Only | Chat and explicitly attached content; no application tools or data queries |
| Read Access | On-demand queries of non-secret local records; matching results can be sent to the selected provider; no proposals or changes |
| Full Agent | Reads and typed proposals; the human must review and approve each exact operation or reviewed batch |

Unknown model capabilities require an explicit synthetic Tools test. Selecting a
mode never sends a model request. Changing modes stops the current response,
expires pending AI reviews and starts fresh provider context on the next Send.
History remains on this phone. Already approved durable work is managed in Tasks;
changing chat permission does not undo its completed changes or cancel its jobs.
The model cannot grant itself permissions, increase budgets or approve proposals.

## What it can inspect

The compact gateway exposes five root tools in Full Agent, four in Read Access,
and none in Chat Only. `app_schema` discovers relevant resource/action arguments
instead of attaching dozens of detailed function definitions to every request.

| Tool | Purpose |
| --- | --- |
| `app_overview` | Actual local counts, latest batch and recognition/Shopify queue states; no network |
| `app_schema` | Discover allowed resources, fields, filters and action arguments |
| `app_read` | Bounded queries, field inspection, local search/aggregation/ranking and explicit broker reads |
| `app_navigate` | Validated native shortcut, opened only when the human taps |
| `app_propose` | Full Agent only; save a one-use human review, never apply it |

Read resources cover every native business table: complete inventory fields and
batch mappings; all batches/groups/photos and capture events; recognition inputs,
results and retained histories; the full market observation/version library and
import/search history; pricing evidence, source IDs and approved-price history;
listing plans, Shopify reviews/jobs/item checkpoints excluding temporary upload
credentials; tasks and their owning jobs; inventory audits, transfers and archives;
chat history, usage and cached model capabilities; agent policies/traces and SIWC
proof records. Safe public settings, database checks, table/storage statistics and
photo dimensions/orientation/hash verification are also available.

Protected credential stores are not exposed. Recognition credential bindings,
Shopify connection fingerprints and upload checkpoints are excluded, including
known protected keys inside structured JSON fields. Common bearer/API-key patterns
are redacted. Ordinary inventory text remains user data: avoid placing credentials
in descriptions, notes or chat. Source text/OCR/logs/attachments are untrusted data,
never authorization or app instructions.

Examples:

- “Find every unpriced VHS in my latest batch.” Resolves the actual latest session,
  filters locally and pages matching inventory; batch UUID and immutable SKU differ.
- “Which 20 copies have the highest historical sold prices?” Select a currency and
  statistic. The device evaluates compatible sold cohorts across the entire scope,
  then returns the top results. It does not rank current asking prices as sales.
- “Find duplicates across batches.” Normalized titles/barcodes identify candidates;
  separate physical copies keep their immutable IDs and are never auto-deleted.
- “What's stopping these products from uploading?” Reads readiness problems,
  photographs, inventory/link state, saved Shopify reviews and actual job errors.
- “Review everything that failed yesterday.” Dates use the phone's timezone. Only
  retained errors with known times can be assigned to a day. Capture/archive errors
  have no stored failure time: the tool reports their undated count and offers all
  retained failures. Successful native retries can replace some current error state.
  The assistant must disclose this limit rather than invent missing error history.

Generic lists support bound identity/state/date filters, column projection and pages
of at most 50 records. `record.field` retrieves complete long fields in chunks of
at most 4,000 characters. Inventory search also supports missing fields, condition,
location, tags, type, Shopify state, photograph presence and CAD asking-price bounds.
No raw SQL, arbitrary table/column names, shell commands or simulated taps are tools.

## What it can propose and execute after review

Existing single/batch recognition, sold-evidence pricing, confirmed-title application,
metadata editing, local listing preparation, batch-review tasks and Shopify drafts
remain available through the gateway. Added actions provide:

- A mixed inventory workflow for up to 20 exact copies: metadata corrections,
  compatible market recommendations for the corrected title, reviewed prices,
  recognition, readiness validation and offline listing plans.
- Start, rename and finish batches through the native capture/archive services.
- Create/rename local review tasks, complete fully reviewed tasks and cancel remaining
  approved work through the owning services.
- Archive/restore selected copies with explicit destructive-operation consent;
  originals and immutable identities are preserved.
- Review non-secret camera/recognition/business settings from a strict allowlist.
- Delegate compatible marked-product linking, selected metadata/tags/price/weight/stock updates and live-publication reviews to native Shopify services.
- Propose exact owner-selected product status/channel changes through `propose_shopify_status`; remote transitions require SUBMIT. Owner write/live switches remain human settings.

The review shows metadata before/after values, exact SKUs, source-backed prices,
recognition selection, readiness and listing actions. A single approval can cover the
whole mixed selection. Prices require explicit financial consent; manual-price and
historical overrides and USD→CAD rate/date remain operator decisions. The model
cannot invent FX or supply arbitrary numeric prices through a metadata patch.

Local mixed changes and queued owning-job records commit in one Room transaction,
or all roll back. Revisions, barcode/photo evidence, recognition settings and the
credential slot are checked again. Changed evidence stops approval. Recognition
runs after commit and remains a suggestion; printed-title confirmation/application
is separate. Paid API-key recognition requires its own checkbox even when the chat
uses a ChatGPT plan. Shopify uploads require review of the actual listing and photo
transfer. Live publication additionally requires typing `SUBMIT` and broker support.

Tasks retains actual outcomes, revisions, prices, listing/recognition references and
owning job progress. Queued/scheduled/Android-completed work is not claimed as verified
remote success. Errors leave reviews open or preserve an actionable outbox error.
Retries reuse owning identities. Restored unfinished tasks pause; restored pending
reviews expire; no restored model request or external job restarts automatically.

After approval the app reports its local result and task link without another model
call. Open Tasks for progress, or explicitly send “Verify the approved task and report
what remains.” Verification is an on-demand tool read, not continuous AI polling.

## Cost, privacy and idle behavior

Only Send, explicit connection discovery/synthetic tests, or explicitly approved
recognition tasks can contact a model/provider. Opening the workspace, viewing
history, ordinary database edits, mode/budget changes and photo staging send no model
requests. UI database observers update native progress only. There are no continuous
screenshots, screenshot tools, AI change feeds, model polling or automatic photo sends.

| Budget | Context characters | Model rounds per Send | Local calls per Send |
| --- | ---: | ---: | ---: |
| Economy | 12,000 | 4 | 12 |
| Standard | 24,000 | 8 | 24 |

Each tool result is bounded to 10,000 characters; all tool results per Send are capped
at 48,000 characters, with at most 16 calls in one round. Context retains complete
recent tool chains, trims older large tool previews and drops older turns as needed.
An oversized current turn stops before another provider request. These are local
request limits, not a guaranteed dollar ceiling or provider output-token limit.

Filtering, joins, statistics, duplicate detection, pricing and ranking run locally.
Ranking caches at most 12 results for 60 seconds, keyed by inventory/market revisions
and resolved batch scope. A ranking over its 30-second local budget stops with an
error instead of presenting a partial selection as the true top 20.

A photo navigation result exposes metadata only. Tap **Attach compressed inventory
photo** to stage a JPEG derivative (at most 960px and 500KB), then press **Send** to
transmit it. Originals remain unchanged. Explicit attachments are transmitted on the
first request of that turn and omitted from later tool rounds/older turns, reducing
repeat image/file charges. Ordinary manually selected file attachments retain the
existing compatible-input checks and 8MB limit.

Each response records provider token counts, cached-input counts and reported USD
cost when its USD units are explicit (or defined by the OpenRouter contract). Unknown
units/plan credits are never labelled dollars. Otherwise, catalog USD rates can support a clearly labelled estimate;
missing usage/rates stay unavailable. Cached-token discounts may make a simple
estimate conservative. No rates, plan allowance or bill are invented. Subscription
limits/errors never switch to a separately billed API connection.

## Delivery limits

Native host tests exercise real Room records, approved workflows, error recovery,
protocol fixtures and the private supplied 75,054-row market CSV. They do not prove
real model reasoning quality, Pixel camera behavior, eligible-account SIWC success
or actual Shopify API acceptance. Physical Pixel SIWC sign-in/model/stream proof
and real Canada VHS authentication, mutations and website publication remain **UNRUN**. No broker is
deployed by this release; no production inventory or products are modified.

The agent operates through the delivered app's services. Photo editing/retaking,
Android file selection/import/export, credential entry and unsupported Shopify
operations remain native human workflows; navigation can open their relevant screens.
There is no model-accessible general filesystem, credential manager, arbitrary SQL,
product deletion, unrelated-product adoption or unrestricted existing-product
price/stock/media editing. The independent desktop apps are unchanged.

Room10 migrates existing data in place. Protected snapshot schema10 includes policies,
traces, response usage and local Trash; legacy schemas3–9 normalize on import. Install the signed
0.8.3 APK over the existing app; do not uninstall or clear private storage.

Local deletion controls and seven-day recovery are documented in [Trash](../next/TRASH.md).

Direct Shopify operations use the phone gateway and encrypted on-device credentials;
no broker is required. See [direct setup and safeguards](../next/DIRECT_SHOPIFY.md).
[Independent match AI](../next/SMART_MARKET_MATCHING.md) is optional and uses one
explicit, bounded text-only request, separate from photo recognition. Local
SQLite/Commons Text matching uses no model tokens.
