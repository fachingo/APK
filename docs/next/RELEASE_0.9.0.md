# SnapIMS Next 0.9.0 — native operator workspace

Version code **15**. Update the existing `ca.canadavhs.snapmobile` app without uninstalling or clearing storage. The retained signing certificate and Room schema **11** are unchanged. Inventory, originals, market records, secure credentials, jobs, journals and saved business preferences retain their existing storage and services.

- **Five workflow destinations:** Dashboard, Capture, Inventory, Market and Shopify. AI, Tasks, Backup & import, Trash, Transfers and stored pricing have a global utility menu. Capture keeps an immediate Settings shortcut. Landscape uses a navigation rail.
- **Actionable Dashboard:** named current/latest batch, local inventory and unpriced counts, validation-based draft readiness, ongoing work and error/recovery shortcuts. A current batch opens in one tap.
- **Selected-item batch dock:** recognition, offline Auto Match & Price, deterministic missing metadata, Create Drafts and reviewed publication stay visible while scrolling. Arbitrary selection persists. More Actions groups title review, pricing presets/rules, optional paid AI, saved location/channels, exceptions and job results/recovery.
- **One-action drafts:** the draft button resolves fresh store health and saved location/channel overrides without requiring a separate refresh. It uses the established native draft/photo pipeline. Invalid/linked copies remain protected and are skipped with reasons. Draft submission is authorization to upload the selection; it does not publish it.
- **Market first, database tools underneath:** offline automatic pricing is prominent. Exact, Likely and Possible candidate groups are distinct; source sales, price distributions and prior operator decisions remain available. Optional AI and database diagnostics are expandable. Conservative/Balanced/Historical presets preserve FX, price bounds and rounding; every original advanced setting remains editable.
- **Catalog-first Shopify:** real explicit searches, filters, cursor paging, inspection, local hiding, ownership/linking, protected edits and human status labels remain. Listings, Connection & jobs and Local preparation are grouped tabs. Activate and Publish remain separate, with the same exact reviews and SUBMIT protection. Opening a screen performs no remote operation.
- **Cohesive Material 3:** restrained teal palette, compact typography/cards, light/dark themes, clearer hierarchy, original photo tools and responsive controls. Central AI Models & Tasks retains six independent overrides, connection probes and cost/approval controls.
- **Preserved work state:** route state, batch selection, list positions, product edits and chat attachment metadata survive normal navigation/recreation. Keystore/API credential fields stay out of saveable UI state. A user-triggered local-only recovery helper refuses AI, cancelled or changed-state workflows.

Deterministic matching/pricing remains **fully offline with zero AI calls**. Optional provider calls stay on demand. No continuous monitoring, new broker, hosting or automatic photo transmission was added. Queued operations remain labeled queued until the existing services verify actual outcomes.

## Design and verification

The pre-edit feature inventory, eight populated design previews, per-control mapping, workflow comparison and actual native screenshots are in [the design evidence](../design/v0.9.0/FEATURE_PARITY.md). These are functional Compose screens using the existing services, not mockup screens shipped as an app.

See [executed checks](../TEST_RESULTS.md) for final counts and build/signature verification. Tests use real local inventory/Room structures, synthetic sold evidence and intercepted native Shopify HTTP. The supplied private market CSV is exercised by the large-data regression and excluded from the public APK and source artifacts.

Physical Pixel camera timing, real-account providers and Canada VHS live-store acceptance for **0.9.0 are unrun here**. No real product was changed. SIWC remains the existing experimental prototype with physical-device sign-in unverified; this redesign does not claim to resolve that acceptance test.
