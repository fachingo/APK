package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

internal suspend fun CaptureRepository.recordPriceDecision(item:InventoryRow,type:String,method:String,evidence:JSONArray=JSONArray(),provenance:JSONObject=JSONObject()) {
    val price=item.priceCents ?: return
    require(type in listOf("OPERATOR_APPROVED_PRICE","MANUAL_ESTIMATE","AI_SUGGESTION"))
    journal.marketDao().insert(PriceDecisionRow(groupId=item.groupId,itemId=item.itemId,title=item.title,titleKey=InventoryRules.normalizedTitle(item.title),edition=item.edition,releaseYear=item.releaseYear,barcode=item.barcode,
        recordType=type,priceCents=price,method=method,evidenceJson=evidence.toString(),provenanceJson=(if(method=="MANUAL" && provenance.length()==0) manualMarketContextLocked(item) else provenance).toString(),createdAt=OffsetDateTime.now().toString()))
}
internal fun marketEvidenceOldestAge(date:String):Long=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(date.take(10)),LocalDate.now())
internal suspend fun CaptureRepository.applyMarketPrice(reviewed:MarketRecommendation,method:String,adjustedCents:Long?,overrideManual:Boolean,allowStale:Boolean,rateText:String="",rateDate:String=""):InventoryRow=mutate {
    require(method in listOf("MEDIAN","AVERAGE"));val old=journal.inventoryDao().item(reviewed.groupId) ?: error("Item missing")
    check(old.revision==reviewed.revision && InventoryRules.normalizedTitle(old.title)==reviewed.titleKey && old.edition==reviewed.edition && old.releaseYear==reviewed.year) {"Item identity changed. Review a fresh recommendation."}
    check(old.priceSource!="MANUAL" || overrideManual) {"Explicitly approve replacing the manual price."}
    val current=recommendMarketLocked(old,reviewed.currency) ?: error("Compatible evidence changed. Review a fresh recommendation.")
    check(current.hash==reviewed.hash && current.ids.toSet()==reviewed.ids.toSet()) {"Local sold evidence changed after review. Calculate and approve a fresh recommendation."}
    val stale=marketEvidenceOldestAge(reviewed.oldest)>preferences.getInt("marketAgeDays",180)
    check(!stale || allowStale) {"Evidence is older than the configured age. Explicitly approve historical reuse."}
    val refs=JSONArray();val values=mutableListOf<SoldComparable>();val hashes=mutableListOf<String>()
    reviewed.ids.forEach {id->val row=journal.marketDao().sale(id) ?: error("Evidence missing");check(row.current && row.eligible && row.currency==reviewed.currency && PricingRules.listingMatches(old.title,row.title)) {"Evidence changed or cannot support this title."}
        hashes+=row.payloadHash;refs.put(id);values+=SoldComparable(marketMoney(row.soldDecimal),marketMoney(row.shippingDecimal),row.title,row.url,row.soldAt)
    }
    check(sha256(hashes.sorted().joinToString("\n").toByteArray())==reviewed.hash) {"Evidence changed; review again."}
    val stats=PricingRules.statistics(values) ?: error("No sold comparisons")
    val sourceCents=if(method=="MEDIAN") stats.medianCents else stats.averageCents
    val rate=if(reviewed.currency=="CAD") BigDecimal.ONE else try {BigDecimal(rateText).also {check(it>BigDecimal.ZERO && it<=BigDecimal("1000") && it.scale()<=8);LocalDate.parse(rateDate)}} catch(_:Exception) {error("Enter an explicit positive conversion rate to CAD and its effective date. No live exchange rate was fetched.")}
    val recommended=BigDecimal(sourceCents).multiply(rate).setScale(0,RoundingMode.HALF_UP).longValueExact()
    val price=adjustedCents ?: recommended;require(price in 1..InventoryRules.maxPriceCents)
    val provenance=JSONObject().put("titleKey",reviewed.titleKey).put("marketIds",refs).put("hash",reviewed.hash).put("method",method).put("currency",reviewed.currency).put("sourceCents",sourceCents).put("count",stats.count)
        .put("newestSale",reviewed.newest).put("oldestSale",reviewed.oldest).put("historicalReuseApproved",allowStale).put("conversionRate",rate.toPlainString()).put("conversionDate",if(reviewed.currency=="CAD") "" else rateDate).put("recommendedCadCents",recommended).put("operatorAdjusted",price!=recommended)
    val saved=old.copy(priceCents=price,priceSource="MARKET_$method",priceEvidenceJson=provenance.toString(),ready=false,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString(),shopifyState=if(old.shopifyProductId.isBlank()) old.shopifyState else "LOCAL_CHANGES")
    journal.inventoryDao().update(saved);inventoryAudit(old,saved,"MARKET_PRICE_APPROVED");recordPriceDecision(saved,"OPERATOR_APPROVED_PRICE",if(price!=recommended) "ADJUSTED_$method" else method,refs,provenance);saved
}
internal suspend fun CaptureRepository.operatorPriceHint(title:String):PriceDecisionRow? {
    if(!preferences.getBoolean("reuseOperatorPrices",false)) return null
    return journal.marketDao().decisions(InventoryRules.normalizedTitle(title),20).firstOrNull {it.titleKey==InventoryRules.normalizedTitle(title) && it.recordType=="OPERATOR_APPROVED_PRICE"}
}

