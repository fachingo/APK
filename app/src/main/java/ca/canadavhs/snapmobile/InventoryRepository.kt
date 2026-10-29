package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.InventoryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime

fun inventoryJson(item: InventoryRow): JSONObject = JSONObject().apply {
    put("groupId", item.groupId); put("itemId", item.itemId); put("sku", item.itemId); put("batchId", item.batchId)
    put("title", item.title); put("releaseYear", item.releaseYear ?: JSONObject.NULL); put("edition", item.edition); put("distributor", item.distributor)
    put("condition", item.condition); put("conditionNotes", item.conditionNotes); put("location", item.location); put("barcode", item.barcode)
    put("priceCents", item.priceCents ?: JSONObject.NULL); put("discountBasisPoints", item.discountBasisPoints); put("quantity", item.quantity)
    put("poolMode", item.poolMode); put("vendor", item.vendor); put("productType", item.productType); put("tags", item.tags); put("description", item.description)
    put("weightGrams", item.weightGrams); put("rare", item.rare); put("review", item.review); put("ready", item.ready); put("archived", item.archived)
    put("priceSource", item.priceSource); put("priceEvidenceJson", item.priceEvidenceJson); put("recognitionId", item.recognitionId); put("captureNote", item.captureNote)
    put("shopifyProductId", item.shopifyProductId); put("shopifyVariantId", item.shopifyVariantId); put("shopifyInventoryItemId", item.shopifyInventoryItemId)
    put("shopifyState", item.shopifyState); put("revision", item.revision); put("createdAt", item.createdAt); put("updatedAt", item.updatedAt)
}
fun inventoryFromJson(value: JSONObject): InventoryRow {
    fun text(key: String, limit: Int = 1000): String = value.getString(key).also { require(it.length <= limit) { "Inventory $key is too long." } }
    val item = InventoryRow(groupId = text("groupId", 200), itemId = text("itemId", 200), batchId = text("batchId", 200),
        title = text("title", 255), releaseYear = if (value.isNull("releaseYear")) null else value.getInt("releaseYear"),
        edition = text("edition"), distributor = text("distributor"), condition = text("condition"), conditionNotes = text("conditionNotes",10000),
        location = text("location",120), barcode = text("barcode",14), priceCents = if(value.isNull("priceCents")) null else value.getLong("priceCents"),
        discountBasisPoints = value.getInt("discountBasisPoints"), quantity = value.getInt("quantity"), poolMode = text("poolMode"),
        vendor = text("vendor"), productType = text("productType"), tags = text("tags",4000), description = text("description",30000),
        weightGrams = value.getInt("weightGrams"), rare = value.getBoolean("rare"), review = value.getBoolean("review"), ready = value.getBoolean("ready"),
        archived = value.getBoolean("archived"), priceSource = text("priceSource"), priceEvidenceJson = text("priceEvidenceJson",100000),
        recognitionId = text("recognitionId"), captureNote = text("captureNote",10000), shopifyProductId = text("shopifyProductId"),
        shopifyVariantId = text("shopifyVariantId"), shopifyInventoryItemId = text("shopifyInventoryItemId"), shopifyState = text("shopifyState"),
        revision = value.getLong("revision"), createdAt = text("createdAt"), updatedAt = text("updatedAt"))
    require(value.getString("sku") == item.itemId) { "SKU must equal Item ID." }
    require(item.itemId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))) { "Invalid immutable Item ID." }
    require(item.batchId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))) { "Invalid inventory batch ID." }
    require(item.revision >= 0 && item.weightGrams in 1..1_000_000 && item.quantity >= 0)
    require(item.priceCents == null || item.priceCents in 0..InventoryRules.maxPriceCents)
    require(item.discountBasisPoints in 0..10000 && item.condition in InventoryRules.conditions && item.poolMode in listOf("POOLED","UNIQUE"))
    require(InventoryRules.validInventoryLocation(item.location))
    require(item.barcode.isEmpty() || (item.barcode.length in listOf(8,12,13,14) && item.barcode.all { it in '0'..'9' }))
    require(item.releaseYear == null || item.releaseYear in 1800..2200)
    OffsetDateTime.parse(item.createdAt); OffsetDateTime.parse(item.updatedAt)
    JSONObject(item.priceEvidenceJson)
    return item
}

