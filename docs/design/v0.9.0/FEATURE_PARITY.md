# Feature parity and navigation plan — baseline v0.8.4

Completed before editing native UI. Business logic, schema 11, network clients and workers stay authoritative.

| Existing capability | Current access | Redesigned access | Level | Preservation contract |
|---|---|---|---|---|
| Dashboard | InventoryHome | Dashboard | Primary | Counts, active/latest batch, capture, inventory, backups and failures; add actionable filtered destinations. |
| Camera and capture | CaptureScreen / CameraEngine | Capture | Primary | Shutter, volume, flash, HDR, zoom/pinch, grid, resolution, role, next/undo, QR commands, note/rare/review, barcode selection, finalize part and finish; same engine/handlers. |
| Capture configuration | Settings / ResolutionSettings | Settings → Camera & capture; Capture settings shortcut | Contextual | Every resolution, fast capture, grid, volume, haptic, tone, barcode, HDR, archive size and location option retained. |
| Batches and capture history | BatchesScreen | Inventory → Batches; Dashboard active batch | Primary | Batch chooser, new, production workspace, finished/source history, finalization and local deletion retained. |
| Selection and batch actions | ProductionBatchScreen | Batch workspace fixed action dock + More actions | Primary | All/none/invert/ready/unpriced/unlisted, count, arbitrary subset; same persistent selection and exact ID sets. |
| Batch pricing | ProductionBatchScreen / AutoPriceRulesDialog | Batch dock → Auto Match & Price; Pricing rules | Primary | One action starts offline jobs; bulk apply, automatic policy, every statistic/FX/outlier/floor/rounding/evidence/manual rule retained. |
| Recognition | RecognitionDialog / ProductionBatchScreen | Batch dock; Product details; Settings → Recognition | Contextual | OFF/model-free/OCR/OpenAI, consent, current-photo checks, printed-title review/apply, barcode candidates, evidence/history, JSON sharing retained. |
| Batch metadata and optional AI | ProductionBatchScreen | Batch More actions | Contextual | Offline fill, title-review list, bounded AI matching/content/edition budgets, review/apply descriptions retained. |
| Batch drafts and publication | ProductionBatchScreen | Fixed dock → Create Shopify Drafts / Publish | Primary | Single draft action, no new confirmation; existing linked-copy safeguards, exact batch SUBMIT for live retained. |
| Batch connection/destinations | ProductionBatchScreen | Batch More actions → Shopify destinations | Contextual | Explicit test/refresh, locations, multi-channel override, defaults, intended destinations and per-channel receipts retained; no automatic networking. |
| Inventory search/filter | InventoryScreen | Inventory → All items | Primary | Title/barcode/SKU/location search, ready/review/archived filters, cross-batch bulk workspace retained. |
| Product editing | InventoryEditor | Tap product → Details | Primary | All identity, title/year/edition/distributor/condition/notes/location/barcode, price/discount/quantity/pool/vendor/type/tags/HTML/weight/rare/review/readiness fields retained. |
| Product provenance | InventoryEditor | Details → Listing details & provenance / audit | Advanced | Immutable IDs/revisions, recognition results, duplicate warnings, price evidence, remote IDs and local archive/restore retained. |
| Photograph management | InventoryPhotoManager / InventoryItemCamera | Details → Photographs | Contextual | Add/retake, role, reorder, delete/recover, move/reassign, full-size inspect and original source integrity retained. |
| Sold market search | MarketDatabaseScreen | Market | Primary | Full search, differentiated exact/likely/possible groups, barcode/date/year/edition/currency/price/sort/history filters retained. |
| Market evidence and price review | MarketDatabaseScreen / MarketDialog | Market → candidate; Product → Price | Contextual | Supporting sales, distribution, target item, CAD/USD recommendation, dated conversion, manual override/stale approval, prior asking hints and history retained. |
| Market data utilities | MarketDatabaseScreen | Market → Filters & tools | Advanced | CSV/SQLite preview/import/commit/cancel, integrity, rebuild, export, source versions/search favorites/history retained. |
| Direct Shopify connection | ShopifyScreen | Shopify → Connection & jobs | Contextual | Existing encrypted direct credentials, connect/test/clear, owner/live switches, identity/scopes, active locations and actual publication discovery retained. |
| Optional legacy Shopify mode | ShopifyScreen | Connection & jobs → Optional legacy connection | Advanced | Existing pairing/authorize/revoke/forget/URL retained, no broker created or required. |
| Shopify catalog | ShopifyListingsScreen | Shopify | Primary | Search title/SKU, Draft/Active/Archived/all, cursor pagination, inspect/refresh, local hide, immutable linking, edit selected metadata/financial fields retained. |
| Shopify ownership/status/publication | ShopifyListingsScreen / ShopifyReviewDialog | Listing → Edit / Activate / Publish | Contextual | Exact fresh reviews, all channels, local approval vs Active vs Published, SUBMIT, reject/replay protection and receipts retained. |
| Shopify durable jobs | ShopifyScreen | Connection & jobs → Jobs; global Tasks | Contextual | Progress/errors/checkpoints, pause/reconcile/resume/cancel, local job/draft Trash, partial outcomes retained. |
| Local listing plans | ListingPlansScreen | Shopify → Local preparation | Contextual | Prepare local plans, inspect all errors, selected item navigation and access to live Shopify retained. |
| Stored sold pricing | PricingScreen | Market → Stored pricing evidence; global More | Advanced | Desktop pricing SQLite import, collections/statistics, current/history distinctions, selected price application and provenance retained. |
| AI workspace | AiWorkspaceScreen / ChatPanel | Global AI shortcut / More → AI workspace | Contextual | Saved/new/search/export/delete chats, attachment upload, streaming/cancel/history, tool activity, navigation, actual usage and permissions preserved. |
| AI providers and subscription prototype | ConnectionsDialog / NewChatDialog | AI workspace → Connections | Advanced | API/free/local/experimental SIWC, discovery and explicit capability probes, account health/proofs, no billing fallback retained. |
| AI permissions and approvals | AgentPermissionPicker / AgentApprovalDialog | Chat permission control / proposed changes | Contextual | Chat Only/Read/Full Agent; economy/budgets, before/after and bulk review, manual/stale/billable/destructive/SUBMIT safeguards retained. |
| Task-specific OpenAI models | AiTaskModelsSettings | Global Settings → AI Models & Tasks | Contextual | Default and six independent overrides, effort/cap/rates, manual ID/discovery/probes, separate keys/limits retained. |
| Tasks and history | TasksScreen | Global More → Tasks; Dashboard jobs | Contextual | Review task create/rename/complete/cancel, scheduling recovery, all owning service jobs/results, item/batch navigation retained. |
| Protected backups and interchange | BackupScreen | More → Backup & import; Settings shortcuts | Contextual | Passphrase backup, restore, native ZIP/desktop import/export, CSV, integrity, progress/cancel, conflict checks and non-secret settings retained. |
| Capture archives | Transfers | More → Transfers | Advanced | Every archive, checksum/state/size/error, retry, verified share and Save to Files retained. |
| Trash and deletion | TrashScreen / local delete actions | More → Trash; contextual delete actions | Contextual | Seven-day recovery, batch/item/photo/local draft hide/delete, expired policy, no remote Shopify deletion retained. |
| Business and appearance settings | BusinessDefaultsSettings / SettingsScreen | Settings grouped sections | Contextual | Vendor/type/weight/location defaults, light/dark, licenses and all archive/retention options retained. |
| Navigation/deep tool shortcuts | MobileApp / app_navigate | Five primary destinations + global utility menu | Primary | All existing numeric routes 0–12 and validated AI record targets preserved, back stack and saveable screen state added. |

