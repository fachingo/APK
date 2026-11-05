# SnapIMS Next 0.10.0 · guided capture-to-draft workflow

[Installable APK](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Next-0.10.0-debug.apk) ·
[Source ZIP](https://github.com/fachingo/APK/raw/refs/heads/main/releases/SnapIMS-Next-0.10.0-source.zip) ·
[Executed test results](../TEST_RESULTS.md) ·
[Workflow and root causes](WORKFLOW_0.10.0.md) ·
[Native screenshots](../design/v0.10.0/SCREENSHOTS.md)

The batch workspace now guides **Capture → Identify → Price → Review Listings →
Create Shopify Drafts**. Closing a physical copy starts offline recognition,
corroborated local identification, CAD price recommendations and missing factual
listing text. Operators approve prices/listings and handle exceptions in the same
screen. Existing controls remain in **Advanced Tools**.

* Fixes false duplicate Shopify identity errors from blank IDs during publication.
  Draft creation accepts new copies; real conflicting product links remain blocked.
  Errors now name their operation and clear when a new action starts.
* Adds Bank of Canada USD/CAD retrieval, persistent dated cache, offline fallback,
  stale-rate safeguards and per-price provenance. Missing FX has a distinct reason.
* Improves indexed UPC/title/FTS/trigram/edit-distance retrieval, OCR correction,
  partial matches and word-order handling. Hard format, edition, sequel, year and
  condition checks remain separate from fuzzy ranking.
* Replaces the three-comparable approval rule with explained evidence-quality
  scores. A strong single sale can support individual review; bulk approval needs
  stronger evidence. Existing manual prices, floors, rounding, median/IQR rules and
  source history are retained.
* Adds an in-workflow inspector with candidate search, source/exclusion explanations,
  sale dates, CAD conversions, manual pricing, retry, correction and deferral.
* Uses durable existing Room/WorkManager services, avoids duplicate jobs, preserves
  progress/selection and isolates exceptions. Individual Shopify recovery retains
  original creation IDs and remote photo checkpoints.

Normal processing uses **zero AI calls**. Optional AI remains independently
configured and explicitly authorized. No broker, hosting or home computer is
required. Shopify uploads happen only after the draft submission action, and live
publication remains a separate explicit review.

## Install without losing data

1. Download the linked `SnapIMS-Next-0.10.0-debug.apk` on the Pixel.
2. Open it and choose **Update** for the existing SnapIMS app. Allow installation
   from the download application if Android requests that setting.
3. **Do not uninstall, clear app storage, or delete the local database.** If Android
   reports a signature mismatch, stop and retain the installed app/data.
4. Confirm **Settings → 0.10.0** and check your existing batches, market count,
   photographs and saved Shopify connection.

Application ID stays `ca.canadavhs.snapmobile`; version code increases to **16**.
The delivered APK uses the retained certificate. Room/snapshot schema remains
**11**; no reset or schema migration is required. Rate caching adds nonsecret
app-private preferences; workflow state uses existing durable job tables.

## Pixel acceptance checklist

1. Use an existing test batch or capture six **physical copies**, each with its
   own card/SKU. Six photographs of one tape should stay on one card.
2. Tap Next product / Finish batch, or **Identify & price 6 copies** for an existing
   batch. Confirm recognition and price jobs advance without opening Market.
3. With a connection, check the Bank of Canada rate/date. Then enable airplane
   mode and retry local pricing with the cache. Inspect any FX or identity reason.
4. Review one strong recommendation and one exception. Confirm a saved manual
   price survives processing. Approve eligible prices together and defer the
   unresolved tape. Rotate/reopen the app and verify selection/progress.
5. Inspect metadata/photographs, approve eligible listings, then deliberately tap
   **Create Shopify Drafts (N)** only for copies you want uploaded. This action
   authorizes a real Shopify write; connection tests do not create products.
6. Confirm draft IDs, unique SKUs, CAD prices, weight, quantity and ordered media in
   Shopify. Test recovery only on that explicitly approved batch. Verify status
   stays **DRAFT** until separately reviewing live publication.

Physical Pixel camera/OCR accuracy, a live Bank of Canada response, and real Canada
VHS Shopify writes are **unverified in this cloud environment**. Host tests use
native Android/Room/Compose with intercepted external HTTP and do not alter real
products. The six records on your phone were not accessible here; source-data
diagnostics use six clearly labeled representative title/year examples.

GitHub's [Android build workflow](https://github.com/fachingo/APK/actions/workflows/android.yml)
also produces test/build artifacts. Use the APK linked above for the locally
verified retained signing identity; CI update signing depends on the repository's
optional signing secret.
