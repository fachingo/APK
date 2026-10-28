# Ordered implementation plan

1. Save directive and source-backed audit (done before implementation).
2. Port money, identifiers, validation, condition and QR vocabulary rules with
   deterministic fixtures generated from desktop functions.
3. Add Room 2→3 inventory/batch identity/audit/job migration and existing-capture
   backfill; reserve new physical identities with capture and keep original files.
4. Add phone inventory list/search/filter, full item editor, readiness validation,
   duplicate-copy warnings, explicit recognition application and real home counts.
5. Add protected photo-inclusive backup and preflight/transactional restore;
   identity-preserving portable and existing native/desktop import/export adapters.
6. Add GitHub Actions APK/test workflow, migration docs, completion matrix and
   deterministic integration/upgrade/recovery/100-item/300-photo stress checks.
7. Build/sign/verify and publish milestone A APK/source with accurate physical limits.
8. Complete the reviewed Shopify auth/API design and real dev-store test prerequisites
   before external integration writes. Then B, C, D and E in the directive's order.

No production inventory/listing edits, no desktop backend dependency, no invented
sales/pricing data, no embedded client secrets and no silent synchronization.

## Owner addition: SIWC and milestones A–D

Continue implementation through A–D with API-key functionality retained. SIWC has a
mandatory physical proof-of-concept gate. Official docs are currently network-blocked
and no eligible account/device is attached; see SIWC_INVESTIGATION.md. This must be
reported as blocked/unverified, never substituted with copied cookies or a mock login.
The owner subsequently supplied externally verified SIWC excerpts; the native prototype can proceed, with physical acceptance still mandatory. B/C/D work that can be built and tested locally may proceed while real Shopify/SIWC
external acceptance remains explicitly gated; do not claim those live workflows passed.

0.3.0 implements independent local C/D work and offline B listing preparation. See MILESTONE_CD.md for delivered scope and remaining live gates.
