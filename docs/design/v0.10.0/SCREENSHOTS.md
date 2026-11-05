# Guided batch workflow · native rendered screens

These are actual Compose screens rendered by Robolectric's native Android runtime
at **412 × 915 dp**, with landscape and **1.6× text** checks. They are not mockups
or physical Pixel screenshots. The test drives the real MainActivity navigation,
Room inventory, imported sold evidence and existing Kotlin Shopify pipeline.

The fixture contains six physical copies and 16 original synthetic photographs:
six photographs on the first tape and two on each remaining tape. Four copies
receive evidence-based price approvals, one retains a manual price, and one is
left for review. Only the five eligible copies and their 14 photographs are
submitted. Intercepted HTTP supplies unique remote draft identities; no real
Shopify products or credentials are used. The fixture FX rate is not a live quote.

| View | Actual rendered screen |
|---|---|
| Capture grouping | [Six photos remain one copy](screenshots/01-capture-groups.png) |
| Price review | [Bulk price approval and exception count](screenshots/02-price-review.png) |
| Listing review | [Price, condition, SKU, weight and expandable metadata](screenshots/03-review-listings.png) |
| Explicit draft submission | [Five eligible selected copies](screenshots/04-submit-drafts.png) |
| Verified outcome | [Remote draft verification](screenshots/05-verified-outcomes.png) |
| Exception inspector | [Local fuzzy candidate search](screenshots/06-ranked-inspector.png) |
| Landscape | [Accessible primary action](screenshots/07-landscape.png) |
| Larger text | [1.6× font scale](screenshots/08-large-text.png) |

The content scrolls while the current stage's primary action stays accessible.
Secondary stage chips scroll horizontally. The inspector retains source evidence,
exclusion reasons, rate provenance, corrections and deferral inside the workflow.
Advanced Tools retains the previous batch controls and explicit live-publication
review. Existing photo history still provides grouping, deletion and Trash recovery.

The previous whole-application redesign remains documented under
[v0.9.0](../v0.9.0/SCREENSHOTS.md). No demonstration records are added to the installed
application. Physical camera/OCR accuracy, device performance and live remote
service acceptance remain separate, unrun checks.
