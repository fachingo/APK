# SnapIMS Next 0.8.1

VersionCode11 · same package/signing certificate · Room10 · snapshot schema10.

This release adds local deletion for batches, inventory records, individual photos,
local publishing jobs and draft receipts. Draft cards in Shopify search can be
removed from SnapIMS without changing Shopify. Deleted entries disappear from
ordinary app lists and can be recovered in Settings → Trash for seven days.

Photo deletion works on finished inventory too. Record/batch removal hides related
photos and processing entries, cancels queued work and preserves immutable IDs.
Recovery restores the local entries without automatically resuming capture,
recognition or external uploads. Database and backup upgrades retain existing
inventory, conversations, market data and signed application compatibility.

See [controls and retention limits](TRASH.md), [executed validation](../TEST_RESULTS.md)
and [existing application agent capabilities](../agent/APPLICATION_AGENT.md).
Retained original files/audit rows are not securely erased after the recovery
window. Historical exports/transcripts remain unchanged. No new model requests or
continuous monitoring were added.

The physical Pixel install/camera check, eligible-account ChatGPT sign-in/stream,
and real Shopify development-store authorization/upload remain UNRUN. No real
Shopify product was modified or deleted to test these local controls.
