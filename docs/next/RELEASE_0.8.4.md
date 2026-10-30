# SnapIMS Next 0.8.4 — production batch workflows

Version code **14**, package `ca.canadavhs.snapmobile`. Install over the existing
signed app; do not uninstall or clear private storage. The retained signing
certificate is unchanged from v0.8.3. Inventory, source photographs, market sales,
audit history, existing drafts and saved encrypted Shopify/API credentials remain
on the device. No broker application was implemented or deployed.

- **Persistent partial selection:** check one, seven, or an arbitrary subset of a
  batch; select all/none/invert/ready/unpriced/unlisted. The same selection drives
  successive workflow actions. Inventory also provides an all-batches workflow.
- **One-tap selected drafts:** Create Shopify Drafts is the operator's authorization.
  No second consent dialog, SUBMIT, or per-product approval for ordinary draft
  creation. Valid selected copies use the established native draft/photo pipeline;
  invalid or already linked copies are skipped with reasons. Existing published
  products are protected. Optional listing review remains available.
- **Offline Auto Match & Price:** deterministic SQLite candidate retrieval, strict
  sold-comparable validation and Decimal statistics run without a model, API key or
  network. Median/average/trimmed mean, minimum counts, age limits, shipping policy,
  IQR outliers, floors/ceilings, adjustments, rounding, manual protection and dated
  USD/CAD conversion are configurable. Questionable identities remain unpriced.
  Apply selected eligible recommendations together, or explicitly enable automatic
  high-confidence application within started jobs. Evidence and asking prices stay
  separate. Changed revisions/source versions cannot apply stale recommendations.
- **Hybrid matching:** useful approximate candidates now appear alongside exact
  hits. FTS/fallback, prefixes, token/word order, trigrams, Levenshtein and Jaro-Winkler
  remain local. An indexed distinct-title catalog bounds retrieval/reranking before
  complete sold-group aggregation. Results explain relevance and identity conflicts;
  actual source evidence can be inspected in local pages of 50 records.
- **Independent OpenAI tasks:** Settings → AI Models & Tasks saves independent
  recognition, reranking, edition, content, chat and agent model overrides, discovery,
  manual model IDs and compatible reasoning configuration. Existing free/local/ChatGPT
  connections remain available. Optional batch AI helpers have one exact-selection
  request/budget authorization, bounded input/output, saved usage/cost information,
  and no automatic fallback. They never make deterministic pricing depend on AI.
- **Multiple actual Shopify publications:** save default channels, override them per
  batch, and retain overrides across refresh/navigation. Drafts save intended channels
  without making products live. One batch SUBMIT binds selected product snapshots and
  all selected compatible publication IDs. Each channel is verified independently;
  interrupted/partial publication resumes only unconfirmed destinations. Active status
  alone is not reported as verified website publication.
- **Functional batch dashboard:** tappable status filters, selected recognition and
  a single recognized-title review, offline missing-metadata templates, reviewable AI
  descriptions, saved job/results history, evidence explanations and per-channel receipts.
  Unknown titles, stale evidence, errors and ambiguous matches stay reviewable.
- **Preservation:** additive Room/snapshot schema 11 migration; schema 3–10 imports
  remain supported. Protected backups include allowlisted non-secret workflow settings.
  Credentials and owner-write/live enablement are excluded; restored AI/external jobs
  pause. Deleted local records remain hidden and unsent workflow work is cancelled.

The existing direct Shopify authentication and native draft/recovery engine were
extended, not rebuilt. There is no continuous AI monitoring, periodic model request,
automatic photo transmission, or mandatory computer/hosting service.

## Validation

**236 unique Android/core host checks passed** in the full regression suite, with
zero failures/errors/skips; final targeted checks cover the final UI/setting changes.
Native intercepted-HTTP tests create seven unique drafts and upload only the seven
selected photographs from 50 physical-copy records. Fifty selected drafts queue in
one operator action with no per-item review receipts. Multi-channel tests persist a
partial failure and verify that retry sends only the missing channel. Existing
camera, inventory, backup/restore, Trash, permissions, approval and idle-agent checks
remain covered.

The large native SQLite test imports all **75,054 supplied records** and **100,000
synthetic records**, retains **175,054** observations after restart, and proves safe
repeat import and unchanged owner CSV. A mixed synthetic batch of 100 copies against
that same library generates/applies **50 high-confidence prices**, leaves questionable
copies unpriced, and makes **zero model requests**. The host test took **4,244 ms** for
that bulk calculation/application; median indexed fuzzy retrieval was **26 ms** in the
final full-run benchmark. Timings are host measurements, not Pixel performance claims.
The FX rate in the benchmark is explicitly synthetic test configuration.

The owner previously confirmed real Canada VHS connection and a real test draft on
v0.8.3. **Physical Pixel and real-store acceptance for these new changes remain
unverified in this cloud environment.** No real store products were changed. Native
HTTP fixtures do not prove actual account scopes, API/schema acceptance, provider
quality or public website behavior. Real credentials belong on the phone, not in
chat, source, logs or the APK.

Per-action limits: 2,000 local/draft records (durable draft jobs are chunked at 100),
100 linked products per reviewed live batch, and 50 actual publication IDs. Fuzzy
retrieval uses bounded candidate windows; refine a broad search rather than assuming
all possible titles are enumerated. Metadata-only AI edition guesses remain
unverified; they cannot supply sold evidence or automatically apply a price.

Read [workflow instructions and exact safeguards](PRODUCTION_WORKFLOW.md) and
[executed test results](../TEST_RESULTS.md).
