package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.*

internal const val GUIDED_KIND="GUIDED_BATCH"
internal suspend fun CaptureRepository.saveGuidedManualPrice(reviewed:InventoryRow,cents:Long):InventoryRow=mutate {
 require(cents in 1..InventoryRules.maxPriceCents)
 val saved=saveInventoryLocked(reviewed.copy(priceCents=cents,priceSource="MANUAL",priceEvidenceJson="{}"),reviewed.revision)
 if(reviewed.priceCents==cents) recordPriceDecision(saved,"MANUAL_ESTIMATE","MANUAL")
 saved
}
internal fun guidedPhase(row:ProductionItemRow?)=row?.let {runCatching {JSONObject(it.resultJson).optString("phase","NOT_STARTED")}.getOrDefault("NOT_STARTED")} ?: "NOT_STARTED"
internal suspend fun CaptureRepository.startGuidedBatch(sessionId:String,ids:Set<String>,retry:Set<String> = emptySet()):String {
 require(ids.size in 1..2000)
 if(retry.isNotEmpty()) preferences.edit().remove("fxLastAttempt").commit()
 val job=mutate {
  val visible=journal.inventoryDao().items().filter {!it.archived}.associateBy {it.groupId}
  check(ids.all {it in visible && dao.group(it).sessionId==sessionId}) {"Batch selection changed; refresh the batch"}
  val d=journal.productionDao();val old=d.guided(sessionId)
  val job=old ?: ProductionJobRow(kind=GUIDED_KIND,optionsJson=JSONObject().put("sessionId",sessionId).put("recognition","OFFLINE_OCR").toString()).also {d.insert(it)}
  val existing=d.items(job.id).associateBy {it.groupId}
  for(id in ids) {
   val prior=existing[id]
   if(prior==null) d.insert(ProductionItemRow(jobId=job.id,groupId=id,revision=visible.getValue(id).revision))
   else if(id in retry && prior.state!="CANCELLED") {
    val data=JSONObject(prior.resultJson);data.remove("recognitionId");data.remove("priceResultId");data.remove("priceJobId");data.remove("deferred");data.remove("phase");data.put("retryRecognition",true);data.put("retryPrice",true)
    d.update(prior.copy(state="QUEUED",error="",resultJson=data.toString()))
   }
  }
  d.update(job.copy(state=if(job.id in activeProduction) "RUNNING" else "QUEUED",error="",updatedAt=System.currentTimeMillis()))
  preferences.edit().putBoolean("guidedEnabled.$sessionId",true).commit();job
 }
 enqueueProduction(job.id);return job.id
}
/** Capture closes a physical copy explicitly. This hook never observes unrelated database changes. */
internal suspend fun CaptureRepository.advanceCapturedBatch(sessionId:String) {
 if(!preferences.getBoolean("guidedEnabled.$sessionId",true)) return
 val groups=dao.groups(sessionId).filter {it.closed}.map {it.id}.toSet()
 val ids=journal.inventoryDao().items().filter {!it.archived && it.groupId in groups}.map {it.groupId}.toSet()
 if(ids.isNotEmpty()) startGuidedBatch(sessionId,ids)
}
internal suspend fun CaptureRepository.deferGuided(groupId:String,deferred:Boolean=true) {
 mutate {val d=journal.productionDao();val j=d.guided(dao.group(groupId).sessionId) ?: error("Start processing first");val row=d.items(j.id).first {it.groupId==groupId};check(journal.shopifyDao().unfinishedForGroup(groupId).isEmpty()) {"Pause Shopify work before deferring this copy"};val data=JSONObject(row.resultJson).put("deferred",deferred).put("phase",if(deferred) "DEFERRED" else "NOT_STARTED");d.update(row.copy(state=if(deferred) "SKIPPED" else "QUEUED",resultJson=data.toString(),error=""))}
}
internal suspend fun CaptureRepository.approveGuidedListings(ids:Set<String>):Pair<Int,Map<String,String>> {
 require(ids.size in 1..2000);var applied=0;val failures=linkedMapOf<String,String>()
 for(id in ids) try {mutate {val item=journal.inventoryDao().items().firstOrNull {it.groupId==id && !it.archived} ?: error("Copy is unavailable");check(journal.shopifyDao().unfinishedForGroup(id).isEmpty()) {"Existing Shopify operation must finish first"};val errors=shopifyProblems(item.copy(ready=true),dao.photos(id).filter {!it.deleted});check(errors.isEmpty()) {errors.joinToString(". ")};if(!item.ready) saveInventoryLocked(item.copy(ready=true),item.revision)};applied++} catch(e:CancellationException) {throw e} catch(e:Exception) {failures[id]=e.message.orEmpty()}
 return applied to failures
}
internal data class LocalIdentification(val title:String?,val reason:String,val candidates:JSONArray)
internal fun recognitionBarcode(result:JSONObject):String {
 val values=result.optJSONArray("barcode_candidates") ?: return ""
 return (0 until values.length()).map {values.getJSONObject(it)}.filter {it.optBoolean("valid")}.map {it.optString("normalized")}.filter {it.length in setOf(8,12,13,14) && it.all(Char::isDigit) && Barcodes.checksum(it)}.distinct().singleOrNull().orEmpty()
}
internal suspend fun CaptureRepository.identifyGuided(item:InventoryRow,result:JSONObject):LocalIdentification {
 val lines=result.optJSONArray("text_lines") ?: JSONArray();val supplied=(listOf(result.optString("title").takeUnless {it.equals("UNKNOWN",true)}.orEmpty())+(0 until lines.length()).map {lines.optString(it)}).map {it.trim()}.filter {filmWords(it).isNotEmpty() && it.length<=240}.distinct().take(25)
 val candidates=linkedMapOf<String,MarketGroup>();val exact=linkedMapOf<String,Pair<String,MarketGroup>>()
 val upc=item.barcode.takeIf {it.isNotBlank()} ?: recognitionBarcode(result)
 if(upc.isNotBlank()) searchMarket(MarketFilters(query=upc),pageSize=30).groups.forEach {candidates[marketMatchId(it)]=it}
 for(line in supplied) {
  val found=searchMarket(MarketFilters(query=line),pageSize=8).groups
  for(g in found) {candidates[marketMatchId(g)]=g
   if(strictFilmWords(line).sorted()==filmWords(g.title,strictFilmWords(line).toSet()).sorted() && g.edition.isBlank() && !g.matchReason.contains("Conflicting") && filmWords(line).isNotEmpty()) exact[filmTitle(line)]=line to g
  }
 }
 val packed=JSONArray(candidates.values.sortedByDescending {it.matchScore}.take(20).map {g->JSONObject().put("title",g.title).put("titleKey",g.titleKey).put("score",g.matchScore).put("reason",g.matchReason).put("edition",g.edition).put("year",g.year ?: JSONObject.NULL)})
 val printed=" "+marketRawKey(supplied.joinToString(" "))+" ";val known=" "+marketRawKey(item.title+" "+item.edition+" "+item.condition)+" "
 val unresolved=listOf("widescreen","fullscreen","special edition","limited edition","collector s edition","collectors edition","anniversary edition","black diamond","masterpiece","screener","factory sealed").filter {printed.contains(" $it ") && !known.contains(" $it ")}
 if(unresolved.isNotEmpty()) return LocalIdentification(null,"Photographs mention ${unresolved.joinToString()}; confirm the physical edition/condition before pricing",packed)
 if(exact.isEmpty() && upc.isNotBlank()) {
  val barcodeHits=searchMarket(MarketFilters(query=upc),pageSize=30).groups
  val identities=barcodeHits.map {Triple(filmTitle(it.title),it.edition,it.year)}.distinct()
  if(identities.size==1 && identities.single().second.isBlank() && barcodeHits.all {marketRawKey(it.title).split(' ').contains("vhs")} && barcodeHits.none {Regex("\\b(dvd|lot|bundle|sealed|graded|collection|trilogy)\\b").containsMatchIn(marketRawKey(it.title))})
   return LocalIdentification(InventoryRules.normalizedTitle(barcodeHits.first().title),"Unique exact barcode in the local catalog; confirm unknown edition and condition during listing review",packed)
 }
 if(exact.size!=1) return LocalIdentification(null,if(exact.isEmpty()) "OCR text has no unique exact local title; choose a ranked candidate or correct the printed title" else "Several film titles occur in the photographs; confirm grouping and title",packed)
 val chosen=exact.values.single();val matching=candidates.values.filter {filmWords(it.title,strictFilmWords(chosen.first).toSet()).sorted()==strictFilmWords(chosen.first).sorted()}
 if(matching.mapNotNull {it.year}.distinct().size>1 || matching.any {it.edition.isNotBlank()}) return LocalIdentification(null,"Title has several editions or years; confirm this physical copy",packed)
 val confidence=result.optDouble("ocr_character_confidence",0.0)
 if(confidence<.85 && upc.isBlank()) return LocalIdentification(null,"Exact local title found, but OCR confidence requires confirmation",packed)
 // The literal printed line is retained; no missing edition, year or condition is inferred.
 return LocalIdentification(chosen.first,"Exact local title corroborates the printed OCR line; no missing facts inferred",packed)
}
/** Reuses durable production/recognition rows, audits and WorkManager. No schema or sales reset. */
internal suspend fun CaptureRepository.runGuidedBatch(jobId:String):Boolean=productionLocks.getOrPut(jobId) {kotlinx.coroutines.sync.Mutex()}.withLock {
 val d=journal.productionDao();val job=d.job(jobId) ?: return@withLock false
 if(job.kind!=GUIDED_KIND || job.state !in setOf("QUEUED","RUNNING")) return@withLock false
 activeProduction.add(jobId);var waiting=false
 try {
  mutate {d.update(job.copy(state="RUNNING",error=""))}
  val rules=resolvePriceFx(autoPriceRules().copy(automatic=false))
  for(initial in d.items(jobId)) {
   currentCoroutineContext().ensureActive();if(d.job(jobId)?.state!="RUNNING") break
   if(initial.state=="CANCELLED") continue
   val data=JSONObject(d.item(initial.id)?.resultJson ?: initial.resultJson);if(data.optBoolean("deferred")) continue
   suspend fun admitted():Boolean {val latest=d.item(initial.id);return d.job(jobId)?.state=="RUNNING" && latest!=null && latest.state !in setOf("CANCELLED","SKIPPED") && !JSONObject(latest.resultJson).optBoolean("deferred")}
   suspend fun state(phase:String,reason:String="",kind:String="NEEDS_REVIEW") {mutate {if(admitted()) d.update(initial.copy(state=kind,resultJson=data.put("phase",phase).put("reason",reason).toString(),error=""))}}
   try {
    var item=journal.inventoryDao().items().firstOrNull {it.groupId==initial.groupId && !it.archived} ?: continue
    val pending=journal.shopifyDao().unfinishedForGroup(item.groupId)
    if(pending.isNotEmpty()) {state("DRAFT_QUEUED","Existing Shopify job owns this copy; inspect its confirmed checkpoints","COMPLETE");continue}
    if(item.shopifyProductId.isNotBlank()) {state(if(item.shopifyState=="PUBLISHED") "PUBLISHED" else if(item.shopifyState in setOf("UPLOADED_AS_DRAFT","LINKED_DRAFT")) "DRAFT_VERIFIED" else "REMOTE_LINK_REVIEW","${item.shopifyState} · ${item.shopifyProductId}","COMPLETE");continue}
    val photos=dao.photos(item.groupId).filter {!it.deleted}.sortedBy {it.position}
    if(photos.isEmpty() || photos.any {it.state!="READY"}) {state("CAPTURE_REVIEW","Capture a front photograph and resolve unfinished files");continue}
    val code=barcodeInfo(dao.group(item.groupId),photos)
    if(item.barcode.isBlank() && code.optString("status") in setOf("FOUND","MANUAL") && !code.isNull("selected")) {val before=item;item=mutate {check(admitted()) {"Copy deferred or workflow stopped"};val now=journal.inventoryDao().item(before.groupId)!!;check(now.revision==before.revision);saveInventoryLocked(now.copy(barcode=code.getString("selected")),now.revision)}}
    if(item.title.isBlank()) {
     val fingerprint=photoFingerprint(photos)
     var recognition=dao.recognitions(item.groupId).firstOrNull {it.inputFingerprint==fingerprint && it.state in setOf("SUCCEEDED","QUEUED","RUNNING") && (!data.optBoolean("retryRecognition") || it.id==data.optString("recognitionId"))}
     if(recognition==null) {
      val prior=data.optString("recognitionId").takeIf {it.isNotBlank()}?.let {dao.recognition(it)}
      if(prior!=null && prior.state !in setOf("QUEUED","RUNNING","SUCCEEDED")) {state("IDENTIFY_REVIEW","${prior.state}: ${prior.error}. Retry this copy or enter its title.");continue}
      recognition=mutate {check(admitted()) {"Copy deferred or workflow stopped"};val row=queueRecognitionLocked(item.groupId,RecognitionSettings("OCR","tesseract-5.5.1/eng-fast-4.1.0","OFFLINE"),"GUIDED:$jobId");data.put("recognitionId",row.id);d.update(initial.copy(state="RUNNING",resultJson=data.put("phase","IDENTIFY_RUNNING").toString()));row}
     }
     data.put("recognitionId",recognition.id)
     if(recognition.state in setOf("QUEUED","RUNNING")) {scheduleRecognition(recognition);waiting=true;state("IDENTIFY_RUNNING","Offline OCR and barcode recognition in progress","RUNNING");continue}
     val identified=if(!recognition.acceptedTitle.isNullOrBlank()) LocalIdentification(recognition.acceptedTitle,"Operator-confirmed recognition",JSONArray()) else identifyGuided(item,JSONObject(recognition.resultJson))
     data.put("identificationCandidates",identified.candidates).put("identityReason",identified.reason)
     if(identified.title==null) {state("IDENTIFY_REVIEW",identified.reason);continue}
     val before=item
     item=mutate {check(admitted()) {"Copy deferred or workflow stopped"};val current=journal.inventoryDao().item(before.groupId)!!;check(current.revision==before.revision && current.title.isBlank()) {"Identity changed during recognition"};check(photoFingerprint(dao.photos(current.groupId).filter {!it.deleted && it.state=="READY"})==recognition.inputFingerprint) {"Photos changed during recognition"}
      // Apply the corroborated title only; provider-suggested editions and years are not automatically asserted.
      saveInventoryLocked(current.copy(title=identified.title,barcode=current.barcode.ifBlank {recognitionBarcode(JSONObject(recognition.resultJson))},recognitionId=recognition.id),current.revision)
     }
    }
    val generated=localListingDescription(item)
    val updateGenerated=data.optString("generatedDescriptionHash").takeIf {it.isNotBlank()}==sha256(item.description.toByteArray()) && item.description!=generated
    if(item.description.isBlank() || item.tags.isBlank() || updateGenerated) {val before=item;item=mutate {
     check(admitted()) {"Copy deferred or workflow stopped"};val current=journal.inventoryDao().item(before.groupId)!!;check(current.revision==before.revision)
     val replace=current.description.isBlank() || updateGenerated
     val saved=saveInventoryLocked(current.copy(description=if(replace) generated else current.description,tags=current.tags.ifBlank {"VHS"}),current.revision)
     if(replace) {data.put("generatedDescriptionHash",sha256(saved.description.toByteArray()));d.update(initial.copy(resultJson=data.toString()))};saved
    }}
    if(item.priceCents==null || item.priceSource.startsWith("STALE")) {
     var price=data.optString("priceResultId").takeIf {it.isNotBlank()}?.let {d.item(it)} ?: d.latestPrice(item.groupId)?.takeIf {it.revision==item.revision}
     if(price==null || price.revision!=item.revision || (price.state !in setOf("QUEUED","RUNNING") && JSONObject(price.resultJson).optInt("pricePolicyVersion")!=2) || data.optBoolean("retryPrice") || (JSONObject(price.resultJson).optString("reasonCode") in setOf("FX_MISSING","FX_STALE") && rules.usdCad.isNotBlank())) {
      data.remove("retryPrice")
      val before=item
      price=mutate {check(admitted()) {"Copy deferred or workflow stopped"};check(journal.inventoryDao().item(before.groupId)?.revision==before.revision);val child=ProductionJobRow(kind="MATCH_PRICE",optionsJson=rules.json().toString());val entry=ProductionItemRow(jobId=child.id,groupId=before.groupId,revision=before.revision);d.insert(child);d.insert(entry);data.put("priceResultId",entry.id).put("priceJobId",child.id);d.update(initial.copy(state="RUNNING",resultJson=data.put("phase","PRICE_RUNNING").toString()));entry}
     }
     if(price.state in setOf("QUEUED","RUNNING")) runProduction(price.jobId)
     price=d.item(price.id)!!;data.put("priceResultId",price.id).put("priceJobId",price.jobId)
     val result=JSONObject(price.resultJson)
     state(if(price.state=="HIGH_CONFIDENCE") "PRICE_RECOMMENDED" else "PRICE_REVIEW",result.optString("reason").ifBlank {price.error},if(price.state=="HIGH_CONFIDENCE") "HIGH_CONFIDENCE" else "NEEDS_REVIEW");continue
    }
    val errors=shopifyProblems(item.copy(ready=true),photos)
    state(if(errors.isNotEmpty()) "LISTING_BLOCKED" else if(item.ready) "LISTING_READY" else "LISTING_REVIEW",errors.joinToString(". ").ifBlank {if(item.ready) "Approved listing ready for draft submission" else "Price approved; factual metadata prepared. Review the listing."},if(errors.isEmpty()) "COMPLETE" else "NEEDS_REVIEW")
   } catch(e:CancellationException) {throw e} catch(e:Exception) {state("PROCESSING_ERROR",e.message.orEmpty().take(700),"ERROR")}
  }
  waiting=waiting || d.items(jobId).any {it.state=="QUEUED"}
  mutate {val latest=d.job(jobId)!!;if(latest.state=="RUNNING") d.update(latest.copy(state=if(waiting) "QUEUED" else "COMPLETE",updatedAt=System.currentTimeMillis()))}
  waiting
 } finally {activeProduction.remove(jobId)}
}
