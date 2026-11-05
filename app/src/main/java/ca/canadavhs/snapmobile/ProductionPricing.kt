package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

internal data class AutoPriceRules(val method:String="MEDIAN",val minimum:Int=1,val ageDays:Int=3650,val floor:Long?=null,val ceiling:Long?=null,val adjustmentBasisPoints:Int=0,val roundingCents:Int=1,val protectManual:Boolean=true,val automatic:Boolean=false,val usdCad:String="",val rateDate:String="",val shipping:String="INCLUDE",val outliers:String="IQR",val fxSource:String="",val fxFetchedAt:Long=0,val fxWarning:String="",val bulkScore:Int=85,val reviewScore:Int=65) {
 fun json()=JSONObject().put("method",method).put("minimum",minimum).put("ageDays",ageDays).put("floor",floor ?: JSONObject.NULL).put("ceiling",ceiling ?: JSONObject.NULL).put("adjustmentBasisPoints",adjustmentBasisPoints).put("roundingCents",roundingCents).put("protectManual",protectManual).put("automatic",automatic).put("usdCad",usdCad).put("rateDate",rateDate).put("shipping",shipping).put("outliers",outliers).put("fxSource",fxSource).put("fxFetchedAt",fxFetchedAt).put("fxWarning",fxWarning).put("bulkScore",bulkScore).put("reviewScore",reviewScore)
 fun validate():AutoPriceRules {require(bulkScore in 85..100 && reviewScore in 65..84);require(method in setOf("MEDIAN","AVERAGE","TRIMMED_MEAN"));require(minimum in 1..1000 && ageDays in 1..36500 && adjustmentBasisPoints in -9900..100000 && roundingCents in 1..10000);require(floor==null || floor in 1..InventoryRules.maxPriceCents);require(ceiling==null || ceiling in 1..InventoryRules.maxPriceCents);require(floor==null || ceiling==null || floor<=ceiling);require(shipping in setOf("INCLUDE","EXCLUDE"));require(outliers in setOf("IQR","NONE"));if(usdCad.isNotBlank()) {val rate=usdCad.toBigDecimal();require(rate>BigDecimal.ZERO && rate<=BigDecimal("1000") && rate.scale()<=8);val day=LocalDate.parse(rateDate);require(day<=LocalDate.now()) {"FX effective date cannot be in the future"}};return this}
 companion object {
  fun parse(o:JSONObject)=AutoPriceRules(o.optString("method","MEDIAN"),o.optInt("minimum",1),o.optInt("ageDays",3650),if(o.isNull("floor")) null else o.getLong("floor"),if(o.isNull("ceiling")) null else o.getLong("ceiling"),o.optInt("adjustmentBasisPoints",0),o.optInt("roundingCents",1),o.optBoolean("protectManual",true),o.optBoolean("automatic",false),o.optString("usdCad"),o.optString("rateDate"),o.optString("shipping","INCLUDE"),o.optString("outliers","IQR"),o.optString("fxSource"),o.optLong("fxFetchedAt"),o.optString("fxWarning"),o.optInt("bulkScore",85),o.optInt("reviewScore",65)).validate()
 }
}
internal fun CaptureRepository.autoPriceRules()=AutoPriceRules.parse(JSONObject(preferences.getString("autoPriceRules","{}")!!))
internal fun CaptureRepository.saveAutoPriceRules(rules:AutoPriceRules) {check(preferences.edit().putString("autoPriceRules",rules.validate().json().toString()).commit())}
internal fun protectedManualPrice(item:InventoryRow)=item.priceCents!=null && (item.priceSource in setOf("MANUAL","MANUAL_ESTIMATE","OPERATOR_APPROVED") || item.priceSource.startsWith("OPERATOR_"))
internal fun compatibleAutoCondition(item:InventoryRow,row:MarketSaleRow):Boolean {
 val source=" "+marketRawKey(row.title+" "+row.edition+" "+runCatching {JSONObject(row.rawJson).optString("condition")}.getOrDefault(""))+" "
 val target=" "+marketRawKey(item.title+" "+item.edition+" "+item.condition)+" "
 for(marker in listOf("sealed","graded","cgc","vga")) if(source.contains(" $marker ")!=target.contains(" $marker ")) return false
 // Do not compare explicitly different known conditions; absent condition is not a fabricated grade.
 val rawCondition=runCatching {JSONObject(row.rawJson).optString("condition")}.getOrDefault("")
 val recorded=marketRawKey(rawCondition)
 if(recorded in setOf("new","brand new","new other")) return item.condition=="Sealed" && source.contains(" sealed ")
 val used=recorded in setOf("used","pre owned","preowned","second hand")
 val unknown=recorded in setOf("","not specified","unspecified","unknown","not graded")
 val grade=when(recorded) {"acceptable"->"Fair";"very good"->"Very Good";"like new"->"Like New";else->rawCondition}
 if(used && item.condition in setOf("Sealed","Damaged","Mold Review")) return false
 if(!used && !unknown && item.condition!="Not Graded" && !grade.equals(item.condition,true)) return false
 return true
}
internal fun compatibleAutoIdentity(item:InventoryRow,row:MarketSaleRow):Boolean = comparableExclusion(item,row)==null
internal fun aggregateAutoPrice(values:List<BigDecimal>,method:String):Long {
 require(values.isNotEmpty());val sorted=values.sorted();val selected=if(method=="TRIMMED_MEAN" && sorted.size>=10) sorted.drop(sorted.size/10).dropLast(sorted.size/10) else sorted
 val amount=if(method=="MEDIAN") {val mid=sorted.size/2;if(sorted.size%2==1) sorted[mid] else (sorted[mid-1]+sorted[mid]).divide(BigDecimal(2),12,RoundingMode.HALF_UP)} else selected.fold(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal(selected.size),12,RoundingMode.HALF_UP)
 return amount.movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact()
}
/** Indexed candidates are suggestions; only strict compatible independent SOLD observations enter prices. */
internal suspend fun CaptureRepository.buildAutoPrice(item:InventoryRow,rules:AutoPriceRules):JSONObject=withContext(Dispatchers.IO) {
 val policy=if(rules.usdCad.isBlank()) resolvePriceFx(rules,refresh=false) else rules.validate()
 val result=JSONObject().put("pricePolicyVersion",2).put("revision",item.revision).put("titleKey",InventoryRules.normalizedTitle(item.title)).put("rules",policy.json()).put("category","NO_RELIABLE_MATCH").put("evidence",JSONArray()).put("warnings",JSONArray()).put("score",0)
 fun blocked(code:String,message:String)=result.put("reasonCode",code).put("reason",message).put("category",if(code=="NO_CANDIDATES") "NO_RELIABLE_MATCH" else "NEEDS_REVIEW")
 if(item.title.isBlank()) return@withContext blocked("IDENTITY_MISSING","Identify the printed title first")
 if(policy.protectManual && protectedManualPrice(item)) return@withContext blocked("MANUAL_PRICE_PROTECTED","Existing manual/operator price is protected")
 val groups=(searchMarket(MarketFilters(query=item.title),pageSize=100).groups + if(item.barcode.isNotBlank()) searchMarket(MarketFilters(query=item.barcode),pageSize=100).groups else emptyList()).distinctBy {listOf(it.titleKey,it.edition,it.year,it.currency)}
 val refs=linkedSetOf<String>();val db=journal.openHelper.writableDatabase
 val keys=(groups.map {it.titleKey}+hybridTitleCandidates(filmTitle(item.title),MarketFilters(query=item.title),200)+InventoryRules.normalizedTitle(item.title)).distinct()
 // Rank titles first, then fetch complete observations only for identity-compatible titles.
 // Broad fuzzy candidates must not exhaust the evidence bound before strict matching runs.
 val barcodeKeys=if(item.barcode.isBlank()) emptySet() else searchMarket(MarketFilters(query=item.barcode),pageSize=100).groups.map {it.titleKey}.toSet()
 val pricingKeys=db.query("SELECT titleKey,title FROM market_titles WHERE titleKey IN (${keys.joinToString(",") {"?"}})",keys.toTypedArray()).use {c->buildList {while(c.moveToNext()) if(c.getString(0) in barcodeKeys || compatibleFilmTitle(item,c.getString(1))) add(c.getString(0))}}
 if(pricingKeys.isNotEmpty()) db.query("SELECT id FROM market_sales WHERE current=1 AND titleKey IN (${pricingKeys.joinToString(",") {"?"}}) ORDER BY soldAt DESC,id LIMIT 5001",pricingKeys.toTypedArray()).use {c->while(c.moveToNext()) refs+=c.getString(0)}
 if(refs.size>5000) return@withContext blocked("CANDIDATE_LIMIT","More than 5,000 observations could apply. Narrow the title/edition; no partial sample was priced.")
 val pricingIds=refs.toSet()
 val diagnosticKeys=keys.filter {it !in pricingKeys}.take(60)
 for(key in diagnosticKeys) db.query("SELECT id FROM market_sales WHERE current=1 AND titleKey=? LIMIT 1",arrayOf(key)).use {c->if(c.moveToFirst()) refs+=c.getString(0)}
 result.put("diagnosticSampling","Excluded-candidate examples use one observation per title, at most 60 titles. Every observation for eligible pricing titles is examined up to the explicit 5,000-row safety bound.")
 val rows=refs.mapNotNull {journal.marketDao().sale(it)}
 val exclusions=JSONArray();val counts=linkedMapOf<String,Int>();val eligible=mutableListOf<MarketSaleRow>();var usdBlocked=0
 fun exclude(row:MarketSaleRow,why:String) {val code=why.substringBefore(':');counts[code]=(counts[code] ?: 0)+1;if(exclusions.length()<100) exclusions.put(JSONObject().put("id",row.id).put("title",row.title).put("reason",why))}
 val independent=mutableSetOf<String>()
 for(row in rows) {
  val why=comparableExclusion(item,row)
  if(why!=null) {exclude(row,why);continue}
  if(row.id !in pricingIds) {exclude(row,"CANDIDATE_ONLY: confirm this title before using its full sold history");continue}
  val age=runCatching {marketEvidenceOldestAge(row.soldAt)}.getOrNull()
  if(age==null || age !in 0..policy.ageDays.toLong()) {exclude(row,"SALE_AGE: sold date is missing, future or outside the evidence window");continue}
  if(row.currency !in setOf("CAD","USD")) {exclude(row,"CURRENCY_UNSUPPORTED: ${row.currency} cannot be converted by this policy");continue}
  if(row.currency=="USD" && (policy.usdCad.isBlank() || runCatching {marketEvidenceOldestAge(policy.rateDate)>FX_MAX_AGE_DAYS}.getOrDefault(true))) {usdBlocked++;exclude(row,"FX_UNAVAILABLE: compatible USD sale needs a valid dated USD/CAD rate");continue}
  val key=(if(row.site.startsWith("EBAY",true)) "EBAY" else row.site.uppercase())+":"+row.sourceItemId
  if(!independent.add(key)) {exclude(row,"DUPLICATE_LISTING: this source listing already contributes one observation");continue}
  eligible+=row
 }
 result.put("candidateCount",rows.size).put("exclusions",exclusions).put("excludedCounts",JSONObject(counts))
 result.put("candidates",JSONArray(groups.take(30).map {g->val representative=rows.firstOrNull {it.titleKey==g.titleKey && it.edition==g.edition && it.currency==g.currency};JSONObject().put("id",marketMatchId(g)).put("title",g.title).put("titleKey",g.titleKey).put("edition",g.edition).put("year",g.year ?: JSONObject.NULL).put("tier",g.matchTier).put("score",g.matchScore).put("reason",g.matchReason).put("validation",representative?.let {comparableExclusion(item,it)} ?: if(representative==null) "Candidate only; inspect source evidence" else "Compatible identity").put("sales",g.samples)}))
 val warnings=result.getJSONArray("warnings");if(policy.fxWarning.isNotBlank()) warnings.put(policy.fxWarning)
 if(eligible.isEmpty()) return@withContext blocked(when {usdBlocked>0->if(policy.fxWarning.startsWith("FX_STALE")) "FX_STALE" else "FX_MISSING";rows.isEmpty()->"NO_CANDIDATES";counts["SALE_AGE"]!=null->"EVIDENCE_OUTSIDE_DATE_WINDOW";else->"INCOMPATIBLE_EVIDENCE"},when {usdBlocked>0->"$usdBlocked compatible USD sold records found, but conversion is blocked. ${policy.fxWarning}";rows.isEmpty()->"No indexed candidates found; correct the printed title or defer";else->"Candidates found but none passed sold-evidence validation: "+counts.entries.joinToString {"${it.key} (${it.value})"}})
 val years=eligible.flatMap {filmYears(it.title)+listOfNotNull(it.releaseYear)}.toSet()
 if(item.releaseYear==null && filmYears(item.title).isEmpty() && years.size>1) return@withContext blocked("YEAR_AMBIGUOUS","Compatible titles span years ${years.sorted().joinToString()}; confirm the physical tape year before pricing")
 val values=eligible.map {row->val source=marketMoney(row.soldDecimal)+(if(policy.shipping=="INCLUDE") marketMoney(row.shippingDecimal) else BigDecimal.ZERO);source*(if(row.currency=="USD") policy.usdCad.toBigDecimal() else BigDecimal.ONE)}
 val keep=if(policy.outliers=="IQR" && values.size>=4) {val sorted=values.sorted();val q1=sorted[sorted.size/4];val q3=sorted[sorted.size*3/4];val fence=(q3-q1)*BigDecimal("1.5");values.indices.filter {values[it]>=q1-fence && values[it]<=q3+fence}} else values.indices.toList()
 val excluded=eligible.indices.filter {it !in keep};result.put("excludedOutliers",JSONArray(excluded.map {JSONObject().put("id",eligible[it].id).put("reason","Outside 1.5 IQR fence on converted CAD totals")}))
 val evidence=keep.map {eligible[it]};val retained=keep.map {values[it]};if(excluded.isNotEmpty()) warnings.put("${excluded.size} outliers excluded using IQR")
 if(evidence.isEmpty()) return@withContext blocked("OUTLIERS_ONLY","No retained evidence after configured outlier handling")
 val source=aggregateAutoPrice(retained,policy.method)
 var price=BigDecimal(source).multiply(BigDecimal(10000+policy.adjustmentBasisPoints)).divide(BigDecimal(10000),0,RoundingMode.HALF_UP).longValueExact()
 price=BigDecimal(price).divide(BigDecimal(policy.roundingCents),0,RoundingMode.HALF_UP).multiply(BigDecimal(policy.roundingCents)).longValueExact()
 policy.floor?.let {price=maxOf(price,it)};policy.ceiling?.let {price=minOf(price,it)}
 if(price !in 1..InventoryRules.maxPriceCents) return@withContext blocked("PRICE_OUT_OF_RANGE","Calculated price is outside supported bounds")
 val quality=evidenceQuality(item,evidence,retained);var score=quality.score
 if(usdBlocked>0) {warnings.put("$usdBlocked compatible USD observations excluded because FX is unavailable");score=minOf(score,84)}
 if(evidence.any {it.currency=="USD"} && marketEvidenceOldestAge(policy.rateDate)>7) score=minOf(score,84)
 if(evidence.any {JSONObject(it.rawJson).optString("condition").isBlank()}) warnings.put("Source condition unspecified; no grade or premium invented")
 if(evidence.any {marketMoney(it.shippingDecimal).signum()==0}) warnings.put("Zero shipping retained as recorded; free shipping is not independently verified")
 result.put("score",score).put("scorePolicy","Heuristic evidence quality, not a calibrated probability; ${policy.bulkScore}+ bulk eligible, ${policy.reviewScore}–${policy.bulkScore-1} individual review").put("scoreFactors",quality.factors)
  .put("count",evidence.size).put("sourceCadCents",source).put("lowCadCents",aggregateAutoPrice(listOf(retained.min()),"MEDIAN")).put("highCadCents",aggregateAutoPrice(listOf(retained.max()),"MEDIAN"))
  .put("evidence",JSONArray(evidence.map {JSONObject().put("id",it.id).put("hash",it.payloadHash)})).put("evidenceHash",sha256(evidence.map {it.id+":"+it.payloadHash}.sorted().joinToString("\n").toByteArray()))
  .put("oldest",evidence.minOf {it.soldAt}).put("newest",evidence.maxOf {it.soldAt})
  .put("fx",JSONObject().put("rate",policy.usdCad).put("effectiveDate",policy.rateDate).put("source",policy.fxSource).put("fetchedAt",policy.fxFetchedAt).put("warning",policy.fxWarning))
 if(score<policy.reviewScore) return@withContext blocked("LOW_EVIDENCE_QUALITY","Evidence score $score/100 is insufficient for a reliable recommendation; review sources or price manually")
 result.put("priceCents",price).put("category",if(score>=policy.bulkScore) "HIGH_CONFIDENCE" else "NEEDS_REVIEW").put("reasonCode",if(score>=policy.bulkScore) "PRICE_RECOMMENDED" else "PRICE_REVIEW_REQUIRED")
  .put("reason","$score/100 evidence quality · ${evidence.size} independent compatible sales. "+if(score>=policy.bulkScore) "Eligible for bulk approval; not yet applied." else "Review this recommendation individually before applying.")
}
internal suspend fun CaptureRepository.applyProductionPrices(ids:Set<String>,reviewedIndividual:Boolean=false):Pair<Int,Map<String,String>> {
 require(ids.size in 1..2000);var count=0;val failures=linkedMapOf<String,String>()
 for(id in ids) {currentCoroutineContext().ensureActive();try {val changed=mutate {
  val dao=journal.productionDao();val row=dao.item(id) ?: error("Result missing");if(row.state=="APPLIED") return@mutate false
  check(row.state=="HIGH_CONFIDENCE" || (reviewedIndividual && ids.size==1 && row.state=="NEEDS_REVIEW"));val result=JSONObject(row.resultJson);check(result.optInt("pricePolicyVersion")==2) {"Stored price predates evidence-quality scoring; rerun matching"};val old=journal.inventoryDao().items().firstOrNull {it.groupId==row.groupId && !it.archived} ?: error("Deleted or archived record")
  check(old.revision==row.revision && old.revision==result.getLong("revision") && InventoryRules.normalizedTitle(old.title)==result.getString("titleKey")) {"Item changed; rerun matching"}
  val rules=AutoPriceRules.parse(result.getJSONObject("rules"));check(result.optInt("score",if(result.optString("category")=="HIGH_CONFIDENCE") 85 else 0)>=(if(reviewedIndividual) rules.reviewScore else rules.bulkScore)) {"Evidence score is not eligible for this approval"};check(!rules.protectManual || !protectedManualPrice(old)) {"Manual price protected"}
  val refs=result.getJSONArray("evidence");check(refs.length()>0);if(refs.length()==1) check(reviewedIndividual) {"Single-sale recommendations require individual review"};var usesUsd=false;val hashes=mutableListOf<String>()
  for(i in 0 until refs.length()) {val ref=refs.getJSONObject(i);val sale=journal.marketDao().sale(ref.getString("id")) ?: error("Evidence missing");check(sale.current && sale.eligible && sale.payloadHash==ref.getString("hash") && compatibleAutoIdentity(old,sale)) {"Evidence changed"};val age=marketEvidenceOldestAge(sale.soldAt);check(age in 0..rules.ageDays.toLong());usesUsd=usesUsd || sale.currency=="USD";hashes+=sale.id+":"+sale.payloadHash}
  if(usesUsd) {val fxAge=marketEvidenceOldestAge(rules.rateDate);check(rules.usdCad.isNotBlank() && fxAge in 0..FX_MAX_AGE_DAYS) {"FX rate expired after recommendation; recalculate"};check(fxAge<=7 || reviewedIndividual) {"FX is older than seven days; refresh or approve the individual price"}}
  check(sha256(hashes.sorted().joinToString("\n").toByteArray())==result.getString("evidenceHash"));val price=result.getLong("priceCents");require(price in 1..InventoryRules.maxPriceCents)
  val provenance=JSONObject(result.toString()).put("jobId",row.jobId).put("historicalSoldEvidence",true).put("askingPrice",true).put("aiEstimate",false)
  val saved=old.copy(priceCents=price,priceSource="MARKET_AUTO_${rules.method}",priceEvidenceJson=provenance.toString(),ready=false,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString(),shopifyState=if(old.shopifyProductId.isBlank()) old.shopifyState else "LOCAL_CHANGES")
  journal.inventoryDao().update(saved);inventoryAudit(old,saved,"BULK_MARKET_PRICE_APPLIED");recordPriceDecision(saved,"OPERATOR_APPROVED_PRICE","AUTO_${rules.method}",JSONArray((0 until refs.length()).map {refs.getJSONObject(it).getString("id")}),provenance)
  dao.update(row.copy(state="APPLIED"));true
 };if(changed) count++} catch(e:CancellationException) {throw e} catch(e:Exception) {failures[id]=e.message.orEmpty().take(700)}}
 return count to failures
}
