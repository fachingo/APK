# Local deletion and seven-day Trash

Deleting removes the entry from ordinary SnapIMS lists immediately. It never sends
a Shopify deletion, archive or publication request. Existing Shopify products,
product IDs and completed remote work remain unchanged.

| What to remove | Control |
| --- | --- |
| Batch and its contained local records/photos | Batches → select batch → Delete batch · move to Trash |
| Bad inventory record | Inventory → open record → Delete record · move to Trash |
| Individual photo, including finished items | Delete photo beside its thumbnail or in its preview; Inventory → Manage photos also has Delete photo |
| Local publishing job | Shopify → Publishing jobs → Delete local job |
| Individual local draft receipt | Publishing job → Delete local draft · SKU |
| Draft shown by Shopify search | Draft listing card → Delete draft from app |
| Recover an entry | Settings → Trash → Recover |

No extra confirmation is needed for these reversible local controls. Trash displays
the exact recovery deadline. A batch recovery restores the children removed with
that batch. Previously independently deleted photos/records stay in their own
Trash entries. Recover a containing batch/record/job before recovering its child.
A draft deleted from a Shopify search stays hidden after another search or restart.

Recovery preserves immutable physical-copy IDs and original photo files. Recovered
batches remain finished; inventory readiness needs review. Recovery does not restart
capture, recognition or uploads. Recovered unfinished publishing jobs are paused;
cancelled item operations require a fresh reviewed operation. Successful historical
receipts keep their successful state and remote IDs.

The service blocks deletion while photos are being saved, an affected archive is
being built, recognition is running, or a related remote operation is in flight.
Wait for completion or pause/stop the operation first. Queued recognition and
unfinished local upload entries for removed inventory are cancelled transactionally.
The local expiry worker makes no model or network requests.

## Retention and backups

Recovery is available for exactly seven days from deletion, checked when Recover
runs even if Android has delayed the local expiry worker. Expired entries disappear
from Trash and remain hidden in normal app views. This release retains internal
identity/audit rows, original photo bytes and immutable historical ZIP exports to
protect existing export and reconciliation invariants. It does **not** securely
erase bytes or reclaim their storage after seven days. Historical chat transcripts
and exported files are not rewritten. Database health statistics can still account
for retained internal rows. A backup includes retained rows/files and Trash markers.

Room migration9→10 preserves existing data and adds Trash. Previously removed
photos receive a seven-day recovery window on database upgrade. Snapshot schema10
includes Trash, preserving the original deadlines; schemas3–9 remain importable.
An older backup taken before deletion predates its Trash marker. Do not use that
backup as a way to expect current deletion decisions to be present automatically.

Install the update over the previous official APK. Do not uninstall or clear app
storage, which removes the private inventory and recovery files.
