package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h900dp-port")
class ShopifyUploadReviewUiTest {
    private lateinit var journal:CaptureJournal;private lateinit var repo:CaptureRepository;private lateinit var item:InventoryRow
    private val location="gid://shopify/Location/1";private val productId="gid://shopify/Product/7"
    private val seed=object:ExternalResource() {
        override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());journal=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();repo=CaptureRepository(app,journal) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putBoolean("shopifyPaired",true).putString("shopifyBroker","https://broker.example.invalid").commit()
            runBlocking {repo.start("Management UI fixture","A1");repeat(2) {val p=repo.reserve();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id)};val old=journal.inventoryDao().item(repo.current().id)!!;item=repo.saveInventory(old.copy(title="Phone fixture title",priceCents=1299,condition="Good",ready=true),old.revision)}
        }
        override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();journal.close()}
    }
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
    @Test fun nativeDraftUploadShowsExactSavedFieldsAndRejectsChangesAfterReview() {
        compose.runOnUiThread {ViewModelProvider(compose.activity)[ShopifyModel::class.java].gatewayFactory={object:ShopifyGateway {
            override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject {check(operation=="health");return JSONObject().put("shopId","gid://shopify/Shop/1").put("shop","fixture.myshopify.com").put("name","Fixture store").put("developmentStore",true).put("currency","CAD").put("apiVersion","2026-10").put("scopes",JSONArray(listOf("write_products"))).put("publications",JSONArray()).put("locations",JSONArray().put(JSONObject().put("id",location).put("name","Main location").put("isActive",true)))}
            override suspend fun upload(target:JSONObject,file:File) {error("Review never uploads photographs")}
        }}}
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Shopify").performScrollTo().performClick()
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.waitUntil(10000) {runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Main location"))}.isSuccess}
        compose.onNodeWithText("Main location").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(item.title));compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("Optional listing review").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Approve Shopify drafts?").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Approve once").assertIsDisplayed();compose.onNodeWithText("SKU ${item.itemId}").assertExists()
        File(System.getProperty("snapims.testOutput"),"shopify-native-draft-review.png").apply {parentFile!!.mkdirs()}.outputStream().use {compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        assertTrue(runBlocking {journal.shopifyDao().active().isEmpty()})
        runBlocking {repo.saveInventory(item.copy(title="Changed after review",ready=true),item.revision)}
        compose.onNodeWithText("Approve once").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes().isEmpty()}
        assertTrue(runBlocking {journal.shopifyDao().active().isEmpty()})
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0);compose.onNodeWithText("Listings & saved reviews").performClick()
        compose.waitUntil(10000) {runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Approve Shopify drafts? · EXPIRED"))}.isSuccess};compose.onNodeWithText("Approve Shopify drafts? · EXPIRED").assertExists()
    }
}