internal suspend fun CaptureRepository.ensureInventoryBatch(session: SessionRow): InventoryBatchRow {
    val inventory = journal.inventoryDao()
    return inventory.batch(session.id) ?: InventoryBatchRow(session.id, InventoryRules.batchId(session.createdAt, session.id)).also { inventory.insert(it) }
}
internal suspend fun CaptureRepository.ensureInventoryItem(group: GroupRow): InventoryRow {
    val inventory = journal.inventoryDao()
    inventory.item(group.id)?.let { return it }
    val batch = ensureInventoryBatch(dao.session(group.sessionId))
    val item = InventoryRow(group.id, InventoryRules.itemId(batch.batchId, group.location, group.number), batch.batchId,
        location = group.location, rare = group.rare, review = group.review, poolMode = if(group.rare) "UNIQUE" else "POOLED",
        captureNote = group.note, createdAt = group.createdAt, weightGrams = preferences.getInt("defaultWeightGrams",250).coerceIn(1,1_000_000),
        vendor = preferences.getString("defaultVendor","Canada VHS") ?: "Canada VHS", productType = preferences.getString("defaultProductType","VHS Tape") ?: "VHS Tape")
    inventory.insert(item); inventoryAudit(null, item, "CREATED"); return item
}
internal suspend fun CaptureRepository.inventoryAudit(before: InventoryRow?, after: InventoryRow, action: String) {
    journal.inventoryDao().insert(InventoryAuditRow(groupId = after.groupId, action = action,
        beforeJson = before?.let { inventoryJson(it).toString() } ?: "{}", afterJson = inventoryJson(after).toString()))
}
suspend fun CaptureRepository.backfillInventory() = mutate {
    journal.openHelper.writableDatabase.query("SELECT id FROM sessions").use { cursor ->
        val ids = mutableListOf<String>(); while(cursor.moveToNext()) ids += cursor.getString(0)
        ids.forEach { id ->
            ensureInventoryBatch(dao.session(id))
            dao.groups(id).filter { dao.photos(it.id).isNotEmpty() }.forEach { ensureInventoryItem(it) }
        }
    }
}
fun effectiveBarcode(item: InventoryRow, group: GroupRow?, photos: List<PhotoRow>): String = item.barcode.ifBlank {
    group?.let { barcodeInfo(it, photos).optString("selected").takeUnless { value -> value == "null" } } ?: ""
}
fun inventoryProblems(item: InventoryRow, photos: List<PhotoRow>, repo: CaptureRepository): List<String> {
    val active = photos.filter { !it.deleted }.sortedBy { it.position }
    val ready = active.filter { it.state == "READY" }
    return InventoryRules.validation(item.title,item.priceCents,item.discountBasisPoints,item.quantity,item.condition,item.poolMode,
        item.location,item.barcode,item.itemId,item.itemId,ready.size,ready.firstOrNull()?.role == "FRONT") + buildList {
        if (active.any { it.state != "READY" }) add("Resolve unfinished photographs in Batches")
        if (ready.any { !repo.original(it.id).isFile || repo.original(it.id).length() != it.bytes }) add("An original photograph is missing or changed")
        if(item.weightGrams !in 1..1_000_000) add("Shipping weight must be 1–1,000,000 grams")
        if(item.priceSource.startsWith("MARKET_")) {
            if(runCatching {JSONObject(item.priceEvidenceJson).getString("titleKey")==InventoryRules.normalizedTitle(item.title)}.getOrDefault(false).not()) add("Market price belongs to a different title; review pricing")
        }
        if(item.priceSource.startsWith("STALE")) add("Price evidence is stale; review the price")
        if(item.priceSource.startsWith("SOLD")) {
            val valid=runCatching { val evidence=JSONObject(item.priceEvidenceJson)
                evidence.getString("titleKey")==InventoryRules.normalizedTitle(item.title)
            }.getOrDefault(false)
            if(!valid) add("Sold-price evidence changed or is invalid; review pricing")
        }
    }
}
suspend fun CaptureRepository.saveInventory(candidate: InventoryRow, expectedRevision: Long): InventoryRow = mutate {saveInventoryLocked(candidate,expectedRevision)}
internal suspend fun CaptureRepository.saveInventoryLocked(candidate: InventoryRow, expectedRevision: Long): InventoryRow = run {
    val inventory = journal.inventoryDao()
    val old = inventory.item(candidate.groupId) ?: error("Inventory item not found.")
    check(old.revision == expectedRevision) { "This item changed while you were editing. Reopen it before saving." }
    check(candidate.itemId == old.itemId && candidate.batchId == old.batchId && candidate.createdAt == old.createdAt) { "Physical identity cannot be changed." }
    check(dao.pending().isEmpty()) { "Wait for captures to save before editing inventory." }
    var saved = inventoryFromJson(inventoryJson(candidate)).copy(title = candidate.title.trim(), ready = false,
        revision = old.revision + 1, updatedAt = OffsetDateTime.now().toString())
    if (old.priceCents != saved.priceCents) saved = saved.copy(priceSource = if(saved.priceCents == null) "UNPRICED" else "MANUAL", priceEvidenceJson = "{}")
    else if ((InventoryRules.normalizedTitle(old.title) != InventoryRules.normalizedTitle(saved.title) || old.edition!=saved.edition || old.releaseYear!=saved.releaseYear || old.barcode!=saved.barcode) && (old.priceSource.startsWith("SOLD") || old.priceSource.startsWith("MARKET_") || old.priceSource=="OPERATOR_APPROVED")) saved = saved.copy(priceSource = "STALE_COMPARABLES")
    if(candidate.ready) {
        val errors = inventoryProblems(saved,dao.photos(saved.groupId),this)
        check(errors.isEmpty()) { errors.joinToString(". ") }; saved = saved.copy(ready = true)
    }
    if(old.shopifyProductId.isNotBlank()) saved = saved.copy(shopifyState = "LOCAL_CHANGES")
    inventory.update(saved); inventoryAudit(old,saved,"EDITED")
    if(old.priceCents!=saved.priceCents && saved.priceCents!=null) recordPriceDecision(saved,"MANUAL_ESTIMATE","MANUAL")
    saved
}
suspend fun CaptureRepository.archiveInventory(groupId:String,archived:Boolean)=mutate {archiveInventoryLocked(groupId,archived)}
internal suspend fun CaptureRepository.archiveInventoryLocked(groupId:String,archived:Boolean)=run {
    val inventory = journal.inventoryDao(); val old = inventory.item(groupId) ?: error("Item not found.")
    val saved = old.copy(archived = archived, ready = false, revision = old.revision + 1, updatedAt = OffsetDateTime.now().toString())
    inventory.update(saved); inventoryAudit(old,saved,if(archived) "ARCHIVED" else "RESTORED")
}
suspend fun CaptureRepository.applyRecognitionToInventory(id: String): InventoryRow = mutate {applyRecognitionLocked(id)}
internal suspend fun CaptureRepository.applyRecognitionLocked(id:String):InventoryRow=run {
    val suggestion = dao.recognition(id) ?: error("Suggestion not found.")
    check(suggestion.state == "SUCCEEDED") { "Wait for recognition to finish." }
    val title = suggestion.acceptedTitle?.takeIf { it.isNotBlank() } ?: error("Confirm the printed title in recognition review first.")
    val photos = dao.photos(suggestion.groupId).filter { !it.deleted && it.state == "READY" }
    check(photoFingerprint(photos) == suggestion.inputFingerprint) { "Photos changed; run recognition again before applying." }
    val inventory = journal.inventoryDao(); val old = inventory.item(suggestion.groupId) ?: error("Inventory item not found.")
    val result = JSONObject(suggestion.resultJson)
    val saved = old.copy(title = title, edition = old.edition.ifBlank { result.optString("edition").take(1000) },
        distributor = old.distributor.ifBlank { result.optString("distributor").take(1000) },
        releaseYear = old.releaseYear ?: result.optInt("year").takeIf { it in 1800..2200 },
        recognitionId = id, ready = false, revision = old.revision + 1, updatedAt = OffsetDateTime.now().toString(),
        priceSource = if(InventoryRules.normalizedTitle(old.title) != InventoryRules.normalizedTitle(title) && (old.priceSource.startsWith("SOLD") || old.priceSource.startsWith("MARKET_"))) "STALE_COMPARABLES" else old.priceSource)
    inventory.update(saved); inventoryAudit(old,saved,"RECOGNITION_APPLIED"); saved
}
