package ca.canadavhs.snapmobile

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w412dp-h915dp-port")
internal abstract class GuidedUiFixture {
 protected lateinit var repo:CaptureRepository;protected lateinit var copies:List<InventoryRow>;protected lateinit var api:NativeShopifyFakeApi
 protected lateinit var credentials:SecureCredentialStore;protected lateinit var vault:ShopifyUploadVault
 protected lateinit var session:SessionRow
 private val seed=object:ExternalResource() {
  override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo
   repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("light",true).putLong("fxLastAttempt",System.currentTimeMillis()).putString("verifiedUsdCad",VerifiedFx("1.35",LocalDate.now().toString(),System.currentTimeMillis()).json().toString()).putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).putBoolean("shopifyOwnerWrites",true).commit()
   val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();api=NativeShopifyFakeApi();credentials=SecureCredentialStore(app,"guided-ui-${newId()}") {key};vault=ShopifyUploadVault(app) {key};val gateway=DirectShopifyGateway(app,credentials,OkHttpClient.Builder().addInterceptor(api).build(),vault);gateway.save("fixture.myshopify.com",newId(),"fixture-secret");repo.directShopifyFactory={gateway}
   runBlocking {session=repo.start("Six tapes · production run","A1");val first=repo.current();copies=(0 until 6).map {i->val group=if(i==0) first else GroupRow(sessionId=session.id,number=i+1,closed=true,location="A1").also {repo.dao.insert(it)};val old=repo.ensureInventoryItem(group)
     repeat(if(i==0) 6 else 2) {position->val bitmap=Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(18+i*16,75,70));canvas.drawText(if(i==4) "UNIDENTIFIED" else "ALIEN",12f,85f,Paint().apply {color=Color.WHITE;textSize=22f});canvas.drawText("VHS · fixture",12f,210f,Paint().apply {color=Color.WHITE;textSize=14f});val bytes=java.io.ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};bitmap.recycle();val photo=PhotoRow(groupId=group.id,position=position,role=if(position==0) "FRONT" else "BACK",state="READY",scanState="OFF",bytes=bytes.size.toLong(),sha256=sha256(bytes));repo.original(photo.id).writeBytes(bytes);repo.dao.insert(photo)}
     repo.saveInventory(old.copy(title=if(i==4) "Unknown Film" else "Alien",condition="Good",releaseYear=1979,priceCents=if(i==5) 900 else null),old.revision)
    }
    val file=File(app.cacheDir,"guided-ui-sales.csv");file.writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url,release_year,condition\n"+(1..5).joinToString("\n") {i->"$i,Alien 1979 VHS,EBAY_US,8000$i,${LocalDate.now().minusDays(3)},10,2,USD,https://www.ebay.com/itm/8000$i,1979,Good"});val preview=repo.previewMarket(file,file.name);repo.commitMarket(preview);preview.file.delete();file.delete()
   }
  }
  override fun after() {Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();repo.journal.close()}
 }
 protected val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
 protected fun waitFor(test:()->Boolean) {compose.waitUntil(15000) {compose.onAllNodesWithTag("guided-batch").fetchSemanticsNodes();test()}}
 protected fun runGuide() {runBlocking {repo.journal.productionDao().guided(session.id)?.let {repo.runGuidedBatch(it.id)}};compose.waitForIdle()}
 protected fun shot(name:String) {File(System.getProperty("snapims.testOutput"),"guided/$name.png").apply {parentFile!!.mkdirs()}.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
}
internal class GuidedWorkflowUiTest:GuidedUiFixture() {
 @Test fun actualFiveStageNavigationApprovesPricesAndListingsThenQueuesOnlyFiveOfSixCopies() {
  compose.openUtility("Batches");waitFor {compose.onAllNodesWithText("Identify & price 6 copies").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(0,api.mutations);assertTrue(runBlocking {repo.journal.productionDao().watchJobs().first().isEmpty()});assertTrue(runBlocking {repo.journal.nextDao().watchChats().first().isEmpty()})
  compose.onNodeWithTag("guided-batch").performScrollToNode(hasText("6 photographs · one physical copy"));compose.onNodeWithText("6 photographs · one physical copy").assertExists();compose.onNodeWithTag("guided-batch").performScrollToIndex(0);shot("01-capture-groups")
  compose.onNodeWithText("Identify & price 6 copies").performClick();waitFor {runBlocking {repo.journal.productionDao().guided(session.id)!=null}};runGuide()
  compose.onNodeWithText("Review prices").performClick();waitFor {compose.onAllNodesWithText("Approve all eligible prices (4)").fetchSemanticsNodes().isNotEmpty()};shot("02-price-review")
  // Activity recreation restores the workflow step, exact selection and persisted recommendations.
  compose.activityRule.scenario.recreate();compose.waitForIdle();waitFor {compose.onAllNodesWithText("Approve all eligible prices (4)").fetchSemanticsNodes().isNotEmpty()};assertEquals(2,repo.preferences.getInt("guidedStep.${session.id}",-1));assertEquals(6,repo.preferences.getStringSet("batchSelection.${session.id}",emptySet())!!.size)
  compose.onNodeWithText("Approve all eligible prices (4)").performClick();waitFor {runBlocking {repo.journal.inventoryDao().items().count {it.priceCents!=null}==5}};runGuide()
  waitFor {compose.onAllNodesWithText("6 copies · 5 priced",substring=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Continue with approved listings (5)").assertIsEnabled().performClick();waitFor {compose.onAllNodesWithText("Approve listings & continue (5)").fetchSemanticsNodes().isNotEmpty()};shot("03-review-listings")
  compose.onNodeWithText("Approve listings & continue (5)").performClick();waitFor {compose.onAllNodesWithText("Create Shopify Drafts (5)").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Create Shopify Drafts (5)").assertIsEnabled();shot("04-submit-drafts")
  runGuide();assertEquals(0,api.mutations);compose.onNodeWithText("Create Shopify Drafts (5)").performClick();waitFor {runBlocking {repo.journal.shopifyDao().active().size==1}}
  val job=runBlocking {repo.journal.shopifyDao().active().single()};val queued=runBlocking {repo.journal.shopifyDao().items(job.id)};assertEquals(5,queued.size);assertFalse(queued.any {it.groupId==copies[4].groupId});assertTrue(queued.all {it.state=="QUEUED"});assertEquals(0,api.mutations);assertTrue(runBlocking {repo.journal.shopifyDao().watchReviews().first().isEmpty()})
  val remotes=mutableListOf<NativeShopifyFakeApi>();runBlocking {for((i,row) in queued.withIndex()) {val remote=NativeShopifyFakeApi(100L+i*10).also {it.sku=row.itemId;it.creationKey=row.creationKey};remotes+=remote;val g=DirectShopifyGateway(repo.context,credentials,OkHttpClient.Builder().addInterceptor(remote).build(),vault);ShopifyPipeline(repo,g,vault).run(job,row)}}
  waitFor {runBlocking {repo.journal.inventoryDao().items().count {it.shopifyState=="UPLOADED_AS_DRAFT"}==5}};assertEquals(5,remotes.sumOf {it.creations});assertEquals(14,remotes.sumOf {it.uploads});assertTrue(remotes.all {it.status=="DRAFT" && !it.published});assertEquals(5,runBlocking {repo.journal.inventoryDao().items().filter {it.shopifyProductId.isNotBlank()}.map {it.shopifyProductId}.distinct().size});assertEquals(900L,runBlocking {repo.journal.inventoryDao().item(copies.last().groupId)!!.priceCents});assertEquals(0,runBlocking {repo.journal.nextDao().watchChats().first().size})
  compose.onNodeWithTag("guided-batch").performScrollToIndex(0);shot("05-verified-outcomes")
 }
}
internal class GuidedInspectorUiTest:GuidedUiFixture() {
 @Test fun inspectorSearchCorrectionsAndDeferredExceptionsStayInTheWorkflow() {
  compose.openUtility("Batches");waitFor {compose.onAllNodesWithText("Identify & price 6 copies").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Identify & price 6 copies").performClick();waitFor {runBlocking {repo.journal.productionDao().guided(session.id)!=null}};runGuide();compose.onNodeWithText("Review prices").performClick()
  compose.onNodeWithTag("guided-batch").performScrollToNode(hasTestTag("copy-${copies[4].groupId}"));compose.onNodeWithTag("copy-${copies[4].groupId}").performClick()
  compose.onNodeWithTag("guided-inspector").performScrollToNode(hasText("Search title, OCR spelling or UPC"));compose.onNode(hasSetTextAction() and hasText("Search title, OCR spelling or UPC")).performTextReplacement("Aliern");compose.onNodeWithText("Search local database").performScrollTo().performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Alien 1979 VHS",substring=true).fetchSemanticsNodes().isNotEmpty()};shot("06-ranked-inspector")
  compose.onNodeWithTag("guided-inspector").performScrollToNode(hasText("Defer tape"));compose.onNodeWithText("Defer tape").performClick();waitFor {runBlocking {repo.journal.productionDao().guided(session.id)?.let {repo.journal.productionDao().items(it.id).any {r->r.groupId==copies[4].groupId && guidedPhase(r)=="DEFERRED"}}==true}}
  compose.onNodeWithTag("guided-batch").performScrollToIndex(0);compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w915dp-h412dp-land");compose.activity.onConfigurationChanged(compose.activity.resources.configuration)};compose.onNodeWithText("Approve all eligible prices (4)").assertIsDisplayed();shot("07-landscape")
  compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w412dp-h915dp-port");RuntimeEnvironment.setFontScale(1.6f);compose.activity.onConfigurationChanged(compose.activity.resources.configuration)};compose.onNodeWithText("Approve all eligible prices (4)").assertIsDisplayed();shot("08-large-text")
  assertEquals(0,api.mutations);assertEquals(0,api.uploads);assertEquals(0,runBlocking {repo.journal.nextDao().watchChats().first().size})
 }
}

internal class GuidedCaptureNavigationTest:GuidedUiFixture() {
 @Test fun finishingCaptureOpensIdentificationWithoutAdvancedMenusOrUploads() {
  compose.onNodeWithText("Capture").performClick();compose.onNodeWithContentDescription("Finish batch").performClick();compose.onNodeWithText("Finish batch").performClick()
  waitFor {compose.onAllNodesWithText("Step 2 of 5 · Identify").fetchSemanticsNodes().isNotEmpty()}
  assertTrue(runBlocking {repo.dao.session(session.id).finished});assertNotNull(runBlocking {repo.journal.productionDao().guided(session.id)});compose.onNodeWithText("Review prices").assertExists()
  assertEquals(0,api.mutations);assertEquals(0,api.uploads);assertEquals(0,runBlocking {repo.journal.nextDao().watchChats().first().size})
 }
}
