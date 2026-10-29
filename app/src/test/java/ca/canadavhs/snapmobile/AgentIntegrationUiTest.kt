package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.work.*
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
abstract class AgentUiFixture {
    protected lateinit var journal:CaptureJournal;protected lateinit var repo:CaptureRepository;protected lateinit var copy:InventoryRow;protected lateinit var chat:ChatConversationRow;protected lateinit var approval:AgentApprovalRow
    private val seed=object:ExternalResource() {
        override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());journal=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();repo=CaptureRepository(app,journal) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit()
            runBlocking {repo.start("Agent UI batch","A1");repeat(2) {val p=repo.reserve();val b=Bitmap.createBitmap(50,70,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id)};val old=journal.inventoryDao().item(repo.current().id)!!;copy=repo.saveInventory(old.copy(title="Wrong title",description="Existing description",condition="Good"),old.revision)
                val f=File(app.cacheDir,"fixture.csv").apply {writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n1,Alien,EBAY_US,1,2026-10-01,10,2,CAD,https://www.ebay.com/itm/1\n")};repo.commitMarket(repo.previewMarket(f,"fixture.csv"));chat=ChatConversationRow(title="Full application fixture",provider="LOCAL",model="fixture",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);journal.nextDao().insert(chat);journal.nextDao().save(AiCapabilityRow(newId(),"LOCAL","fixture","Fixture",supportsTools=true))
                val plan=JSONObject().put("group_id",copy.groupId).put("patch_json","{\"title\":\"Alien\",\"location\":\"J10\"}").put("market_currency","CAD").put("price_method","MEDIAN").put("recognize",false).put("prepare_listing",true).put("mark_ready",true)
                val result=AgentTools(repo).execute(chat,"app_propose",JSONObject().put("action","propose_inventory_workflow").put("arguments_json",JSONObject().put("items_json",JSONArray().put(plan).toString()).toString()));approval=journal.nextDao().approval(result.getString("approval_id"))!!
            }
        }
        override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();journal.close()}
    }
    protected val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
    protected fun open() {compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("AI workspace").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Full application fixture").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Full application fixture").performClick()}
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h900dp-port")
class AgentIntegrationUiTest:AgentUiFixture() {
    @Test fun mixedReviewShowsExactBeforeAfterFinancialConsentAndSurvivesLandscapeWithoutAutoModelRequests() {
        open();var modelRequests=0;compose.runOnUiThread {ViewModelProvider(compose.activity)[AiWorkspaceModel::class.java].modelRequest={_,_,_,_->modelRequests++;error("Must remain idle")}}
        compose.onNodeWithText("Full Agent").assertExists();compose.waitUntil(10000) {compose.onAllNodesWithText("Review proposed change").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Review proposed change").performScrollTo().performClick();compose.onNodeWithText("Review inventory workflow").assertExists();compose.onNodeWithText("Before: Wrong title").performScrollTo().assertExists();compose.onNodeWithText("After: Alien").assertExists();compose.onNodeWithText("Approve once").assertIsNotEnabled()
        compose.onNodeWithText("I explicitly approve the reviewed price changes").performScrollTo().performClick();compose.onNodeWithText("Approve once").assertIsEnabled()
        compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w900dp-h420dp-land");compose.activity.onConfigurationChanged(compose.activity.resources.configuration)};compose.onNodeWithText("Approve once").assertIsDisplayed()
        val png=File(System.getProperty("snapims.testOutput"),"agent-mixed-review-landscape.png").apply {parentFile!!.mkdirs()};png.outputStream().use {compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w420dp-h900dp-port");compose.activity.onConfigurationChanged(compose.activity.resources.configuration)};compose.onNodeWithText("Approve once").performClick();compose.waitForIdle();compose.waitUntil(10000) {runBlocking {journal.nextDao().approval(approval.id)!!.state=="APPLIED"}};val actual=runBlocking {journal.inventoryDao().item(copy.groupId)!!};assertEquals("Alien",actual.title);assertEquals("J10",actual.location);assertEquals(1200L,actual.priceCents);assertTrue(actual.ready);assertEquals(copy.itemId,actual.itemId);assertEquals(0,modelRequests)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h900dp-port")
class AgentPermissionUiTest:AgentUiFixture() {
    @Test fun modeChangeExpiresPendingProposalAndToolNavigationOpensActualInventoryRecord() {
        open();compose.onNodeWithText("Full Agent").performClick();compose.onNodeWithText("Read Access").performClick();compose.waitForIdle();compose.waitUntil(10000) {runBlocking {journal.nextDao().approval(approval.id)!!.state=="EXPIRED"}};assertEquals("Wrong title",runBlocking {journal.inventoryDao().item(copy.groupId)!!.title})
        runBlocking {val response=AgentTools(repo).execute(journal.nextDao().chat(chat.id)!!,"app_navigate",JSONObject().put("screen","inventory").put("record_id",copy.groupId));journal.nextDao().insert(ChatMessageRow(conversationId=chat.id,role="tool",body=response.toString(),toolCallId="fixture"))}
        compose.waitUntil(10000) {compose.onAllNodesWithText("Open inventory").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Open inventory").performScrollTo().performClick();compose.onNodeWithText("Tape details").assertExists();compose.onNodeWithText(copy.itemId,substring=true).assertExists()
    }
}