internal fun CaptureRepository.manualMarketContextLocked(item:InventoryRow):JSONObject {
    val context=JSONObject().put("note","Local market lookup at manual pricing; asking price is not a sold record.")
    return try {ensureMarketSearch();for(currency in listOf("CAD","USD")) {val r=recommendMarketLocked(item,currency);context.put(currency,if(r==null) JSONObject().put("compatibleSoldCount",0) else JSONObject().put("compatibleSoldCount",r.stats.count).put("medianCents",r.stats.medianCents).put("newestSale",r.newest).put("evidenceHash",r.hash))};context} catch(e:Exception) {context.put("lookupWarning",e.message.orEmpty().take(500))}
}
internal suspend fun CaptureRepository.applyOperatorPriceHint(groupId:String,decisionId:String,revision:Long,overrideManual:Boolean):InventoryRow=mutate {
    check(preferences.getBoolean("reuseOperatorPrices",false)) {"Enable approved asking-price hints first."}
    val old=journal.inventoryDao().item(groupId) ?: error("Item missing");check(old.revision==revision)
    val row=journal.openHelper.writableDatabase.query("SELECT * FROM price_decisions WHERE id=?",arrayOf(decisionId)).use {c->check(c.moveToFirst());cursorObject(c)}
    check(row.getString("recordType")=="OPERATOR_APPROVED_PRICE" && row.getString("titleKey")==InventoryRules.normalizedTitle(old.title) && row.getString("edition")==old.edition && (if(row.isNull("releaseYear")) null else row.getInt("releaseYear"))==old.releaseYear)
    check(old.priceSource!="MANUAL" || overrideManual) {"Explicitly approve replacing a manual price."}
    val saved=old.copy(priceCents=row.getLong("priceCents"),priceSource="OPERATOR_APPROVED",priceEvidenceJson=JSONObject().put("decisionId",decisionId).put("titleKey",row.getString("titleKey")).toString(),ready=false,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString(),shopifyState=if(old.shopifyProductId.isBlank()) old.shopifyState else "LOCAL_CHANGES")
    journal.inventoryDao().update(saved);inventoryAudit(old,saved,"ASKING_PRICE_REUSED");recordPriceDecision(saved,"OPERATOR_APPROVED_PRICE","PRIOR_ASKING_PRICE",provenance=JSONObject().put("sourceDecisionId",decisionId));saved
}
