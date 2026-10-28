package ca.canadavhs.snapmobile

import android.database.sqlite.SQLiteDatabase
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

internal fun stableRowId(value:String)=UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
fun comparableJson(value:SoldComparable)=JSONObject().put("sold",value.sold.toPlainString()).put("shipping",value.shipping.toPlainString())
    .put("title",value.title).put("url",value.url).put("soldDate",value.soldDate)
fun PricingEvidenceRow.comparables():List<SoldComparable> {
    val values=JSONArray(listingsJson)
    require(values.length()<=10000)
    return (0 until values.length()).map { i -> values.getJSONObject(i).let {
        SoldComparable(BigDecimal(it.getString("sold")),BigDecimal(it.getString("shipping")),it.getString("title"),it.getString("url"),it.getString("soldDate"))
    } }
}
fun PricingEvidenceRow.statistics()=PricingRules.statistics(comparables().filter {PricingRules.listingMatches(queriedTitle,it.title)})
fun PricingEvidenceRow.usable(now:OffsetDateTime=OffsetDateTime.now()):Boolean = try {
    currency=="CAD" && status.equals("Complete",true) && OffsetDateTime.parse(collectedAt)<=now.plusMinutes(5) && run {OffsetDateTime.parse(expiresAt);statistics()!=null}
} catch(_:Exception) {false}

fun PricingEvidenceRow.fresh(now:OffsetDateTime=OffsetDateTime.now()):Boolean=usable(now) && runCatching {OffsetDateTime.parse(expiresAt)>now}.getOrDefault(false)

/** Reads a consistent, exported desktop SQLite file. Never opens or changes the desktop database. */
suspend fun CaptureRepository.importPricingDatabase(file:File):Int=withContext(Dispatchers.IO) {
    require(file.length() in 16..512_000_000) {"Choose an exported pricing SQLite database under 512 MB."}
    val source="sha256:${hashFile(file)}";val rows=mutableListOf<PricingEvidenceRow>();var metadataBytes=0L
    SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA quick_check",null).use {check(it.moveToFirst() && it.getString(0)=="ok") {"Pricing database integrity check failed."}}
        db.rawQuery("SELECT evidence_id,collection_id,title_key,queried_title,currency,status,search_url,collected_at,expires_at FROM pricing_evidence ORDER BY evidence_id",null).use { c ->
            while(c.moveToNext()) {
                require(rows.size<10000) {"Export a pricing subset of at most 10,000 collections."}
                val listings=JSONArray()
                db.rawQuery("SELECT sold_price_cad,shipping_price_cad,listing_title,listing_url,sold_date FROM pricing_evidence_listings WHERE evidence_id=? ORDER BY ordinal",arrayOf(c.getString(0))).use { l ->
                    while(l.moveToNext()) {
                        require(listings.length()<10000)
                        val value=SoldComparable(BigDecimal(l.getString(0)),BigDecimal(l.getString(1)),l.getString(2),l.getString(3),l.getString(4))
                        PricingRules.statistics(listOf(value));require(value.title.length<=2000 && value.url.length<=4000)
                        listings.put(comparableJson(value))
                    }
                }
                for(index in 1 until c.columnCount) require(c.getString(index).length<=4000) {"Pricing metadata field is too large."}
                require(listings.toString().length<=1_000_000) {"Export smaller pricing collections for phone backup compatibility."}
                val title=c.getString(3);require(title.length in 1..1000 && InventoryRules.normalizedTitle(title)==c.getString(2)) {"Pricing title identity does not match this schema."}
                val collection=c.getString(1);require(collection.length in 1..200)
                val payload=JSONObject().put("collection",collection).put("title",title).put("currency",c.getString(4)).put("status",c.getString(5))
                    .put("searchUrl",c.getString(6)).put("collectedAt",c.getString(7)).put("expiresAt",c.getString(8)).put("listings",listings)
                metadataBytes+=payload.toString().toByteArray(Charsets.UTF_8).size
                require(metadataBytes<=16_000_000) {"Import a pricing subset under 16 MB of evidence metadata."}
                rows+=PricingEvidenceRow(stableRowId("$source/$collection"),source,collection,1,title,c.getString(2),currency=c.getString(4),status=c.getString(5),
                    searchUrl=c.getString(6),collectedAt=c.getString(7),expiresAt=c.getString(8),listingsJson=listings.toString(),evidenceHash=sha256(canonicalJson(payload).toByteArray()))
            }
        }
    }
    mutate {var count=0;rows.forEach { row ->val existing=journal.nextDao().evidence(row.id)
        if(existing==null) {journal.nextDao().insert(row);count++} else check(existing.evidenceHash==row.evidenceHash) {"Conflicting pricing collection; import stopped."}
    };count}
}
suspend fun CaptureRepository.applySoldPrice(groupId:String,evidenceId:String,method:String,expectedRevision:Long,overrideManual:Boolean=false):InventoryRow=mutate {applySoldPriceLocked(groupId,evidenceId,method,expectedRevision,overrideManual)}
internal suspend fun CaptureRepository.applySoldPriceLocked(groupId:String,evidenceId:String,method:String,expectedRevision:Long,overrideManual:Boolean):InventoryRow=run {
    require(method in listOf("AVERAGE","MEDIAN"))
    val old=journal.inventoryDao().item(groupId) ?: error("Inventory item not found.")
    check(old.revision==expectedRevision) {"Item changed. Review the current item first."}
    check(old.priceSource!="MANUAL" || overrideManual) {"Explicitly confirm replacing the manual price."}
    val evidence=journal.nextDao().evidence(evidenceId) ?: error("Pricing evidence not found.")
    check(evidence.titleKey==InventoryRules.normalizedTitle(old.title) && evidence.usable()) {"Evidence is expired, missing, or does not match this title. Review manually."}
    check(evidence.fresh() || preferences.getBoolean("reuseHistoricalSold",false)) {"This stored evidence is older than its online cache period. Enable reviewed historical reuse or use Market database; it has not been deleted."}
    val stats=evidence.statistics() ?: error("No valid sold comparables.")
    val price=if(method=="AVERAGE") stats.averageCents else stats.medianCents
    require(price in 0..InventoryRules.maxPriceCents)
    val provenance=JSONObject().put("evidenceId",evidence.id).put("sourceId",evidence.sourceId).put("collectionId",evidence.collectionId)
        .put("historicalReuseApproved",!evidence.fresh()).put("hash",evidence.evidenceHash).put("method",method).put("count",stats.count).put("currency","CAD")
        .put("collectedAt",evidence.collectedAt).put("expiresAt",evidence.expiresAt).put("titleKey",evidence.titleKey)
    val saved=old.copy(priceCents=price,priceSource="SOLD_$method",priceEvidenceJson=provenance.toString(),ready=false,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString())
    journal.inventoryDao().update(saved);inventoryAudit(old,saved,"SOLD_PRICE_APPLIED");recordPriceDecision(saved,"OPERATOR_APPROVED_PRICE",method,JSONArray().put(evidence.id),provenance);saved
}

