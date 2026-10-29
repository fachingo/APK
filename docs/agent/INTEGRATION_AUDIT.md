# SnapIMS agent integration audit and execution plan — 0.8.0

Baseline: fachingo/APK89030a4 (0.7.0), Room8. Preserve the existing application,
provider clients, business rules, IDs, proofs, approvals and durable owning jobs.

Already working: persistent provider chat/discovery/streaming/attachments; opt-in
read/proposal booleans; metadata and confirmed-recognition proposals; single/batch
recognition, real-market pricing and Shopify drafts; one-use revision/photo/barcode
binding; explicit separately billed recognition and operator FX/manual/historical
approval; native Shopify saved reviews; task/outbox recovery and protected backups.

Partial: tools expose narrow or capped views without general pagination; chat lacks
app context, named permission modes and active tool traces; context repeats complete
history; legacy read results can include large records; no mixed local workflow
approval; native Shopify management is outside the chat registry; navigation is manual.

Implementation order:
1. Add explicit Chat Only / Read Access / Full Agent policies, permission epochs,
   fresh-mode enforcement, bounded context/tool budgets and on-demand-only tests.
2. Add a compact schema-discovered typed gateway for all non-secret local domains.
   SQL is application-owned with bound values and allowlisted operations/fields.
   Local cross-reference/ranking/duplicates/health computations return bounded facts.
3. Extend existing proposals with reviewed mixed inventory/pricing/recognition/local
   listing workflows, batch controls, task controls, safe settings and native Shopify
   review delegation. No model approval, raw SQL, shell, taps or credential access.
4. Show tool traces and compact results, explicit navigation/photo-derivative handoff,
   named modes and persistent task/outcome links in the existing workspace.
5. Test permissions, rejection/atomicity/recovery, dataset access/cost bounds and zero
   idle model calls; run complete regression/build then lint, sign and publish APK.

No continuous observation/model requests. Database observers used for UI state do
not call a provider. Only Send and explicitly labelled model discovery/probes or
approved recognition tasks can request a provider. Photographs require an explicit
user handoff and Send; originals are not automatically attached. No silent billing
fallback. Physical Pixel/SIWC and real Shopify acceptance remain unverified; tests
must label mocked transport accurately. No production mutations or desktop changes.
