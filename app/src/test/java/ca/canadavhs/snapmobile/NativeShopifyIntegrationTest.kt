package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class NativeShopifyIntegrationTest {
 private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
 private lateinit var operationVault:ShopifyUploadVault;private lateinit var credentialVault:SecureCredentialStore;private lateinit var httpClient:OkHttpClient;private lateinit var repo:CaptureRepository;private lateinit var gateway:DirectShopifyGateway;private lateinit var server:NativeShopifyFakeApi
 private val product="gid://shopify/Product/7";private val location="gid://shopify/Location/1";private val publication="gid://shopify/Publication/1"
 @Before fun setup() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).putBoolean("shopifyOwnerWrites",true).putBoolean("shopifyOwnerLive",true).commit()
  val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val vault=SecureCredentialStore(app,"native-shopify-${newId()}") {key};server=NativeShopifyFakeApi();val http=OkHttpClient.Builder().addInterceptor(server).build();operationVault=ShopifyUploadVault(app) {key};credentialVault=vault;httpClient=http;gateway=DirectShopifyGateway(app,vault,http,operationVault);gateway.save("fixture.myshopify.com",newId(),"fixture-secret");repo.directShopifyFactory={gateway}
 }
 @After fun close() {repo.journal.close()}
 private suspend fun fixture():Pair<ShopifyJobRow,ShopifyItemJobRow> {
  repo.start("Native Shopify fixture","A1");repeat(2) {val p=repo.reserve();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,90,it)};b.recycle();repo.complete(p.id)}
  val old=repo.journal.inventoryDao().item(repo.current().id)!!;val item=repo.saveInventory(old.copy(title="Alien",description="Reviewed description",priceCents=1299,condition="Good",ready=true),old.revision);server.sku=item.itemId;server.creationKey=operationKey("snapims-create:gid://shopify/Shop/1:${item.itemId}")
  val photos=repo.dao.photos(item.groupId);val job=ShopifyJobRow(kind="CREATE_DRAFT",shopId="gid://shopify/Shop/1",shop="fixture.myshopify.com",broker=gateway.endpoint(),connectionFingerprint=gateway.fingerprint(),locationId=location,state="RUNNING");repo.journal.shopifyDao().insert(job)
  val row=ShopifyItemJobRow(jobId=job.id,groupId=item.groupId,itemId=item.itemId,expectedRevision=item.revision,inputFingerprint=shopifyFingerprint(item,photos),snapshotJson=shopifySnapshot(item,photos,"").toString(),creationKey=server.creationKey);repo.journal.shopifyDao().insert(row);return job to row
 }
 @Test fun nativeDraftPhotoStockAndLivePipelineVerifyActualMockedApiOutcomes():Unit=runBlocking {
  val (job,row)=fixture();val before=repo.dao.photos(row.groupId).associate {it.id to hashFile(repo.original(it.id))};ShopifyPipeline(repo,gateway,operationVault).run(job,row)
  val saved=repo.journal.shopifyDao().item(row.id)!!;assertEquals("SUCCESS",saved.state);assertEquals(1,server.creations);assertEquals(2,server.uploads);assertEquals(2,server.fileCreations);assertEquals("DRAFT",server.status);assertEquals(1299,server.price);assertEquals(250,server.weight);assertEquals(1,server.stock);assertEquals(row.itemId,server.actualSku)
  val item=repo.journal.inventoryDao().item(row.groupId)!!;assertEquals("UPLOADED_AS_DRAFT",item.shopifyState);before.forEach {(id,hash)->assertEquals(hash,hashFile(repo.original(id)))}
  val live=job.copy(id=newId(),kind="PUBLISH_LIVE",publicationId=publication);repo.journal.shopifyDao().insert(live);val liveRow=row.copy(id=newId(),jobId=live.id,state="QUEUED",expectedRevision=item.revision,inputFingerprint=shopifyFingerprint(item,repo.dao.photos(item.groupId)),snapshotJson=shopifySnapshot(item,repo.dao.photos(item.groupId),"").toString(),remoteJson=saved.remoteJson);repo.journal.shopifyDao().insert(liveRow)
  ShopifyPipeline(repo,gateway,operationVault).run(live,liveRow);assertEquals("SUCCESS",repo.journal.shopifyDao().item(liveRow.id)!!.state);assertEquals("PUBLISHED",repo.journal.inventoryDao().item(item.groupId)!!.shopifyState);assertTrue(server.published);assertEquals("ACTIVE",server.status);assertEquals(1,server.creations)
 }
 @Test fun lostCreationResponseReconcilesAfterGatewayRestartWithoutDuplicate():Unit=runBlocking {
  val (_,row)=fixture();val s=JSONObject(row.snapshotJson);val args=JSONObject().put("creationKey",row.creationKey).put("itemId",row.itemId).put("title",s.getString("title")).put("descriptionHtml",s.getString("descriptionHtml")).put("vendor",s.getString("vendor")).put("productType",s.getString("productType")).put("tags",s.getJSONArray("publishTags"));server.loseCreation=true
  try {gateway.call(row.creationKey,"create",args);fail()} catch(_:ShopifyBrokerException) {}
  assertEquals(1,server.creations);gateway=DirectShopifyGateway(app,credentialVault,httpClient,operationVault);val recovered=gateway.call(row.creationKey,"create",args);assertEquals(product,recovered.getJSONObject("product").getString("id"));assertTrue(recovered.getBoolean("reconciled"));assertEquals(1,server.creations)
  try {gateway.call(row.creationKey,"create",JSONObject(args.toString()).put("title","Different approved request"));fail()} catch(_:IllegalStateException) {};assertEquals(1,server.creations)
 }
 @Test fun disabledOwnerWritesAndForeignResourcesNeverSendMutations():Unit=runBlocking {
  val (_,row)=fixture();repo.preferences.edit().putBoolean("shopifyOwnerWrites",false).commit();try {gateway.call(newId(),"inventory_item",JSONObject().put("creationKey",row.creationKey).put("inventoryItemId","gid://shopify/InventoryItem/9").put("itemId",row.itemId).put("weightGrams",250));fail()} catch(_:IllegalStateException) {};assertEquals(0,server.mutations)
  repo.preferences.edit().putBoolean("shopifyOwnerWrites",true).commit();try {gateway.call(newId(),"activate_product",JSONObject().put("creationKey",row.creationKey).put("productId",product));fail()} catch(_:IllegalStateException) {};assertEquals(0,server.mutations)
 }
 @Test fun ownerStatusRequiresAppliedApprovalAndVerifiesPublicationSeparately():Unit=runBlocking {
  val (_,row)=fixture();server.created=true;server.actualSku=row.itemId;val before=gateway.call(newId(),"product",JSONObject().put("productId",product).put("locationId",location));val health=gateway.call(newId(),"health",JSONObject());val payload=JSONObject().put("action","PUBLISH_LIVE").put("before",before).put("broker",gateway.endpoint()).put("connectionFingerprint",gateway.fingerprint()).put("health",health).put("location",location).put("publication",publication).put("group_ids",JSONArray()).put("items",JSONArray())
  val review=ShopifyReviewRow(kind="OWNER_ACTION",payloadJson=payload.toString(),bindingHash=sha256(canonicalJson(payload).toByteArray()));val job=ShopifyJobRow(kind="OWNER_ACTION",shopId="gid://shopify/Shop/1",shop="fixture.myshopify.com",broker=gateway.endpoint(),connectionFingerprint=gateway.fingerprint(),locationId=location,state="RUNNING");repo.journal.shopifyDao().insert(review);repo.journal.shopifyDao().insert(job)
  val args=JSONObject().put("reviewId",review.id).put("jobId",job.id);try {gateway.call(operationKey("owner:${review.id}"),"owner_action",args);fail()} catch(_:IllegalStateException) {};assertEquals(0,server.mutations)
  repo.journal.shopifyDao().update(review.copy(state="APPLIED",resultJson=JSONObject().put("jobId",job.id).toString()));val result=gateway.call(operationKey("owner:${review.id}"),"owner_action",args);assertEquals("ACTIVE",result.getString("status"));assertTrue(result.getBoolean("publishedToSelectedChannel"));assertTrue(server.published)
 }
 @Test fun ownerApprovalExpiresIfRemoteDataChanged():Unit=runBlocking {
  val (_,row)=fixture();server.created=true;server.actualSku=row.itemId;val before=gateway.call(newId(),"product",JSONObject().put("productId",product).put("locationId",location));val p=JSONObject().put("action","ARCHIVED").put("before",before).put("broker",gateway.endpoint()).put("connectionFingerprint",gateway.fingerprint()).put("location",location).put("publication","")
  val job=ShopifyJobRow(kind="OWNER_ACTION",shopId="gid://shopify/Shop/1",shop="fixture.myshopify.com",broker=gateway.endpoint(),connectionFingerprint=gateway.fingerprint(),locationId=location,state="RUNNING");val review=ShopifyReviewRow(kind="OWNER_ACTION",state="APPLIED",payloadJson=p.toString(),bindingHash=sha256(canonicalJson(p).toByteArray()),resultJson=JSONObject().put("jobId",job.id).toString());repo.journal.shopifyDao().insert(job);repo.journal.shopifyDao().insert(review);server.title="Changed outside phone"
  try {gateway.call(operationKey("owner:${review.id}"),"owner_action",JSONObject().put("reviewId",review.id).put("jobId",job.id));fail()} catch(_:IllegalStateException) {};assertEquals(0,server.mutations)
 }
 @Test fun ownedPriceTagsWeightAndStockUpdateProtectUnselectedFields():Unit=runBlocking {
  val (create,row)=fixture();ShopifyPipeline(repo,gateway,operationVault).run(create,row);server.taxable=false
  val before=gateway.call(newId(),"product",JSONObject().put("productId",product).put("locationId",location));val old=repo.journal.inventoryDao().item(row.groupId)!!;val item=repo.saveInventory(old.copy(title="Alien corrected",tags="science-fiction",priceCents=1599,weightGrams=300,quantity=0,ready=false),old.revision)
  val values=directShopifyMetadata(item,create.shopId);val patch=JSONObject();for(k in listOf("title","tags","priceCents","weightGrams","quantity")) patch.put(k,values.get(k))
  val job=create.copy(id=newId(),kind="UPDATE_METADATA");repo.journal.shopifyDao().insert(job);val update=row.copy(id=newId(),jobId=job.id,state="QUEUED",expectedRevision=item.revision,inputFingerprint=shopifyFingerprint(item,repo.dao.photos(item.groupId)),snapshotJson=inventoryJson(item).put("before",before).put("patch",patch).toString(),remoteJson=repo.journal.shopifyDao().item(row.id)!!.remoteJson);repo.journal.shopifyDao().insert(update)
  ShopifyManagementPipeline(repo,gateway).run(job,update);assertEquals("SUCCESS",repo.journal.shopifyDao().item(update.id)!!.state);assertEquals("Alien corrected",server.title);assertEquals(1599,server.price);assertEquals(300,server.weight);assertEquals(0,server.stock);assertFalse(server.taxable);assertTrue(server.tags.toString().contains("science-fiction"));assertEquals(2,server.fileCreations);assertEquals(1,server.creations)
 }
 @Test fun lostFileResponseResumesWithoutDuplicateProductOrPhoto():Unit=runBlocking {
  val (job,row)=fixture();server.loseFile=true;try {ShopifyPipeline(repo,gateway,operationVault).run(job,row);fail()} catch(_:ShopifyBrokerException) {};assertEquals(1,server.fileCreations)
  gateway=DirectShopifyGateway(app,credentialVault,httpClient,operationVault);ShopifyPipeline(repo,gateway,operationVault).run(job,repo.journal.shopifyDao().item(row.id)!!);assertEquals("SUCCESS",repo.journal.shopifyDao().item(row.id)!!.state);assertEquals(1,server.creations);assertEquals(2,server.fileCreations);assertEquals(2,server.uploads)
 }
 @Test fun externalPriceChangeStopsReviewedUpdateWithoutOverwritingIt():Unit=runBlocking {
  val (create,row)=fixture();ShopifyPipeline(repo,gateway,operationVault).run(create,row);val before=gateway.call(newId(),"product",JSONObject().put("productId",product).put("locationId",location));val old=repo.journal.inventoryDao().item(row.groupId)!!;val item=repo.saveInventory(old.copy(title="Reviewed title",priceCents=1599,ready=false),old.revision);val patch=JSONObject().put("title",item.title).put("priceCents",1599L);val job=create.copy(id=newId(),kind="UPDATE_METADATA");repo.journal.shopifyDao().insert(job);val update=row.copy(id=newId(),jobId=job.id,state="QUEUED",expectedRevision=item.revision,inputFingerprint=shopifyFingerprint(item,repo.dao.photos(item.groupId)),snapshotJson=inventoryJson(item).put("before",before).put("patch",patch).toString(),remoteJson=repo.journal.shopifyDao().item(row.id)!!.remoteJson);repo.journal.shopifyDao().insert(update);server.changePriceAfterMetadata=true;val writes=server.variantWrites
  try {ShopifyManagementPipeline(repo,gateway).run(job,update);fail()} catch(_:IllegalStateException) {};assertEquals(9999,server.price);assertEquals(writes,server.variantWrites);assertEquals("NEEDS_REVIEW",repo.journal.shopifyDao().item(update.id)!!.state)
 }
 @Test fun statusApprovalNeedsSubmitAndCannotReplayOrModifyOnRejection():Unit=runBlocking {
  val (_,row)=fixture();server.created=true;server.actualSku=row.itemId;val health=gateway.call(newId(),"health",JSONObject());val rejected=repo.prepareShopifyOwnerAction(product,location,"ACTIVE","",health,gateway);repo.decideShopifyReview(rejected.id,false);assertEquals("REJECTED",repo.journal.shopifyDao().review(rejected.id)!!.state);assertEquals(0,server.mutations)
  val review=repo.prepareShopifyOwnerAction(product,location,"ACTIVE","",health,gateway);val count=repo.journal.shopifyDao().active().size;try {repo.decideShopifyReview(review.id,true,"");fail()} catch(_:IllegalStateException) {};assertEquals(count,repo.journal.shopifyDao().active().size);assertEquals("PENDING",repo.journal.shopifyDao().review(review.id)!!.state);assertEquals(0,server.mutations)
  repo.decideShopifyReview(review.id,true,"SUBMIT");assertEquals(count+1,repo.journal.shopifyDao().active().size);try {repo.decideShopifyReview(review.id,true,"SUBMIT");fail()} catch(_:IllegalStateException) {};assertEquals(count+1,repo.journal.shopifyDao().active().size);assertEquals(0,server.mutations)
 }

 @Test fun agentDirectStatusUsesNativeApprovalAndReportsOnlyVerifiedCompletion():Unit=runBlocking {
  val (_,item)=fixture();server.created=true;server.actualSku=item.itemId;val chat=ChatConversationRow(title="Native owner agent",provider="OPENAI",model="fixture",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);repo.journal.nextDao().insert(chat);val tools=AgentTools(repo)
  val read=tools.execute(chat,"app_read",JSONObject().put("resource","shopify.catalog").put("arguments_json",JSONObject().put("query","Alien").put("status","DRAFT").put("cursor","").toString()));assertEquals(product,read.getJSONArray("nodes").getJSONObject(0).getString("id"));assertFalse(read.toString().contains("fixture-secret"))
  suspend fun propose():AgentApprovalRow {val response=tools.execute(chat,"app_propose",JSONObject().put("action","propose_shopify_status").put("arguments_json",JSONObject().put("product_id",product).put("location_id",location).put("status","ACTIVE").put("publication_id","").toString()));return repo.journal.nextDao().approval(response.getString("approval_id"))!!}
  val rejected=propose();tools.decide(rejected.id,false);assertEquals(0,server.mutations);assertEquals("REJECTED",repo.journal.shopifyDao().review(JSONObject(rejected.payloadJson).getString("native_review_id"))!!.state)
  val pending=propose();try {tools.decide(pending.id,true,AgentDecisionOptions());fail()} catch(_:IllegalStateException) {};assertEquals(0,server.mutations)
  tools.decide(pending.id,true,AgentDecisionOptions(confirmation="SUBMIT"));val taskId=JSONObject(repo.journal.nextDao().approval(pending.id)!!.resultJson).getString("taskId");val task=repo.journal.agentTaskDao().get(taskId)!!;assertNotEquals("COMPLETE",repo.agentTaskStatus(task).state);val id=JSONArray(task.jobIdsJson).getString(0);val job=repo.journal.shopifyDao().job(id)!!;assertEquals("OWNER_ACTION",job.kind);repo.runOwnerJob(job,gateway);val status=repo.agentTaskStatus(task);assertEquals("COMPLETE",status.state);assertEquals(1,status.done);assertEquals("ACTIVE",status.jobs.getJSONObject(0).getJSONObject("outcome").getString("status"));assertEquals(1,server.mutations)
 }

}

