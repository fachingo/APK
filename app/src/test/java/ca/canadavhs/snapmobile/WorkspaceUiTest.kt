package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.room.Room
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
class WorkspaceUiTest {
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
            }
        }
        override fun after() {org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();journal.close()}
    }
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
    @Test fun workspaceHistoryAndConnectionsRenderWithoutSendingProviderRequests() {
        val id=runBlocking {val row=ChatConversationRow(title="Local history fixture",provider="OPENAI",model="gpt-4.1-mini",authMode="API_KEY");journal.nextDao().insert(row);journal.nextDao().insert(ChatMessageRow(conversationId=row.id,role="assistant",body="**Local history**\n\n<script>inert fixture</script>"));row.id}
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("AI workspace").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Local history fixture").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Local history fixture").performClick();compose.waitUntil(10000) {compose.onAllNodesWithText("Local history",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Local history",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("<script>inert fixture</script>",substring=true,useUnmergedTree=true).assertExists()
        val png=File(System.getProperty("snapims.testOutput"),"ai-workspace.png").apply {parentFile!!.mkdirs()}
        png.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("← Chats").performClick();compose.onNodeWithText("Connections").performClick()
        compose.onNodeWithText("Discover models").assertExists();assertEquals("Local history fixture",runBlocking {journal.nextDao().chat(id)!!.title})
    }

}
