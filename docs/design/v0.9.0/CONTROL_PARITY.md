# Per-control source parity audit

Baseline: commit `6fd40e12cf90af0493c4ec813ab6c3d402865aec` (v0.8.4). 491 static source/control records captured before edits. Each record below maps to the redesigned UI. Dynamic enum-driven controls, icons and fields are also covered by the grouped feature matrix and unchanged handlers. Source presence is a reachability aid, not a claim that every remote action was executed.

| Baseline component / line | Labels / information | New access | Verification |
|---|---|---|---|
| MainActivity.kt · MobileApp:138 | Save verified capture ZIPs to Files or share them. Your inventory remains usable on this phone. Open Settings → Backup & import for protected backups and inventory packages. | Transfers / root navigation / batch finish | Information retained / presentation revised |
| MainActivity.kt · MobileApp:142 | ${a.state.lowercase().replaceFirstChar { it.uppercase() }} · ${ | Transfers / root navigation / batch finish | Information retained / presentation revised |
| MainActivity.kt · MobileApp:143 | Above archive target · whole products kept together | Transfers / root navigation / batch finish | Information retained / presentation revised |
| MainActivity.kt · MobileApp:145 | Share; Save to Files; SHA-256 ${a.sha256.take(16)}… | Transfers / root navigation / batch finish | Existing handler and controls retained |
| MainActivity.kt · MobileApp:146 | Retry archive | Transfers / root navigation / batch finish | Existing handler and controls retained |
| MainActivity.kt · MobileApp:174 | Recovering your capture journal… | Transfers / root navigation / batch finish | Information retained / presentation revised |
| MainActivity.kt · MobileApp:179 | Finish this batch?; The open product will be finalized, including incomplete front/back sets. Archives will be verified in the background. Originals stay on this device. Inventory details and photo corrections remain available in Inventory.; Finish batch; Keep capturing | Transfers / root navigation / batch finish | Existing handler and controls retained |
| MainActivity.kt · StartBatchDialog:186 | A new capture session; One tape at a time. Capture, then Next.; Batch name; Location · optional; A1–J10 or Q1; Start capturing; Cancel | Capture → Start a batch | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:208 | SNAPIMS | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:230 | Your tapes, in focus; Allow camera access to begin. Photos stay on this device.; Allow camera | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:255 | Front/back incomplete · Next is still available | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:256 | ${info?.optString( | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:260 | %.1f× | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:263 | 1× | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:268 | Capturing… | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:272 | ${photoSize.label} ▾ | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:279 | Start a batch | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:283 | Rare | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:284 | Review | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:287 | ${ | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:297 | CAPTURE | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:298 | Retake last | Capture | Existing handler and controls retained |
| MainActivity.kt · CaptureScreen:302 | NEXT | Capture | Information retained / presentation revised |
| MainActivity.kt · CaptureScreen:309 | Capture location; A1–J10, Q1, or leave blank. Applies to this and following products.; Save | Capture | Existing handler and controls retained |
| MainActivity.kt · BarcodeDialog:318 | Product barcode | Capture / photograph history → barcode review | Information retained / presentation revised |
| MainActivity.kt · BarcodeDialog:319 | ${info.getString( | Capture / photograph history → barcode review | Information retained / presentation revised |
| MainActivity.kt · BarcodeDialog:321 | ${c.getString( | Capture / photograph history → barcode review | Existing handler and controls retained |
| MainActivity.kt · BarcodeDialog:322 | UPC / EAN · blank for no barcode | Capture / photograph history → barcode review | Existing handler and controls retained |
| MainActivity.kt · BarcodeDialog:323 | Rescan back photo | Capture / photograph history → barcode review | Existing handler and controls retained |
| MainActivity.kt · BarcodeDialog:324 | Confirm; Cancel | Capture / photograph history → barcode review | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:340 | Open production workflow | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:341 | New batch | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:344 | Finalize part | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:345 | Delete batch · move to Trash | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:346 | Recognize completed products | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:351 | Product %03d; ${group.location.ifEmpty {  | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:353 | Flags & notes | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:354 | Recognize | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:357 | ${recognitionLabel(recognition.provider)} · ${recognition.state} | Batch → Photographs & capture history | Information retained / presentation revised |
| MainActivity.kt · BatchesScreen:362 | ${barcode.optString( | Batch → Photographs & capture history | Information retained / presentation revised |
| MainActivity.kt · BatchesScreen:368 | Recover file | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:372 | Delete photo | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:375 | Restore last removed photo | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:388 | Recognize ${eligible.size} products? | Batch → Photographs & capture history | Information retained / presentation revised |
| MainActivity.kt · BatchesScreen:392 | Run recognition; Cancel | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:397 | ${p.role} · photo ${p.position + 1} | Batch → Photographs & capture history | Information retained / presentation revised |
| MainActivity.kt · BatchesScreen:399 | ${p.capturedAt}\n${ | Batch → Photographs & capture history | Information retained / presentation revised |
| MainActivity.kt · BatchesScreen:401 | Move to previous product | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:402 | Move to next product | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:404 | Delete photo · move to Trash | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · BatchesScreen:405 | Done | Batch → Photographs & capture history | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:416 | Backup & import; Transfers | Settings → grouped sections | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:417 | Tasks; AI workspace; Sold pricing; Shopify; Market database | Settings → grouped sections | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:418 | Trash · recover deleted records | Settings → grouped sections | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:419 | CAMERA | Settings → grouped sections | Information retained / presentation revised |
| MainActivity.kt · SettingsScreen:422 | Fast capture uses zero shutter lag where supported, with a low-latency fallback and JPEG 95%. Turn it off for JPEG 100% and quality priority. Flash, HDR and low light can add delay. Pinch the preview or use the zoom slider. | Settings → grouped sections | Information retained / presentation revised |
| MainActivity.kt · SettingsScreen:427 | RECOGNITION | Settings → grouped sections | Information retained / presentation revised |
| MainActivity.kt · SettingsScreen:432 | ARCHIVES & RETENTION | Settings → grouped sections | ARCHIVES & RETENTION → Layout/help prose revised; grouped feature contract preserved |
| MainActivity.kt · SettingsScreen:433 | Archive target · MB; 1–10,000 MB · whole products only; oversized products stay together | Settings → grouped sections | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:434 | Default location; A1–J10 or Q1, optional | Settings → grouped sections | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:435 | Choose an export destination each time using Save to Files. Existing capture ZIPs remain unchanged; make inventory and photo corrections in Inventory. Removing a photo keeps its source file. Deleted records can be recovered in Trash for seven days. Historical exports remain unchanged. No automatic cloud backup. Uninstalling Android apps removes private files: save and verify all ZIPs before uninstalling or clearing storage. | Settings → grouped sections | Information retained / presentation revised |
| MainActivity.kt · SettingsScreen:437 | Third-party notices & licenses | Settings → grouped sections | Existing handler and controls retained |
| MainActivity.kt · SettingsScreen:438 | SnapIMS Next 0.8.4 · production batch workflows | Settings → grouped sections | SnapIMS Next 0.8.4 · production batch workflows → Layout/help prose revised; grouped feature contract preserved |
| MainActivity.kt · NotesDialog:451 | Flags & notes | Capture / photograph history → Flags & notes | Information retained / presentation revised |
| MainActivity.kt · NotesDialog:452 | Rare; Review | Capture / photograph history → Flags & notes | Existing handler and controls retained |
| MainActivity.kt · NotesDialog:456 | Capture notes | Capture / photograph history → Flags & notes | Existing handler and controls retained |
| MainActivity.kt · NotesDialog:457 | Save; Cancel | Capture / photograph history → Flags & notes | Existing handler and controls retained |
| MainActivity.kt · NoticesDialog:465 | Third-party notices | Capture / batch / settings, same component | Information retained / presentation revised |
| MainActivity.kt · NoticesDialog:467 | $filename · page ${page + 1}/$pages | Capture / batch / settings, same component | Information retained / presentation revised |
| MainActivity.kt · NoticesDialog:470 | Previous | Capture / batch / settings, same component | Existing handler and controls retained |
| MainActivity.kt · NoticesDialog:471 | Next | Capture / batch / settings, same component | Existing handler and controls retained |
| MainActivity.kt · NoticesDialog:472 | Done | Capture / batch / settings, same component | Existing handler and controls retained |
| InventoryUi.kt · InventoryHome:30 | CANADA VHS | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:31 | Your collection,\nin your hands. | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:32 | Inventory stays on this phone, even offline. | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:43 | Open inventory | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryHome:44 | Review batches | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryHome:47 | Protect your collection | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:48 | Create a passphrase-protected backup with original photographs. | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:49 | Backup & import → | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:51 | ${sessions.count { it.finished }} finished batches · ${ | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryHome:52 | ${job.kind.lowercase().replaceFirstChar { it.uppercase() }} needs attention: ${job.error} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryScreen:85 | Bulk workflow · all eligible inventory | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryScreen:86 | Search title, barcode, SKU or location | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryScreen:99 | ${item.condition} · ${item.location.ifBlank {  | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryScreen:100 | ${list.size} photos · ${sessions.firstOrNull { it.id==group?.sessionId }?.name ?: item.batchId} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryScreen:103 | $${InventoryRules.priceText(item.priceCents)} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:130 | Tape details | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:136 | Save changes | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:139 | SKU = Item ID · permanent physical-copy identity | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:143 | Manage original photos, retakes & grouping | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:147 | Release year | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:148 | Location | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:152 | Capture notes: ${draft.captureNote} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:154 | ${duplicates.size} other copies share this title or barcode. They keep separate Item IDs and condition records. | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:156 | Price · CAD | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:157 | Discount · % | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:159 | Search market & approved prices | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:160 | Source: ${draft.priceSource.lowercase().replace('_',' ')}. Entering a price records a manual decision. | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:162 | Quantity | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:163 | Weight · grams | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:166 | Rare | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:167 | Review | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:170 | Ready for listing; Saved only after validation. Creates no Shopify product. | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:172 | • $it | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:174 | Recognize / review suggestions | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:176 | Confirmed suggestion: ${suggestion.acceptedTitle} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:177 | Use confirmed title in inventory | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:187 | Shopify: ${item.shopifyState}\nProduct: ${item.shopifyProductId.ifBlank {  | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:188 | Created ${item.createdAt}\nUpdated ${item.updatedAt}\nRevision ${item.revision} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:190 | ${if(showHistory)  | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:191 | ${audit.occurredAt.take(19)} · ${audit.action} | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:192 | Delete record · move to Trash | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · InventoryEditor:195 | ${if(item.archived)  | All inventory → product details; batch → product | Information retained / presentation revised |
| InventoryUi.kt · InventoryEditor:196 | Confirm; Cancel | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryUi.kt · ChoiceField:209 | $value ▾ | All inventory → product details; batch → product | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:32 | Original photographs | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:34 | Corrections update this inventory record. Previously exported ZIPs stay unchanged. Removed photos can be restored; original files are retained. | Product → Manage original photos | Information retained / presentation revised |
| InventoryPhotoUi.kt · InventoryPhotoManager:35 | Add photographs | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:39 | ${photo.role} · ${if(photo.deleted)  | Product → Manage original photos | Information retained / presentation revised |
| InventoryPhotoUi.kt · InventoryPhotoManager:41 | Restore photo | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:44 | Move earlier | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:45 | Move later | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:46 | Retake | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:49 | Change role | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:50 | Delete photo | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:52 | Move to another item | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:54 | ${target.itemId.takeLast(12)} · ${target.title.ifBlank {  | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryPhotoManager:58 | Recover retained file | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryItemCamera:83 | Allow Camera access in Capture first. | Product → Manage original photos | Information retained / presentation revised |
| InventoryPhotoUi.kt · InventoryItemCamera:87 | ${resolution.label} ▾ | Product → Manage original photos | Existing handler and controls retained |
| InventoryPhotoUi.kt · InventoryItemCamera:91 | %.1f× | Product → Manage original photos | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:113 | ← Back; Market database; Your offline pricing library | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:114 | $count sold observations; ${imports.size} recent imports · schema 1 · ${model.page.index}; Last import ${it.observedAt.take(19)} | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:115 | Search title, words or barcode; Save | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:116 | Local matching: SQLite full-text/prefix indexes, trigram spelling candidates and Apache Commons Text similarity. Match scores are heuristics, not probabilities or approved prices. | Market; Filters & tools; Optional AI assistance; candidate evidence | Local matching: SQLite full-text/prefix indexes, trigram spelling candidates and Apache Commons Text similarity. Match scores are heuristics, not probabilities or approved prices. → Layout/help prose revised; grouped feature contract preserved |
| MarketUi.kt · MarketDatabaseScreen:117 | Optional AI match ranking · separate model · only when I request it | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:118 | Match AI model · independent from photo recognition; Configure this provider's key in AI workspace Connections. No photos are sent for matching.; Review AI ranking request | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:122 | Historical sold evidence; Operator price history; Filters & tools | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:124 | Currency · blank = all; Evidence edition | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:125 | Year / title year | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:126 | Min · sale + shipping; Max · sale + shipping | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:127 | Sold from YYYY-MM-DD; Sold through YYYY-MM-DD | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:128 | Include superseded source observations; Source revisions are preserved. Recommendations use only eligible current records and keep currencies separate. | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:129 | Import CSV / SQLite; Export sold evidence; Verify integrity; Rebuild search | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:130 | Show prior approved asking-price hints (never sold evidence); Warn about evidence older than days | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:132 | Bring your market history; Import your Harvester CSV, supported SQLite export or SnapIMS market archive. Existing records stay searchable without a network connection.; Import market records | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:133 | Cancel | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:136 | ${it.itemId} · current CAD ${InventoryRules.priceText(it.priceCents)} · ${it.edition.ifBlank {; Recommend · CAD; USD evidence; Only conservative compatible sold matches support a recommendation. Approximate results and asking prices are labeled separately. | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:137 | Prior approved asking price; ${marketMoneyText(hint.priceCents,hint.currency)} · ${hint.createdAt.take(10)}; Operator decision, not a completed market sale.; Review reuse | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:138 | Exact, likely and possible results are retrieved together. Approximate titles are suggestions, not confirmed sold-price evidence. | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:140 | Your decisions are asking prices, not completed market sales. | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:141 | ${marketMoneyText(record.priceCents,record.currency)} · ${record.recordType}; ${record.itemId} · ${record.method}\n${record.createdAt.take(19)}; ${JSONArray(record.evidenceJson).length()} evidence references | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:142 | More price decisions | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:144 | ${group.samples} source sales · ${marketMoneyText(group.low,group.currency)} – ${marketMoneyText(group.high,group.currency)}; ${group.oldest.take(10)} – ${group.newest.take(10)}${if(group.edition.isNotBlank()) ; AI estimate ${(match.confidence*100).toInt()}% · ${match.reason}; ${when(group.matchTier) {; Review title from this match | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:145 | Load more | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:146 | No matching records. Try fewer words, another currency, or a barcode. | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:149 | Approve one AI match request; Send the current title and up to 20 compact candidate identities to ${model.matchProvider} / ${model.matchModel}. No photos or full database upload. This provider may charge API fees; there is no automatic fallback.; I authorize this one provider request and any applicable API charges.; Send ranking request; Cancel | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:150 | Review matched title; Current: ${item.title}\nCandidate: ${group.title}\n${group.currency} · ${group.samples} source sales; Confirmed inventory title · edit listing clutter out; Also use candidate edition '${group.edition}' and year ${group.year ?: ; This changes local metadata and clears readiness. It does not apply a price or publish anything.; Save reviewed title; Cancel | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:151 | Import preview; ${preview.filename}\n${preview.rows} source rows · ${preview.distinctRows} distinct observations\n${preview.format}; Existing evidence will be retained. Changed observations become preserved revisions.; Approve import; Cancel | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:152 | Choose inventory item; ${item.title.ifBlank {; Close | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:154 | ${group.samples} observations in ${group.currency}. Amounts include shipping. Grouping uses the complete normalized listing identity; no inferred film/edition merge. | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDatabaseScreen:156 | ${marketMoneyText(row.getLong(; Sale ${row.getString(; ${row.getString(; Barcode: ${row.getString(; Collected: ${row.getString(; Conflicting observation · excluded from statistics; Open source listing · requires internet | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:157 | More source listings | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:158 | Close | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDatabaseScreen:159 | Reuse an approved asking price?; This replaces ${target.title}'s current price with a prior operator asking price. It does not use sold-market statistics.; Approve replacing an existing manual price; Approve asking price; Cancel | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketPriceApproval:166 | Review evidence-based price | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketPriceApproval:168 | ${value.stats.count} compatible sold records\n${value.oldest.take(10)} to ${value.newest.take(10)}\nOldest ${marketEvidenceOldestAge(value.oldest)} days · newest ${marketEvidenceOldestAge(value.newest)} days; Median ${marketMoneyText(value.stats.medianCents,value.currency)}\nAverage ${marketMoneyText(value.stats.averageCents,value.currency)}\nRange ${marketMoneyText(value.stats.lowestCents,value.currency)} – ${marketMoneyText(value.stats.highestCents,value.currency)}\nIncludes original shipping charges. | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketPriceApproval:170 | Evidence is ${value.currency}, inventory is CAD. Enter your explicit CAD per ${value.currency} rate; SnapIMS has not fetched an exchange rate.; CAD per ${value.currency}; Rate effective date YYYY-MM-DD | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketPriceApproval:171 | Optional adjusted price · CAD; Approve replacing a manual price; Approve reuse if evidence is older than my configured age | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketPriceApproval:172 | Saves an operator-approved asking price and exact evidence references. It does not create a sold-market record or publish to Shopify. | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketPriceApproval:173 | Approve price; Cancel | Market; Filters & tools; Optional AI assistance; candidate evidence | Existing handler and controls retained |
| MarketUi.kt · MarketDistribution:179 | Eligible sold-price distribution · includes shipping | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| MarketUi.kt · MarketDistribution:180 | ${marketMoneyText(bin.startCents,currency)}–${marketMoneyText(bin.endCents,currency)} · ${bin.count} sales | Market; Filters & tools; Optional AI assistance; candidate evidence | Information retained / presentation revised |
| ProductionUi.kt · ProductionBatchScreen:73 | ← Photographs & capture history | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:74 | Delete batch · move to Trash | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:76 | ${if(label== | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:77 | $name verified $count | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:78 | ${selected.size} selected of ${records.size} | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:80 | Recognize selected | Inventory/batch → selected dock / More Actions / pricing rules | Recognize selected → Recognize |
| ProductionUi.kt · ProductionBatchScreen:81 | Review recognized titles | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:82 | Auto Match & Price | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:83 | Pricing rules | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:84 | Apply ${selectedResults.size} eligible prices | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:85 | Generate missing metadata · offline | Inventory/batch → selected dock / More Actions / pricing rules | Generate missing metadata · offline → Metadata |
| ProductionUi.kt · ProductionBatchScreen:86 | Offline matching and pricing use zero AI calls. Approximate candidates and insufficient evidence stay in review. Applying eligible prices is one batch action. | Inventory/batch → selected dock / More Actions / pricing rules | Offline matching and pricing use zero AI calls. Approximate candidates and insufficient evidence stay in review. Applying eligible prices is one batch action. → Layout/help prose revised; grouped feature contract preserved |
| ProductionUi.kt · ProductionBatchScreen:87 | Optional OpenAI assistance; Review ${content.size} missing descriptions | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:88 | Shopify; Refresh saved Shopify connection | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:91 | Intended sales channels · drafts remain unpublished | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:92 | Save selected channels as defaults | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:93 | Create Shopify Drafts (${selected.size}) | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:94 | Review batch publication; Create Drafts is your authorization to upload selected listing data and photos. Valid selected copies are submitted directly; invalid copies are skipped with reasons. Published products are never updated by this action. | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:100 | Skipped / needs attention; ${records.firstOrNull {it.groupId==id}?.itemId ?: id}: $reason | Inventory/batch → selected dock / More Actions / pricing rules | Information retained / presentation revised |
| ProductionUi.kt · ProductionBatchScreen:101 | Find selected batch items; Showing ${shown.size}; actions use the ${selected.size} checked copies even when a filter hides them. | Inventory/batch → selected dock / More Actions / pricing rules | Find selected batch items → Search title or SKU; Showing ${shown.size}; actions use the ${selected.size} checked copies even when a filter hides them. → Persistent selected count and exact selected IDs; filtering does not alter selection. |
| ProductionUi.kt · ProductionBatchScreen:103 | ${item.shopifyState} · CAD ${InventoryRules.priceText(item.priceCents)}; ${result.state} · ${price?.optInt( | Inventory/batch → selected dock / More Actions / pricing rules | ${item.shopifyState} · CAD ${InventoryRules.priceText(item.priceCents)} → StatusLabel + Unpriced/CAD amount; ${result.state} · ${price?.optInt( → Human result status, comparable count and suggested price |
| ProductionUi.kt · ProductionBatchScreen:104 | Open item; Evidence & explanation | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:105 | Review optional AI result · ${r.state} | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:106 | ${o.optString( | Inventory/batch → selected dock / More Actions / pricing rules | Information retained / presentation revised |
| ProductionUi.kt · ProductionBatchScreen:108 | Workflow jobs | Inventory/batch → selected dock / More Actions / pricing rules | Information retained / presentation revised |
| ProductionUi.kt · ProductionBatchScreen:109 | ${job.kind} · ${job.state}; View saved workflow results; Cancel remaining workflow | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:110 | Shopify ${job.kind} · ${job.state}; ${it.itemId}: ${it.error}; Reconcile / resume existing job | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:112 | Review batch descriptions; ${inventory.firstOrNull {it.groupId==r.groupId}?.itemId ?: r.groupId} → ${data.optString(; Apply reviewed missing descriptions; Cancel | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:113 | Saved workflow history; ${inventory.firstOrNull {it.groupId==r.groupId}?.itemId ?: r.groupId} · ${r.state} · ${value.optString(; Done | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:116 | Recognize ${selected.size} selected copies; Mode: ${recognitionLabel(vm.repo.recognitionMode())}. ${if(vm.repo.recognitionMode()==; Start selected recognition; Cancel | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:117 | Review selected recognized titles; ${records.firstOrNull {it.groupId==r.groupId}?.itemId ?: r.groupId}: ${r.title.ifBlank {; Apply the shown suggestions only where the existing title is blank. Existing operator titles, unknown suggestions and changed photos are protected.; Apply reviewed titles; Cancel | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionBatchScreen:119 | Saved evidence / result; ${data.optString(; Done | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · AutoPriceRulesDialog:124 | Offline Auto Match & Price rules; Prices use strictly compatible current SOLD records. Approximate identities are routed to review, never assumed confirmed. No model requests. | Inventory/batch → selected dock / More Actions / pricing rules | Information retained / presentation revised |
| ProductionUi.kt · AutoPriceRulesDialog:126 | Minimum compatible sales; Maximum historical evidence age · days | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · AutoPriceRulesDialog:127 | Optional CAD price floor; Optional CAD price ceiling | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · AutoPriceRulesDialog:128 | Adjustment · basis points (100 = 1%); Round to CAD cents · 1 = cent, 100 = dollar | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · AutoPriceRulesDialog:129 | USD → CAD rate · blank excludes USD; Rate effective date YYYY-MM-DD | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · AutoPriceRulesDialog:130 | Protect manual/operator prices; Automatically apply high-confidence prices within explicitly started jobs; TRIMMED_MEAN removes 10% from each tail when at least 10 sales remain. IQR excludes totals outside 1.5× interquartile range when at least 4 sales exist. Unknown source condition is disclosed; no condition premium or FX rate is invented. | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · AutoPriceRulesDialog:131 | Save pricing rules; Cancel | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionAiBudget:137 | Authorize optional AI batch; ${task.label} · ${choice.model}. One consent covers this exact selection, up to the chosen request count and estimated budget. Pricing runs independently offline. Candidate-only text is used; no photographs are transmitted by these helpers.; Maximum model requests; Estimated USD budget ceiling; Conservative configured-tariff reservation: ${maximum?.toPlainString() ?: ; Start authorized AI job; Cancel | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionEvidenceContent:145 | Sold evidence ${offset+1}–${minOf(offset+50,evidence.length())} of ${evidence.length()} | Inventory/batch → selected dock / More Actions / pricing rules | Information retained / presentation revised |
| ProductionUi.kt · ProductionEvidenceContent:146 | ${sale.title} · ${sale.soldAt.take(10)}\n${sale.currency} ${sale.soldDecimal} + shipping ${sale.shippingDecimal}${if(!sale.current) ; Open sold source · ${sale.sourceItemId} | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ProductionUi.kt · ProductionEvidenceContent:147 | Previous evidence; Next evidence | Inventory/batch → selected dock / More Actions / pricing rules | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:80 | ← Back; Shopify; Drafts and durable jobs; Listings & saved reviews | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:81 | Choose Direct Shopify Connection for your owner-operated phone, or an optional hosted broker. Inventory stays on this phone.  | Shopify → Connection & jobs | Information retained / presentation revised |
| ShopifyUi.kt · ShopifyScreen:82 | Direct Shopify Connection; Store domain · myshopify.com; Shopify Client ID; Shopify Client Secret; Save credentials & connect directly; Clear direct credentials; Open saved Shopify connection; Test saved direct connection | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:83 | Enable writes to my Shopify store · deliberate draft submission authorizes uploads | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:84 | Enable live publication controls · separate SUBMIT approval required | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:85 | Credentials stay encrypted on this phone. Approved jobs connect directly to Shopify. No broker or computer is required. | Shopify → Connection & jobs | Information retained / presentation revised |
| ShopifyUi.kt · ShopifyScreen:86 | Optional legacy connection | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:88 | Hosted broker HTTPS origin | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:89 | One-use pairing code from broker owner; Pair this device | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:90 | Authorize in browser; Test connection | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:91 | Revoke device; Forget locally | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:93 | Test connection | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:99 | ${health.getString(; Scopes: ${health.getJSONArray( | Shopify → Connection & jobs | Information retained / presentation revised |
| ShopifyUi.kt · ShopifyScreen:101 | Inventory location | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:103 | Sales channel for separately approved publication | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:105 | Select inventory; New uploads default to DRAFT. Existing linked products are protected. One item represents one physical copy with stock 1. | Shopify → Connection & jobs | Information retained / presentation revised |
| ShopifyUi.kt · ShopifyScreen:106 | CAD ${InventoryRules.priceText(item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)})} · ${item.weightGrams} g · stock ${item.quantity}; ${if(item.ready)  | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:107 | Create Shopify Drafts authorizes sending selected listing data and photos. Invalid records are skipped with reasons.; Create Shopify Drafts (${selected.size}); Optional listing review | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:108 | Review live publication | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:109 | Publishing jobs; A paused or failed job can leave a remote draft. Retrying reconciles saved identities. Android work completion alone does not prove Shopify completion. | Shopify → Connection & jobs | Information retained / presentation revised |
| ShopifyUi.kt · ShopifyScreen:110 | ${job.kind} · ${job.state}; ${job.shop}\n${job.id}; Delete local job | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:111 | Delete local draft · ${entry.itemId}; ${entry.itemId}: ${entry.state} · ${entry.step} | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyUi.kt · ShopifyScreen:112 | Pause; Reconcile / resume; Cancel remaining | Shopify → Connection & jobs | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyReviewDialog:34 | ${health.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:35 | Location: ${p.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:36 | Review ${row.state.lowercase()}. ${if(row.state== | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:39 | Publish ${p.getJSONArray( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:41 | ${product.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:43 | Current: ${before.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:45 | Only this reviewed product and status/channel are changed. A remote change after review stops the job. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:47 | ${before.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:51 | Remote SKU: ${variant.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:52 | ${before.getJSONObject( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:53 | Shopify: ${before.optString(key)}; Phone → ${patch.getString(key)} | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:56 | Publication: ${p.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:58 | SKU ${item.itemId} | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:59 | CAD ${InventoryRules.priceText(item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)})} · ${item.weightGrams} g · stock ${item.quantity}; ${item.vendor} · ${item.productType}; Tags: ${item.tags}; Barcode: ${items.getJSONObject(i).optString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:62 | ${photos.length()} original-linked photographs in reviewed order | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:65 | One-use approval · local revision/photo binding · changes after review require another review. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyReviewDialog:66 | SUBMIT | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyReviewDialog:71 | Reject | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyReviewDialog:72 | Close | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyReviewDialog:73 | Approve once | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:94 | ← Uploads & connection | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:95 | Connect and test your saved Shopify connection in Settings. Local inventory remains available offline. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:96 | Search listing title | Shopify → Listings → inspection / saved reviews | Search listing title → Search title or SKU |
| ShopifyManagementUi.kt · ShopifyListingsScreen:98 | Search Shopify | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:102 | ${h.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:103 | Inventory location for inspection | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:106 | ${model.catalogQuery.ifBlank {; Results are a read snapshot. Preparing a review fetches the listing again. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:110 | ${product.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:111 | Approved locally · publishing still requires a separate review | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:113 | Delete draft from app | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:114 | Inspect listing | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:116 | Next 20 listings | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:119 | Listing details; ${detail.getString( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:120 | Refresh this listing; SKU ${v.getString( | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:121 | ${detail.getJSONObject( | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:124 | Workflow: Draft → Approved locally → Active → Live on selected channel | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:125 | Review local approval | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:129 | Save default channels | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:130 | Review publication to selected channels | Shopify → Listings → inspection / saved reviews | Review publication to selected channels → Publish to selected channels |
| ShopifyManagementUi.kt · ShopifyListingsScreen:131 | Online Store publication plus Active status is required for website availability. Changes always require the exact review and SUBMIT. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:133 | Find local copy by title or Item ID; Showing up to 30 matching copies. Refine the search to find a specific Item ID. | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:134 | ${item.title.ifBlank { | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:136 | Selected: ${selectedItem.itemId}; Linking requires this exact SKU and both existing SnapIMS identity markers. No marker is added to an unrelated product. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:137 | Prepare link review | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:138 | Refresh the Shopify connection before selecting update fields. | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:140 | Select metadata fields to update | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:141 | Shopify: ${detail.optString(key)}\nPhone: $value | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:142 | Prepare metadata review | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| ShopifyManagementUi.kt · ShopifyListingsScreen:146 | Saved reviews | Shopify → Listings → inspection / saved reviews | Information retained / presentation revised |
| ShopifyManagementUi.kt · ShopifyListingsScreen:147 | ${shopifyReviewTitle(row.kind)} · ${row.state}; ${c.optString(; Job ${result.optString( | Shopify → Listings → inspection / saved reviews | Existing handler and controls retained |
| NextUi.kt · PricingScreen:45 | ← Back | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:46 | Search Market database | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:47 | Allow reviewed reuse after online cache expiry | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:48 | Import a consistent SQLite export from the desktop pricing module. Valid CAD evidence stays available offline; older evidence requires reviewed historical reuse. Prices include sold price and shipping; no AI-generated prices. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:49 | Import pricing SQLite; ${evidence.size} collections · ${evidence.count {it.fresh()}} fresh | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:53 | Review a batch of prices | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:54 | Find inventory title | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:59 | ${item.priceCents?.let {money(it)} ?:  | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:60 | Review ${matches.size} collections | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:62 | No sold evidence for this exact title. Set a manual price in Inventory. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:64 | ${e.status} · ${if(e.fresh())  | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:65 | Collected ${e.collectedAt}\nExpires ${e.expiresAt} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:66 | ${it.count} valid matches · average ${money(it.averageCents)} · median ${money(it.medianCents)}\nRange ${money(it.lowestCents)}–${money(it.highestCents)} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:67 | ${c.title}\n${c.sold} + ${c.shipping} shipping · ${c.soldDate}\n${c.url} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:68 | Choose and review price | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:76 | Apply reviewed sold price? | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:77 | ${item.title}\n${item.itemId}\n${stats.count} valid comparisons. This clears listing readiness. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:78 | Median ${money(stats.medianCents)}; Average ${money(stats.averageCents)} | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:79 | Replace my manual price | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:80 | Apply; Cancel | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:82 | Review batch prices | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:83 | Up to 200 exact-title CAD matches (older records require historical reuse enabled). Manual prices are excluded. Every selected change clears readiness; changed records stop the entire batch. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:84 | Median; Average | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:85 | No eligible fresh matches. Import evidence or review manual prices individually. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · PricingScreen:86 | ${item.itemId} · ${money(if(method== | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · PricingScreen:87 | Apply ${selectedBulk.size}; Cancel | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AiWorkspaceScreen:100 | Dismiss | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AiWorkspaceScreen:101 | Dismiss | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AiWorkspaceScreen:106 | ← Back | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AiWorkspaceScreen:107 | New chat; Connections; Tasks | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AiWorkspaceScreen:108 | Search chats and messages | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AiWorkspaceScreen:109 | ${c.provider} · ${c.model} | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:126 | ← Conversations | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:130 | Experimental official SIWC preview. Eligible Plus/Pro accounts only. Native Pixel compatibility is unverified until sign-in, discovery and streaming all pass on the device. Uses the system browser and an HTTP listener bound to 127.0.0.1; no mandatory home server. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ConnectionsPanel:132 | Continue with ChatGPT | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:133 | Cancel sign-in | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:135 | Usage totals and remaining plan allowance are not supplied by this preview contract. A plan-limit error stops the request. API-key mode is never selected automatically. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ConnectionsPanel:136 | Check connection & discover models | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:137 | Disconnect locally; Forget account | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:138 | Device proof log; Physical acceptance requires LISTENER → SIGNED_IN → MODELS_DISCOVERED → STREAM_COMPLETED on a Pixel. Host tests do not establish this. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ConnectionsPanel:139 | ${p.stage} · ${if(p.physicalPixel)  | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ConnectionsPanel:140 | Optional on-device OpenAI-compatible server at http://127.0.0.1:8080/v1. Start a compatible local-model Android app first. SnapIMS does not download or run a model itself. No off-device server is required for inventory. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ConnectionsPanel:142 | Provider API key | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:143 | Save securely; Remove key | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:144 | Keys are encrypted with Android Keystore and excluded from backup/export. Free tiers and :free models remain subject to provider limits. SnapIMS does not silently choose another provider. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ConnectionsPanel:146 | Discover models | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ConnectionsPanel:148 | ${model.displayName}\n${model.model} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · NewChatDialog:157 | New conversation | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · NewChatDialog:160 | Use saved chat model; Use saved agent model | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · NewChatDialog:162 | Filter model catalog | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · NewChatDialog:164 | ${m.model}\nTools: ${m.supportsTools ?:  | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · NewChatDialog:168 | Optional capability tests send synthetic content using this connection. Plan usage or API charges apply. A successful test enables that capability; no inventory is sent. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · NewChatDialog:169 | Test $kind | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · NewChatDialog:172 | Conversation instructions · optional | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · NewChatDialog:175 | Create; Cancel | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:188 | ← Chats; Export; Delete | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:189 | ${chat.provider} · ${chat.model} · ${chat.authMode} | Global AI workspace → conversation / Connections | ${chat.provider} · ${chat.model} · ${chat.authMode} → Layout/help prose revised; grouped feature contract preserved |
| NextUi.kt · ChatPanel:192 | Tools · ${traces.count {it.state== | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:193 | Budget: ${if(policy.contextChars==12000) ; Economy · smaller context, 4 rounds; Standard · 8 rounds | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:197 | ${t.tool} · ${t.state}; ${t.durationMs} ms · ${t.resultChars} characters\n${t.summary} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:199 | ${m.role.uppercase()} · ${m.status} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:201 | ${JSONArray(m.attachmentsJson).length()} attachments · included in backup | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:202 | ${JSONArray(m.toolCallsJson).length()} local tool requests | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:204 | Tokens ${m.inputTokens ?:  | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:205 | Cached input tokens: ${JSONObject(m.usageJson).getLong( | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:207 | ${approvalTitle(p.action)} · ${p.state}; Review proposed change; Open approved task | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:209 | ${a.getString( | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:210 | These attachments leave the device only when you press Send. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · ChatPanel:211 | Message | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:213 | Attach | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:214 | Stop | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:215 | Send | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · ChatPanel:219 | Delete this local conversation?; Its messages and approvals will be deleted. Approved tasks remain in Tasks. Original inventory and photographs are retained. Export the conversation first if needed.; Delete; Cancel | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · BusinessDefaultsSettings:228 | INVENTORY DEFAULTS | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · BusinessDefaultsSettings:229 | Applied to new physical copies. Existing records keep their reviewed values. | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · BusinessDefaultsSettings:230 | Default vendor | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · BusinessDefaultsSettings:231 | Default product type | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · BusinessDefaultsSettings:232 | Default shipping weight · grams | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AgentResult:249 | Attach compressed inventory photo | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AgentResult:250 | Open $screen | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AgentResult:255 | ${entries.length()} returned${if(json.has( | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| NextUi.kt · AgentResult:257 | Open copy | Global AI workspace → conversation / Connections | Existing handler and controls retained |
| NextUi.kt · AgentResult:259 | $k: ${json.get(k).toString().take(300)} | Global AI workspace → conversation / Connections | Information retained / presentation revised |
| AiTaskModels.kt · parse:45 | 7 | Settings → AI Models & Tasks | Information retained / presentation revised |
| AiTaskModels.kt · AiTaskModelsSettings:59 | AI Models & Tasks | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:61 | AI Models & Tasks; OpenAI API models only here. Offline matching/pricing never uses a model. Existing free/local/chat connections remain available in AI workspace. | Settings → AI Models & Tasks | Information retained / presentation revised |
| AiTaskModels.kt · AiTaskModelsSettings:63 | OpenAI model ID | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:64 | Discover OpenAI models | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:67 | Maximum output tokens for authorized task requests | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:68 | Verified input USD / million tokens · optional | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:69 | Verified output USD / million tokens · optional | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:70 | Enter current verified prices for batch budget estimates. Prices are operator configuration, not a fetched tariff. Unknown costs remain unknown. Agent tool support can be tested separately in AI workspace Connections. | Settings → AI Models & Tasks | Information retained / presentation revised |
| AiTaskModels.kt · AiTaskModelsSettings:71 | Save model; Use default | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:72 | Review compatibility test; Done | Settings → AI Models & Tasks | Existing handler and controls retained |
| AiTaskModels.kt · AiTaskModelsSettings:74 | Authorize one OpenAI compatibility request?; ${choice.model} · synthetic ${if(selected?.images==true) ; Run test; Cancel | Settings → AI Models & Tasks | Existing handler and controls retained |
| RecognitionUi.kt · RecognitionSettings:44 | Recognition | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionSettings:56 | Recognition runs from Batches after capture; switching modes cancels unfinished jobs from the previous mode. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionSettings:61 | Remove key | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · RecognitionSettings:63 | OpenAI model ID | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · RecognitionSettings:64 | Default: gpt-4.1-mini. Requires image input, Responses and structured output. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionSettings:77 | Choose model | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · RecognitionSettings:82 | Available model IDs | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionSettings:84 | The Models API does not report image capabilities. Choose a vision model; incompatible requests produce a clear error. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionSettings:87 | Done | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · SecureKeyDialog:93 | OpenAI API key | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · SecureKeyDialog:94 | Stored encrypted on this device. It is never included in exports or source code. Enter an API key, not a ChatGPT password. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · SecureKeyDialog:95 | Paste API key | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · SecureKeyDialog:99 | Save securely; Cancel | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · RecognitionDialog:110 | Product %03d · recognition | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:112 | Mode: ${recognitionLabel(repo.recognitionMode())} | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:113 | Run sends up to 3 downsized photos to OpenAI; API charges apply. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:115 | ${latest.state} · ${latest.model} | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:116 | Waits for an internet connection. Capture still works offline. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:117 | Photos changed since this result. Run recognition again before confirming. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:123 | Barcode candidates · verify before use | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:133 | • ${warnings.optString(j)} | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:137 | Visible text · tap a line to edit it as the title | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:140 | API tokens: ${usage.optLong( | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:142 | Verified printed title | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| RecognitionUi.kt · RecognitionDialog:145 | Confirmed: ${latest.acceptedTitle} | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:155 | Share results JSON | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:156 | Run recognition when you are ready. Camera capture never waits for it. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:157 | Existing ZIP archives stay unchanged. Newly available results can be shared separately. | Batch Recognize; product recognition; Settings → Recognition & AI | Information retained / presentation revised |
| RecognitionUi.kt · RecognitionDialog:161 | Done | Batch Recognize; product recognition; Settings → Recognition & AI | Existing handler and controls retained |
| CameraSettingsUi.kt · ResolutionSettings:13 | Photo resolution | Settings → Camera & capture | Information retained / presentation revised |
| CameraSettingsUi.kt · ResolutionSettings:15 | ${selected.label} ▾ | Settings → Camera & capture | Existing handler and controls retained |
| CameraSettingsUi.kt · ResolutionSettings:22 | 3 MP is the new default. Smaller sizes save faster but retain less tiny text and barcode detail. The camera selects the closest supported size, shown in the preview. Originals retain their camera JPEG and EXIF. | Settings → Camera & capture | Information retained / presentation revised |
| BackupUi.kt · share:75 | ← Settings | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:76 | A protected backup includes inventory, audit history, capture sessions, original photos and archive records. API keys are excluded. Keep the passphrase somewhere safe; it cannot be recovered. | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:77 | Create protected backup | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:78 | Restore protected backup | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:81 | Passphrase · 12+ characters | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:82 | Repeat passphrase | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:83 | Restore merges new records. Different existing records/IDs are rejected; your current database and originals are never replaced. | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:97 | Cancel | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:99 | Portable exports | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:100 | Portable ZIPs include photos and inventory without encryption for interchange. CSV uses SnapIMS Item IDs and column names. Use protected backup for confidential storage. | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:102 | Photo ZIP | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:103 | Inventory CSV | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:105 | Import inventory / capture ZIP | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:106 | Supports native SnapIMS capture ZIPs, this app's portable inventory packages, and identity-preserving desktop exports from the included read-only export utility. | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:107 | Working on this phone. Keep the app open until completion.; Cancel current transfer | More → Backup & import | Existing handler and controls retained |
| BackupUi.kt · share:108 | Recent operations | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:110 | ${job.kind.lowercase().replaceFirstChar { it.uppercase() }} · ${job.state.lowercase().replace('_',' ')} | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:111 | ${job.completed} / ${job.total} | More → Backup & import | Information retained / presentation revised |
| BackupUi.kt · share:116 | Save to Files; Share | More → Backup & import | Existing handler and controls retained |
| TrashUi.kt · TrashScreen:17 | ← Settings | More → Trash; Settings → Trash | Existing handler and controls retained |
| TrashUi.kt · TrashScreen:18 | Deletion only affects SnapIMS. Shopify products stay unchanged. Recovery does not restart uploads or recognition. Batches recover as finished batches. | More → Trash; Settings → Trash | Information retained / presentation revised |
| TrashUi.kt · TrashScreen:20 | Trash is empty | More → Trash; Settings → Trash | Information retained / presentation revised |
| TrashUi.kt · TrashScreen:21 | ${entry.kind.lowercase()} · recover until ${Instant.ofEpochMilli(entry.expiresAt)}; Recover | More → Trash; Settings → Trash | Existing handler and controls retained |
| TasksUi.kt · run:39 | ← Back | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:40 | New inventory review task; AI proposals are reviewed in their conversation. Recognition needs printed-title confirmation. Shopify completion requires verified server results. | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:46 | ${status?.state ?: row.state} · ${status?.done ?: 0}/${status?.total ?: JSONArray(row.groupIdsJson).length()} | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:50 | Review ${item?.title?.ifBlank { | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:52 | ${job.optString( | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:54 | ${o.optString( | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:56 | ${p.getString( | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:57 | Open batch | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:58 | Open Shopify jobs & server verification | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:59 | Retry scheduling existing jobs | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:60 | Mark reviewed task complete | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:61 | Restored task is paused. Review inventory and request a fresh proposal; external requests will not restart automatically. | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:62 | Cancel remaining work | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:69 | Select physical copies | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:70 | ${selected.size}/50 selected · offline review only | More → Tasks; dashboard → task history | Information retained / presentation revised |
| TasksUi.kt · run:71 | Search title or SKU | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:72 | ${item.title.ifBlank { | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:75 | Cancel | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:76 | Create review task | More → Tasks; dashboard → task history | Existing handler and controls retained |
| TasksUi.kt · run:82 | Cancel remaining work?; Completed recognition results, prices, photographs and Shopify drafts remain available. An already-sent provider request can still consume usage. This does not delete remote products.; Cancel remaining work; Keep task | More → Tasks; dashboard → task history | Existing handler and controls retained |
| AgentApprovalDialog.kt · AgentApprovalDialog:62 | ${config.getString(; Results remain suggestions. Confirm each printed title before applying it to inventory. | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:63 | Store: ${health?.optString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:64 | Requested status/channel action: ${payload.optString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:65 | Target Shopify product: ${remote.optString(; Selected operation: ${if(row.action== | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:67 | ${payload.getString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:68 | ${payload.getString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:70 | Sold evidence is in $currency. Enter your own CAD conversion rate; no exchange rate is fetched. | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:71 | CAD per 1 $currency | AI workspace → pending proposal | Existing handler and controls retained |
| AgentApprovalDialog.kt · AgentApprovalDialog:72 | Rate date · YYYY-MM-DD | AI workspace → pending proposal | Existing handler and controls retained |
| AgentApprovalDialog.kt · AgentApprovalDialog:76 | SKU ${item.getString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:79 | Quantity ${item.optInt( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:84 | Current base: CAD ${InventoryRules.priceText(item.getLong( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:90 | $selectedMethod: $currency ${InventoryRules.priceText(cents)} · ${r.getInt( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:91 | Sales: ${r.getString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:93 | New base: ${cad?.let { | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:94 | Existing discount: ${BigDecimal(item.optInt( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:99 | SKU ${l.optString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:100 | Quantity ${l.optInt( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:102 | Tags:  | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:103 | Upload ${photos.length()} photographs:  | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:105 | SKU ${payload.getString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:106 | Current base: CAD ${InventoryRules.priceText(payload.getLong( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:107 | ${e.getInt( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:109 | Confirmed title → ${payload.getString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:110 | Saved sold evidence · ${payload.optString( | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:112 | Optional adjusted base price · CAD; Leave empty to use the displayed recommendation. | AI workspace → pending proposal | Existing handler and controls retained |
| AgentApprovalDialog.kt · AgentApprovalDialog:118 | Type SUBMIT for live publication | AI workspace → pending proposal | Existing handler and controls retained |
| AgentApprovalDialog.kt · AgentApprovalDialog:119 | Approval applies once to this exact selection. Completed external operations remain recorded in Tasks. | AI workspace → pending proposal | Information retained / presentation revised |
| AgentApprovalDialog.kt · AgentApprovalDialog:124 | Reject | AI workspace → pending proposal | Existing handler and controls retained |
| AgentApprovalDialog.kt · AgentApprovalDialog:125 | Close | AI workspace → pending proposal | Existing handler and controls retained |
| AgentApprovalDialog.kt · ReviewPatch:139 | Before: ${if(before.has(key)) before.get(key) else ; After: ${patch.get(key)} | AI workspace → pending proposal | Information retained / presentation revised |
