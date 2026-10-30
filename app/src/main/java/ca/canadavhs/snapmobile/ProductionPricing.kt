package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

internal data class AutoPriceRules(val method:String="MEDIAN",val minimum:Int=3,val ageDays:Int=3650,val floor:Long?=null,val ceiling:Long?=null,val adjustmentBasisPoints:Int=0,val roundingCents:Int=1,val protectManual:Boolean=true,val automatic:Boolean=false,val usdCad:String="",val rateDate:String="",val shipping:String="INCLUDE",val outliers:String="IQR") {
 fun json()=JSONObject().put("method",method).put("minimum",minimum).put("ageDays",ageDays).put("floor",floor ?: JSONObject.NULL).put("ceiling",ceiling ?: JSONObject.NULL).put("adjustmentBasisPoints",adjustmentBasisPoints).put("roundingCents",roundingCents).put("protectManual",protectManual).put("automatic",automatic).put("usdCad",usdCad).put("rateDate",rateDate).put("shipping",shipping).put("outliers",outliers)
 fun validate():AutoPriceRules {require(method in setOf("MEDIAN","AVERAGE","TRIMMED_MEAN"));require(minimum in 1..1000 && ageDays in 1..36500 && adjustmentBasisPoints in -9900..100000 && roundingCents in 1..10000);require(floor==null || floor in 1..InventoryRules.maxPriceCents);require(ceiling==null || ceiling in 1..InventoryRules.maxPriceCents);require(floor==null || ceiling==null || floor<=ceiling);require(shipping in setOf("INCLUDE","EXCLUDE"));require(outliers in setOf("IQR","NONE"));if(usdCad.isNotBlank()) {val rate=usdCad.toBigDecimal();require(rate>BigDecimal.ZERO && rate<=BigDecimal("1000") && rate.scale()<=8);val day=LocalDate.parse(rateDate);require(day<=LocalDate.now()) {"FX effective date cannot be in the future"}};return this}
 companion object {
  fun parse(o:JSONObject)=AutoPriceRules(o.optString("method","MEDIAN"),o.optInt("minimum",3),o.optInt("ageDays",3650),if(o.isNull("floor")) null else o.getLong("floor"),if(o.isNull("ceiling")) null else o.getLong("ceiling"),o.optInt("adjustmentBasisPoints",0),o.optInt("roundingCents",1),o.optBoolean("protectManual",true),o.optBoolean("automatic",false),o.optString("usdCad"),o.optString("rateDate"),o.optString("shipping","INCLUDE"),o.optString("outliers","IQR")).validate()
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
 if(rawCondition.isNotBlank() && item.condition!="Not Graded" && !rawCondition.equals(item.condition,true)) return false
 return true
}
internal fun compatibleAutoIdentity(item:InventoryRow,row:MarketSaleRow):Boolean {
 val source=" "+marketRawKey(row.title+" "+row.edition)+" "
 if(listOf("lot","lots","bundle","bundles","collection","trilogy","boxset","box set","dvd","blu ray","laserdisc","betamax").any {source.contains(" $it ")}) return false
 if(!item.edition.equals(row.edition,true)) return false
 if(item.releaseYear!=null && row.releaseYear!=null && row.releaseYear!=item.releaseYear) return false
 if(item.releaseYear!=null && row.releaseYear==null && !Regex("(?:^|\\W)${item.releaseYear}(?:$|\\W)").containsMatchIn(row.title)) return false
 if(item.barcode.isNotBlank() && row.barcode.isNotBlank() && item.barcode!=row.barcode) return false
 if(!compatibleAutoCondition(item,row)) return false
 val wanted=" "+marketRawKey(item.title+" "+item.edition)+" "
 val variants=listOf("widescreen","fullscreen","special edition","limited edition","collectors edition","collector s edition","anniversary edition","directors cut","director s cut")
 if(variants.any {source.contains(" $it ")!=wanted.contains(" $it ")}) return false
 if(PricingRules.listingMatches(item.title,row.title)) return true
 // An exact indexed UPC plus an agreeing release year may corroborate a minor OCR typo.
 // Similarity alone never confirms a comparable, and a barcode never overrides incompatible edition/format metadata.
 if(item.barcode.isBlank() || item.barcode!=row.barcode || item.releaseYear==null || item.releaseYear!=row.releaseYear) return false

 fun identity(value:String)=InventoryRules.normalizedTitle(value).split(' ').filterNot {it in setOf("the","a","an","ntsc","pal","tested","rare","used","classic","clamshell") || it.matches(Regex("(?:18|19|20|21)[0-9]{2}"))}.joinToString(" ")
 val a=identity(item.title);val b=identity(row.title);return a.isNotBlank() && b.isNotBlank() && a.split(' ').size==b.split(' ').size && editDistance(a,b,2)<=2
}
internal fun aggregateAutoPrice(values:List<BigDecimal>,method:String):Long {
 require(values.isNotEmpty());val sorted=values.sorted();val selected=if(method=="TRIMMED_MEAN" && sorted.size>=10) sorted.drop(sorted.size/10).dropLast(sorted.size/10) else sorted
 val amount=if(method=="MEDIAN") {val mid=sorted.size/2;if(sorted.size%2==1) sorted[mid] else (sorted[mid-1]+sorted[mid]).divide(BigDecimal(2),12,RoundingMode.HALF_UP)} else selected.fold(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal(selected.size),12,RoundingMode.HALF_UP)
 return amount.movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact()
}
/** Existing strict identity matcher remains the authority. Fuzzy/AI rankings never become sale evidence by themselves. */
internal suspend fun CaptureRepository.buildAutoPrice(item:InventoryRow,rules:AutoPriceRules):JSONObject=withContext(Dispatchers.IO) {
 rules.validate();val result=JSONObject().put("revision",item.revision).put("titleKey",InventoryRules.normalizedTitle(item.title)).put("rules",rules.json()).put("category","NO_RELIABLE_MATCH").put("evidence",JSONArray()).put("warnings",JSONArray())
 if(item.title.isBlank()) return@withContext result.put("reason","Identify the printed title first")
 if(rules.protectManual && protectedManualPrice(item)) return@withContext result.put("category","NEEDS_REVIEW").put("reason","Manual/operator price protected")
 val candidates=searchMarket(MarketFilters(query=item.title),pageSize=20).groups
 result.put("candidates",JSONArray(candidates.map {g->JSONObject().put("id",marketMatchId(g)).put("title",g.title).put("edition",g.edition).put("year",g.year ?: JSONObject.NULL).put("tier",g.matchTier).put("score",g.matchScore).put("reason",g.matchReason).put("sales",g.samples)}))
 val evidence=mutableListOf<MarketSaleRow>();val warnings=result.getJSONArray("warnings");var conflict=""
 for(currency in listOf("CAD","USD")) {
  if(currency=="USD" && rules.usdCad.isBlank()) continue
  val recommendation=try {recommendMarket(item.groupId,currency)} catch(e:CancellationException) {throw e} catch(e:Exception) {conflict=e.message.orEmpty();null}
  val ids=recommendation?.ids.orEmpty().toMutableSet()
  if(item.barcode.isNotBlank()) {journal.openHelper.writableDatabase.query("SELECT id FROM market_sales WHERE barcode=? AND current=1 AND eligible=1 AND currency=? LIMIT 5001",arrayOf(item.barcode,currency)).use {c->var count=0;while(c.moveToNext()) {check(++count<=5000) {"Too many barcode evidence records; refine identity"};ids+=c.getString(0)}}}
  for(id in ids) {val row=journal.marketDao().sale(id) ?: continue
   val date=runCatching {LocalDate.parse(row.soldAt.take(10))}.getOrNull() ?: continue
   val age=java.time.temporal.ChronoUnit.DAYS.between(date,LocalDate.now())
   if(age !in 0..rules.ageDays.toLong() || !compatibleAutoIdentity(item,row)) continue
   evidence+=row
  }
 }
 if(evidence.isEmpty()) return@withContext result.put("category",if(candidates.isNotEmpty()) "NEEDS_REVIEW" else "NO_RELIABLE_MATCH").put("reason",conflict.ifBlank {if(rules.usdCad.isBlank() && candidates.any {it.currency=="USD"}) "USD evidence needs an explicit dated USD/CAD rate" else "No compatible current sold evidence within the configured date/condition/edition rules"})
 val rate=rules.usdCad.toBigDecimalOrNull() ?: BigDecimal.ONE
 val originalValues=evidence.map {row->val value=marketMoney(row.soldDecimal)+(if(rules.shipping=="INCLUDE") marketMoney(row.shippingDecimal) else BigDecimal.ZERO);value*(if(row.currency=="USD") rate else BigDecimal.ONE)}
 val keep=if(rules.outliers=="IQR" && originalValues.size>=4) {val sorted=originalValues.sorted();val q1=sorted[sorted.size/4];val q3=sorted[sorted.size*3/4];val spread=(q3-q1)*BigDecimal("1.5");originalValues.indices.filter {originalValues[it]>=q1-spread && originalValues[it]<=q3+spread}} else originalValues.indices.toList()
 val excluded=evidence.indices.filter {it !in keep};result.put("excludedOutliers",JSONArray(excluded.map {JSONObject().put("id",evidence[it].id).put("reason","Outside 1.5 IQR fence on converted CAD totals")}))
 val keptEvidence=keep.map {evidence[it]};val values=keep.map {originalValues[it]};if(excluded.isNotEmpty()) warnings.put("${excluded.size} outliers excluded using configured IQR rule")
 val source=aggregateAutoPrice(values,rules.method)
 var price=BigDecimal(source).multiply(BigDecimal(10000+rules.adjustmentBasisPoints)).divide(BigDecimal(10000),0,RoundingMode.HALF_UP).longValueExact()
 price=BigDecimal(price).divide(BigDecimal(rules.roundingCents),0,RoundingMode.HALF_UP).multiply(BigDecimal(rules.roundingCents)).longValueExact()
 rules.floor?.let {price=maxOf(price,it)};rules.ceiling?.let {price=minOf(price,it)}
 if(price !in 1..InventoryRules.maxPriceCents) return@withContext result.put("category","NEEDS_REVIEW").put("reason","Calculated asking price is outside supported bounds")
 if(evidence.any {runCatching {JSONObject(it.rawJson).optString("condition")}.getOrDefault("").isBlank()}) warnings.put("Some source conditions are unspecified; no condition premium was invented")
 if(evidence.any {marketMoney(it.shippingDecimal).signum()==0}) warnings.put("Zero shipping is used as recorded; it is not assumed to be a verified free-shipping offer")
 val refs=JSONArray(keptEvidence.map {JSONObject().put("id",it.id).put("hash",it.payloadHash)})
 result.put("evidence",refs).put("evidenceHash",sha256(keptEvidence.map {it.id+":"+it.payloadHash}.sorted().joinToString("\n").toByteArray())).put("count",keptEvidence.size).put("sourceCadCents",source).put("priceCents",price)
  .put("oldest",keptEvidence.minOf {it.soldAt}).put("newest",keptEvidence.maxOf {it.soldAt}).put("category",if(keptEvidence.size>=rules.minimum) "HIGH_CONFIDENCE" else "NEEDS_REVIEW").put("reason",if(keptEvidence.size>=rules.minimum) "Compatible title/edition/year identity (or exact UPC plus corroborated minor OCR correction) and sufficient sold evidence" else "Only ${keptEvidence.size} compatible sales; minimum ${rules.minimum}")
 result
}
internal suspend fun CaptureRepository.applyProductionPrices(ids:Set<String>):Pair<Int,Map<String,String>> {
 require(ids.size in 1..2000);var count=0;val failures=linkedMapOf<String,String>()
 for(id in ids) {currentCoroutineContext().ensureActive();try {val changed=mutate {
  val dao=journal.productionDao();val row=dao.item(id) ?: error("Result missing");if(row.state=="APPLIED") return@mutate false
  check(row.state=="HIGH_CONFIDENCE");val result=JSONObject(row.resultJson);check(result.getString("category")=="HIGH_CONFIDENCE");val old=journal.inventoryDao().items().firstOrNull {it.groupId==row.groupId && !it.archived} ?: error("Deleted or archived record")
  check(old.revision==row.revision && old.revision==result.getLong("revision") && InventoryRules.normalizedTitle(old.title)==result.getString("titleKey")) {"Item changed; rerun matching"}
  val rules=AutoPriceRules.parse(result.getJSONObject("rules"));check(!rules.protectManual || !protectedManualPrice(old)) {"Manual price protected"}
  val refs=result.getJSONArray("evidence");check(refs.length()>=rules.minimum);val hashes=mutableListOf<String>()
  for(i in 0 until refs.length()) {val ref=refs.getJSONObject(i);val sale=journal.marketDao().sale(ref.getString("id")) ?: error("Evidence missing");check(sale.current && sale.eligible && sale.payloadHash==ref.getString("hash") && compatibleAutoIdentity(old,sale)) {"Evidence changed"};val age=marketEvidenceOldestAge(sale.soldAt);check(age in 0..rules.ageDays.toLong());hashes+=sale.id+":"+sale.payloadHash}
  check(sha256(hashes.sorted().joinToString("\n").toByteArray())==result.getString("evidenceHash"));val price=result.getLong("priceCents");require(price in 1..InventoryRules.maxPriceCents)
  val provenance=JSONObject(result.toString()).put("jobId",row.jobId).put("historicalSoldEvidence",true).put("askingPrice",true).put("aiEstimate",false)
  val saved=old.copy(priceCents=price,priceSource="MARKET_AUTO_${rules.method}",priceEvidenceJson=provenance.toString(),ready=false,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString(),shopifyState=if(old.shopifyProductId.isBlank()) old.shopifyState else "LOCAL_CHANGES")
  journal.inventoryDao().update(saved);inventoryAudit(old,saved,"BULK_MARKET_PRICE_APPLIED");recordPriceDecision(saved,"OPERATOR_APPROVED_PRICE","AUTO_${rules.method}",JSONArray((0 until refs.length()).map {refs.getJSONObject(it).getString("id")}),provenance)
  dao.update(row.copy(state="APPLIED"));true
 };if(changed) count++} catch(e:CancellationException) {throw e} catch(e:Exception) {failures[id]=e.message.orEmpty().take(700)}}
 return count to failures
}
