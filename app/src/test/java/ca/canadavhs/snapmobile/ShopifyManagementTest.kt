package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class ShopifyManagementTest {
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private val databases=mutableListOf<CaptureJournal>();private lateinit var repo:CaptureRepository
    private val base="https://broker.example.invalid";private val location="gid://shopify/Location/1"
    private fun create():CaptureRepository {val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();databases+=db;return CaptureRepository(app,db,File(app.filesDir,"manage-${newId()}").apply {mkdirs()}) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}}
    @Before fun setup() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=create();app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("shopifyPaired",true).putString("shopifyBroker",base).commit()}
    @After fun cleanup() {databases.forEach {it.close()}}
    private fun health()=JSONObject().put("shopId","gid://shopify/Shop/1").put("shop","fixture.myshopify.com").put("currency","CAD").put("developmentStore",true).put("allowPublish",true)
        .put("publications",JSONArray().put(JSONObject().put("id","gid://shopify/Publication/1"))).put("locations",JSONArray().put(JSONObject().put("id",location).put("isActive",true)))
    private suspend fun item():InventoryRow {if(repo.dao.active()==null) repo.start("Fixture","A1") else repo.next();repeat(2) {val p=repo.reserve();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id)};val old=repo.journal.inventoryDao().item(repo.current().id)!!;return repo.saveInventory(old.copy(title="Phone title",priceCents=1299,condition="Good",description="<p>Phone description</p>",ready=true),old.revision)}
    private suspend fun failure(block:suspend()->Unit) {try {block();fail("Expected rejection")} catch(_:IllegalStateException) {} catch(_:IllegalArgumentException) {}}
    private suspend fun job(review:ShopifyReviewRow):Pair<ShopifyJobRow,ShopifyItemJobRow> {val id=JSONObject(repo.journal.shopifyDao().review(review.id)!!.resultJson).getString("jobId");return repo.journal.shopifyDao().job(id)!! to repo.journal.shopifyDao().items(id).single()}
    private suspend fun link(item:InventoryRow,gateway:Remote):InventoryRow {val r=repo.prepareShopifyManagement(item.groupId,gateway.product.getString("id"),health(),base,location,emptySet(),gateway);repo.decideShopifyReview(r.id,true);val (j,i)=job(r);ShopifyPipeline(repo,gateway).run(j,i);return repo.journal.inventoryDao().item(item.groupId)!!}
    @Test fun linkRequiresExplicitOneUseReviewAndPreservesLocalAndRemoteMetadata():Unit=runBlocking {
        val item=item();val remote=Remote(item);val before=canonicalJson(remote.product);val r=repo.prepareShopifyManagement(item.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote)
        assertEquals("",repo.journal.inventoryDao().item(item.groupId)!!.shopifyProductId);assertEquals(0,remote.claims)
        repo.decideShopifyReview(r.id,true);val (j,i)=job(r);assertEquals("LINK_EXISTING",j.kind);ShopifyPipeline(repo,remote).run(j,i)
        val linked=repo.journal.inventoryDao().item(item.groupId)!!;assertEquals("LINKED_DRAFT",linked.shopifyState);assertEquals(item.title,linked.title);assertEquals(item.priceCents,linked.priceCents);assertEquals(before,canonicalJson(remote.product));assertEquals(0,remote.writes);assertEquals(1,remote.claims)
        failure {repo.decideShopifyReview(r.id,true)};assertEquals("SUCCESS",repo.journal.shopifyDao().item(i.id)!!.state)
    }
    @Test fun localRevisionPhotoAndDeviceChangesInvalidatePendingReviews():Unit=runBlocking {
        val a=item();val remote=Remote(a);val r=repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote)
        repo.saveInventory(a.copy(title="Edited"),a.revision);repo.decideShopifyReview(r.id,true);assertEquals("EXPIRED",repo.journal.shopifyDao().review(r.id)!!.state);assertEquals(0,remote.claims)
        val updated=repo.journal.inventoryDao().item(a.groupId)!!;val photoReview=repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);repo.role(repo.dao.photos(a.groupId).first().id,Role.BACK);repo.decideShopifyReview(photoReview.id,true);assertEquals("EXPIRED",repo.journal.shopifyDao().review(photoReview.id)!!.state)
        val config=repo.prepareShopifyManagement(updated.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);repo.preferences.edit().putString("shopifyBroker","https://other.example.invalid").commit();failure {repo.decideShopifyReview(config.id,true)};assertEquals("PENDING",repo.journal.shopifyDao().review(config.id)!!.state)
    }
    @Test fun foreignSkuMarkersMultiVariantActiveAndConflictingLocalLinkAreRejected():Unit=runBlocking {
        val a=item();val remote=Remote(a)
        for(state in listOf("ACTIVE","ARCHIVED")) {remote.product.put("status",state);failure {repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote)}}
        remote.product.put("status","DRAFT");remote.product.getJSONObject("identity").put("value","another-copy");failure {repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote)};remote.product.getJSONObject("identity").put("value",a.itemId)
        remote.product.getJSONObject("variants").getJSONObject("pageInfo").put("hasNextPage",true);failure {repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote)};remote.product.getJSONObject("variants").getJSONObject("pageInfo").put("hasNextPage",false)
        val changed=repo.saveInventory(a.copy(shopifyProductId="gid://shopify/Product/99"),a.revision);failure {repo.prepareShopifyManagement(changed.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote)};assertEquals(0,remote.claims)
    }
    @Test fun selectedMetadataOnlyIsUpdatedAndUncertainResponseResumesWithoutSecondWrite():Unit=runBlocking {
        val a=item();val remote=Remote(a);val linked=link(a,remote);val edited=repo.saveInventory(linked.copy(title="Reviewed new title",vendor="Reviewed vendor"),linked.revision)
        val r=repo.prepareShopifyManagement(edited.groupId,remote.product.getString("id"),health(),base,location,setOf("title"),remote);val before=JSONObject(remote.product.toString());repo.decideShopifyReview(r.id,true);val (j,i)=job(r)
        remote.uncertain=true;failure {ShopifyPipeline(repo,remote).run(j,i)};assertEquals("UNCERTAIN",repo.journal.shopifyDao().item(i.id)!!.state);assertEquals(1,remote.writes)
        ShopifyPipeline(repo,remote).run(j,repo.journal.shopifyDao().item(i.id)!!);assertEquals(1,remote.writes);assertEquals("Reviewed new title",remote.product.getString("title"));assertEquals(before.getString("vendor"),remote.product.getString("vendor"))
        for(key in listOf("status","tags","variants","media")) assertEquals(canonicalJson(before.get(key)),canonicalJson(remote.product.get(key)))
        assertEquals("SUCCESS",repo.journal.shopifyDao().item(i.id)!!.state);assertEquals("LINKED_DRAFT",repo.journal.inventoryDao().item(a.groupId)!!.shopifyState)
    }
    @Test fun remoteEditsAfterApprovalStopBeforeClaimOrMutation():Unit=runBlocking {
        val a=item();val remote=Remote(a);val r=repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);repo.decideShopifyReview(r.id,true);val (j,i)=job(r)
        remote.product.put("vendor","Edited in Shopify");failure {ShopifyPipeline(repo,remote).run(j,i)};assertEquals(0,remote.claims);assertEquals(0,remote.writes);assertEquals("NEEDS_REVIEW",repo.journal.shopifyDao().item(i.id)!!.state);assertEquals("",repo.journal.inventoryDao().item(a.groupId)!!.shopifyProductId)
    }
    @Test fun changedProtectedRemoteFieldsAfterMetadataResponsePreventVerifiedSuccess():Unit=runBlocking {
        val a=item();val remote=Remote(a);val linked=link(a,remote);val r=repo.prepareShopifyManagement(linked.groupId,remote.product.getString("id"),health(),base,location,setOf("title"),remote);repo.decideShopifyReview(r.id,true);val (j,i)=job(r);remote.changeProtected=true
        failure {ShopifyPipeline(repo,remote).run(j,i)};assertEquals("NEEDS_REVIEW",repo.journal.shopifyDao().item(i.id)!!.state);assertEquals(1,remote.writes)
    }
    @Test fun cancelledJobsNeverClaimOrOverwriteTheirCancellation():Unit=runBlocking {
        val a=item();val remote=Remote(a);val r=repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);repo.decideShopifyReview(r.id,true);val (j,i)=job(r);repo.cancelShopify(j.id)
        failure {ShopifyPipeline(repo,remote).run(j,i)};assertEquals("CANCELLED",repo.journal.shopifyDao().item(i.id)!!.state);assertEquals(0,remote.claims)
    }
    @Test fun cancellationWhileMetadataIsAcceptedPreservesCancellationAndRequiresServerReview():Unit=runBlocking {
        val a=item();val remote=Remote(a);val linked=link(a,remote);val r=repo.prepareShopifyManagement(linked.groupId,remote.product.getString("id"),health(),base,location,setOf("title"),remote);repo.decideShopifyReview(r.id,true);val (j,i)=job(r)
        remote.afterMetadata={repo.cancelShopify(j.id)};failure {ShopifyPipeline(repo,remote).run(j,i)}
        assertEquals(1,remote.writes);assertEquals(linked.title,remote.product.getString("title"));assertEquals("CANCELLED",repo.journal.shopifyDao().item(i.id)!!.state);assertEquals("CANCELLED",repo.journal.shopifyDao().job(j.id)!!.state)
    }
    @Test fun nativeDraftReviewsBindExactDisplayedItemsPhotosAndRequireNewReviewOnEdits():Unit=runBlocking {
        val a=item();val r=repo.prepareShopifyUploadReview(setOf(a.groupId),health(),base,location,false,"");repo.saveInventory(a.copy(title="Edited after review",ready=true),a.revision)
        repo.decideShopifyReview(r.id,true);assertEquals("EXPIRED",repo.journal.shopifyDao().review(r.id)!!.state);assertTrue(repo.journal.shopifyDao().active().isEmpty())
        val fresh=repo.prepareShopifyUploadReview(setOf(a.groupId),health(),base,location,false,"");repo.decideShopifyReview(fresh.id,true);val (j,i)=job(fresh);assertEquals("CREATE_DRAFT",j.kind);assertEquals("Edited after review",JSONObject(i.snapshotJson).getString("title"));failure {repo.decideShopifyReview(fresh.id,true)}
    }
    @Test fun barcodeEvidenceChangesInvalidateSavedUploadBeforeJobCreation():Unit=runBlocking {
        val a=item();val r=repo.prepareShopifyUploadReview(setOf(a.groupId),health(),base,location,false,"");val photo=repo.dao.photos(a.groupId).last()
        repo.dao.update(photo.copy(scanState="DONE",barcodeJson="[{\"raw\":\"036000291452\",\"valid\":true,\"format\":\"UPC_A\"}]"));repo.decideShopifyReview(r.id,true)
        assertEquals("EXPIRED",repo.journal.shopifyDao().review(r.id)!!.state);assertTrue(repo.journal.shopifyDao().active().isEmpty())
    }
    @Test fun publicationReviewsCannotQueueWithoutExplicitSubmitAndRetainPendingApproval():Unit=runBlocking {
        val a=item();val linked=repo.saveInventory(a.copy(shopifyProductId="gid://shopify/Product/7",shopifyVariantId="gid://shopify/ProductVariant/8",shopifyInventoryItemId="gid://shopify/InventoryItem/9",shopifyState="UPLOADED_AS_DRAFT",ready=true),a.revision)
        val r=repo.prepareShopifyUploadReview(setOf(a.groupId),health(),base,location,true,"gid://shopify/Publication/1");failure {repo.decideShopifyReview(r.id,true,"submit")};assertEquals("PENDING",repo.journal.shopifyDao().review(r.id)!!.state);assertTrue(repo.journal.shopifyDao().active().isEmpty())
        repo.decideShopifyReview(r.id,true,"SUBMIT");assertEquals("PUBLISH_LIVE",job(r).first.kind);assertEquals(linked.itemId,job(r).second.itemId)
    }
    @Test fun snapshotEightExpiresPendingReviewsPausesJobsAndPreservesLegacySevenBindings():Unit=runBlocking {
        val a=item();val remote=Remote(a);val pending=repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);val applied=repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);repo.decideShopifyReview(applied.id,true)
        val rec=RecognitionRow(groupId=a.groupId,provider="OPENAI",model=DEFAULT_OPENAI_MODEL,inputJson=photoSnapshot(repo.dao.photos(a.groupId)),inputFingerprint=photoFingerprint(repo.dao.photos(a.groupId)),authBinding="fixture-opaque-slot");repo.dao.insert(rec)
        val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());assertEquals(9,snapshot.getInt("schema"));val stage=File(app.cacheDir,newId()).apply {mkdirs()};val files=snapshot.getJSONArray("files")
        for(i in 0 until files.length()) {val path=files.getJSONObject(i).getString("path");File(stage,path).apply {parentFile!!.mkdirs();repo.snapshotFile(path).copyTo(this)}}
        val destination=create();destination.mergeSnapshot(snapshot,stage);assertEquals("EXPIRED",destination.journal.shopifyDao().review(pending.id)!!.state);assertEquals("APPLIED",destination.journal.shopifyDao().review(applied.id)!!.state);assertEquals("PAUSED_RECONNECT",destination.journal.shopifyDao().job(job(applied).first.id)!!.state);assertEquals("NEEDS_REVIEW",destination.journal.shopifyDao().items(job(applied).first.id).single().state);failure {destination.resumeShopify(job(applied).first.id,base,health())}
        val legacy=JSONObject(snapshot.toString()).put("schema",7);legacy.getJSONObject("tables").remove("shopify_reviews");listOf("agent_policies","agent_traces").forEach {legacy.getJSONObject("tables").remove(it)};destination.validateSnapshot(legacy);assertEquals(9,legacy.getInt("schema"));assertEquals("fixture-opaque-slot",legacy.getJSONObject("tables").getJSONArray("recognition").getJSONObject(0).getString("authBinding"))
    }
    @Test fun rejectsNonMetadataFieldsUnlinkedUpdatesAndEmptyDifferences():Unit=runBlocking {
        val a=item();val remote=Remote(a);failure {repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,setOf("status"),remote)};failure {repo.prepareShopifyManagement(a.groupId,remote.product.getString("id"),health(),base,location,setOf("title"),remote)}
        val linked=link(a,remote);failure {repo.prepareShopifyManagement(linked.groupId,remote.product.getString("id"),health(),base,location,setOf("vendor"),remote)}
        val rejected=repo.prepareShopifyManagement(linked.groupId,remote.product.getString("id"),health(),base,location,emptySet(),remote);repo.decideShopifyReview(rejected.id,false);failure {repo.decideShopifyReview(rejected.id,true)}
    }
    @Test fun agentDelegatesExactNativeManagementReviewAndReportsOnlyVerifiedOwningJob():Unit=runBlocking {
        val a=item();val remote=Remote(a);val gateway=object:ShopifyGateway by remote {override suspend fun call(operationId:String,operation:String,args:JSONObject)=if(operation=="health") health() else remote.call(operationId,operation,args)}
        val actions=AgentApplicationActions(repo) {gateway};val chat=ChatConversationRow(title="Agent management",provider="LOCAL",model="fixture",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);repo.journal.nextDao().insert(chat)
        val args=JSONObject().put("group_id",a.groupId).put("product_id",remote.product.getString("id")).put("location_id",location).put("fields_json","[]")
        val p=actions.propose(chat,"propose_shopify_management",args);val id=p.getString("approval_id");val payload=JSONObject(repo.journal.nextDao().approval(id)!!.payloadJson);assertTrue(repo.journal.shopifyDao().active().isEmpty());assertEquals(0,remote.claims)
        actions.decide(id,false,AgentDecisionOptions());assertEquals("REJECTED",repo.journal.shopifyDao().review(payload.getString("native_review_id"))!!.state);assertTrue(repo.journal.shopifyDao().active().isEmpty())
        val approved=actions.propose(chat,"propose_shopify_management",args).getString("approval_id");actions.decide(approved,true,AgentDecisionOptions());val taskId=JSONObject(repo.journal.nextDao().approval(approved)!!.resultJson).getString("taskId");val task=repo.journal.agentTaskDao().get(taskId)!!;assertEquals("SCHEDULED",task.state);assertNotEquals("COMPLETE",repo.agentTaskStatus(task).state)
        val jobId=JSONArray(task.jobIdsJson).getString(0);val j=repo.journal.shopifyDao().job(jobId)!!;val copy=repo.journal.shopifyDao().items(jobId).single();ShopifyPipeline(repo,remote).run(j,copy)
        // The worker normally derives owning job completion after all item verifications.
        repo.journal.shopifyDao().update(j.copy(state="SUCCESS"));assertEquals("COMPLETE",repo.agentTaskStatus(task).state);assertEquals("LINKED_DRAFT",repo.journal.inventoryDao().item(a.groupId)!!.shopifyState);assertEquals(1,remote.claims);assertEquals(0,remote.writes);failure {actions.decide(approved,true,AgentDecisionOptions())}
    }
    @Test fun agentLivePublicationRequiresSubmitBeforeNativeReviewOrJobIsApplied():Unit=runBlocking {
        val a=item();repo.saveInventory(a.copy(shopifyProductId="gid://shopify/Product/7",shopifyVariantId="gid://shopify/ProductVariant/8",shopifyInventoryItemId="gid://shopify/InventoryItem/9",shopifyState="UPLOADED_AS_DRAFT",ready=true),a.revision)
        val remote=Remote(a);val gateway=object:ShopifyGateway by remote {override suspend fun call(operationId:String,operation:String,args:JSONObject)=if(operation=="health") health() else remote.call(operationId,operation,args)};val actions=AgentApplicationActions(repo) {gateway}
        val c=ChatConversationRow(title="Agent live review",provider="LOCAL",model="fixture",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);repo.journal.nextDao().insert(c)
        val id=actions.propose(c,"propose_shopify_publish",JSONObject().put("group_ids",JSONArray(listOf(a.groupId))).put("location_id",location).put("publication_id","gid://shopify/Publication/1")).getString("approval_id")
        failure {actions.decide(id,true,AgentDecisionOptions())};assertEquals("PENDING",repo.journal.nextDao().approval(id)!!.state);assertTrue(repo.journal.shopifyDao().active().isEmpty());actions.decide(id,true,AgentDecisionOptions(confirmation="SUBMIT"));assertEquals("PUBLISH_LIVE",repo.journal.shopifyDao().active().single().kind);assertEquals(0,remote.writes)
    }
    private class Remote(item:InventoryRow):ShopifyGateway {
        var product=JSONObject().put("id","gid://shopify/Product/7").put("status","DRAFT").put("title","Remote title").put("descriptionHtml","<p>Remote description</p>").put("vendor",item.vendor).put("productType",item.productType).put("tags",JSONArray(listOf("retain-tag"))).put("updatedAt","2026-10-28T01:00:00Z")
            .put("identity",JSONObject().put("value",item.itemId)).put("metafield",JSONObject().put("value",operationKey("snapims-create:gid://shopify/Shop/1:${item.itemId}")))
            .put("variants",JSONObject().put("pageInfo",JSONObject().put("hasNextPage",false)).put("nodes",JSONArray().put(JSONObject().put("id","gid://shopify/ProductVariant/8").put("sku",item.itemId).put("price","12.99").put("barcode","").put("inventoryItem",JSONObject().put("id","gid://shopify/InventoryItem/9").put("tracked",true).put("requiresShipping",true).put("measurement",JSONObject().put("weight",JSONObject().put("value",250).put("unit","GRAMS"))).put("inventoryLevel",JSONObject().put("quantities",JSONArray().put(JSONObject().put("name","available").put("quantity",1))))))))
            .put("media",JSONObject().put("pageInfo",JSONObject().put("hasNextPage",false)).put("nodes",JSONArray().put(JSONObject().put("id","gid://shopify/MediaImage/10").put("preview",JSONObject().put("status","READY")))))
        var afterMetadata:(suspend ()->Unit)?=null
        var claims=0;var writes=0;var uncertain=false;var changeProtected=false;private val cache=mutableMapOf<String,JSONObject>()
        private fun version()=sha256(canonicalJson(product).toByteArray())
        override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject {
            if(operation=="product") return JSONObject(product.toString()).put("reviewVersion",version())
            cache[operationId]?.let {return JSONObject(it.toString())}
            check(args.getString("expectedVersion")==version())
            when(operation) {"claim"->claims++;"metadata_update"->{writes++;val patch=args.getJSONObject("patch");patch.keys().forEach {product.put(it,patch.get(it))};product.put("updatedAt","2026-10-28T02:00:00Z");if(changeProtected) product.put("vendor","Concurrent remote edit")};else->error("Unexpected operation $operation")}
            val result=JSONObject().put("product",JSONObject().put("id",product.getString("id")));cache[operationId]=result
            if(operation=="metadata_update") afterMetadata?.invoke()
            if(operation=="metadata_update" && uncertain) {uncertain=false;throw ShopifyBrokerException("UNCERTAIN","Synthetic disconnect after server acceptance",409)}
            return JSONObject(result.toString())
        }
        override suspend fun upload(target:JSONObject,file:File) {error("Management must not upload photos")}
    }
}
