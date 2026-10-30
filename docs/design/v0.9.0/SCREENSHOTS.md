# Native rendered screen review

Actual Compose screens rendered through the native Android host runtime at **420 × 960 dp**, with additional landscape and **1.6× text** checks. The fixture uses six real local Room inventory records, original synthetic JPEG covers, imported sold rows, a saved conversation and a paused durable workflow. Store reads use the actual Kotlin direct gateway through intercepted HTTP with synthetic credentials. No screenshots contain production credentials or customer inventory.

Dialog screenshots capture their native window; transparent space outside a modal sheet may appear black in the host PNG.

These are rendered app screens, not the [pre-edit layout plans](PREVIEWS.md). Capture shows its real controls and camera-permission state: a cloud host cannot supply a physical Pixel camera preview. Device camera timing and real Shopify/account acceptance remain unrun.

| Area | Rendered evidence |
|---|---|
| Dashboard | [Light](screenshots/01-dashboard-light.png) |
| Capture | [Camera controls / permission state](screenshots/02-capture.png) |
| Selected batch | [Persistent action dock](screenshots/03-batch-selected.png) · [More Actions](screenshots/03-more-actions.png) |
| Product / photos | [Details](screenshots/04-product-details.png) · [Photo tools](screenshots/04-photographs.png) |
| Responsive / accessibility | [Landscape](screenshots/04-product-landscape.png) · [Dark](screenshots/04-product-dark.png) · [Large text](screenshots/04-product-large-text.png) |
| Market / pricing | [Search](screenshots/05-market.png) · [Match groups](screenshots/05-market-results.png) · [Presets](screenshots/05-pricing-presets.png) |
| Shopify | [Catalog](screenshots/06-shopify.png) |
| AI | [Conversation](screenshots/07-ai-workspace.png) · [Task model settings](screenshots/07-task-models.png) |
| Utilities | [Settings](screenshots/08-settings.png) · [Jobs / recovery](screenshots/08-jobs.png) |

Review corrections made from actual renders: removed inherited purple surface tint; shortened the draft label to fit a phone dock; labeled missing prices “Unpriced”; prevented large-text navigation wrapping; made the product header adapt; removed duplicate Back arrows where the screen already supplies Back; let the collapsed pricing dialog fit its content; captured the actual model-settings dialog instead of its underlying route; separated candidate tiers; restored Capture → Settings. Long detail forms scroll while Save and selected batch actions stay accessible. Secondary horizontally scrolling filters retain touch targets and full accessibility labels.

Retained baseline renders: [v0.8.4 batch](before/batch-v0.8.4.png), [v0.8.4 Dashboard](before/dashboard-v0.8.4.png).

See [feature parity](FEATURE_PARITY.md), [per-control reconciliation](CONTROL_PARITY.md) and [workflow comparison](WORKFLOW_COMPARISON.md). Synthetic data is used only in tests and images; no demonstration inventory is injected into the installed app.
