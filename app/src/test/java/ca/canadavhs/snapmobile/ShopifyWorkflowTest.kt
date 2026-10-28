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
import javax.crypto.KeyGenerator

/** Host/transport fixtures only. No real Shopify account/store acceptance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class ShopifyWorkflowTest {
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private val databases=mutableListOf<CaptureJournal>()
    private lateinit var repo:CaptureRepository
    private val base="https://broker.example.invalid"
    private val location="gid://shopify/Location/1"
    private val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
    private fun create():CaptureRepository {val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();databases+=db;return CaptureRepository(app,db,File(app.filesDir,"shopify-test-${newId()}").apply {mkdirs()}) {directory->java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}}
    @Before fun before() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=create();app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit()}
    @After fun after() {databases.forEach {it.close()}}
    private fun health()=JSONObject().put("shopId","gid://shopify/Shop/1").put("shop","fixture.myshopify.com").put("name","Fixture").put("currency","CAD").put("developmentStore",true).put("allowPublish",true).put("publications",JSONArray().put(JSONObject().put("id","gid://shopify/Publication/1"))).put("locations",JSONArray().put(JSONObject().put("id",location).put("isActive",true)))
    private suspend fun item(first:Boolean=true):InventoryRow {
        if(first) repo.start("Fixture","A1") else repo.next()
        repeat(2) {val p=repo.reserve();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id)}
        val old=repo.journal.inventoryDao().item(repo.current().id)!!
        return repo.saveInventory(old.copy(title="Synthetic fixture",priceCents=1299,discountBasisPoints=1000,condition="Good",description="A <real> description\nSecond line",tags="VHS, Used",ready=true),old.revision)
    }
    private fun pipeline(gateway:FakeShopify)=ShopifyPipeline(repo,gateway,ShopifyUploadVault(app) {key})
    @Test fun interruptedCreationReusesIdentityAndVerifiesBothImages():Unit=runBlocking {
        val item=item();val id=repo.queueShopify(setOf(item.groupId),health(),base,location);val job=repo.journal.shopifyDao().job(id)!!;val row=repo.journal.shopifyDao().items(id).single();val remote=FakeShopify();remote.failAfterCreate=true
        try {pipeline(remote).run(job,row);fail()} catch(_:ShopifyBrokerException) {}
        assertEquals("RETRYABLE",repo.journal.shopifyDao().item(row.id)!!.state);assertEquals("NOT_UPLOADED",repo.journal.inventoryDao().item(item.groupId)!!.shopifyState)
        pipeline(remote).run(job,repo.journal.shopifyDao().item(row.id)!!)
        assertEquals(1,remote.created);assertEquals(2,remote.fileCreates);assertEquals(2,remote.uploads)
        assertEquals("SUCCESS",repo.journal.shopifyDao().item(row.id)!!.state)
        val saved=repo.journal.inventoryDao().item(item.groupId)!!;assertEquals("UPLOADED_AS_DRAFT",saved.shopifyState);assertEquals(item.itemId,remote.sku);assertEquals(1169,remote.price);assertEquals(1,remote.stock);assertEquals(250,remote.weight);assertTrue(saved.shopifyProductId.isNotBlank());assertEquals(listOf("gid://shopify/MediaImage/1","gid://shopify/MediaImage/2"),remote.attached)
        val before=remote.requests.size;pipeline(remote).run(job,repo.journal.shopifyDao().item(row.id)!!);assertEquals(before,remote.requests.size)
    }
    @Test fun serverFileProcessingDoesNotBecomeAndroidSuccessAndResumes():Unit=runBlocking {
        val item=item();val id=repo.queueShopify(setOf(item.groupId),health(),base,location);val job=repo.journal.shopifyDao().job(id)!!;val row=repo.journal.shopifyDao().items(id).single();val remote=FakeShopify();remote.processing=true
        try {pipeline(remote).run(job,row);fail()} catch(_:ShopifyWait) {}
        assertEquals("WAITING_REMOTE",repo.journal.shopifyDao().item(row.id)!!.state);assertEquals("NOT_UPLOADED",repo.journal.inventoryDao().item(item.groupId)!!.shopifyState)
        remote.processing=false;pipeline(remote).run(job,repo.journal.shopifyDao().item(row.id)!!);assertEquals(1,remote.created);assertEquals(2,remote.fileCreates);assertEquals("SUCCESS",repo.journal.shopifyDao().item(row.id)!!.state)
    }
    @Test fun changedInventoryAndUnexpectedStockCannotBeOverwritten():Unit=runBlocking {
        val item=item();val id=repo.queueShopify(setOf(item.groupId),health(),base,location);val job=repo.journal.shopifyDao().job(id)!!;val row=repo.journal.shopifyDao().items(id).single();repo.saveInventory(item.copy(title="Changed"),item.revision)
        val remote=FakeShopify();try {pipeline(remote).run(job,row);fail()} catch(_:ShopifyBrokerException) {}
        assertEquals("NEEDS_REVIEW",repo.journal.shopifyDao().item(row.id)!!.state);assertEquals(0,remote.requests.size)
        val second=item(false);val other=repo.queueShopify(setOf(second.groupId),health(),base,location);val secondJob=repo.journal.shopifyDao().job(other)!!;remote.stock=8
        try {pipeline(remote).run(secondJob,repo.journal.shopifyDao().items(other).single());fail()} catch(_:IllegalStateException) {}
        assertEquals(8,remote.stock);assertFalse(remote.requests.contains("stock_set"));assertEquals("NOT_UPLOADED",repo.journal.inventoryDao().item(second.groupId)!!.shopifyState)
    }
    @Test fun quantityAndProductionAndLiveConfirmationAreHardGates():Unit=runBlocking {
        val item=item();val changed=repo.saveInventory(item.copy(quantity=2,ready=true),item.revision)
        try {repo.queueShopify(setOf(item.groupId),health(),base,location);fail()} catch(_:IllegalStateException) {}
        assertTrue(repo.journal.shopifyDao().unfinishedForGroup(item.groupId).isEmpty())
        try {repo.queueShopify(setOf(item.groupId),health().put("developmentStore",false),base,location);fail()} catch(_:IllegalStateException) {}
        try {repo.queueShopify(setOf(item.groupId),health(),base,location,true,"gid://shopify/Publication/1","submit");fail()} catch(_:IllegalStateException) {}
        assertEquals(changed,repo.journal.inventoryDao().item(item.groupId))
    }
    @Test fun batchSkipsVerifiedSuccessAndPublishRequiresSeparateApproval():Unit=runBlocking {
        val a=item();val b=item(false);val id=repo.queueShopify(setOf(a.groupId,b.groupId),health(),base,location);val job=repo.journal.shopifyDao().job(id)!!;val rows=repo.journal.shopifyDao().items(id);val remotes=rows.associate {it.id to FakeShopify()}
        rows.forEach {pipeline(remotes.getValue(it.id)).run(job,it)};assertTrue(repo.journal.shopifyDao().items(id).all {it.state=="SUCCESS"});assertTrue(remotes.values.all {"publish" !in it.requests})
        val liveId=repo.queueShopify(setOf(a.groupId),health(),base,location,true,"gid://shopify/Publication/1","SUBMIT");val live=repo.journal.shopifyDao().items(liveId).single();val remote=remotes.getValue(rows.first {it.groupId==a.groupId}.id)
        pipeline(remote).run(repo.journal.shopifyDao().job(liveId)!!,live);assertTrue(remote.published);assertEquals("PUBLISHED",repo.journal.inventoryDao().item(a.groupId)!!.shopifyState)
    }
    @Test fun backupRedactsUploadCredentialsAndRestoredJobsRequireManualPairing():Unit=runBlocking {
        val item=item();val id=repo.queueShopify(setOf(item.groupId),health(),base,location);val row=repo.journal.shopifyDao().items(id).single();val photo=repo.dao.photos(item.groupId).first()
        val checkpoint=JSONObject().put("photos",JSONObject().put(photo.id,JSONObject().put("target","PRIVATE_UPLOAD_CIPHER").put("uploaded",true).put("fileCreateSent",true)))
        repo.journal.shopifyDao().update(row.copy(checkpointJson=checkpoint.toString()))
        val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());assertEquals(6,snapshot.getInt("schema"));assertFalse(snapshot.toString().contains("PRIVATE_UPLOAD_CIPHER"));assertFalse(snapshot.toString().contains("target"))
        val stage=File(app.cacheDir,newId()).apply {mkdirs()};val files=snapshot.getJSONArray("files");for(i in 0 until files.length()) {val path=files.getJSONObject(i).getString("path");File(stage,path).apply {parentFile!!.mkdirs();repo.snapshotFile(path).copyTo(this)}}
        val destination=create();destination.mergeSnapshot(snapshot,stage);assertEquals("PAUSED_RECONNECT",destination.journal.shopifyDao().job(id)!!.state);assertTrue(JSONObject(destination.journal.shopifyDao().item(row.id)!!.checkpointJson).getJSONObject("photos").getJSONObject(photo.id).getBoolean("fileCreateSent"))
        val legacy=JSONObject(snapshot.toString());legacy.put("schema",4);listOf("market_imports","price_decisions","market_searches").forEach {legacy.getJSONObject("tables").remove(it)};legacy.getJSONObject("tables").remove("shopify_jobs");legacy.getJSONObject("tables").remove("shopify_job_items");destination.validateSnapshot(legacy);assertEquals(6,legacy.getInt("schema"))
    }
    @Test fun agentDraftApprovalIsOneTimeAndQueuesOnlyAfterLocalReview():Unit=runBlocking {
        val item=item();repo.preferences.edit().putBoolean("shopifyPaired",true).putString("shopifyBroker",base).commit()
        val chat=ChatConversationRow(title="Fixture",provider="OPENAI",model="fixture",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);repo.journal.nextDao().insert(chat)
        val photos=repo.dao.photos(item.groupId).filter {!it.deleted && it.state=="READY"}
        val payload=JSONObject().put("group_id",item.groupId).put("expectedRevision",item.revision).put("broker",base).put("health",health()).put("location_id",location).put("connectionFingerprint",shopifyConnectionFingerprint(app,base))
        val approval=AgentApprovalRow(conversationId=chat.id,action="propose_shopify_draft",payloadJson=payload.toString(),bindingHash=sha256((canonicalJson(inventoryJson(item))+photoFingerprint(photos)).toByteArray()))
        repo.journal.nextDao().insert(approval);assertTrue(repo.journal.shopifyDao().unfinishedForGroup(item.groupId).isEmpty())
        val tools=AgentTools(repo);assertTrue(tools.decide(approval.id,true).contains("queued"));val record=repo.journal.nextDao().approval(approval.id)!!;assertEquals("APPLIED",record.state)
        val job=repo.journal.shopifyDao().job(JSONObject(record.resultJson).getString("shopifyJobId"))!!;assertEquals("CREATE_DRAFT",job.kind)
        val read=tools.execute(chat,"shopify_job_status",JSONObject().put("job_id",job.id));assertEquals("QUEUED",read.getString("state"));assertEquals(1,read.getJSONArray("items").length())
        try {tools.decide(approval.id,true);fail()} catch(_:IllegalStateException) {}
    }
    @Test fun pauseAndCancelRetainRemoteCheckpointsAndStopFurtherRequests():Unit=runBlocking {
        val item=item();val id=repo.queueShopify(setOf(item.groupId),health(),base,location);val job=repo.journal.shopifyDao().job(id)!!;val row=repo.journal.shopifyDao().items(id).single();val remote=FakeShopify();remote.processing=true
        try {pipeline(remote).run(job,row);fail()} catch(_:ShopifyWait) {}
        val checkpoint=repo.journal.shopifyDao().item(row.id)!!;assertTrue(JSONObject(checkpoint.remoteJson).has("productId"));repo.pauseShopify(id)
        val before=remote.requests.size;try {pipeline(remote).run(job,checkpoint);fail()} catch(_:IllegalStateException) {};assertEquals(before,remote.requests.size)
        repo.cancelShopify(id);assertEquals("CANCELLED",repo.journal.shopifyDao().job(id)!!.state);assertEquals("CANCELLED",repo.journal.shopifyDao().item(row.id)!!.state);assertEquals(checkpoint.remoteJson,repo.journal.shopifyDao().item(row.id)!!.remoteJson)
    }
    @Test fun uploadTargetsAndEncryptedVaultRejectRedirectionAndCrossPhotoReuse() {
        for(value in listOf("http://storage.googleapis.com/a","https://storage.googleapis.com.evil.invalid/a","https://user@storage.googleapis.com/a","https://127.0.0.1/a")) try {requireShopifyUploadUrl(value);fail(value)} catch(_:IllegalArgumentException) {}
        requireShopifyUploadUrl("https://storage.googleapis.com/shopify-fixture/key")
        val vault=ShopifyUploadVault(app) {key};val encrypted=vault.encrypt("photo-one",JSONObject().put("fixture","value"));assertEquals("value",vault.decrypt("photo-one",encrypted).getString("fixture"));try {vault.decrypt("photo-two",encrypted);fail()} catch(_:ShopifyBrokerException) {}
    }
}
private class FakeShopify:ShopifyGateway {
    val requests=mutableListOf<String>();private val cache=mutableMapOf<String,JSONObject>();var created=0;var fileCreates=0;var uploads=0;var failAfterCreate=false;var processing=false;var stock=0;var sku="";var price=0;var weight=0;var barcode="";var product=JSONObject();val attached=mutableListOf<String>();var published=false;var status="DRAFT"
    override suspend fun upload(target:JSONObject,file:File) {assertTrue(file.isFile);assertTrue(file.length()>0);uploads++}
    override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject {
        requests+=operation;cache[operationId]?.let {return JSONObject(it.toString())}
        val result=when(operation) {
            "create"->{created++;product=JSONObject(args.toString());JSONObject().put("product",JSONObject().put("id","gid://shopify/Product/1").put("status","DRAFT").put("variants",JSONObject().put("nodes",JSONArray().put(JSONObject().put("id","gid://shopify/ProductVariant/1").put("inventoryItem",JSONObject().put("id","gid://shopify/InventoryItem/1"))))))}
            "variant"->{price=args.getInt("priceCents");barcode=args.getString("barcode");JSONObject()}
            "inventory_item"->{sku=args.getString("itemId");weight=args.getInt("weightGrams");JSONObject()}
            "quantity"->JSONObject().put("inventoryLevel",JSONObject().put("quantities",JSONArray().put(JSONObject().put("quantity",stock))))
            "stock_set","stock_activate"->{stock=args.getInt("quantity");JSONObject()}
            "stage"->JSONObject().put("stagedTargets",JSONArray().put(JSONObject().put("url","https://storage.googleapis.com/fixture").put("resourceUrl","https://storage.googleapis.com/${args.getString("filename")}").put("parameters",JSONArray())))
            "file_create"->{fileCreates++;JSONObject().put("files",JSONArray().put(JSONObject().put("id","gid://shopify/MediaImage/$fileCreates")))}
            "file_status"->JSONObject().put("fileStatus",if(processing) "PROCESSING" else "READY")
            "attach"->{attached+=args.getString("fileId");JSONObject()}
            "product"->JSONObject().put("id","gid://shopify/Product/1").put("metafield",JSONObject().put("value",product.getString("creationKey"))).put("status",status).put("title",product.getString("title")).put("vendor",product.getString("vendor")).put("productType",product.getString("productType")).put("descriptionHtml",product.getString("descriptionHtml")).put("tags",product.getJSONArray("tags")).put("variants",JSONObject().put("nodes",JSONArray().put(JSONObject().put("id","gid://shopify/ProductVariant/1").put("sku",sku).put("price",java.math.BigDecimal.valueOf(price.toLong(),2).toPlainString()).put("barcode",barcode).put("inventoryItem",JSONObject().put("id","gid://shopify/InventoryItem/1").put("tracked",true).put("requiresShipping",true).put("measurement",JSONObject().put("weight",JSONObject().put("unit","GRAMS").put("value",weight))).put("inventoryLevel",JSONObject().put("quantities",JSONArray().put(JSONObject().put("quantity",stock)))))))).put("media",JSONObject().put("nodes",JSONArray(attached.map {JSONObject().put("id",it).put("preview",JSONObject().put("status","READY"))})).put("pageInfo",JSONObject().put("hasNextPage",false)))
            "publication_status"->JSONObject().put("status",status).put("publishedOnPublication",published)
            "activate_product"->{status="ACTIVE";JSONObject()}
            "publish"->{published=true;JSONObject()}
            else->error("Unexpected fixture operation $operation")
        }
        if(operation !in listOf("product","quantity","publication_status","file_status")) cache[operationId]=JSONObject(result.toString())
        if(operation=="create" && failAfterCreate) {failAfterCreate=false;throw ShopifyBrokerException("NETWORK","Synthetic interruption after server acceptance",502)}
        return result
    }
}
