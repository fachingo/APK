package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.*
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
class AgentTasksUiTest {
    private lateinit var journal:CaptureJournal;private lateinit var repo:CaptureRepository;private lateinit var item:InventoryRow
    private val seed=object:ExternalResource() {
        override fun before() {val app=RuntimeEnvironment.getApplication() as CaptureApplication;WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());journal=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();repo=CaptureRepository(app,journal) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit()
            runBlocking {repo.start("Task UI fixture","A1");val p=repo.reserve();val b=Bitmap.createBitmap(50,70,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id);val old=journal.inventoryDao().item(p.groupId)!!;item=repo.saveInventory(old.copy(title="Alien",priceCents=777),old.revision)
                val f=File(app.cacheDir,"fixture.csv").apply {writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n1,Alien,EBAY_US,1,2020-01-01,10,2,CAD,https://www.ebay.com/itm/1\n")};repo.commitMarket(repo.previewMarket(f,"fixture.csv"))
                val chat=ChatConversationRow(title="Approval UI fixture",provider="CHATGPT",model="fixture",authMode="CHATGPT_PLAN",toolsEnabled=true,proposalsEnabled=true);journal.nextDao().insert(chat);AgentTools(repo).execute(chat,"propose_market_price",JSONObject().put("group_id",item.groupId).put("currency","CAD").put("method","MEDIAN"))
            }
        }
        override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();journal.close()}
    }
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
    @Test fun approvalFailureStaysOpenAndApprovedOutcomeAppearsInTasksWithInventoryNavigation() {
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("AI workspace").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Approval UI fixture").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Approval UI fixture").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Review proposed change",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Review proposed change",useUnmergedTree=true).performScrollTo().performClick();compose.waitUntil(5000) {compose.onAllNodesWithText("Apply reviewed prices",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Apply reviewed prices").assertExists();compose.onNodeWithText("Approve once").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Explicitly approve replacing the manual price.",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Apply reviewed prices").assertExists();assertEquals(777L,runBlocking {journal.inventoryDao().item(item.groupId)!!.priceCents})
        val activity=compose.activity
        compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w900dp-h420dp-land");activity.onConfigurationChanged(activity.resources.configuration)}
        compose.onNodeWithText("Apply reviewed prices").assertIsDisplayed();compose.onNodeWithText("Approve once").assertIsDisplayed()
        val landscape=File(System.getProperty("snapims.testOutput"),"workflow-approval-landscape.png").apply {parentFile!!.mkdirs()};landscape.outputStream().use {compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.runOnUiThread {RuntimeEnvironment.setQualifiers("w420dp-h900dp-port");activity.onConfigurationChanged(activity.resources.configuration)}
        compose.onNodeWithText("Allow replacing manual prices").performScrollTo();compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("Allow reuse of evidence older than my configured limit").performScrollTo();compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("Alien",useUnmergedTree=true).performScrollTo()
        val approvalImage=File(System.getProperty("snapims.testOutput"),"workflow-price-approval.png").apply {parentFile!!.mkdirs()};approvalImage.outputStream().use {compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Approve once").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Approve once").fetchSemanticsNodes().isEmpty()};compose.waitUntil(10000) {runBlocking {journal.inventoryDao().item(item.groupId)!!.priceCents==1200L}};assertEquals(1200L,runBlocking {journal.inventoryDao().item(item.groupId)!!.priceCents})
        compose.onNodeWithText("← Chats").performClick();compose.onNodeWithText("Tasks").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Price 1 copy").fetchSemanticsNodes().isNotEmpty()};compose.waitUntil(10000) {compose.onAllNodesWithText("COMPLETE · 1/1",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("COMPLETE · 1/1",useUnmergedTree=true).assertExists()
        val tasksImage=File(System.getProperty("snapims.testOutput"),"workflow-tasks.png").apply {parentFile!!.mkdirs()};tasksImage.outputStream().use {compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("New inventory review task").performClick();compose.onNodeWithText("Create review task").assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[0].performClick();compose.onNodeWithText("Create review task").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Review 1 physical copies").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Review Alien · ${item.itemId}")[0].performScrollTo().performClick();compose.onNodeWithText("Tape details").assertExists()
    }
}
