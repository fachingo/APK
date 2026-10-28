package ca.canadavhs.snapmobile

import android.content.Context
import androidx.work.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun operationKey(value:String)=UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
internal fun shopifyFingerprint(item:InventoryRow,photos:List<PhotoRow>)=sha256((canonicalJson(inventoryJson(item))+photoFingerprint(photos)).toByteArray())
internal fun shopifyConnectionFingerprint(context:Context,base:String)=sha256((base+"\n"+ShopifyDeviceIdentity(context).deviceId).toByteArray())
internal fun shopifyTags(item:InventoryRow)=item.tags.split(',').map {it.trim()}.filter {it.isNotEmpty()}.distinct()
internal fun shopifyDescription(item:InventoryRow)=item.description
internal fun shopifySnapshot(item:InventoryRow,photos:List<PhotoRow>,barcode:String):JSONObject=inventoryJson(item).apply {
    put("effectiveBarcode",barcode);put("finalPriceCents",InventoryRules.discounted(item.priceCents!!,item.discountBasisPoints));put("descriptionHtml",shopifyDescription(item));put("publishTags",JSONArray(shopifyTags(item)))
    put("photos",JSONArray().apply {photos.forEachIndexed {index,p->put(JSONObject().put("id",p.id).put("sha256",p.sha256).put("bytes",p.bytes).put("filename",InventoryRules.photoName(item.itemId,index+1)))}})
}
internal fun CaptureRepository.shopifyProblems(item:InventoryRow,photos:List<PhotoRow>):List<String> = inventoryProblems(item,photos,this)+buildList {
    if(!item.ready || item.archived) add("Mark this active item ready after review")
    if(item.quantity!=1) add("One physical copy must have quantity one")
    if(item.shopifyProductId.isNotBlank()) add("This item already has a Shopify product; automatic replacement is disabled")
    if(photos.size>100) add("A draft supports at most 100 photographs")
    if(shopifyTags(item).size>99 || shopifyTags(item).any {it.length>255}) add("Review product tags")
}
internal suspend fun CaptureRepository.queueShopify(groups:Set<String>,health:JSONObject,base:String,location:String,live:Boolean=false,publication:String="",confirmation:String=""):String {
    val id=withContext(Dispatchers.IO) {mutate {queueShopifyLocked(groups,health,base,location,live,publication,confirmation)}}
    enqueueShopify(id);return id
}
internal suspend fun CaptureRepository.queueShopifyLocked(groups:Set<String>,health:JSONObject,base:String,location:String,live:Boolean=false,publication:String="",confirmation:String=""):String {
    requireBrokerUrl(base);require(groups.size in 1..100);require(health.getString("currency")=="CAD")
    check(health.getBoolean("developmentStore")) {"This Android preview requires a development store. Production writes are not enabled."}
    check(health.getJSONArray("locations").let {array->(0 until array.length()).any {array.getJSONObject(it).getString("id")==location && array.getJSONObject(it).optBoolean("isActive")}}) {"Choose an active Shopify location."}
    if(live) {check(confirmation=="SUBMIT") {"Type SUBMIT to approve live publication."};check(health.optBoolean("allowPublish"));check(health.getJSONArray("publications").let {a->(0 until a.length()).any {a.getJSONObject(it).getString("id")==publication}})}
    val job=ShopifyJobRow(kind=if(live) "PUBLISH_LIVE" else "CREATE_DRAFT",shopId=health.getString("shopId"),shop=health.getString("shop"),broker=base,connectionFingerprint=shopifyConnectionFingerprint(context,base),locationId=location,publicationId=publication)
    run {
        val jobs=journal.shopifyDao();jobs.insert(job)
        groups.forEach {id->val item=journal.inventoryDao().item(id) ?: error("Inventory item missing")
            val photos=dao.photos(id).filter {!it.deleted}.sortedBy {it.position}
            check(jobs.unfinishedForGroup(id).isEmpty()) {"An unfinished Shopify job already owns this item."}
            if(live) check(item.ready && !item.archived && item.shopifyState=="UPLOADED_AS_DRAFT" && item.shopifyProductId.isNotBlank()) {"Only a verified unchanged draft can be published."}
            else check(shopifyProblems(item,photos).isEmpty()) {shopifyProblems(item,photos).joinToString(". ")}
            if(item.barcode.isBlank()) check(barcodeInfo(dao.group(id),photos).getString("status") !in listOf("SCANNING","SCAN_ERROR","AMBIGUOUS")) {"Resolve barcode recognition before Shopify approval."}
            photos.forEach {check(hashFile(original(it.id))==it.sha256) {"An original photograph changed."}}
            val key=operationKey("snapims-create:${job.shopId}:${item.itemId}")
            jobs.insert(ShopifyItemJobRow(jobId=job.id,groupId=id,itemId=item.itemId,expectedRevision=item.revision,inputFingerprint=shopifyFingerprint(item,photos),snapshotJson=shopifySnapshot(item,photos,effectiveBarcode(item,dao.group(id),photos)).toString(),creationKey=key,
                remoteJson=if(live) JSONObject().put("productId",item.shopifyProductId).put("variantId",item.shopifyVariantId).put("inventoryItemId",item.shopifyInventoryItemId).toString() else "{}"))
        }
    }
    return job.id
}
internal fun CaptureRepository.enqueueShopify(id:String):Operation {
    val request=OneTimeWorkRequestBuilder<ShopifyWorker>().setInputData(workDataOf("jobId" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.SECONDS).build()
    return WorkManager.getInstance(context).enqueueUniqueWork("shopify-$id",ExistingWorkPolicy.KEEP,request)
}
internal suspend fun CaptureRepository.pauseShopify(id:String) {mutate {journal.shopifyDao().job(id)?.let {journal.shopifyDao().update(it.copy(state="PAUSED",error="Paused locally. Existing remote drafts and server jobs are retained.",updatedAt=System.currentTimeMillis()))}};WorkManager.getInstance(context).cancelUniqueWork("shopify-$id")}
internal suspend fun CaptureRepository.cancelShopify(id:String) {
    mutate {val jobs=journal.shopifyDao();val job=jobs.job(id) ?: error("Job missing")
        jobs.update(job.copy(state="CANCELLED",error="Remaining phone work cancelled. Remote resources are retained; inspect saved IDs before starting another job."))
        jobs.items(id).filter {it.state!="SUCCESS"}.forEach {jobs.update(it.copy(state="CANCELLED"))}
    };WorkManager.getInstance(context).cancelUniqueWork("shopify-$id")
}
internal suspend fun CaptureRepository.resumeShopify(id:String,base:String,health:JSONObject) {
    check(health.getBoolean("developmentStore"))
    mutate {val jobs=journal.shopifyDao();val old=jobs.job(id) ?: error("Job missing")
        check(old.shopId==health.getString("shopId") && old.shop==health.getString("shop")) {"Reconnect to the original store."}
        check(jobs.items(id).none {it.state=="NEEDS_REVIEW"}) {"An item or restored review requires fresh approval. Cancel remaining work and prepare a new review."}
        jobs.items(id).filter {it.state!="SUCCESS"}.forEach {jobs.update(it.copy(state="QUEUED",error=""))}
        jobs.update(old.copy(broker=base,connectionFingerprint=shopifyConnectionFingerprint(context,base),state="QUEUED",error="",updatedAt=System.currentTimeMillis()))
    };enqueueShopify(id)
}
internal class ShopifyWait:Exception()
internal class ShopifyPipeline(private val repo:CaptureRepository,private val gateway:ShopifyGateway,private val vault:ShopifyUploadVault=ShopifyUploadVault(repo.context)) {
    private val jobs=repo.journal.shopifyDao()
    private suspend fun persist(row:ShopifyItemJobRow,step:String,remote:JSONObject,checkpoint:JSONObject,state:String="RUNNING"):ShopifyItemJobRow {
        val saved=row.copy(step=step,remoteJson=remote.toString(),checkpointJson=checkpoint.toString(),state=state,error="",updatedAt=System.currentTimeMillis());repo.mutate {check(jobs.job(row.jobId)?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) {"Job paused or cancelled."};jobs.update(saved)};return saved
    }
    private suspend fun current(job:ShopifyJobRow,row:ShopifyItemJobRow) {
        currentCoroutineContext().ensureActive()
        check(jobs.job(job.id)?.state in listOf("RUNNING","QUEUED","WAITING_REMOTE")) {"Job paused."}
        val item=repo.journal.inventoryDao().item(row.groupId) ?: error("Item no longer exists")
        val photos=repo.dao.photos(row.groupId).filter {!it.deleted}.sortedBy {it.position}
        if(item.revision!=row.expectedRevision || shopifyFingerprint(item,photos)!=row.inputFingerprint) throw ShopifyBrokerException("STALE_ITEM","Item or photographs changed after approval. Remote IDs are retained for review.")
    }
    suspend fun run(job:ShopifyJobRow,initial:ShopifyItemJobRow) {
        if(job.kind in listOf("LINK_EXISTING","UPDATE_METADATA")) {ShopifyManagementPipeline(repo,gateway).run(job,initial);return}
        if(initial.state=="SUCCESS") return
        var row=initial;val snapshot=JSONObject(row.snapshotJson);val remote=JSONObject(row.remoteJson);val cp=JSONObject(row.checkpointJson)
        suspend fun save(step:String) {row=persist(row,step,remote,cp)}
        suspend fun rpc(step:String,kind:String,args:JSONObject,write:Boolean=true):JSONObject {
            current(job,row);save(step)
            return gateway.call(if(write) operationKey("${row.id}:$step") else newId(),kind,args)
        }
        fun owned()=JSONObject().put("creationKey",row.creationKey)
        try {
            current(job,row)
            if(job.kind=="PUBLISH_LIVE") {
                val draft=rpc("LIVE_PREFLIGHT","product",JSONObject().put("productId",remote.getString("productId")).put("locationId",job.locationId),false)
                val original=repo.journal.inventoryDao().item(row.groupId)!!
                val draftHistory=repo.journal.inventoryDao().history(row.groupId).firstOrNull {it.action=="SHOPIFY_UPLOADED_AS_DRAFT"} ?: error("Verified draft history missing")
                val known=JSONObject(draftHistory.afterJson).getString("shopifyProductId");check(known==remote.getString("productId"))
                val source=repo.journal.openHelper.writableDatabase.query("SELECT remoteJson FROM shopify_job_items WHERE groupId=? AND state='SUCCESS' ORDER BY updatedAt DESC",arrayOf(row.groupId)).use {c->if(c.moveToFirst()) JSONObject(c.getString(0)) else error("Verified media checkpoint missing")}
                val fileIds=source.getJSONArray("fileIds").let {a->(0 until a.length()).map {a.getString(it)}}
                // If a previous approved attempt activated the product, verify the same fields as its original draft.
                verifyShopifyIdentity(draft,remote,row.creationKey)
                verifyShopifyDraft(snapshot,JSONObject(draft.toString()).put("status","DRAFT"),fileIds)
                check(original.shopifyState=="UPLOADED_AS_DRAFT")
                val status=rpc("LIVE_STATUS","publication_status",owned().put("productId",remote.getString("productId")).put("publicationId",job.publicationId),false)
                if(!status.optBoolean("publishedOnPublication")) {
                    if(status.getString("status")!="ACTIVE") {check(status.getString("status")=="DRAFT");rpc("ACTIVATE","activate_product",owned().put("productId",remote.getString("productId")));cp.put("active",true);save("ACTIVE_UNPUBLISHED")}
                    rpc("PUBLISH","publish",owned().put("productId",remote.getString("productId")).put("publicationId",job.publicationId))
                }
                val verified=rpc("VERIFY_PUBLICATION","publication_status",owned().put("productId",remote.getString("productId")).put("publicationId",job.publicationId),false)
                check(verified.getString("status")=="ACTIVE" && verified.getBoolean("publishedOnPublication")) {"Shopify has not confirmed live publication."}
                finish(job,row,remote,"PUBLISHED");return
            }
            if(!remote.has("productId")) {
                val args=owned().put("itemId",row.itemId).put("title",snapshot.getString("title")).put("descriptionHtml",snapshot.getString("descriptionHtml")).put("vendor",snapshot.getString("vendor")).put("productType",snapshot.getString("productType")).put("tags",snapshot.getJSONArray("publishTags"))
                current(job,row);save("CREATE")
                // Per-copy/store creation identity also survives independently queued jobs.
                val product=gateway.call(row.creationKey,"create",args).getJSONObject("product");check(product.getString("status")=="DRAFT")
                val variant=product.getJSONObject("variants").getJSONArray("nodes").getJSONObject(0)
                remote.put("productId",product.getString("id")).put("variantId",variant.getString("id")).put("inventoryItemId",variant.getJSONObject("inventoryItem").getString("id"));save("CREATED")
            }
            if(!cp.optBoolean("variant")) {rpc("VARIANT","variant",owned().put("productId",remote.getString("productId")).put("variantId",remote.getString("variantId")).put("priceCents",snapshot.getLong("finalPriceCents")).put("barcode",snapshot.getString("effectiveBarcode")));cp.put("variant",true);save("VARIANT_DONE")}
            if(!cp.optBoolean("inventory")) {rpc("INVENTORY","inventory_item",owned().put("inventoryItemId",remote.getString("inventoryItemId")).put("itemId",row.itemId).put("weightGrams",snapshot.getInt("weightGrams")));cp.put("inventory",true);save("INVENTORY_DONE")}
            if(!cp.optBoolean("stock")) {
                val quantity=rpc("READ_QUANTITY","quantity",JSONObject().put("inventoryItemId",remote.getString("inventoryItemId")).put("locationId",job.locationId),false).optJSONObject("inventoryLevel")
                val args=owned().put("inventoryItemId",remote.getString("inventoryItemId")).put("locationId",job.locationId).put("quantity",1).put("idempotencyKey",operationKey("${row.id}:stock"))
                if(quantity==null) rpc("STOCK_ACTIVATE","stock_activate",args)
                else {val old=quantity.getJSONArray("quantities").getJSONObject(0).getInt("quantity");check(old in 0..1) {"Unexpected remote inventory. Review instead of overwriting."};if(old==0) rpc("STOCK_SET","stock_set",args.put("changeFromQuantity",old))}
                cp.put("stock",true);save("STOCK_DONE")
            }
            val photos=snapshot.getJSONArray("photos");val files=cp.optJSONObject("photos") ?: JSONObject().also {cp.put("photos",it)}
            for(i in 0 until photos.length()) {
                val photo=photos.getJSONObject(i);val id=photo.getString("id");val state=files.optJSONObject(id) ?: JSONObject().also {files.put(id,it)}
                val name=photo.getString("filename");val alt="snapims:${row.itemId}:photo:$id"
                val fileKey=operationKey("${row.id}:FILE_$id")
                if(!state.has("fileId")) {
                    if(state.has("target") && runCatching {vault.decrypt("${row.id}/$id",state.getString("target"))}.isFailure) {
                    state.remove("target");state.put("uploaded",false);state.put("stageEpoch",state.optInt("stageEpoch")+1);save("UNLOCK_$id")
                }
                if(state.optBoolean("fileCreateSent") && !state.has("target")) {
                        val recovered=rpc("RECOVER_$id","recover_file",owned().put("pendingOperationId",fileKey).put("filename",name).put("alt",alt),false)
                        state.put("fileId",recovered.getJSONArray("files").getJSONObject(0).getString("id"));save("FILE_RECOVERED_$id")
                    } else {
                        val file=derivative(row,photo)
                        var target=if(state.has("target")) vault.decrypt("${row.id}/$id",state.getString("target")) else null
                        if(target==null) {val epoch=state.optInt("stageEpoch");target=rpc("STAGE_${id}_$epoch","stage",JSONObject().put("filename",name).put("bytes",file.length())).getJSONArray("stagedTargets").getJSONObject(0);state.put("target",vault.encrypt("${row.id}/$id",target));save("STAGED_$id")}
                        if(!state.optBoolean("uploaded")) {
                            current(job,row)
                            try {gateway.upload(target,file)} catch(e:ShopifyBrokerException) {if(e.code=="STAGING_EXPIRED") {state.remove("target");state.put("stageEpoch",state.optInt("stageEpoch")+1);save("RESTAGE_$id");throw ShopifyWait()};throw e}
                            state.put("uploaded",true);save("UPLOADED_$id")
                        }
                        state.put("fileCreateSent",true);save("FILE_CREATE_$id")
                        val result=rpc("FILE_$id","file_create",owned().put("source",target.getString("resourceUrl")).put("filename",name).put("alt",alt))
                        state.put("fileId",result.getJSONArray("files").getJSONObject(0).getString("id"));state.remove("target");save("FILE_CREATED_$id");file.delete()
                    }
                }
                val fileId=state.getString("fileId")
                val status=rpc("FILE_STATUS_$id","file_status",JSONObject().put("fileId",fileId),false).getString("fileStatus")
                check(status!="FAILED") {"Shopify could not process photograph ${i+1}. Original retained."}
                if(status!="READY") throw ShopifyWait()
                if(!state.optBoolean("attached")) {rpc("ATTACH_$id","attach",owned().put("productId",remote.getString("productId")).put("fileId",fileId));state.put("attached",true);save("ATTACHED_$id")}
            }
            val productArgs=JSONObject().put("productId",remote.getString("productId")).put("locationId",job.locationId)
            var product=rpc("VERIFY","product",productArgs,false)
            val intended=(0 until photos.length()).map {files.getJSONObject(photos.getJSONObject(it).getString("id")).getString("fileId")}
            val actual=product.getJSONObject("media").getJSONArray("nodes").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}}
            check(actual.all {it in intended} && actual.distinct().size==actual.size && !product.getJSONObject("media").getJSONObject("pageInfo").getBoolean("hasNextPage")) {"Remote media differs from the approved photographs. Review required."}
            if(actual.size<intended.size) throw ShopifyWait()
            if(cp.has("reorderJob")) {
                if(!rpc("REMOTE_JOB","remote_job",JSONObject().put("jobId",cp.getString("reorderJob")),false).getBoolean("done")) throw ShopifyWait()
            } else if(actual!=intended) {
                val result=rpc("REORDER","reorder",owned().put("productId",remote.getString("productId")).put("fileIds",JSONArray(intended)))
                result.optJSONObject("job")?.let {cp.put("reorderJob",it.getString("id"));save("REORDER_PENDING");if(!it.optBoolean("done")) throw ShopifyWait()}
            }
            product=rpc("FINAL_VERIFY","product",productArgs,false)
            val finalMedia=product.getJSONObject("media").getJSONArray("nodes")
            if((0 until finalMedia.length()).any {finalMedia.getJSONObject(it).getJSONObject("preview").getString("status")!="READY"}) throw ShopifyWait()
            if(cp.has("reorderJob") && (0 until finalMedia.length()).map {finalMedia.getJSONObject(it).getString("id")}!=intended) throw ShopifyWait()
            verifyShopifyDraft(snapshot,product,intended)
            verifyShopifyIdentity(product,remote,row.creationKey)
            remote.put("fileIds",JSONArray(intended));finish(job,row,remote,"UPLOADED_AS_DRAFT")
        } catch(e:CancellationException) {throw e}
        catch(e:ShopifyWait) {persist(row,row.step,remote,cp,"WAITING_REMOTE");throw e}
        catch(e:Exception) {
            val state=when {e is ShopifyBrokerException && e.code=="STALE_ITEM"->"NEEDS_REVIEW";e is ShopifyBrokerException && e.code=="UNCERTAIN"->"UNCERTAIN";e is ShopifyBrokerException && (e.httpStatus>=500 || e.httpStatus==429)->"RETRYABLE";else->"ERROR"}
            repo.mutate {if(jobs.job(row.jobId)?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) jobs.update(row.copy(state=state,remoteJson=remote.toString(),checkpointJson=cp.toString(),error=e.message.orEmpty().take(1500),attempts=row.attempts+1,updatedAt=System.currentTimeMillis())) else jobs.item(row.id)?.let {latest->jobs.update(latest.copy(remoteJson=remote.toString(),checkpointJson=cp.toString(),updatedAt=System.currentTimeMillis()))}};throw e
        }
    }
    private suspend fun derivative(row:ShopifyItemJobRow,photo:JSONObject):File=withContext(Dispatchers.IO) {
        val original=repo.original(photo.getString("id"));check(original.length()==photo.getLong("bytes") && hashFile(original)==photo.getString("sha256")) {"Original photograph changed."}
        val dir=File(repo.dataRoot,"shopify-staging/${row.id}").apply {mkdirs()};val file=File(dir,photo.getString("filename"))
        if(!file.isFile) {val temp=File(dir,file.name+".pending");val bitmap=recognitionBitmap(original,2048);try {java.io.FileOutputStream(temp).use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it));it.fd.sync()};requireJpeg(temp);atomicMove(temp,file);repo.sync(dir)} finally {bitmap.recycle()}}
        file
    }
    private suspend fun finish(job:ShopifyJobRow,row:ShopifyItemJobRow,remote:JSONObject,state:String) = repo.mutate {
        current(job,row);val inventory=repo.journal.inventoryDao();val old=inventory.item(row.groupId)!!
        val saved=old.copy(shopifyProductId=remote.getString("productId"),shopifyVariantId=remote.getString("variantId"),shopifyInventoryItemId=remote.getString("inventoryItemId"),shopifyState=state,revision=old.revision+1,updatedAt=OffsetDateTime.now().toString())
        inventory.update(saved);repo.inventoryAudit(old,saved,"SHOPIFY_$state");jobs.update(row.copy(state="SUCCESS",step="VERIFIED",remoteJson=remote.toString(),error="",updatedAt=System.currentTimeMillis()))
    }
}
internal fun verifyShopifyIdentity(product:JSONObject,remote:JSONObject,creationKey:String) {
    check(product.getString("id")==remote.getString("productId") && product.getJSONObject("metafield").getString("value")==creationKey) {"Remote product identity changed."}
    val v=product.getJSONObject("variants").getJSONArray("nodes").getJSONObject(0)
    check(v.getString("id")==remote.getString("variantId") && v.getJSONObject("inventoryItem").getString("id")==remote.getString("inventoryItemId")) {"Remote variant or inventory identity changed."}
}
internal fun verifyShopifyDraft(snapshot:JSONObject,product:JSONObject,files:List<String>) {
    check(product.getString("status")=="DRAFT") {"Product must remain a draft."}
    for(key in listOf("title","vendor","productType")) check(product.getString(key)==snapshot.getString(key)) {"Shopify $key did not match."}
    fun plain(html:String)=androidx.core.text.HtmlCompat.fromHtml(html,androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+")," ").trim()
    check(plain(product.getString("descriptionHtml"))==plain(snapshot.getString("descriptionHtml"))) {"Description did not match."}
    val tags=product.getJSONArray("tags").let {a->(0 until a.length()).map {a.getString(it)}.toSet()};check(snapshot.getJSONArray("publishTags").let {a->(0 until a.length()).all {a.getString(it) in tags}})
    val variants=product.getJSONObject("variants").getJSONArray("nodes");check(variants.length()==1)
    val v=variants.getJSONObject(0);check(v.getString("sku")==snapshot.getString("itemId"));check(java.math.BigDecimal(v.getString("price")).movePointRight(2).longValueExact()==snapshot.getLong("finalPriceCents"));check(v.optString("barcode").let {if(it=="null") "" else it}==snapshot.getString("effectiveBarcode"))
    val item=v.getJSONObject("inventoryItem");check(item.getBoolean("tracked") && item.getBoolean("requiresShipping"))
    val weight=item.getJSONObject("measurement").getJSONObject("weight");val factor=when(weight.getString("unit")) {"GRAMS"->1.0;"KILOGRAMS"->1000.0;"POUNDS"->453.59237;"OUNCES"->28.349523125;else->error("Unknown weight unit")};check(kotlin.math.abs(weight.getDouble("value")*factor-snapshot.getInt("weightGrams"))<0.01)
    check(item.getJSONObject("inventoryLevel").getJSONArray("quantities").getJSONObject(0).getInt("quantity")==1)
    val media=product.getJSONObject("media");check(!media.getJSONObject("pageInfo").getBoolean("hasNextPage"));val nodes=media.getJSONArray("nodes");check((0 until nodes.length()).map {nodes.getJSONObject(it).getString("id")}==files)
    check((0 until nodes.length()).all {nodes.getJSONObject(it).getJSONObject("preview").getString("status")=="READY"}) {"Photo processing is not complete."}
}
class ShopifyWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val repo=(applicationContext as CaptureApplication).repository;val jobs=repo.journal.shopifyDao();val id=inputData.getString("jobId") ?: return@withContext Result.failure()
        var job=jobs.job(id) ?: return@withContext Result.failure()
        if(job.state !in listOf("QUEUED","RUNNING","WAITING_REMOTE")) return@withContext Result.success()
        try {
            check(shopifyConnectionFingerprint(applicationContext,job.broker)==job.connectionFingerprint) {"Device connection changed. Pair and resume manually."}
            val gateway=ShopifyBrokerClient(applicationContext,job.broker);val health=gateway.call(newId(),"health",JSONObject())
            check(health.getString("shopId")==job.shopId && health.getBoolean("developmentStore") && health.getString("currency")=="CAD") {"Connected store differs from this approved development-store job."}
            val admitted=repo.mutate {val current=jobs.job(id);if(current?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) {job=current!!.copy(state="RUNNING",error="");jobs.update(job);true} else false}
            if(!admitted) return@withContext Result.success()
            val pipeline=ShopifyPipeline(repo,gateway);val started=System.currentTimeMillis();var wait=false
            for(item in jobs.items(id).filter {it.state in listOf("QUEUED","RUNNING","WAITING_REMOTE","RETRYABLE")}) {
                if(System.currentTimeMillis()-started>70_000) {wait=true;break}
                try {withTimeout(75_000) {pipeline.run(job,item)}} catch(_:ShopifyWait) {wait=true} catch(_:TimeoutCancellationException) {wait=true} catch(e:CancellationException) {throw e} catch(_:Exception) {if(jobs.item(item.id)?.state=="RETRYABLE") wait=true}
                if(jobs.job(id)?.state in listOf("PAUSED","CANCELLED")) return@withContext Result.success()
            }
            val all=jobs.items(id);val state=if(all.all {it.state=="SUCCESS"}) "SUCCESS" else if(wait && runAttemptCount<20) "WAITING_REMOTE" else "PARTIAL_REVIEW"
            repo.mutate {val current=jobs.job(id);if(current?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) jobs.update(current!!.copy(state=state,error=if(state=="PARTIAL_REVIEW") "Review individual outcomes. Remote completion is independent of Android job completion." else "",updatedAt=System.currentTimeMillis()))}
            if(state=="WAITING_REMOTE") Result.retry() else Result.success()
        } catch(e:CancellationException) {throw e} catch(e:Exception) {
            if(e is ShopifyBrokerException && (e.httpStatus>=500 || e.httpStatus==429) && runAttemptCount<20) {
                repo.mutate {val latest=jobs.job(id);if(latest?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) jobs.update(latest!!.copy(state="WAITING_REMOTE",error=e.message.orEmpty().take(1500)))}
                Result.retry()
            }
            else {repo.mutate {val current=jobs.job(id);if(current?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE")) jobs.update(current!!.copy(state="PAUSED_RECONNECT",error=e.message.orEmpty().take(1500),updatedAt=System.currentTimeMillis()))};Result.success()}
        }
    }
}
