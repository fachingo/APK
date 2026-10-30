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
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate
import okhttp3.OkHttpClient
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h960dp-port")
internal abstract class OperatorUiFixture {
 protected lateinit var repo:CaptureRepository;protected lateinit var sample:InventoryRow;protected lateinit var api:NativeShopifyFakeApi
 private val seed=object:ExternalResource() {
  override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("light",true).putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).putBoolean("shopifyOwnerWrites",true).putBoolean("shopifyOwnerLive",true).commit()
   val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();api=NativeShopifyFakeApi().apply {created=true;actualSku="CVHS-EXAMPLE";sku=actualSku;publicationNames["gid://shopify/Publication/2"]="Point of Sale"};val gateway=DirectShopifyGateway(app,SecureCredentialStore(app,"operator-ui") {key},OkHttpClient.Builder().addInterceptor(api).build(),ShopifyUploadVault(app) {key});gateway.save("fixture.myshopify.com",newId(),"fixture-secret");repo.directShopifyFactory={gateway}
   runBlocking {val session=repo.start("Weekend arrivals","A1");val first=repo.current();val titles=listOf("Alien","The Matrix","Halloween","Blade Runner","Jaws","The Thing")
    titles.forEachIndexed {i,title->val g=if(i==0) first else GroupRow(sessionId=session.id,number=i+1,location="A1",closed=true).also {repo.dao.insert(it)};val old=repo.ensureInventoryItem(g);val b=Bitmap.createBitmap(180,260,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.rgb(30+i*12,60+i*9,65+i*8));c.drawText(title.take(14),12f,100f,Paint().apply {color=Color.WHITE;textSize=19f});c.drawText("VHS • fixture",12f,225f,Paint().apply {color=Color.LTGRAY;textSize=15f});val bytes=java.io.ByteArrayOutputStream().use {b.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};b.recycle();val photo=PhotoRow(groupId=g.id,position=0,role="FRONT",state="READY",scanState="OFF",bytes=bytes.size.toLong(),sha256=sha256(bytes));repo.original(photo.id).writeBytes(bytes);repo.dao.insert(photo);val item=repo.saveInventory(old.copy(title=title,condition="Good",description="An original VHS copy. See photos for condition.",priceCents=if(i<3) 1400 else null,releaseYear=if(i==0) 1979 else null),old.revision);if(i==0) sample=item}
    val data=File(app.cacheDir,"operator-market.csv");data.writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n"+(1..6).joinToString("\n") {i->"$i,${if(i<4) "Alien" else "Aliens"},EBAY_US,${100+i},${LocalDate.now().minusDays(i.toLong())},${10+i},2,CAD,https://www.ebay.com/itm/${100+i}"});repo.commitMarket(repo.previewMarket(data,data.name))
    val chat=ChatConversationRow(title="Prepare this batch for listing",provider="OPENAI",model="gpt-4.1-mini",authMode="API_KEY",toolsEnabled=true);repo.journal.nextDao().insert(chat);repo.journal.nextDao().insert(ChatMessageRow(conversationId=chat.id,role="user",body="What can you inspect in SnapIMS?"));repo.journal.nextDao().insert(ChatMessageRow(conversationId=chat.id,role="assistant",body="I can query your local inventory and sold history when asked. I have not inspected this batch yet. Changes require your approval.",createdAt=System.currentTimeMillis()+1))
    repo.journal.productionDao().insert(ProductionJobRow(kind="MATCH_PRICE",state="PAUSED_REVIEW",error="One title needs a clearer identification"))
   }
  }
  override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();repo.journal.close()}
 }
 protected val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
 protected fun show(text:String) {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))}
 protected fun shot(name:String) {compose.mainClock.advanceTimeBy(700);compose.waitForIdle();File(System.getProperty("snapims.testOutput"),"redesign/$name.png").apply {parentFile!!.mkdirs()}.outputStream().use {(if(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onAllNodes(isDialog()).onLast() else compose.onAllNodes(isRoot()).onLast()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}}
}

internal class OperatorScreensUiTest:OperatorUiFixture() {
 @Test fun populatedScreensSelectionAndIdleSafety() {
  compose.waitUntil(10000) {compose.onAllNodesWithText("Open active batch →").fetchSemanticsNodes().isNotEmpty()};shot("01-dashboard-light")
  compose.onNodeWithText("Open active batch →").performClick();compose.waitUntil(10000) {compose.onAllNodesWithTag("production-batch").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("production-batch").performScrollToNode(hasTestTag("select-${sample.groupId}"));compose.onNodeWithTag("select-${sample.groupId}").performClick();compose.onNodeWithText("Auto Match & Price").assertIsDisplayed();shot("03-batch-selected")
  compose.onNodeWithTag("production-batch").performScrollToIndex(0);compose.onNodeWithText("Auto Match & Price").assertIsDisplayed()
  compose.onAllNodesWithText("More Actions").onFirst().performClick();shot("03-more-actions");compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("Pricing rules"));compose.onNodeWithText("Pricing rules").performClick();shot("05-pricing-presets");compose.onNodeWithText("Cancel").performClick();compose.onNodeWithContentDescription("Close actions").performClick()
  compose.onNodeWithText("Settings").performClick();shot("08-settings")
  compose.onNodeWithText("AI Models & Tasks").performClick();shot("07-task-models");compose.onNodeWithText("Done").performClick()
  compose.openUtility("Batches");compose.waitUntil(10000) {compose.onAllNodesWithText("1 selected of 6").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Auto Match & Price").assertIsDisplayed()
  compose.openUtility("All inventory");show(sample.title);compose.onNodeWithText(sample.title).performClick();compose.onNodeWithText("Tape details").assertExists();shot("04-product-details")
  compose.onNodeWithText("Manage original photos, retakes & grouping").performScrollTo().performClick();shot("04-photographs")
  compose.onNodeWithText("Market").performClick();compose.waitUntil(10000) {compose.onAllNodes(hasSetTextAction() and hasText("Search title, words or barcode")).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasSetTextAction() and hasText("Search title, words or barcode")).performTextInput("Alien");compose.waitUntil(10000) {runCatching {show("Exact Matches")}.isSuccess};shot("05-market");compose.onNode(hasScrollToIndexAction()).performTouchInput {swipeUp()};shot("05-market-results")
  compose.onNodeWithText("Shopify").performClick();compose.onNodeWithText("Search Shopify").performScrollTo().performClick();compose.waitUntil(10000) {runCatching {show("Alien")}.isSuccess};shot("06-shopify")
  compose.openUtility("AI workspace");compose.waitUntil(10000) {runCatching {show("Prepare this batch for listing")}.isSuccess};compose.onNodeWithText("Prepare this batch for listing").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("I can query your local inventory",substring=true).fetchSemanticsNodes().isNotEmpty()};shot("07-ai-workspace")
  compose.openUtility("Tasks");shot("08-jobs")
  compose.onNodeWithText("Capture").performClick();shot("02-capture")
  assertEquals(0,api.mutations);assertEquals(0,api.uploads);assertEquals(0,runBlocking {repo.journal.shopifyDao().active().size});assertEquals(0,runBlocking {repo.dao.unsettledRecognition().size})
 }
}

internal class OperatorRecreationUiTest:OperatorUiFixture() {
 @Test fun unsavedInputAndNavigationSurviveRecreationAndLargeText() {
  compose.openUtility("All inventory");show(sample.title);compose.onNodeWithText(sample.title).performClick();compose.onNode(hasSetTextAction() and hasText("Title")).performTextReplacement("Alien — verified sleeve")
  compose.onNodeWithText("Dashboard").performClick();compose.openUtility("All inventory");compose.onNode(hasSetTextAction() and hasText("Title")).assertTextContains("Alien — verified sleeve")
  compose.activityRule.scenario.recreate();compose.waitForIdle();compose.waitUntil(10000) {compose.onAllNodesWithText("Tape details").fetchSemanticsNodes().isNotEmpty()};compose.onNode(hasSetTextAction() and hasText("Title")).assertTextContains("Alien — verified sleeve")
  assertEquals("Alien",runBlocking {repo.journal.inventoryDao().item(sample.groupId)!!.title})
  compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w900dp-h480dp-land");compose.activity.onConfigurationChanged(compose.activity.resources.configuration)};compose.onNodeWithText("Save changes").assertIsDisplayed();shot("04-product-landscape")
  compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w420dp-h960dp-port");compose.activity.onConfigurationChanged(compose.activity.resources.configuration);repo.preferences.edit().putBoolean("light",false).commit()};shot("04-product-dark")
  compose.runOnUiThread {RuntimeEnvironment.setFontScale(1.6f)};compose.onNodeWithText("Save changes").assertIsDisplayed();shot("04-product-large-text")
  assertEquals(0,api.mutations)
 }
}

internal class OperatorActionsUiTest:OperatorUiFixture() {
 @Test fun oneTapOfflinePricingAndDirectDraftUseOnlySelectionAndSavedBatchDestinations() {
  val session=runBlocking {repo.dao.group(sample.groupId).sessionId};repo.preferences.edit().putStringSet("batchPublications.fixture.myshopify.com.$session",setOf("gid://shopify/Publication/2")).commit()
  compose.onNodeWithText("Open active batch →").performClick();compose.waitUntil(10000) {compose.onAllNodesWithTag("production-batch").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("production-batch").performScrollToNode(hasTestTag("select-${sample.groupId}"));compose.onNodeWithTag("select-${sample.groupId}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
  compose.onNodeWithText("Auto Match & Price").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Auto Match & Price").fetchSemanticsNodes();runBlocking {repo.journal.productionDao().watchJobs().first().count {it.state=="QUEUED"}==1}}
  val price=runBlocking {repo.journal.productionDao().watchJobs().first().single {it.state=="QUEUED"}};assertEquals("MATCH_PRICE",price.kind);assertEquals(setOf(sample.groupId),runBlocking {repo.journal.productionDao().items(price.id).map {it.groupId}.toSet()})
  compose.onNodeWithText("Create Drafts (1)").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Create Drafts (1)").fetchSemanticsNodes();runBlocking {repo.journal.shopifyDao().active().size==1}}
  val job=runBlocking {repo.journal.shopifyDao().active().single()};assertEquals(setOf(sample.groupId),runBlocking {repo.journal.shopifyDao().items(job.id).map {it.groupId}.toSet()});assertEquals(setOf("gid://shopify/Publication/2"),repo.preferences.getStringSet("draftChannels.${sample.groupId}",null));assertEquals(0,api.mutations);compose.onAllNodes(isDialog()).assertCountEquals(0)
 }
}

internal class OperatorRecoveryUiTest:OperatorUiFixture() {
 @Test fun pausedLocalJobIsVisibleAndRecoveryRejectsAiOrChangedState() {
  compose.openUtility("Tasks");show("Resume local job");compose.onNodeWithText("Resume local job").performClick()
  compose.waitUntil(10000) {compose.onAllNodesWithText("All background jobs",substring=true).fetchSemanticsNodes();runBlocking {repo.journal.productionDao().watchJobs().first().single().state=="QUEUED"}}
  runBlocking {
   val local=repo.journal.productionDao().watchJobs().first().single();assertTrue(runCatching {repo.resumeLocalProduction(local.id)}.isFailure)
   val ai=ProductionJobRow(kind="AI_MATCH",state="PAUSED_REVIEW");repo.journal.productionDao().insert(ai);assertTrue(runCatching {repo.resumeLocalProduction(ai.id)}.isFailure);assertEquals("PAUSED_REVIEW",repo.journal.productionDao().job(ai.id)!!.state)
   repo.cancelProduction(local.id);assertTrue(runCatching {repo.resumeLocalProduction(local.id)}.isFailure);assertEquals("CANCELLED",repo.journal.productionDao().job(local.id)!!.state)
  }
  assertEquals(0,api.mutations)
 }
}
