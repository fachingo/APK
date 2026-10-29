package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h900dp-port")
class NativeShopifyOwnerUiTest {
 private lateinit var repo:CaptureRepository;private lateinit var gateway:DirectShopifyGateway;private lateinit var server:NativeShopifyFakeApi
 private val seed=object:ExternalResource() {
  override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo
   repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).putBoolean("shopifyOwnerWrites",true).putBoolean("shopifyOwnerLive",true).commit();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val vault=SecureCredentialStore(app,"native-owner-ui") {key};server=NativeShopifyFakeApi().apply {created=true;sku="FIXTURE-COPY";actualSku=sku;creationKey=newId()};gateway=DirectShopifyGateway(app,vault,OkHttpClient.Builder().addInterceptor(server).build(),ShopifyUploadVault(app) {key});gateway.save("fixture.myshopify.com",newId(),"fixture-secret");repo.directShopifyFactory={gateway};runBlocking {repo.start("Owner UI linked inventory","A1");val item=repo.ensureInventoryItem(repo.current());repo.journal.inventoryDao().update(item.copy(title="Alien",shopifyProductId="gid://shopify/Product/7",shopifyVariantId="gid://shopify/ProductVariant/8",shopifyInventoryItemId="gid://shopify/InventoryItem/9",shopifyState="UPLOADED_AS_DRAFT"));server.sku=item.itemId;server.actualSku=item.itemId;server.creationKey=operationKey("snapims-create:gid://shopify/Shop/1:${item.itemId}") }
  }
  override fun after() {Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();repo.journal.close()}
 }
 private val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
 private fun scroll(text:String) {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
  compose.onNodeWithText(text).performClick()}
 private fun waitReview() {try {compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes().isNotEmpty()}} catch(e:Throwable) {println("OWNER_UI_ERROR="+ViewModelProvider(compose.activity)[ShopifyModel::class.java].error);println(compose.onRoot().printToString(12));throw e}}
 private fun waitJob() {try {compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes();runBlocking {repo.journal.shopifyDao().active().size==1}}} catch(e:Throwable) {println("OWNER_JOB_ERROR="+ViewModelProvider(compose.activity)[ShopifyModel::class.java].error);repo.journal.openHelper.writableDatabase.query("SELECT id,kind,state,error FROM shopify_jobs").use {c->while(c.moveToNext()) println("OWNER_JOB="+cursorObject(c))};repo.journal.openHelper.writableDatabase.query("SELECT state,resultJson FROM shopify_reviews").use {c->while(c.moveToNext()) println("OWNER_REVIEW="+cursorObject(c))};throw e}}
 private fun runApprovedJob():ShopifyJobRow {val job=runBlocking {repo.journal.shopifyDao().active().single()}
  assertEquals("QUEUED",job.state);runBlocking {repo.runOwnerJob(job,gateway)};return runBlocking {repo.journal.shopifyDao().job(job.id)!!}}
 @Test fun localApprovalActiveAndOnlineStorePublicationRequireSeparateExactReviews() {
  compose.onNodeWithText("Settings").performClick()
  scroll("Shopify")
  scroll("Listings & saved reviews")
  scroll("Search Shopify")
  compose.waitUntil(10000) {runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Main"))}.isSuccess}
  scroll("Main")
  scroll("Inspect listing")
  compose.waitUntil(10000) {runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Listing details"))}.isSuccess}
  scroll("Review local approval");waitReview()
  compose.onNodeWithText("Approve once").assertIsEnabled().performClick()
  waitJob()
  assertEquals("SUCCESS",runApprovedJob().state)
  assertEquals(0,server.mutations)
  scroll("Set Active");waitReview()
  compose.onNodeWithText("Approve once").assertIsNotEnabled()
  compose.onNodeWithText("SUBMIT").performTextInput("SUBMIT")
  compose.onNodeWithText("Approve once").performClick()
  waitJob()
  assertEquals("SUCCESS",runApprovedJob().state)
  assertEquals("ACTIVE",server.status)
  assertFalse(server.published)
  scroll("Online Store")
  scroll("Review publication to selected channel");waitReview()
  compose.onNodeWithText("Approve once").assertIsNotEnabled()
  compose.onNodeWithText("SUBMIT").performTextInput("SUBMIT")
  compose.onNodeWithText("Approve once").performClick()
  waitJob()
  assertEquals("SUCCESS",runApprovedJob().state)
  assertTrue(server.published)
  assertEquals("PUBLISHED",runBlocking {repo.journal.inventoryDao().items().single().shopifyState})
  assertEquals("ACTIVE",server.status)
  compose.runOnUiThread {ViewModelProvider(compose.activity)[ShopifyModel::class.java].enableOwnerWrites(false)}
  compose.onNode(hasScrollToIndexAction()).performScrollToNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.RadioButton))
  compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.RadioButton)).performClick()
  compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Refresh the Shopify connection before selecting update fields."))
  compose.onNodeWithText("Refresh the Shopify connection before selecting update fields.").assertExists()
 }
}
