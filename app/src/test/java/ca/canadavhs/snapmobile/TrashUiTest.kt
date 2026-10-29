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
abstract class TrashUiFixture {
    protected lateinit var journal:CaptureJournal
    protected lateinit var repo:CaptureRepository
    protected lateinit var physicalId:String
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
    protected val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rule:RuleChain=RuleChain.outerRule(seed).around(compose)
}

class TrashUiTest:TrashUiFixture() {
    @Test fun batchDeleteDisappearsAndSettingsTrashRecoversWithoutReopeningCapture() {
        compose.onNodeWithText("Batches").performClick();compose.onNodeWithText("Delete batch · move to Trash").performScrollTo().performClick();compose.waitForIdle()
        compose.waitUntil(10000) {runBlocking {repo.dao.active()==null}}
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Trash · recover deleted records").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Recover").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Recover").performClick();compose.waitForIdle()
        compose.waitUntil(10000) {runBlocking {repo.journal.trashDao().target("BATCH",repo.dao.group(physicalId).sessionId)==null}}
        assertEquals(physicalId,runBlocking {repo.journal.inventoryDao().items().single().groupId});assertNull(runBlocking {repo.dao.active()})
    }
}

class TrashPhotoUiTest:TrashUiFixture() {
    @Test fun finishedPhotoHasDirectDeleteAndTrashRecovery() {
        runBlocking {val group=repo.dao.group(physicalId);repo.dao.update(repo.dao.session(group.sessionId).copy(finished=true))}
        compose.onNodeWithText("Batches").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Delete photo"))
        compose.onNodeWithText("Delete photo").performClick()
        compose.waitUntil(10000) {runBlocking {repo.dao.photos(physicalId).single().deleted}}
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Trash · recover deleted records").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Recover").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("Recover").performClick()
        compose.waitUntil(10000) {runBlocking {!repo.dao.photos(physicalId).single().deleted}}
        assertNull(runBlocking {repo.dao.active()})
    }

}

class TrashDraftUiTest:TrashUiFixture() {
    @Test fun localDraftDeleteButtonRemovesReceiptWithoutPairingOrRemoteMutation() {
        val pair=runBlocking {
            val item=journal.inventoryDao().item(physicalId)!!
            val job=ShopifyJobRow(kind="CREATE_DRAFT",shopId="fixture",shop="fixture.myshopify.com",broker="https://broker.example.invalid",connectionFingerprint="fixture",locationId="fixture",state="SUCCESS");journal.shopifyDao().insert(job)
            val entry=ShopifyItemJobRow(jobId=job.id,groupId=item.groupId,itemId=item.itemId,expectedRevision=item.revision,inputFingerprint="fixture",snapshotJson=inventoryJson(item).toString(),creationKey=ca.canadavhs.snapmobile.core.newId(),state="SUCCESS",remoteJson="{\"productId\":\"gid://shopify/Product/7\"}");journal.shopifyDao().insert(entry);job to entry
        }
        compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Shopify").performScrollTo().performClick()
        val label="Delete local draft · ${pair.second.itemId}"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label));compose.onNodeWithText(label).performClick()
        compose.waitUntil(10000) {runBlocking {journal.trashDao().target("DRAFT_ITEM",pair.second.id)!=null}}
        compose.waitUntil(10000) {compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()}
        assertEquals(pair.second.remoteJson,runBlocking {journal.shopifyDao().item(pair.second.id)!!.remoteJson})
        compose.onNodeWithText("Delete local job").performScrollTo().performClick()
        compose.waitUntil(10000) {runBlocking {journal.trashDao().target("DRAFT",pair.first.id)!=null}}
        assertFalse(repo.preferences.getBoolean("shopifyPaired",false))
    }

}
