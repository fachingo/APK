package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class NextIntegrationTest {
    private lateinit var repo:CaptureRepository
    private val journals=mutableListOf<CaptureJournal>()
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private fun create():CaptureRepository {val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();journals+=db;return CaptureRepository(app,db,File(app.filesDir,"next-${newId()}").apply {mkdirs()}) {directory->java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}}
    @Before fun before() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=create();app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit()}
    @After fun after() {journals.forEach {it.close()}}
    private suspend fun item():InventoryRow {repo.start("Fixture","A1");val photo=repo.reserve();val image=Bitmap.createBitmap(30,40,Bitmap.Config.ARGB_8888);repo.pendingFile(photo.id).outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)};image.recycle();repo.complete(photo.id);val old=repo.journal.inventoryDao().item(photo.groupId)!!;return repo.saveInventory(old.copy(title="Alien",priceCents=1000),old.revision)}
    private suspend fun evidence(expired:Boolean=false):PricingEvidenceRow {val e=PricingEvidenceRow(newId(),"fixture-source",newId(),1,"Alien","alien",status="Complete",searchUrl="https://example.invalid/sold",collectedAt=OffsetDateTime.now().minusDays(1).toString(),expiresAt=OffsetDateTime.now().plusDays(if(expired) -1 else 1).toString(),listingsJson="[{\"sold\":\"10\",\"shipping\":\"2\",\"title\":\"Alien VHS\",\"url\":\"https://example.invalid/fixture\",\"soldDate\":\"2026-01-01\"}]",evidenceHash="a".repeat(64));repo.journal.nextDao().insert(e);return e}
    @Test fun soldEvidenceRequiresManualOverrideAndExpiresAfterTitleChange():Unit=runBlocking {
        val item=item();val e=evidence();assertTrue(e.usable())
        try {repo.applySoldPrice(item.groupId,e.id,"MEDIAN",item.revision);fail()} catch(_:IllegalStateException) {}
        val priced=repo.applySoldPrice(item.groupId,e.id,"MEDIAN",item.revision,true);assertEquals(1200L,priced.priceCents);assertEquals("SOLD_MEDIAN",priced.priceSource);assertFalse(priced.ready)
        val changed=repo.saveInventory(priced.copy(title="Aliens"),priced.revision);assertEquals("STALE_COMPARABLES",changed.priceSource)
        assertTrue(evidence(true).usable());assertFalse(evidence(true).fresh())
    }
    @Test fun typedProposalsRequireApprovalBindRevisionAndCannotInventPrices():Unit=runBlocking {
        val item=item();val chat=ChatConversationRow(title="Fixture",provider="OPENAI",model="gpt-4.1-mini",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);repo.journal.nextDao().insert(chat);val tools=AgentTools(repo)
        val proposal=tools.execute(chat,"propose_inventory_update",JSONObject().put("group_id",item.groupId).put("patch_json","{\"title\":\"Alien 1979\"}"));val id=proposal.getString("approval_id")
        assertEquals("Alien",repo.journal.inventoryDao().item(item.groupId)!!.title)
        tools.decide(id,true);assertEquals("Alien 1979",repo.journal.inventoryDao().item(item.groupId)!!.title);assertEquals("APPLIED",repo.journal.nextDao().approval(id)!!.state)
        try {tools.decide(id,true);fail()} catch(_:IllegalStateException) {}
        val stale=tools.execute(chat,"propose_inventory_update",JSONObject().put("group_id",item.groupId).put("patch_json","{\"location\":\"J10\"}"));val old=repo.journal.inventoryDao().item(item.groupId)!!;repo.saveInventory(old.copy(quantity=2),old.revision)
        tools.decide(stale.getString("approval_id"),true);assertEquals("EXPIRED",repo.journal.nextDao().approval(stale.getString("approval_id"))!!.state)
        try {tools.execute(chat,"propose_inventory_update",JSONObject().put("group_id",item.groupId).put("patch_json","{\"priceCents\":9999}"));fail()} catch(_:IllegalArgumentException) {}
        try {tools.execute(chat,"shell",JSONObject());fail()} catch(_:IllegalStateException) {}
    }
    @Test fun snapshotIncludesChatEvidenceAndAttachmentsButInterruptsPendingOperations():Unit=runBlocking {
        val item=item();val e=evidence();val chat=ChatConversationRow(title="Fixture",provider="CHATGPT",model="fixture-model",authMode="CHATGPT_PLAN");repo.journal.nextDao().insert(chat)
        val path="${newId()}.txt";val file=File(repo.dataRoot,"chat-attachments/$path").apply {parentFile!!.mkdirs();writeText("Synthetic attachment fixture")}
        val attachments=JSONArray().put(JSONObject().put("path",path).put("name",path).put("mime","text/plain").put("bytes",file.length()).put("sha256",hashFile(file)))
        repo.journal.nextDao().insert(ChatMessageRow(conversationId=chat.id,role="user",body="Fixture",attachmentsJson=attachments.toString()))
        val pending=ChatMessageRow(conversationId=chat.id,role="assistant",body="Partial fixture",status="STREAMING");repo.journal.nextDao().insert(pending)
        val approval=AgentApprovalRow(conversationId=chat.id,action="fixture",payloadJson="{}",bindingHash="fixture");repo.journal.nextDao().insert(approval)
        val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());assertEquals(7,snapshot.getInt("schema"));assertFalse(snapshot.toString().contains("accessToken"))
        val stage=File(app.cacheDir,"stage-${newId()}").apply {mkdirs()};val files=snapshot.getJSONArray("files")
        for(i in 0 until files.length()) {val p=files.getJSONObject(i).getString("path");val target=File(stage,p);target.parentFile!!.mkdirs();repo.snapshotFile(p).copyTo(target)}
        val destination=create();destination.mergeSnapshot(snapshot,stage)
        assertEquals(item.itemId,destination.journal.inventoryDao().item(item.groupId)!!.itemId);assertEquals(e.evidenceHash,destination.journal.nextDao().evidence(e.id)!!.evidenceHash)
        assertEquals("INTERRUPTED",destination.journal.nextDao().message(pending.id)!!.status);assertEquals("EXPIRED",destination.journal.nextDao().approval(approval.id)!!.state)
        assertEquals(hashFile(file),hashFile(destination.snapshotFile("chat-attachments/$path")))
    }
    @Test fun sourceCreatedSqliteEvidenceImportsWithoutChangingItsBytesAndDeduplicates():Unit=runBlocking {
        val file=File(app.cacheDir,"pricing-fixture.sqlite");javaClass.getResourceAsStream("/desktop-pricing-fixture.sqlite")!!.use {input->file.outputStream().use {input.copyTo(it)}}
        val before=hashFile(file);assertEquals(1,repo.importPricingDatabase(file));assertEquals(before,hashFile(file));assertEquals(0,repo.importPricingDatabase(file))
        val imported=repo.journal.nextDao().evidenceForTitle("alien").single();assertEquals("synthetic-desktop-fixture",imported.collectionId);assertEquals("Complete",imported.status);assertTrue(imported.usable());assertEquals(1800L,imported.statistics()!!.medianCents)
    }
    @Test fun reviewedBulkPricingRollsBackWhenAnyItemRevisionChanges():Unit=runBlocking {
        val item=item();val e=evidence();repo.saveInventory(item.copy(quantity=2),item.revision)
        try {repo.applySoldPrices(listOf(item to e),"MEDIAN",true);fail()} catch(_:IllegalStateException) {}
        assertEquals(1000L,repo.journal.inventoryDao().item(item.groupId)!!.priceCents)
    }
    @Test fun recognitionToolAppliesOnlyAnAlreadyConfirmedTitleAfterApproval():Unit=runBlocking {
        val item=item();val chat=ChatConversationRow(title="Fixture",provider="OPENAI",model="gpt-4.1-mini",authMode="API_KEY",toolsEnabled=true,proposalsEnabled=true);repo.journal.nextDao().insert(chat)
        val result=RecognitionRow(groupId=item.groupId,provider="OFFLINE_OCR",model="synthetic fixture",inputJson="[]",inputFingerprint=photoFingerprint(repo.dao.photos(item.groupId)),state="SUCCEEDED",resultJson="{}",acceptedTitle="Alien 1979")
        repo.dao.insert(result);val tool=AgentTools(repo);val proposal=tool.execute(chat,"propose_apply_recognition",JSONObject().put("group_id",item.groupId).put("recognition_id",result.id))
        assertEquals("Alien",repo.journal.inventoryDao().item(item.groupId)!!.title);tool.decide(proposal.getString("approval_id"),true)
        assertEquals("Alien 1979",repo.journal.inventoryDao().item(item.groupId)!!.title);assertEquals(result.id,repo.journal.inventoryDao().item(item.groupId)!!.recognitionId)
    }
    @Test fun listingPreparationIsLocalStableAndNeverMarksUploaded():Unit=runBlocking {
        val item=item();val a=repo.prepareListing(item.groupId);val b=repo.prepareListing(item.groupId)
        assertEquals(a.id,b.id);assertEquals("NEEDS_REVIEW",a.state);assertEquals("DRAFT",JSONObject(a.payloadJson).getString("status"));assertEquals("NOT_UPLOADED",repo.journal.inventoryDao().item(item.groupId)!!.shopifyState)
    }
}