internal class NativeShopifyFakeApi:Interceptor {
  private val product="gid://shopify/Product/7";private val location="gid://shopify/Location/1";private val publication="gid://shopify/Publication/1"
  var loseFile=false;var changePriceAfterMetadata=false;var variantWrites=0;val fileAlts=mutableMapOf<String,String>();val fileNames=mutableMapOf<String,String>();var taxable=true;var tags=JSONArray();var created=false;var creations=0;var fileCreations=0;var mutations=0;var uploads=0;var loseCreation=false;var sku="";var creationKey="";var actualSku="";var title="Alien";var status="DRAFT";var price=1299;var weight=250;var stock:Int?=null;var published=false;val files=mutableListOf<String>();val attached=mutableListOf<String>()
  fun product():JSONObject=JSONObject().put("id",product).put("title",title).put("status",status).put("vendor","Canada VHS").put("productType","VHS Tape").put("tags",tags).put("descriptionHtml","Reviewed description").put("updatedAt","2026-10-29T00:00:00Z").put("identity",JSONObject().put("value",sku)).put("metafield",JSONObject().put("value",creationKey)).put("variants",JSONObject().put("nodes",JSONArray().put(JSONObject().put("id","gid://shopify/ProductVariant/8").put("sku",actualSku).put("price",java.math.BigDecimal.valueOf(price.toLong(),2).toPlainString()).put("taxable",taxable).put("barcode","").put("inventoryItem",JSONObject().put("id","gid://shopify/InventoryItem/9").put("tracked",true).put("requiresShipping",true).put("measurement",JSONObject().put("weight",JSONObject().put("value",weight).put("unit","GRAMS"))).put("inventoryLevel",stock?.let {JSONObject().put("quantities",JSONArray().put(JSONObject().put("name","available").put("quantity",it)))} ?: JSONObject.NULL)))).put("pageInfo",JSONObject().put("hasNextPage",false))).put("media",JSONObject().put("nodes",JSONArray(attached.map {JSONObject().put("id",it).put("alt","fixture").put("preview",JSONObject().put("status","READY"))})).put("pageInfo",JSONObject().put("hasNextPage",false)))
  override fun intercept(chain:Interceptor.Chain):Response {val request=chain.request();if(request.url.host!="fixture.myshopify.com") {uploads++;return response(request,JSONObject())};if(request.url.encodedPath.endsWith("access_token")) return response(request,JSONObject().put("access_token","fixture-token").put("expires_in",86399).put("scope","write_products,write_inventory,write_files,read_locations,write_publications"))
   val buffer=okio.Buffer();request.body!!.writeTo(buffer);val input=JSONObject(buffer.readUtf8());val q=input.getString("query");val v=input.getJSONObject("variables");if(q.startsWith("mutation")) mutations++
   val data=when {
    q.contains("SnapIMSConnection")->JSONObject().put("shop",JSONObject().put("id","gid://shopify/Shop/1").put("name","Fixture Canada VHS").put("myshopifyDomain","fixture.myshopify.com").put("currencyCode","CAD").put("plan",JSONObject().put("partnerDevelopment",false))).put("currentAppInstallation",JSONObject().put("accessScopes",JSONArray(listOf("write_products","write_inventory","write_files","read_locations","write_publications").map {JSONObject().put("handle",it)}))).put("locations",JSONObject().put("nodes",JSONArray().put(JSONObject().put("id",location).put("name","Main").put("isActive",true))))
    q.contains("SnapIMSPublications")->JSONObject().put("publications",JSONObject().put("nodes",JSONArray().put(JSONObject().put("id",publication).put("name","Online Store"))))
    q.contains("SnapIMSFind")->JSONObject().put("variants",JSONObject().put("nodes",JSONArray(if(created && actualSku==sku) listOf(product().getJSONObject("variants").getJSONArray("nodes").getJSONObject(0).put("product",JSONObject().put("id",product))) else emptyList<JSONObject>())).put("pageInfo",JSONObject().put("hasNextPage",false))).put("products",JSONObject().put("nodes",JSONArray(if(created) listOf(product()) else emptyList<JSONObject>())).put("pageInfo",JSONObject().put("hasNextPage",false)))
    q.contains("SnapIMSCreate")-> {created=true;creations++;title=v.getJSONObject("product").getString("title");tags=v.getJSONObject("product").getJSONArray("tags");if(loseCreation) {loseCreation=false;throw java.io.IOException("Simulated accepted request, lost response")};JSONObject().put("productCreate",JSONObject().put("product",product()).put("userErrors",JSONArray()))}
    q.contains("SnapIMSMetadata")-> {val p=v.getJSONObject("product");if(p.has("title")) title=p.getString("title");if(p.has("tags")) tags=p.getJSONArray("tags");if(changePriceAfterMetadata) price=9999;JSONObject().put("productUpdate",JSONObject().put("product",product()).put("userErrors",JSONArray()))}
    q.contains("SnapIMSVariant")-> {variantWrites++;price=java.math.BigDecimal(v.getJSONArray("variants").getJSONObject(0).getString("price")).movePointRight(2).intValueExact();taxable=v.getJSONArray("variants").getJSONObject(0).getBoolean("taxable");JSONObject().put("productVariantsBulkUpdate",JSONObject().put("productVariants",JSONArray()).put("userErrors",JSONArray()))}
    q.contains("SnapIMSItem")-> {actualSku=v.getJSONObject("input").getString("sku");weight=v.getJSONObject("input").getJSONObject("measurement").getJSONObject("weight").getInt("value");JSONObject().put("inventoryItemUpdate",JSONObject().put("inventoryItem",JSONObject().put("id","gid://shopify/InventoryItem/9")).put("userErrors",JSONArray()))}
    q.contains("SnapIMSStock")->JSONObject().put("inventoryItem",JSONObject().put("inventoryLevel",stock?.let {JSONObject().put("quantities",JSONArray().put(JSONObject().put("quantity",it)))} ?: JSONObject.NULL))
    q.contains("SnapIMSActivate")-> {stock=v.getInt("available");JSONObject().put("inventoryActivate",JSONObject().put("inventoryLevel",JSONObject().put("id","gid://shopify/InventoryLevel/1")).put("userErrors",JSONArray()))}
    q.contains("SnapIMSQuantity")-> {stock=v.getJSONObject("input").getJSONArray("quantities").getJSONObject(0).getInt("quantity");JSONObject().put("inventorySetQuantities",JSONObject().put("userErrors",JSONArray()))}
    q.contains("SnapIMSStage")->JSONObject().put("stagedUploadsCreate",JSONObject().put("stagedTargets",JSONArray().put(JSONObject().put("url","https://storage.googleapis.com/fixture").put("resourceUrl","https://storage.googleapis.com/fixture/photo.jpg").put("parameters",JSONArray()))).put("userErrors",JSONArray()))
    q.contains("SnapIMSFile(")-> {fileCreations++;val id="gid://shopify/MediaImage/${fileCreations}";files+=id;fileAlts[id]=v.getJSONArray("input").getJSONObject(0).getString("alt");fileNames[id]=v.getJSONArray("input").getJSONObject(0).getString("filename");if(loseFile) {loseFile=false;throw java.io.IOException("Simulated accepted file creation, lost response")};JSONObject().put("fileCreate",JSONObject().put("files",JSONArray().put(JSONObject().put("id",id).put("alt",v.getJSONArray("input").getJSONObject(0).getString("alt")).put("fileStatus","READY"))).put("userErrors",JSONArray()))}
    q.contains("SnapIMSFiles(")->JSONObject().put("files",JSONObject().put("nodes",JSONArray(files.filter {fileNames[it]==v.getString("query").removePrefix("filename:")}.map {JSONObject().put("id",it).put("alt",fileAlts[it]).put("fileStatus","READY")})).put("pageInfo",JSONObject().put("hasNextPage",false)))
    q.contains("SnapIMSFileState")->JSONObject().put("node",JSONObject().put("id",v.getString("id")).put("fileStatus","READY"))
    q.contains("SnapIMSAttach")-> {val id=v.getJSONArray("files").getJSONObject(0).getString("id");if(id !in attached) attached+=id;JSONObject().put("fileUpdate",JSONObject().put("files",JSONArray().put(JSONObject().put("id",id).put("fileStatus","READY"))).put("userErrors",JSONArray()))}
    q.contains("SnapIMSCatalog")->JSONObject().put("products",JSONObject().put("nodes",JSONArray(if(created) listOf(product()) else emptyList<JSONObject>())).put("pageInfo",JSONObject().put("hasNextPage",false).put("endCursor",JSONObject.NULL)))
    q.contains("SnapIMSVerify")->JSONObject().put("product",if(created) product() else JSONObject.NULL)
    q.contains("SnapIMSPublished")->JSONObject().put("product",JSONObject().put("id",product).put("status",status).put("publishedOnPublication",published))
    q.contains("SnapIMSLive") || q.contains("SnapIMSOwnerStatus")-> {status=v.getJSONObject("product").getString("status");JSONObject().put("productUpdate",JSONObject().put("product",JSONObject().put("id",product).put("status",status)).put("userErrors",JSONArray()))}
    q.contains("SnapIMSPublish(")-> {published=true;JSONObject().put("publishablePublish",JSONObject().put("userErrors",JSONArray()))}
    else->error("Unexpected native API query $q")
   };return response(request,JSONObject().put("data",data))
  }
  private fun response(request:Request,body:JSONObject)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toString().toResponseBody("application/json".toMediaType())).build()
 }
