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
class ShopifyManagementUiTest {
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
    @Test fun listingInspectionSavedReviewAndStaleApprovalWorkWithoutLiveCalls() {
        val calls=mutableListOf<String>()
        compose.runOnUiThread {ViewModelProvider(compose.activity)[ShopifyModel::class.java].gatewayFactory={object:ShopifyGateway {
            override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject {calls+=operation;return when(operation) {
                "health"->JSONObject().put("shopId","gid://shopify/Shop/1").put("shop","fixture.myshopify.com").put("name","Fixture store").put("developmentStore",true).put("currency","CAD").put("apiVersion","2026-10").put("scopes",JSONArray(listOf("write_products"))).put("publications",JSONArray()).put("locations",JSONArray().put(JSONObject().put("id",location).put("name","Main location").put("isActive",true)))
                "browse"->JSONObject().put("nodes",JSONArray().put(product())).put("pageInfo",JSONObject().put("hasNextPage",false).put("endCursor",JSONObject.NULL))
                "product"->product()
                else->error("Unexpected network write $operation")
            }}
            override suspend fun upload(target:JSONObject,file:File) {error("No upload in read/review test")}
        }}}
        compose.onNodeWithText("Settings").performClick();compose.onAllNodesWithText("Shopify").onLast().performClick();compose.onNodeWithText("Connection & jobs").performClick();compose.onNodeWithText("Listings & saved reviews").performClick()
        compose.onNodeWithText("Search title or SKU").performTextInput("Remote")
        compose.onNodeWithText("Search Shopify").performClick()
        compose.waitUntil(10000) {ViewModelProvider(compose.activity)[ShopifyModel::class.java].health!=null}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Listing location & connection details"))
        compose.onNodeWithContentDescription("Expand Listing location & connection details").performClick()
        try {compose.waitUntil(10000) {runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Main location"))}.isSuccess}} catch(e:Throwable) {println(compose.onRoot().printToString(12));throw e}
        compose.onNodeWithText("Main location").performScrollTo().performClick();compose.onNodeWithText("Inspect listing").performScrollTo().performClick()
        compose.waitUntil(10000) {ViewModelProvider(compose.activity)[ShopifyModel::class.java].productDetail!=null}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Listing details"))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton)).performClick()
        compose.onNodeWithText("Prepare link review").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Link existing draft").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Approve once").assertIsDisplayed()
        val activity=compose.activity
        compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w900dp-h420dp-land");activity.onConfigurationChanged(activity.resources.configuration)}
        compose.onNodeWithText("Approve once").assertIsDisplayed()
        compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w420dp-h900dp-port");activity.onConfigurationChanged(activity.resources.configuration)}
        File(System.getProperty("snapims.testOutput"),"shopify-link-review.png").apply {parentFile!!.mkdirs()}.outputStream().use {compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        runBlocking {repo.saveInventory(item.copy(title="Local edit after review"),item.revision)}
        compose.onNodeWithText("Approve once").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes().isEmpty()}
        assertTrue(runBlocking {journal.shopifyDao().active().isEmpty()})
        compose.onNodeWithText("Listings & saved reviews").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Saved reviews"));compose.waitUntil(10000) {runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Link existing draft · EXPIRED"))}.isSuccess};compose.onNodeWithText("Link existing draft · EXPIRED").assertExists()
        compose.onNodeWithText("Prepare link review").performScrollTo().performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Approve once").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes();runBlocking {journal.shopifyDao().active().size==1}}
        assertEquals("LINK_EXISTING",runBlocking {journal.shopifyDao().active().single().kind});assertTrue(calls.all {it in listOf("health","browse","product")})
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("← Uploads & connection").performClick();compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("LINK_EXISTING · QUEUED"));compose.onNodeWithText("LINK_EXISTING · QUEUED").assertExists()
    }
    private fun product()=JSONObject().put("id",productId).put("title","Remote fixture title").put("status","DRAFT").put("descriptionHtml","<p>Remote fixture description</p>").put("vendor","Canada VHS").put("productType","VHS Tape").put("updatedAt","2026-10-28T01:00:00Z").put("reviewVersion","a".repeat(64))
        .put("identity",JSONObject().put("value",item.itemId)).put("metafield",JSONObject().put("value",operationKey("snapims-create:gid://shopify/Shop/1:${item.itemId}")))
        .put("variants",JSONObject().put("nodes",JSONArray().put(JSONObject().put("id","gid://shopify/ProductVariant/8").put("sku",item.itemId).put("price","12.99").put("inventoryItem",JSONObject().put("id","gid://shopify/InventoryItem/9")))).put("pageInfo",JSONObject().put("hasNextPage",false)))
        .put("media",JSONObject().put("nodes",JSONArray()).put("pageInfo",JSONObject().put("hasNextPage",false)))
}