/** Local draft preparation only. No network write or uploaded status is implied. */
suspend fun CaptureRepository.prepareListing(groupId:String):ListingPlanRow=mutate {prepareListingLocked(groupId)}
internal suspend fun CaptureRepository.prepareListingLocked(groupId:String):ListingPlanRow=run {
    val item=journal.inventoryDao().item(groupId) ?: error("Item not found.")
    val photos=dao.photos(groupId).filter {!it.deleted && it.state=="READY"}.sortedBy {it.position}
    val issues=inventoryProblems(item,photos,this).toMutableList()
    if(!item.ready) issues+="Mark this item ready after review"
    if(item.shopifyProductId.isNotEmpty()) issues+="Existing remote product requires explicit reconciliation"
    val payload=JSONObject().put("title",item.title).put("status","DRAFT").put("itemId",item.itemId).put("sku",item.itemId)
        .put("priceCents",item.priceCents ?: JSONObject.NULL).put("discountBasisPoints",item.discountBasisPoints).put("quantity",item.quantity)
        .put("vendor",item.vendor).put("productType",item.productType).put("tags",item.tags).put("description",item.description).put("weightGrams",item.weightGrams)
        .put("photos",JSONArray(photos.map {JSONObject().put("id",it.id).put("sha256",it.sha256).put("role",it.role)}))
    val fingerprint=sha256((canonicalJson(inventoryJson(item))+photoFingerprint(photos)).toByteArray())
    journal.nextDao().plan(groupId,fingerprint) ?: ListingPlanRow(groupId=groupId,itemId=item.itemId,inventoryRevision=item.revision,inputFingerprint=fingerprint,
        payloadJson=payload.toString(),errorsJson=JSONArray(issues).toString(),state=if(issues.isEmpty()) "PREPARED_LOCAL" else "NEEDS_REVIEW").also {journal.nextDao().insert(it)}
}

suspend fun CaptureRepository.applySoldPrices(reviewed:List<Pair<InventoryRow,PricingEvidenceRow>>,method:String,overrideManual:Boolean):Int=mutate {
    require(reviewed.size in 1..200 && reviewed.map {it.first.groupId}.distinct().size==reviewed.size)
    reviewed.forEach {(item,evidence)->applySoldPriceLocked(item.groupId,evidence.id,method,item.revision,overrideManual)}
    reviewed.size
}