The machine-readable [baseline control inventory](baseline-controls.json) enumerates text/actions with original source file, component and line, including dynamic labels. This matrix groups every enumerated control; moved controls retain their existing callbacks rather than being replaced with placeholders.

## Screen plan

1. Dashboard: compact operational counts, current batch → one tap, local job health and shortcuts.
2. Capture: keep the full-bleed real camera, concise identity bar, legible shutter/next controls and immediate capture feedback.
3. Batch: name/count, horizontally scrolling status filters, search and dense thumbnail rows; selected-action dock remains visible while scrolling. More actions contains existing recognition review, AI, destination and history controls.
4. Product: compact details header, cover strip, clear save/dirty state; essentials first, listing/provenance expandable; original photo manager remains one step away.
5. Market: Auto Match & Price entry, search/candidate groups and evidence first; administration under Filters & tools; pricing presets plus advanced statistical controls.
6. Shopify: catalog first with readable statuses; connection/jobs and local plan utilities in tabs, exact review/publication guards unchanged.
7. AI: workspace remains intact with concise app context, permissions and usage; central task model settings use task selectors and saved edits.
8. Utilities: global menu has Tasks, Settings, Backups, Trash, Transfers and stored sold pricing; sectioned settings preserve all controls.

## Flow and interaction targets

Dashboard → active batch (1 tap) → select subset → Recognize (one batch consent only if necessary) → review suggested titles → Auto Match & Price (1 tap) → apply eligible prices (1 tap) → Generate Metadata (1 action in More) → Create Shopify Drafts (1 tap with configured destination) → Publish (one exact batch SUBMIT). All advanced panels return to the same selection/scroll. Opening a route never starts Shopify/model requests.

## Visual system

Material 3; muted teal accent, neutral slate surfaces, 20/16/12/8 dp spacing, 22sp screen titles and 14sp operational body. Rounded surfaces identify groups rather than wrapping every label. Minimum 48dp controls, no fixed card heights, wrapping long content, rail for landscape/tablet, light and dark palettes. Human labels sit above raw status/IDs; technical details remain inspectable.

## Acceptance

Keep existing service regression tests; add populated native render tests for all eight areas, utility reachability, selected dock, offline idle, screen return/rotation, input retention, job recovery and theme/large-text layouts. Compare before/after workflow counts and publish actual screenshots plus update-compatible APK.

## Final delivery map

[Per-control reconciliation](CONTROL_PARITY.md) maps all 491 captured source/control records to the delivered routes, including explicit terminology changes. The grouped matrix also covers dynamic enum-generated choices and icon actions whose underlying services remain in place.

[Actual native renders](SCREENSHOTS.md) and [before/after interaction comparison](WORKFLOW_COMPARISON.md) accompany the initial plans. Runtime regression covers the existing services plus populated navigation, selected dock, exact one-action pricing/draft queues, saved channel override, work-state restoration, local job recovery and refusal to restart AI or cancelled jobs. No live-account/physical-device result is implied by source parity or intercepted HTTP tests.
