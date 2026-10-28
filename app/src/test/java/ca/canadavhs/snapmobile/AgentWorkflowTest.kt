package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class AgentWorkflowTest {
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private val databases=mutableListOf<CaptureJournal>();private lateinit var repo:CaptureRepository
    private fun create():CaptureRepository {val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();databases+=db;return CaptureRepository(app,db,File(app.filesDir,"agent-${newId()}").apply {mkdirs()}) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}}
    @Before fun setup() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=create();app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putString("recognitionMode","CLASSICAL").commit();app.getSharedPreferences("openai-key-encrypted",0).edit().clear().commit()}
    @After fun cleanup() {databases.forEach {it.close()}}
    private suspend fun item():InventoryRow {if(repo.dao.active()==null) repo.start("Task fixture","A1") else repo.next();val p=repo.reserve();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id);val old=repo.journal.inventoryDao().item(p.groupId)!!;return repo.saveInventory(old.copy(title="Alien",priceCents=777),old.revision)}
    private suspend fun chat(proposals:Boolean=true):ChatConversationRow=ChatConversationRow(title="Fixture",provider="CHATGPT",model="fixture-model",authMode="CHATGPT_PLAN",toolsEnabled=true,proposalsEnabled=proposals).also {repo.journal.nextDao().insert(it)}
    private suspend fun propose(c:ChatConversationRow,name:String,groups:List<String>,currency:String="CAD"):AgentApprovalRow {val args=JSONObject().put("group_ids",JSONArray(groups));if(name.contains("market_price")) args.put("currency",currency).put("method","MEDIAN");val id=AgentTools(repo).execute(c,name,args).getString("approval_id");return repo.journal.nextDao().approval(id)!!}
    private suspend fun sold(id:String="1",currency:String="CAD",price:String="10",date:String="2020-01-01",title:String="Alien") {val f=File(app.cacheDir,"${newId()}.csv").apply {writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n$id,$title,EBAY_US,$id,$date,$price,2,$currency,https://www.ebay.com/itm/$id\n")};val p=repo.previewMarket(f,"fixture.csv");repo.commitMarket(p);f.delete()}
    private fun count(table:String):Long=repo.journal.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {it.moveToFirst();it.getLong(0)}
    private suspend fun failure(block:suspend()->Unit) {try {block();fail("Expected rejection")} catch(e:IllegalArgumentException) {} catch(e:IllegalStateException) {}}
    private suspend fun task(approval:AgentApprovalRow)=repo.journal.agentTaskDao().get(JSONObject(repo.journal.nextDao().approval(approval.id)!!.resultJson).getString("taskId"))!!

    @Test fun offlineRecognitionRequiresOneUseApprovalAndReusesQueuedWork():Unit=runBlocking {
        val item=item();val c=chat();val p=propose(c,"propose_recognition_batch",listOf(item.groupId));assertEquals(0,count("recognition"))
        AgentTools(repo).decide(p.id,true);val t=task(p);val row=repo.dao.recognitions(item.groupId).single();assertEquals("CLASSICAL",row.provider);assertEquals("SCHEDULED",t.state);assertEquals("RUNNING",repo.agentTaskStatus(t).state)
        val workId=repo.dao.recognition(row.id)!!.workId;assertTrue(workId.isNotBlank())
        coroutineScope {repeat(3) {launch {repo.scheduleRecognition(row)}}};assertEquals(workId,repo.dao.recognition(row.id)!!.workId)
        val again=propose(c,"propose_recognition_batch",listOf(item.groupId));AgentTools(repo).decide(again.id,true);assertEquals(1,count("recognition"));failure {AgentTools(repo).decide(p.id,true)}
        val result=repo.dao.recognition(row.id)!!;repo.dao.update(result.copy(state="SUCCEEDED",resultJson="{}"));assertEquals("NEEDS_TITLE_REVIEW",repo.agentTaskStatus(t).state);assertEquals("Alien",repo.journal.inventoryDao().item(item.groupId)!!.title)
        repo.confirmRecognitionTitle(row.id,"Alien 1979");assertEquals("COMPLETE",repo.agentTaskStatus(t).state);assertEquals("Alien",repo.journal.inventoryDao().item(item.groupId)!!.title);repo.role(repo.dao.photos(item.groupId).first().id,Role.BACK);assertEquals("NEEDS_REVIEW",repo.agentTaskStatus(t).state)
    }
    @Test fun chatgptConversationCannotSilentlyPayForRecognitionAndCredentialRotationInvalidatesConsent():Unit=runBlocking {
        val item=item();repo.preferences.edit().putString("recognitionMode","OPENAI").commit();val slot=app.getSharedPreferences("openai-key-encrypted",0);slot.edit().putString("ciphertext","synthetic-unusable-slot").putString("iv","fixture").commit()
        val p=propose(chat(),"propose_recognition_batch",listOf(item.groupId));assertEquals("SEPARATE_OPENAI_API_KEY",JSONObject(p.payloadJson).getString("billing"))
        failure {AgentTools(repo).decide(p.id,true)};assertEquals("PENDING",repo.journal.nextDao().approval(p.id)!!.state);assertEquals(0,count("recognition"));assertEquals(0,count("agent_tasks"))
        slot.edit().putString("ciphertext","rotated-unusable-slot").commit();failure {AgentTools(repo).decide(p.id,true,AgentDecisionOptions(allowPaidRecognition=true))};assertEquals(0,count("recognition"))
        val fresh=propose(chat(),"propose_recognition_batch",listOf(item.groupId));AgentTools(repo).decide(fresh.id,true,AgentDecisionOptions(allowPaidRecognition=true));val row=repo.dao.recognitions(item.groupId).single();assertEquals(SecureApiKeyStore(app).bindingId(),row.authBinding);assertEquals(fresh.id,row.approvalId)
        slot.edit().clear().commit();val worker=TestListenableWorkerBuilder<RecognitionWorker>(app).setInputData(workDataOf("id" to row.id)).build();worker.doWork();assertEquals("ERROR",repo.dao.recognition(row.id)!!.state);assertTrue(repo.dao.recognition(row.id)!!.error.contains("credentials changed"));assertEquals("CHATGPT_PLAN",repo.journal.nextDao().chat(fresh.conversationId)!!.authMode)
    }
    @Test fun encryptedSlotBindingRejectsRemovedAndResavedKeysWithoutPlaintextHashes() {
        val key=SecretKeySpec(ByteArray(32) {9},"AES");val store=SecureApiKeyStore(app,{key},"binding-test","fixture");store.save("synthetic-api-key-1234");val first=store.bindingId();assertEquals("synthetic-api-key-1234",store.readAuthorized(first));store.save("synthetic-api-key-1234");assertNotEquals(first,store.bindingId());try {store.readAuthorized(first);fail()} catch(_:IllegalStateException) {};store.remove();assertEquals("MISSING",store.bindingId())
    }
    @Test fun batchPricesAreAtomicRequireOperatorFxAndKeepSoldFactsSeparate():Unit=runBlocking {
        val a=item();val b=item();sold(currency="USD");val p=propose(chat(),"propose_market_price_batch",listOf(a.groupId,b.groupId),"USD")
        failure {AgentTools(repo).decide(p.id,true,AgentDecisionOptions(replaceManual=true,allowHistorical=true))};assertEquals(777L,repo.journal.inventoryDao().item(a.groupId)!!.priceCents)
        failure {AgentTools(repo).decide(p.id,true,AgentDecisionOptions(replaceManual=true,allowHistorical=true,rate="1.25",rateDate="2026-10-28",adjustedCents=999))}
        AgentTools(repo).decide(p.id,true,AgentDecisionOptions(replaceManual=true,allowHistorical=true,rate="1.25",rateDate="2026-10-28"));assertEquals(1500L,repo.journal.inventoryDao().item(a.groupId)!!.priceCents);assertEquals(1500L,repo.journal.inventoryDao().item(b.groupId)!!.priceCents);assertEquals(1,count("market_sales"));assertEquals(2,repo.agentTaskStatus(task(p)).done)
        val decision=repo.journal.marketDao().decisionsForItem(a.groupId).first {it.recordType=="OPERATOR_APPROVED_PRICE"};assertEquals("1.25",JSONObject(decision.provenanceJson).getString("conversionRate"));assertFalse(repo.journal.inventoryDao().item(a.groupId)!!.ready)
    }
    @Test fun changedSecondEvidenceRollsBackFirstPriceAndProposalRemainsReviewable():Unit=runBlocking {
        val a=item();val old=item();val b=repo.saveInventory(old.copy(title="Aliens"),old.revision);sold();sold("2",title="Aliens");val p=propose(chat(),"propose_market_price_batch",listOf(a.groupId,b.groupId));sold("3",price="30",title="Aliens")
        failure {AgentTools(repo).decide(p.id,true,AgentDecisionOptions(replaceManual=true,allowHistorical=true))};assertEquals(777L,repo.journal.inventoryDao().item(a.groupId)!!.priceCents);assertEquals(777L,repo.journal.inventoryDao().item(b.groupId)!!.priceCents);assertEquals(0,count("agent_tasks"));assertEquals("PENDING",repo.journal.nextDao().approval(p.id)!!.state)
    }
    @Test fun batchRevisionAndPhotoChangesExpireWholeSelection():Unit=runBlocking {
        val a=item();val b=item();val p=propose(chat(),"propose_recognition_batch",listOf(a.groupId,b.groupId));repo.saveInventory(b.copy(location="J10"),b.revision);AgentTools(repo).decide(p.id,true);assertEquals("EXPIRED",repo.journal.nextDao().approval(p.id)!!.state);assertEquals(0,count("recognition"))
        val again=propose(chat(),"propose_recognition_batch",listOf(a.groupId));val photo=repo.dao.photos(a.groupId).first();repo.role(photo.id,Role.BACK);AgentTools(repo).decide(again.id,true);assertEquals("EXPIRED",repo.journal.nextDao().approval(again.id)!!.state)
    }
    @Test fun reviewTasksWorkOfflineSurviveChatDeletionAndCannotCompleteUnreadyInventory():Unit=runBlocking {
        val a=item();val id=repo.createLocalReviewTask(listOf(a.groupId));failure {repo.completeReviewTask(id)};assertEquals("OPEN",repo.journal.agentTaskDao().get(id)!!.state)
        val c=chat();val p=propose(c,"propose_batch_review",listOf(a.groupId));AgentTools(repo).decide(p.id,true);val t=task(p);repo.journal.nextDao().deleteChat(c.id);assertNotNull(repo.journal.agentTaskDao().get(t.id));assertEquals(0,count("recognition"));assertEquals(0,count("shopify_jobs"))
        repo.cancelAgentTask(id);assertEquals("CANCELLED",repo.journal.agentTaskDao().get(id)!!.state);assertEquals("Alien",repo.journal.inventoryDao().item(a.groupId)!!.title)
    }
    @Test fun cancellationRetainsPartialResultsAndRecoveryReusesAlreadyScheduledJobs():Unit=runBlocking {
        val a=item();val b=item();val p=propose(chat(),"propose_recognition_batch",listOf(a.groupId,b.groupId));AgentTools(repo).decide(p.id,true);val t=task(p);val ar=repo.dao.recognitions(a.groupId).single();repo.dao.update(ar.copy(state="SUCCEEDED",resultJson="{}"));val br=repo.dao.recognitions(b.groupId).single();val work=br.workId
        repo.journal.agentTaskDao().update(t.copy(state="SCHEDULE_PENDING"));repo.recoverAgentTasks();assertEquals(work,repo.dao.recognition(br.id)!!.workId);assertEquals(2,count("recognition"))
        repo.cancelAgentTask(t.id);val status=repo.agentTaskStatus(repo.journal.agentTaskDao().get(t.id)!!);assertEquals("CANCELLED",status.state);assertEquals(1,status.done);assertEquals("SUCCEEDED",repo.dao.recognition(ar.id)!!.state);assertEquals("CANCELLED",repo.dao.recognition(br.id)!!.state)
    }
    @Test fun restoredTasksPauseAndLegacySixSnapshotGetsNewColumnsWithoutRestartingAnything():Unit=runBlocking {
        val a=item();val p=propose(chat(),"propose_recognition_batch",listOf(a.groupId));AgentTools(repo).decide(p.id,true);val t=task(p);val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());val stage=File(app.cacheDir,newId()).apply {mkdirs()};val files=snapshot.getJSONArray("files")
        for(i in 0 until files.length()) {val path=files.getJSONObject(i).getString("path");File(stage,path).apply {parentFile!!.mkdirs();repo.snapshotFile(path).copyTo(this)}}
        val destination=create();destination.mergeSnapshot(snapshot,stage);val restored=destination.journal.agentTaskDao().get(t.id)!!;assertEquals("PAUSED_REVIEW",restored.state);assertEquals("ERROR",destination.dao.recognitions(a.groupId).single().state);destination.recoverAgentTasks();assertEquals("PAUSED_REVIEW",destination.journal.agentTaskDao().get(t.id)!!.state)
        val legacy=JSONObject(snapshot.toString()).put("schema",6);legacy.getJSONObject("tables").remove("agent_tasks");val rec=legacy.getJSONObject("tables").getJSONArray("recognition");for(i in 0 until rec.length()) {rec.getJSONObject(i).remove("authBinding");rec.getJSONObject(i).remove("approvalId")};destination.validateSnapshot(legacy);assertEquals(7,legacy.getInt("schema"));assertEquals("",rec.getJSONObject(0).getString("authBinding"))
    }
    @Test fun sixToSevenMigrationRetainsCapturesAndStopsUnboundApiQueue():Unit=runBlocking {
        val a=item();val photos=repo.dao.photos(a.groupId);val queued=RecognitionRow(groupId=a.groupId,provider="OPENAI",model=DEFAULT_OPENAI_MODEL,inputJson=photoSnapshot(photos),inputFingerprint=photoFingerprint(photos));repo.dao.insert(queued)
        val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());val schema=JSONObject(File(System.getProperty("snapims.schemaDir"),"ca.canadavhs.snapmobile.CaptureJournal/6.json").readText()).getJSONObject("database");val name="upgrade-six-${newId()}.db"
        val helper=androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app).name(name).callback(object:androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase) {val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()) {val e=entities.getJSONObject(i);val table=e.getString("tableName");db.execSQL(e.getString("createSql").replace("${'$'}{TABLE_NAME}",table));val indices=e.optJSONArray("indices") ?: JSONArray();for(j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",table))};val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) db.execSQL(setup.getString(i))}
            override fun onUpgrade(db:androidx.sqlite.db.SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        val db=helper.writableDatabase
        snapshotTables.take(22).forEach {table->val rows=snapshot.getJSONObject("tables").getJSONArray(table);for(i in 0 until rows.length()) {val row=JSONObject(rows.getJSONObject(i).toString());if(table=="recognition") {row.remove("authBinding");row.remove("approvalId")};val columns=row.keys().asSequence().toList();db.execSQL("INSERT INTO $table ("+columns.joinToString(",")+") VALUES ("+columns.joinToString(",") {"?"}+")",columns.map {row.get(it).takeUnless {v->v==JSONObject.NULL}}.toTypedArray())}}
        helper.close();val migrated=Room.databaseBuilder(app,CaptureJournal::class.java,name).addMigrations(MIGRATION_6_7).allowMainThreadQueries().build();databases+=migrated
        assertEquals(a.itemId,migrated.inventoryDao().item(a.groupId)!!.itemId);assertEquals(photos.first().sha256,migrated.dao().photos(a.groupId).first().sha256);assertEquals("ERROR",migrated.dao().recognition(queued.id)!!.state);assertTrue(migrated.dao().recognition(queued.id)!!.error.contains("manual retry"));assertTrue(migrated.agentTaskDao().pending().isEmpty())
    }
    @Test fun toolsRejectUnapprovedWritesUnknownRateFieldsAndDuplicateSelections():Unit=runBlocking {
        val a=item();val c=chat(false);val tools=AgentTools(repo);failure {propose(c,"propose_batch_review",listOf(a.groupId))};assertFalse(tools.definitions(false).toString().contains("propose_market_price"));failure {propose(chat(),"propose_batch_review",listOf(a.groupId,a.groupId))}
        val args=JSONObject().put("group_id",a.groupId).put("currency","CAD").put("method","MEDIAN").put("rate","1.25");failure {tools.execute(chat(),"propose_market_price",args)};assertEquals(0,count("agent_approvals"))
        val batches=tools.execute(c,"capture_batches",JSONObject().put("offset",0));assertEquals(1,batches.getJSONArray("batches").length());assertEquals(repo.dao.group(a.groupId).sessionId,batches.getJSONArray("batches").getJSONObject(0).getString("sessionId"))
        val batch=tools.execute(c,"batch_review",JSONObject().put("session_id",repo.dao.group(a.groupId).sessionId).put("offset",0));assertEquals(1,batch.getJSONArray("items").length());assertFalse(batch.getJSONArray("items").getJSONObject(0).getBoolean("ready"))
    }
}
