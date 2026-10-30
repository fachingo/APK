package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class,qualifiers="w420dp-h900dp-port")
class MarketUiTest {
    private lateinit var journal:CaptureJournal
    private lateinit var repo:CaptureRepository
    private lateinit var physicalId:String
    private val seed=object:ExternalResource() {
        override fun before() {
            val app=RuntimeEnvironment.getApplication() as CaptureApplication
            WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build())
            journal=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()
            repo=CaptureRepository(app,journal) {directory->java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit()
            runBlocking {
                repo.start("Offline UI fixture","A1")
                val photo=repo.reserve();physicalId=photo.groupId
                val image=Bitmap.createBitmap(80,120,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.BLUE)
                repo.pendingFile(photo.id).outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)};image.recycle();repo.complete(photo.id)
                val item=journal.inventoryDao().item(physicalId)!!;repo.saveInventory(item.copy(title="Existing phone tape"),item.revision)
                val market=File(app.cacheDir,"synthetic-ui-market.csv").apply {writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n1,Alien 1979 VHS,EBAY_US,1,2026-09-01,10,2,USD,https://www.ebay.com/itm/1\n2,Aliens VHS,EBAY_US,2,2026-09-02,15,2,USD,https://www.ebay.com/itm/2\n3,Alien 3 VHS,EBAY_US,3,2026-09-03,8,2,USD,https://www.ebay.com/itm/3\n")}
                val preview=repo.previewMarket(market,"synthetic-ui-market.csv");repo.commitMarket(preview);preview.file.delete();market.delete()
            }
        }
        override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();journal.close()}
    }
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
    @Test fun realOfflineMarketRecordsSearchDistributionAndHistoryRenderWithoutNetwork() {
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Market database").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("3 sold observations").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Search title, words or barcode").performTextInput("alien")
        compose.waitUntil(10000) {!ViewModelProvider(compose.activity)[MarketModel::class.java].searching && runCatching {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Alien 1979 VHS"))}.isSuccess}
        compose.onNodeWithText("Alien 1979 VHS").performScrollTo().assertExists()
        val png=File(System.getProperty("snapims.testOutput"),"market-browser.png").apply {parentFile!!.mkdirs()}
        png.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Alien 1979 VHS").performClick()
        compose.onNodeWithText("Eligible sold-price distribution · includes shipping").assertExists()
        compose.onNodeWithText("Open source listing · requires internet").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Operator price history"))
        compose.onNodeWithText("Operator price history").performClick()
        compose.onNodeWithText("Operator price history").assertIsSelected()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Your decisions are asking prices, not completed market sales."))
        compose.onNodeWithText("Your decisions are asking prices, not completed market sales.").assertExists()
        assertEquals(0,runBlocking {journal.marketDao().decisionsForItem(physicalId).size})
    }
}
