package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h1000dp-port")
class ProductionBatchUiTest {
 private lateinit var repo:CaptureRepository;private lateinit var items:List<InventoryRow>;private lateinit var server:NativeShopifyFakeApi
 private val seed=object:ExternalResource() {
  override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).putBoolean("shopifyOwnerWrites",true).commit();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();server=NativeShopifyFakeApi().apply {publicationNames["gid://shopify/Publication/2"]="Point of Sale"};val gateway=DirectShopifyGateway(app,SecureCredentialStore(app,"production-ui-${newId()}") {key},OkHttpClient.Builder().addInterceptor(server).build(),ShopifyUploadVault(app) {key});gateway.save("fixture.myshopify.com",newId(),"fixture-secret");repo.directShopifyFactory={gateway}
   runBlocking {val session=repo.start("Production batch fixture","A1");val first=repo.current();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);val bytes=java.io.ByteArrayOutputStream().use {b.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};b.recycle();items=(0 until 50).map {n->val g=if(n==0) first else GroupRow(sessionId=session.id,number=n+1,closed=true,location="A1").also {repo.dao.insert(it)};val item=repo.ensureInventoryItem(g);val photo=PhotoRow(groupId=g.id,position=0,role="FRONT",state="READY",scanState="OFF",bytes=bytes.size.toLong(),sha256=sha256(bytes));repo.original(photo.id).writeBytes(bytes);repo.dao.insert(photo);repo.saveInventory(item.copy(title="Selected tape ${n+1}",description="Actual inventory facts",condition="Good",priceCents=1200),item.revision)}}
  }
  override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();repo.journal.close()}
 }
 private val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
 private fun show(text:String) {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))}
 @Test fun sevenCheckboxesPersistAndDraftActionQueuesExactlySelectionWithoutDialog() {
  compose.onNodeWithText("Batches").performClick();compose.waitUntil(10000) {compose.onAllNodesWithTag("production-batch").fetchSemanticsNodes().isNotEmpty()}
  items.take(7).forEach {compose.onNodeWithTag("production-batch").performScrollToNode(hasTestTag("select-${it.groupId}"));compose.onNodeWithTag("select-${it.groupId}").performClick()}
  show("7 selected of 50");compose.onNodeWithText("7 selected of 50").assertExists()
  show("← Photographs & capture history");compose.onNodeWithText("← Photographs & capture history").performClick();show("Open production workflow");compose.onNodeWithText("Open production workflow").performClick();show("7 selected of 50");compose.onNodeWithText("7 selected of 50").assertExists()
  show("Refresh saved Shopify connection");compose.onNodeWithText("Refresh saved Shopify connection").performClick();compose.waitUntil(10000) {runCatching {show("Create Shopify Drafts (7)");compose.onNodeWithText("Create Shopify Drafts (7)").assertIsEnabled()}.isSuccess}
  show("Point of Sale");compose.onNodeWithText("Point of Sale").assertIsNotSelected().performClick()
  show("Refresh saved Shopify connection");compose.onNodeWithText("Refresh saved Shopify connection").performClick();compose.waitUntil(10000) {runCatching {show("Create Shopify Drafts (7)");compose.onNodeWithText("Create Shopify Drafts (7)").assertIsEnabled()}.isSuccess}
  show("Point of Sale");compose.onNodeWithText("Point of Sale").assertIsSelected();show("Create Shopify Drafts (7)")
  compose.onNodeWithText("Create Shopify Drafts (7)").performScrollTo().assertIsDisplayed().performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Create Shopify Drafts (7)").fetchSemanticsNodes();runBlocking {repo.journal.shopifyDao().active().size==1}}
  val job=runBlocking {repo.journal.shopifyDao().active().single()};assertEquals(items.take(7).map {it.groupId}.toSet(),runBlocking {repo.journal.shopifyDao().items(job.id).map {it.groupId}.toSet()});assertEquals(0,server.mutations);assertTrue(items.take(7).all {repo.preferences.getStringSet("draftChannels.${it.groupId}",emptySet())!!.contains("gid://shopify/Publication/2")});assertTrue(runBlocking {repo.journal.shopifyDao().watchReviews().first().isEmpty()});compose.onAllNodes(isDialog()).assertCountEquals(0)
  show("7 selected of 50");File(System.getProperty("snapims.testOutput"),"production-batch-selection.png").apply {parentFile!!.mkdirs()}.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
 }
}
