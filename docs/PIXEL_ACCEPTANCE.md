# Physical Pixel acceptance — required before production

Status: NOT RUN. This cloud workspace has no physical Pixel and no /dev/kvm.
Host tests and a synthetic 100-product/300-image import do not substitute for the
following sustained physical capture run. Record Pixel model, Android build, APK
SHA-256, free storage, lighting, actual capture dimensions/EXIF, timing and failures.

1. Install the acceptance APK on a Pixel without production captures. Enable
   airplane mode and do not configure an API key. Grant Camera access. Capture
   front/back/extras for 100 actual tapes (at least 300 photographs). Include
   duplicate UPCs on separate physical copies, missing barcodes, multiple barcodes,
   UPC-E and EAN-13 with a leading zero, and a group missing its back photograph.
2. Confirm Capture never creates a group boundary on a time gap. Next always does.
   Exercise Undo Next, retake, removal/restore, role changes, reorder, moving a
   photograph to a neighboring group, Rare/Review and persistent locations.
   Confirm operations blocked by an in-flight shutter explain why.
3. Force-stop immediately after tapping Capture, after save feedback, during Next,
   while archiving and immediately after archive rename. Restart the app. Every
   acknowledged saved photo must be present and hash-correct in the exact group.
   Incomplete pending writes must remain visible as errors. Confirm retry/recovery
   retains suspect outputs and never silently deletes source files.
4. Compare photographs of the same objects, lens and lighting against the native
   Pixel Camera. Measure resolution, autofocus, orientation, detail, blur, glare,
   dynamic range, capture latency and sustained throughput. CameraX's maximum JPEG
   settings do not establish Pixel Camera feature parity. Test rear/front switch,
   autofocus/tap focus, exposure, flash, torch, available HDR, rotation and volume
   shutter. Include low light and rapidly repeated captures. Record unsupported
   combinations and thermal behavior.
5. Run a representative barcode fixture set against both bundled ML Kit and ZXing
   before final decoder acceptance. Record successful, incorrect, ambiguous and
   missed results by symbology and image size. The current decoder is ML Kit; no
   measured superiority claim has been made. Test manual corrections/check digits
   and rescanning the back photo. Verify original bytes remain unchanged.
6. With actual data near the default 500 MB target, confirm automatic rollover
   keeps whole products together. Test one oversized group; it must be exported
   alone. Use a smaller Settings threshold to repeat quickly, but also exercise
   one real 500 MB boundary. Continue capturing while archive generation runs.
7. Exercise low-storage conditions on a disposable device/test profile. Confirm
   warnings below 1 GB and refusal below 100 MB before shutter reservation. Simulate
   storage exhaustion mid-write/export; errors must be actionable and existing
   originals must survive. Do not fill a production user's phone to test this.
8. Finish Batch, share/save every ZIP to a workstation, compare archive SHA-256,
   inspect all manifest photo hashes/roles/order/counts and use the CLI importer
   against a disposable SnapIMS data root. Import twice: actual desktop IDs must
   be identical and item count unchanged. Check duplicate physical copies remain
   distinct. Verify existing QR-based import still passes its regression suite.
9. Install a higher-version APK signed with the same key using an update (never an
   uninstall). Confirm all sessions, capture-group IDs, originals and archives
   remain present. Test database migrations before shipping a schema change.
10. On a connected test device run `./gradlew connectedDebugAndroidTest` for the
    non-destructive navigation test. Record the actual runner results separately
    from host/JVM tests. The test must not clear data or capture into live batches.

Wireless transfer failure/retry, authentication/pairing, import receipts on Android,
OCR/OpenAI recovery and expanded AI capabilities need their own milestone 2/3 tests.
No such network endpoint or persistent agent service is enabled in this build.

If any acknowledged saved photograph is missing, a group boundary moves, an import
duplicates physical inventory, or an archive silently corrupts, stop acceptance.
Keep originals, Room journal and suspect ZIPs, and reproduce before production use.


## 0.1.2 camera acceptance

- Update over 0.1.1 with unfinished captures and a saved API key; verify retention.
- Capture at each size on rear/front, compare actual JPEG dimensions and file sizes.
  Confirm changing size affects future photos and never rewrites old ones.
- In bright light with flash/HDR off, measure press-to-exposure and shot-to-shot
  timing for fast capture vs quality mode. Repeat in low light and with flash/HDR.
  Record phone model, OS and selected resolution; do not infer timing from host tests.
- Capture three photos rapidly while saving, then Next; verify exact IDs, order,
  FRONT/BACK/ADDITIONAL and no crossed product boundary. Interrupt during saving and
  relaunch; verify originals and journal recover without duplicates.
- Pinch and use zoom slider/1× reset, try both lens limits, then tap focus. Verify
  pinch never focuses accidentally and zoom survives portrait/landscape rotation.
- Rotate both directions before, during and after capture. Shutter/Next/resolution
  must remain reachable, app screen must remain selected, long notes/new-batch/API-key
  dialogs must scroll with reachable confirmation/cancel buttons, and exported JPEG EXIF
  must orient correctly. Repeat in split-screen and with large accessibility text.
- Photograph real UPC-A/E and EAN codes at ordinary framing, small near every corner,
  and multiple distinct codes. Rescan old photographs. Compare v0.1.1 vs v0.1.2
  success rates; include blurry/glare samples and retain ambiguity/manual correction.
