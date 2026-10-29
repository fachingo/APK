package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.time.OffsetDateTime

internal val shopifyMetadataFields=listOf("title","descriptionHtml","vendor","productType")
internal fun shopifyMetadata(item:InventoryRow)=JSONObject().put("title",item.title).put("descriptionHtml",item.description).put("vendor",item.vendor).put("productType",item.productType)
internal fun managementIdentity(product:JSONObject,item:InventoryRow,shopId:String,allowActive:Boolean=false):JSONObject {
    check(product.optString("status")=="DRAFT" || (allowActive && product.optString("status")=="ACTIVE")) {"Only a draft or explicitly reviewed active marked listing can be linked or updated."}
    val key=operationKey("snapims-create:$shopId:${item.itemId}")
    check(product.optJSONObject("metafield")?.optString("value")==key && product.optJSONObject("identity")?.optString("value")==item.itemId) {"This product lacks matching SnapIMS ownership markers. Matching title or UPC is insufficient."}
    val variants=product.getJSONObject("variants");val nodes=variants.getJSONArray("nodes")
    check(nodes.length()==1 && variants.optJSONObject("pageInfo")?.optBoolean("hasNextPage",true)==false) {"Choose a single-variant SnapIMS product; additional variants require manual reconciliation."}
    check(product.getJSONObject("media").getJSONObject("pageInfo").getBoolean("hasNextPage").not()) {"A complete remote image list is required for review."}
    val v=nodes.getJSONObject(0);check(v.getString("sku")==item.itemId) {"Remote SKU must equal this physical copy's immutable Item ID."}
    val remote=JSONObject().put("productId",product.getString("id")).put("variantId",v.getString("id")).put("inventoryItemId",v.getJSONObject("inventoryItem").getString("id"))
    for((field,kind) in listOf("productId" to "Product","variantId" to "ProductVariant","inventoryItemId" to "InventoryItem")) require(remote.getString(field).matches(Regex("gid://shopify/$kind/[0-9]{1,30}")))
    check(item.shopifyProductId.isBlank() || item.shopifyProductId==remote.getString("productId")) {"This copy is already linked to another product; automatic reassignment is disabled."}
    check(item.shopifyVariantId.isBlank() || item.shopifyVariantId==remote.getString("variantId")) {"Saved variant identity changed."}
    check(item.shopifyInventoryItemId.isBlank() || item.shopifyInventoryItemId==remote.getString("inventoryItemId")) {"Saved inventory identity changed."}
    require(product.getString("reviewVersion").matches(Regex("[a-f0-9]{64}"))) {"Refresh the listing to obtain a reviewed version."}
    return remote
}
internal fun protectedProduct(product:JSONObject,patch:JSONObject):String {
    val copy=JSONObject(product.toString());copy.remove("reviewVersion");copy.remove("updatedAt");patch.keys().forEach {copy.remove(it)}
    copy.optJSONObject("variants")?.optJSONArray("nodes")?.let {nodes->for(i in 0 until nodes.length()) {val v=nodes.getJSONObject(i);if(patch.has("priceCents")) v.remove("price");if(patch.has("sku")) v.remove("sku");val inv=v.optJSONObject("inventoryItem");if(patch.has("weightGrams")) inv?.optJSONObject("measurement")?.remove("weight");if(patch.has("quantity")) inv?.remove("inventoryLevel")}}
    return canonicalJson(copy)
}
private fun CaptureRepository.checkReviewedConnection(payload:JSONObject) {
    val base=payload.getString("broker");check(preferences.getBoolean("shopifyPaired",false) && shopifyEndpoint()==base && shopifyConnectionFingerprint(context,base)==payload.getString("connectionFingerprint")) {"Shopify connection changed. Prepare a fresh review."}
}
internal suspend fun CaptureRepository.prepareShopifyManagement(groupId:String,productId:String,health:JSONObject,base:String,location:String,fields:Set<String>,gateway:ShopifyGateway=shopifyGateway(context,base)):ShopifyReviewRow {
    requireShopifyWriteHealth(health)
    check(health.getJSONArray("locations").let {a->(0 until a.length()).any {a.getJSONObject(it).getString("id")==location && a.getJSONObject(it).optBoolean("isActive")}}) {"Select an active inventory location."}
    require(fields.all {it in if(directShopifyEndpoint(base)) directShopifyFields else shopifyMetadataFields})
    val product=gateway.call(newId(),"product",JSONObject().put("productId",productId).put("locationId",location))
    return mutate {
        val item=journal.inventoryDao().item(groupId) ?: error("Local copy missing");check(!item.archived)
        val remote=managementIdentity(product,item,health.getString("shopId"),directShopifyEndpoint(base))
        check(journal.inventoryDao().items().none {it.groupId!=groupId && it.shopifyProductId==remote.getString("productId")}) {"Another local copy is already linked to this product."}
        val kind=if(fields.isEmpty()) "LINK_EXISTING" else "UPDATE_METADATA"
        check(kind!="UPDATE_METADATA" || item.shopifyProductId==productId) {"Link the exact marked listing first; then review metadata updates."}
        val metadata=if(directShopifyEndpoint(base)) directShopifyMetadata(item,health.getString("shopId")) else shopifyMetadata(item);val patch=JSONObject();fields.forEach {key->val value=metadata.get(key);if(value is String) {require(value.length<=if(key=="descriptionHtml") 60000 else 255);require(key!="title" || value.isNotBlank())};if(key=="priceCents") require(value is Number && value.toLong() in 0..InventoryRules.maxPriceCents);if(key=="quantity") require((value as Number).toInt() in 0..1);if(key=="weightGrams") require((value as Number).toInt() in 1..1000000);if(canonicalJson(product.opt(key))!=canonicalJson(value)) patch.put(key,value)}
        check(kind!="UPDATE_METADATA" || patch.length()>0) {"The selected metadata already matches Shopify."}
        val payload=JSONObject().put("group_ids",JSONArray(listOf(groupId))).put("items",JSONArray().put(inventoryJson(item))).put("health",health).put("broker",base).put("location",location)
            .put("connectionFingerprint",shopifyConnectionFingerprint(context,base)).put("before",product).put("patch",patch).put("remote",remote)
        checkReviewedConnection(payload);check(payload.toString().length<900000)
        ShopifyReviewRow(kind=kind,payloadJson=payload.toString(),bindingHash=workflowBinding(listOf(groupId))).also {journal.shopifyDao().insert(it)}
    }
}
internal suspend fun CaptureRepository.prepareShopifyUploadReview(groups:Set<String>,health:JSONObject,base:String,location:String,live:Boolean,publication:String):ShopifyReviewRow=mutate {
    require(groups.size in 1..100);requireShopifyWriteHealth(health)
    val items=groups.map {journal.inventoryDao().item(it) ?: error("Copy missing")}
    if(!live) items.forEach {check(shopifyProblems(it,dao.photos(it.groupId).filter {!it.deleted}).isEmpty()) {shopifyProblems(it,dao.photos(it.groupId).filter {!it.deleted}).joinToString(". ")}}
    val payload=JSONObject().put("group_ids",JSONArray(groups.toList())).put("items",JSONArray(items.map {item->inventoryJson(item).put("reviewedBarcode",effectiveBarcode(item,dao.group(item.groupId),dao.photos(item.groupId)))})).put("health",health).put("broker",base).put("location",location)
        .put("publication",publication).put("connectionFingerprint",shopifyConnectionFingerprint(context,base))
    items.forEach {item->if(item.barcode.isBlank()) check(barcodeInfo(dao.group(item.groupId),dao.photos(item.groupId)).getString("status") !in listOf("SCANNING","SCAN_ERROR","AMBIGUOUS")) {"Resolve barcode scanning before preparing a review."}}
    val photos=JSONObject();items.forEach {i->photos.put(i.groupId,JSONArray(dao.photos(i.groupId).filter {!it.deleted}.sortedBy {it.position}.map {p->JSONObject().put("id",p.id).put("role",p.role)}))};payload.put("photos",photos)
    checkReviewedConnection(payload);check(payload.toString().length<900000) {"Review a smaller selection."}
    ShopifyReviewRow(kind=if(live) "PUBLISH_LIVE" else "CREATE_DRAFT",payloadJson=payload.toString(),bindingHash=workflowBinding(groups.toList())).also {journal.shopifyDao().insert(it)}
}
internal suspend fun CaptureRepository.decideShopifyReview(id:String,accept:Boolean,confirmation:String=""):String {
    val initial=journal.shopifyDao().review(id) ?: error("Review missing");if(initial.kind=="OWNER_ACTION") return decideShopifyOwnerReview(initial,accept,confirmation)
    var scheduled:String?=null
    val message=mutate {
        val dao=journal.shopifyDao();val review=dao.review(id) ?: error("Review missing");check(review.state=="PENDING") {"This review was already handled."}
        if(!accept) {dao.update(review.copy(state="REJECTED",completedAt=System.currentTimeMillis()));return@mutate "Review rejected."}
        val payload=JSONObject(review.payloadJson);checkReviewedConnection(payload);val groups=jsonIds(payload.getJSONArray("group_ids"),100)
        if(workflowBinding(groups)!=review.bindingHash) {dao.update(review.copy(state="EXPIRED",resultJson="{\"error\":\"Local items or photographs changed; prepare a fresh review\"}",completedAt=System.currentTimeMillis()));return@mutate "Items or photographs changed. This review expired."}
        check(!shopifyReviewNeedsSubmit(review) || confirmation=="SUBMIT") {"Type SUBMIT to approve live, destructive or financial changes."};val health=payload.getJSONObject("health");val base=payload.getString("broker");val location=payload.getString("location")
        val jobId=if(review.kind in listOf("CREATE_DRAFT","PUBLISH_LIVE")) queueShopifyLocked(groups.toSet(),health,base,location,review.kind=="PUBLISH_LIVE",payload.getString("publication"),confirmation)
        else {
            check(review.kind in listOf("LINK_EXISTING","UPDATE_METADATA") && groups.size==1)
            check(dao.unfinishedForGroup(groups.single()).isEmpty()) {"An unfinished job owns this copy. Review or cancel that job first."}
            val item=journal.inventoryDao().item(groups.single())!!;check(!item.archived)
            val before=payload.getJSONObject("before");val remote=managementIdentity(before,item,health.getString("shopId"),directShopifyEndpoint(base));val patch=payload.getJSONObject("patch")
            val job=ShopifyJobRow(kind=review.kind,shopId=health.getString("shopId"),shop=health.getString("shop"),broker=base,connectionFingerprint=payload.getString("connectionFingerprint"),locationId=location)
            val snapshot=inventoryJson(item).put("before",before).put("patch",patch)
            dao.insert(job);dao.insert(ShopifyItemJobRow(jobId=job.id,groupId=item.groupId,itemId=item.itemId,expectedRevision=item.revision,inputFingerprint=shopifyFingerprint(item,this.dao.photos(item.groupId).filter {!it.deleted}.sortedBy {it.position}),snapshotJson=snapshot.toString(),creationKey=operationKey("snapims-create:${job.shopId}:${item.itemId}"),remoteJson=remote.toString()));job.id
        }
        dao.update(review.copy(state="APPLIED",resultJson=JSONObject().put("jobId",jobId).toString(),completedAt=System.currentTimeMillis()));scheduled=jobId
        "Approved job saved. Check Shopify jobs for verified remote completion."
    }
    // Durable QUEUED job rows are recovered if the process stops before enqueue completes.
    scheduled?.let {try {withContext(Dispatchers.IO) {enqueueShopify(it).result.get()}} catch(e:CancellationException) {throw e} catch(_:Exception) {return "Approved job saved; scheduling needs attention. Open Shopify jobs to resume existing work."}};return message
}
internal class ShopifyManagementPipeline(private val repo:CaptureRepository,private val gateway:ShopifyGateway) {
    private val jobs=repo.journal.shopifyDao()
    suspend fun run(job:ShopifyJobRow,initial:ShopifyItemJobRow) {
        if(initial.state=="SUCCESS") return
        var row=initial;val snapshot=JSONObject(row.snapshotJson);val before=snapshot.getJSONObject("before");val patch=snapshot.getJSONObject("patch");val cp=JSONObject(row.checkpointJson);val remote=JSONObject(row.remoteJson)
        suspend fun current() {
            currentCoroutineContext().ensureActive();check(jobs.job(job.id)?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) {"Job paused or cancelled."}
            val item=repo.journal.inventoryDao().item(row.groupId) ?: error("Item missing");check(!item.archived && item.revision==row.expectedRevision && shopifyFingerprint(item,repo.dao.photos(row.groupId).filter {!it.deleted}.sortedBy {it.position})==row.inputFingerprint) {"Local item or photos changed after approval."}
        }
        suspend fun save(step:String) {repo.mutate {current();row=row.copy(state="RUNNING",step=step,checkpointJson=cp.toString(),updatedAt=System.currentTimeMillis());jobs.update(row)}}
        suspend fun read():JSONObject {current();return gateway.call(newId(),"product",JSONObject().put("productId",remote.getString("productId")).put("locationId",job.locationId))}
        try {
            current();val item=repo.journal.inventoryDao().item(row.groupId)!!;val now=read();managementIdentity(now,item,job.shopId,directShopifyEndpoint(job.broker))
            if(!cp.optBoolean("sent")) check(now.getString("reviewVersion")==before.getString("reviewVersion")) {"Shopify changed after review. Prepare a fresh review; no metadata update was sent."}
            val args=JSONObject().put("creationKey",row.creationKey).put("itemId",row.itemId).put("productId",remote.getString("productId")).put("locationId",job.locationId).put("expectedVersion",before.getString("reviewVersion"))
            if(!cp.optBoolean("claimed")) {save("CLAIM_OWNERSHIP");gateway.call(operationKey("${row.id}:CLAIM"),"claim",args);cp.put("claimed",true);save("OWNERSHIP_CONFIRMED")}
            if(job.kind=="UPDATE_METADATA") {check(patch.length()>0 && patch.keys().asSequence().all {it in if(directShopifyEndpoint(job.broker)) directShopifyFields else shopifyMetadataFields});cp.put("sent",true);save("UPDATE_METADATA")
                if(directShopifyEndpoint(job.broker)) applyOwnedFields(gateway,repo,job,row,args,patch,cp,::save)
                else gateway.call(operationKey("${row.id}:METADATA"),"metadata_update",JSONObject(args.toString()).put("patch",patch))}
            val final=read();managementIdentity(final,item,job.shopId,directShopifyEndpoint(job.broker))
            if(job.kind=="LINK_EXISTING") check(final.getString("reviewVersion")==before.getString("reviewVersion")) {"The listing changed while linking; review again."}
            else {check(protectedProduct(final,patch)==protectedProduct(before,patch)) {"An unselected Shopify field changed. Inspect the product before further updates."};patch.keys().forEach {check(canonicalJson(final.get(it))==canonicalJson(patch.get(it))) {"Shopify has not confirmed the selected $it update."}}}
            remote.put("reviewVersion",final.getString("reviewVersion")).put("fieldsUpdated",JSONArray(patch.keys().asSequence().toList()))
            repo.mutate {current();val old=repo.journal.inventoryDao().item(row.groupId)!!
                check(repo.journal.inventoryDao().items().none {it.groupId!=old.groupId && it.shopifyProductId==remote.getString("productId")}) {"Another copy is linked to this product."}
                val saved=old.copy(shopifyProductId=remote.getString("productId"),shopifyVariantId=remote.getString("variantId"),shopifyInventoryItemId=remote.getString("inventoryItemId"),shopifyState=if(final.getString("status")=="ACTIVE") "LINKED_ACTIVE" else "LINKED_DRAFT",revision=old.revision+1,updatedAt=OffsetDateTime.now().toString())
                repo.journal.inventoryDao().update(saved);repo.inventoryAudit(old,saved,if(job.kind=="LINK_EXISTING") "SHOPIFY_LINKED_DRAFT" else "SHOPIFY_METADATA_UPDATED")
                jobs.update(row.copy(state="SUCCESS",step="VERIFIED",remoteJson=remote.toString(),checkpointJson=cp.toString(),error="",updatedAt=System.currentTimeMillis()))}
        } catch(e:CancellationException) {throw e} catch(e:Exception) {
            repo.mutate {if(jobs.job(job.id)?.state !in listOf("CANCELLED","PAUSED")) {val state=when {e is ShopifyBrokerException && e.code=="UNCERTAIN"->"UNCERTAIN";e is ShopifyBrokerException && (e.httpStatus>=500 || e.httpStatus==429)->"RETRYABLE";else->"NEEDS_REVIEW"};jobs.update(row.copy(state=state,error=e.message.orEmpty().take(1500),checkpointJson=cp.toString(),remoteJson=remote.toString(),attempts=row.attempts+1,updatedAt=System.currentTimeMillis()))}};throw e
        }
    }
}
