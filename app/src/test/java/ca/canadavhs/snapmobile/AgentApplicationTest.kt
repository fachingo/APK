package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.*
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class AgentApplicationTest {
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private val databases=mutableListOf<CaptureJournal>();private lateinit var repo:CaptureRepository;private lateinit var tools:AgentTools
    private fun create():CaptureRepository {val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();databases+=db;return CaptureRepository(app,db,File(app.filesDir,"integration-${newId()}").apply {mkdirs()}) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}}
    @Before fun setup() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=create();app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putString("recognitionMode","CLASSICAL").commit();tools=AgentTools(repo)}
    @After fun cleanup() {databases.forEach {it.close()}}
    private suspend fun chat(mode:AgentPermission=AgentPermission.FULL_AGENT)=ChatConversationRow(title="Application fixture",provider="CHATGPT",model="fixture",authMode="CHATGPT_PLAN",toolsEnabled=mode!=AgentPermission.CHAT_ONLY,proposalsEnabled=mode==AgentPermission.FULL_AGENT).also {repo.journal.nextDao().insert(it)}
    private suspend fun item(title:String="Alien"):InventoryRow {if(repo.dao.active()==null) repo.start("Integration fixture","A1") else if(repo.dao.photos(repo.current().id).any {!it.deleted}) repo.next();repeat(2) {val p=repo.reserve();val b=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id)};val old=repo.journal.inventoryDao().item(repo.current().id)!!;return repo.saveInventory(old.copy(title=title,condition="Good",description="Complete description"),old.revision)}
    private suspend fun read(c:ChatConversationRow,resource:String,args:JSONObject=JSONObject())=tools.execute(c,"app_read",JSONObject().put("resource",resource).put("arguments_json",args.toString()))
    private suspend fun propose(c:ChatConversationRow,action:String,args:JSONObject):AgentApprovalRow {val r=tools.execute(c,"app_propose",JSONObject().put("action",action).put("arguments_json",args.toString()));return repo.journal.nextDao().approval(r.getString("approval_id"))!!}
    private fun plan(item:InventoryRow,patch:JSONObject=JSONObject(),currency:String="",recognize:Boolean=false,listing:Boolean=false,ready:Boolean=false)=JSONObject().put("group_id",item.groupId).put("patch_json",patch.toString()).put("market_currency",currency).put("price_method","MEDIAN").put("recognize",recognize).put("prepare_listing",listing).put("mark_ready",ready)
    private suspend fun workflow(c:ChatConversationRow,vararg plans:JSONObject)=propose(c,"propose_inventory_workflow",JSONObject().put("items_json",JSONArray(plans.toList()).toString()))
    private suspend fun sold(title:String="Alien",price:String="10",id:String="1",currency:String="CAD") {val f=File(app.cacheDir,"${newId()}.csv").apply {writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n$id,$title,EBAY_US,$id,2026-10-01,$price,2,$currency,https://www.ebay.com/itm/$id\n")};repo.commitMarket(repo.previewMarket(f,"fixture.csv"));f.delete()}
    private fun count(table:String)=repo.journal.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {it.moveToFirst();it.getLong(0)}
    private suspend fun failure(block:suspend()->Unit) {try {block();fail("Expected controlled rejection")} catch(_:IllegalStateException) {} catch(_:IllegalArgumentException) {}}
    private suspend fun task(p:AgentApprovalRow)=repo.journal.agentTaskDao().get(JSONObject(repo.journal.nextDao().approval(p.id)!!.resultJson).getString("taskId"))!!

    @Test fun entireCompactRegistryAndEveryReadEntityUsesRealSchemaAndBoundPages():Unit=runBlocking {
        val a=item();val c=chat(AgentPermission.READ_ACCESS)
        val full=tools.applicationDefinitions(c.copy(proposalsEnabled=true));assertEquals(5,full.length());assertTrue(full.toString().length<6000);assertEquals(4,tools.applicationDefinitions(c).length());assertEquals(0,tools.applicationDefinitions(c.copy(toolsEnabled=false)).length())
        val all=tools.execute(c,"app_schema",JSONObject().put("resource","all"));assertEquals(AgentReadAccess.resources.size,all.getJSONArray("resources").length())
        AgentReadAccess.resources.forEach {resource->assertEquals(resource,tools.execute(c,"app_schema",JSONObject().put("resource",resource)).getString("resource"))}
        AgentApplicationActions.actions.forEach {action->assertEquals(action,tools.execute(c,"app_schema",JSONObject().put("resource","action:$action")).getString("action"))}
        for((name,spec) in AgentReadAccess.entities) {
            val j=read(c,"$name.list",JSONObject().put("limit",2));assertTrue(j.toString().length<=10000);assertTrue(j.getInt("returned")<=2)
            val columns=repo.journal.openHelper.writableDatabase.query("PRAGMA table_info(${spec.table})").use {cursor->buildSet {while(cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))}}
            assertTrue("$name has invalid read field",columns.containsAll(spec.fields));assertTrue(spec.fields.containsAll(spec.brief))
        }
        assertEquals(a.itemId,read(c,"inventory.get",JSONObject().put("group_id",a.groupId)).getString("itemId"))
        assertEquals(0,read(c,"database.health").getInt("foreignKeyViolations"));assertFalse(read(c,"settings.public").getBoolean("aiMonitoring"))
    }
    @Test fun chatOnlyAndReadAccessEnforceFreshPermissionsAndNeverProposeWrites():Unit=runBlocking {
        val a=item();val c=chat(AgentPermission.CHAT_ONLY);failure {tools.execute(c,"app_overview",JSONObject())};failure {read(c,"inventory.get",JSONObject().put("group_id",a.groupId))}
        val read=chat(AgentPermission.READ_ACCESS);failure {workflow(read,plan(a,JSONObject().put("title","Changed")))};failure {tools.execute(read,"propose_inventory_update",JSONObject().put("group_id",a.groupId).put("patch_json","{\"title\":\"Changed\"}"))}
        val full=chat();repo.journal.nextDao().update(full.copy(toolsEnabled=false));failure {tools.execute(full,"app_overview",JSONObject())};assertEquals(0,count("agent_approvals"));assertEquals("Alien",repo.journal.inventoryDao().item(a.groupId)!!.title)
    }
    @Test fun rejectsSqlShellUnknownResourcesFiltersImmutableFieldsAndProviderChosenFx():Unit=runBlocking {
        val c=chat();val a=item();failure {read(c,"inventory.list; DROP TABLE inventory")};failure {tools.execute(c,"shell",JSONObject().put("command","echo unsafe"))};failure {read(c,"inventory.search",JSONObject().put("sql","SELECT * FROM inventory"))}
        assertEquals(0,read(c,"inventory.search",JSONObject().put("query","%' OR 1=1 --")).getInt("returned"));failure {read(c,"record.field",JSONObject().put("entity","recognition").put("id",newId()).put("field","authBinding"))}
        for(key in listOf("groupId","itemId","batchId","revision","ready","priceCents","shopifyProductId")) failure {workflow(c,plan(a,JSONObject().put(key,1)))}
        failure {workflow(c,plan(a).put("rate","1.1"))};failure {read(c,"inventory.search",JSONObject().put("limit",51))};failure {read(c,"inventory.search",JSONObject().put("offset",-1))};assertEquals(1,count("inventory"))
    }
    @Test fun queriesLatestUnpricedCrossBatchDuplicatesRankingAndInvalidationLocally():Unit=runBlocking {
        val c=chat();val a=item();val b=item("Aliens");val priced=repo.saveInventory(b.copy(priceCents=555),b.revision);sold();sold("Aliens","30","2")
        val stats=read(c,"inventory.stats");assertEquals(2,stats.getInt("copies"));assertEquals(1,stats.getInt("unpriced"));assertEquals(555,stats.getInt("askingBaseCentsCad"))
        val rank=JSONObject().put("currency","CAD").put("statistic","HIGHEST").put("limit",20)
        assertEquals(priced.itemId,read(c,"inventory.rank_market",rank).getJSONArray("items").getJSONObject(0).getString("itemId"));assertTrue(read(c,"inventory.rank_market",rank).getBoolean("cacheHit"))
        repo.saveInventory(a.copy(title="Aliens"),a.revision);assertFalse(read(c,"inventory.rank_market",rank).optBoolean("cacheHit"))
        // Create a second genuine batch without archives/network and deterministic dates.
        repo.mutate {val first=repo.dao.active()!!;repo.dao.update(first.copy(finished=true))};repo.start("Newest fixture","J10");val other=item("Aliens");val latest=repo.dao.group(other.groupId).sessionId
        val unpriced=read(c,"inventory.search",JSONObject().put("session_id","latest").put("filter","UNPRICED"));assertEquals(1,unpriced.getInt("total"));assertEquals(latest,unpriced.getJSONArray("items").getJSONObject(0).getString("sessionId"))
        assertEquals(3,read(c,"inventory.duplicates",JSONObject().put("by","TITLE")).getJSONArray("items").getJSONObject(0).getInt("copies"));assertEquals(1,read(c,"batch.review",JSONObject().put("session_id","latest")).getInt("returned"))
        assertEquals(other.itemId,read(c,"inventory.rank_market",JSONObject(rank.toString()).put("session_id","latest")).getJSONArray("items").getJSONObject(0).getString("itemId"))
    }
    @Test fun metadataAndPriceForCorrectedTitleApplyAtomicallyOnceWithAuditsAndVerifiedTask():Unit=runBlocking {
        val a=item("Wrong printed title");val b=item();sold();val c=chat();val p=workflow(c,plan(a,JSONObject().put("title","Alien").put("location","J10"),"CAD",listing=true,ready=true),plan(b,JSONObject().put("tags","reviewed, VHS").put("description","Verified description")))
        assertEquals("Wrong printed title",repo.journal.inventoryDao().item(a.groupId)!!.title);failure {tools.decide(p.id,true)};assertEquals(0,count("agent_tasks"))
        tools.decide(p.id,true,AgentDecisionOptions(allowFinancial=true,allowHistorical=true));val actual=repo.journal.inventoryDao().item(a.groupId)!!;assertEquals("Alien",actual.title);assertEquals("J10",actual.location);assertEquals(1200L,actual.priceCents);assertTrue(actual.ready);assertEquals(a.itemId,actual.itemId);assertEquals(a.batchId,actual.batchId)
        assertEquals("reviewed, VHS",repo.journal.inventoryDao().item(b.groupId)!!.tags);assertEquals(1,count("price_decisions"));assertEquals(1,count("listing_plans"));assertTrue(count("inventory_audit")>=6);assertEquals("COMPLETE",repo.agentTaskStatus(task(p)).state);failure {tools.decide(p.id,true,AgentDecisionOptions(allowFinancial=true))}
        assertEquals(0,count("recognition"));assertEquals(0,count("shopify_jobs"))
    }
    @Test fun changedSecondEvidenceRollsBackMixedMetadataPricesAuditsAndTask():Unit=runBlocking {
        val a=item("Wrong title");val b=item("Aliens");sold();sold("Aliens","20","2");val c=chat();val p=workflow(c,plan(a,JSONObject().put("title","Alien"),"CAD"),plan(b,JSONObject().put("description","After"),"CAD"));val audits=count("inventory_audit");sold("Aliens","40","3")
        failure {tools.decide(p.id,true,AgentDecisionOptions(allowFinancial=true,allowHistorical=true))};assertEquals("Wrong title",repo.journal.inventoryDao().item(a.groupId)!!.title);assertNull(repo.journal.inventoryDao().item(a.groupId)!!.priceCents);assertEquals("Complete description",repo.journal.inventoryDao().item(b.groupId)!!.description);assertEquals(audits,count("inventory_audit"));assertEquals(0,count("price_decisions"));assertEquals(0,count("agent_tasks"));assertEquals("PENDING",repo.journal.nextDao().approval(p.id)!!.state)
    }
    @Test fun rejectedAndStaleProposalsNeverChangeRecordsOrQueueRecognition():Unit=runBlocking {
        val a=item();val c=chat();val p=workflow(c,plan(a,JSONObject().put("title","After"),recognize=true));tools.decide(p.id,false);assertEquals("REJECTED",repo.journal.nextDao().approval(p.id)!!.state);assertEquals("Alien",repo.journal.inventoryDao().item(a.groupId)!!.title);assertEquals(0,count("recognition"));assertEquals(0,count("agent_tasks"))
        val q=workflow(c,plan(a,JSONObject().put("title","After"),recognize=true));repo.saveInventory(a.copy(location="J10"),a.revision);tools.decide(q.id,true);assertEquals("EXPIRED",repo.journal.nextDao().approval(q.id)!!.state);assertEquals(0,count("recognition"))
    }
    @Test fun mixedRecognitionRequiresExplicitPaidConsentAndReportsQueuedAsNotComplete():Unit=runBlocking {
        val a=item();val c=chat();repo.preferences.edit().putString("recognitionMode","OPENAI").commit();app.getSharedPreferences("openai-key-encrypted",0).edit().putString("ciphertext","unusable-fixture").putString("iv","fixture").commit()
        val p=workflow(c,plan(a,JSONObject().put("description","Reviewed"),recognize=true));failure {tools.decide(p.id,true)};assertEquals("Complete description",repo.journal.inventoryDao().item(a.groupId)!!.description)
        tools.decide(p.id,true,AgentDecisionOptions(allowPaidRecognition=true));val t=task(p);assertEquals("SCHEDULED",t.state);assertNotEquals("COMPLETE",repo.agentTaskStatus(t).state);assertEquals(1,count("recognition"));assertEquals("Reviewed",repo.journal.inventoryDao().item(a.groupId)!!.description);repo.cancelAgentTask(t.id);assertEquals("CANCELLED",repo.agentTaskStatus(repo.journal.agentTaskDao().get(t.id)!!).state)
    }
    @Test fun archiveRequiresDestructiveConsentRetainsIdsOriginalsAndRestore():Unit=runBlocking {
        val a=item();val photos=repo.dao.photos(a.groupId);val c=chat();val args=JSONObject().put("group_ids",JSONArray(listOf(a.groupId))).put("archived",true);val p=propose(c,"propose_archive",args);failure {tools.decide(p.id,true)};assertFalse(repo.journal.inventoryDao().item(a.groupId)!!.archived)
        tools.decide(p.id,true,AgentDecisionOptions(allowDestructive=true));assertTrue(repo.journal.inventoryDao().item(a.groupId)!!.archived);photos.forEach {assertEquals(it.sha256,hashFile(repo.original(it.id)))};val restored=propose(c,"propose_archive",args.put("archived",false));tools.decide(restored.id,true,AgentDecisionOptions(allowDestructive=true));assertFalse(repo.journal.inventoryDao().item(a.groupId)!!.archived);assertEquals(a.itemId,repo.journal.inventoryDao().item(a.groupId)!!.itemId)
    }
    @Test fun settingsUsePublicAllowlistNoCredentialsAndDurableOutbox():Unit=runBlocking {
        val c=chat();repo.preferences.edit().putString("apiKey","secret-not-readable").putString("shopifyBroker","https://sensitive.invalid").commit();app.getSharedPreferences("openai-key-encrypted",0).edit().putString("ciphertext","secret-ciphertext").commit()
        val public=read(c,"settings.public").toString();assertFalse(public.contains("secret-"));assertFalse(public.contains("sensitive.invalid"));failure {propose(c,"propose_settings",JSONObject().put("patch_json","{\"apiKey\":\"illegal\"}"))};failure {propose(c,"propose_settings",JSONObject().put("patch_json","{\"defaultWeightGrams\":4294967297}"))}
        val p=propose(c,"propose_settings",JSONObject().put("patch_json","{\"fastCapture\":false,\"photoResolution\":\"SMALL\",\"recognitionMode\":\"OFF\"}"));assertTrue(repo.preferences.getBoolean("fastCapture",true));tools.decide(p.id,true);assertFalse(repo.preferences.getBoolean("fastCapture",true));assertEquals(PhotoResolution.SMALL,PhotoResolution.read(repo.preferences));assertEquals("OFF",repo.recognitionMode());assertEquals("COMPLETE",task(p).state);repo.recoverAgentTasks();assertEquals("secret-not-readable",repo.preferences.getString("apiKey",null));assertEquals(0,count("recognition"))
    }
    @Test fun batchRenameAndTaskCreateRenameCancelPreserveIdentitiesAndNativeServices():Unit=runBlocking {
        val a=item();val c=chat();val batch=repo.dao.group(a.groupId).sessionId;val p=propose(c,"propose_batch_change",JSONObject().put("operation","RENAME").put("session_id",batch).put("name","Organized batch").put("location",""));tools.decide(p.id,true);assertEquals("Organized batch",repo.dao.session(batch).name);assertEquals(a.itemId,repo.journal.inventoryDao().item(a.groupId)!!.itemId)
        val q=propose(c,"propose_task_change",JSONObject().put("operation","CREATE").put("task_id","").put("title","Review missing fields").put("group_ids_json",JSONArray(listOf(a.groupId)).toString()));tools.decide(q.id,true);val t=task(q);assertEquals("OPEN",t.state);assertEquals("Review missing fields",t.title)
        val r=propose(c,"propose_task_change",JSONObject().put("operation","RENAME").put("task_id",t.id).put("title","Next review").put("group_ids_json","[]"));tools.decide(r.id,true);assertEquals("Next review",repo.journal.agentTaskDao().get(t.id)!!.title)
        val cancel=propose(c,"propose_task_change",JSONObject().put("operation","CANCEL").put("task_id",t.id).put("title","").put("group_ids_json","[]"));tools.decide(cancel.id,true);assertEquals("CANCELLED",repo.journal.agentTaskDao().get(t.id)!!.state)
    }
    @Test fun fieldChunksPaginationCompactionAndCredentialRedactionKeepCostsBounded():Unit=runBlocking {
        val a=item();val c=chat();val long="A".repeat(29900);repo.saveInventory(a.copy(description=long),a.revision)
        val first=read(c,"record.field",JSONObject().put("entity","inventory").put("id",a.groupId).put("field","description").put("length",4000));assertEquals(4000,first.getString("text").length);assertEquals(4000,first.getInt("nextOffset"));assertEquals(long.length,first.getInt("totalCharacters"));assertTrue(read(c,"inventory.get",JSONObject().put("group_id",a.groupId)).getBoolean("previewTruncated"))
        val huge=JSONObject().put("items",JSONArray((1..50).map {JSONObject().put("title","X".repeat(1000)).put("id",it)})).put("offset",10).put("total",60)
        val compact=compactAgentJson(huge);assertTrue(compact.toString().length<=10000);assertEquals(10+compact.getInt("returned"),compact.getInt("nextOffset"))
        val safe=safeAgentJson(JSONObject().put("access_token","protected").put("nested",JSONObject().put("authBinding","protected")).put("error","Bearer abc.def.ghi sk-syntheticlong123456789")) as JSONObject;assertFalse(safe.toString().contains("protected"));assertFalse(safe.toString().contains("abc.def"));assertFalse(safe.toString().contains("sk-synthetic"))
    }
    @Test fun yesterdayFailuresRespectDeviceTimezoneAndNeverReadProtectedBindings():Unit=runBlocking {
        val previous=TimeZone.getDefault();try {TimeZone.setDefault(TimeZone.getTimeZone("America/Edmonton"));val a=item();val c=chat();val yesterday=LocalDate.now().minusDays(1).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();val row=RecognitionRow(groupId=a.groupId,provider="CLASSICAL",model="fixture",inputJson="[]",inputFingerprint="fixture",authBinding="protected-binding",state="ERROR",error="Fixture failure",requestedAt=yesterday,completedAt=yesterday);repo.dao.insert(row)
            val j=read(c,"diagnostics.failures",JSONObject().put("period","yesterday").put("module","recognition"));assertEquals(1,j.getInt("total"));assertEquals("America/Edmonton",j.getString("timezone"));assertFalse(j.toString().contains("protected-binding"));assertFalse(read(c,"recognition.list").toString().contains("protected-binding"))
        } finally {TimeZone.setDefault(previous)}
    }
    @Test fun nativeNavigationIsValidatedWithoutMutatingScreensOrSendingPhotos():Unit=runBlocking {
        val a=item();val c=chat(AgentPermission.READ_ACCESS);val before=count("inventory_audit");for(screen in listOf("home","inventory","batches","market","recognition","shopify","tasks","settings","capture","pricing","backup")) {val j=tools.execute(c,"app_navigate",JSONObject().put("screen",screen).put("record_id",""));assertEquals(screen,j.getJSONObject("navigation").getString("screen"))}
        val photo=repo.dao.photos(a.groupId).first();val j=tools.execute(c,"app_navigate",JSONObject().put("screen","photo").put("record_id",photo.id));assertFalse(j.toString().contains("base64"));failure {tools.execute(c,"app_navigate",JSONObject().put("screen","https://evil.invalid").put("record_id",""))};failure {tools.execute(c,"app_navigate",JSONObject().put("screen","inventory").put("record_id",newId()))};failure {tools.execute(c,"app_navigate",JSONObject().put("screen","photo").put("record_id",""))};assertEquals(before,count("inventory_audit"));assertEquals(0,count("recognition"))
    }
    @Test fun restoredPolicyTraceUsageAndApprovedTasksPreserveHistoryWithoutRestart():Unit=runBlocking {
        val a=item();val c=chat();repo.journal.agentPolicyDao().save(AgentPolicyRow(c.id,contextChars=12000,maxRounds=4,maxCalls=12));val trace=AgentTraceRow(conversationId=c.id,tool="fixture",argumentsJson="{}");repo.journal.agentPolicyDao().insert(trace);repo.journal.nextDao().insert(ChatMessageRow(conversationId=c.id,role="assistant",body="Observed",usageJson="{\"costKind\":\"PROVIDER_REPORTED\"}"))
        val p=workflow(c,plan(a,JSONObject().put("description","Reviewed"),recognize=true));tools.decide(p.id,true);val t=task(p);val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());assertEquals(9,snapshot.getInt("schema"));assertEquals(26,snapshot.getJSONObject("tables").length());val stage=File(app.cacheDir,newId()).apply {mkdirs()};val files=snapshot.getJSONArray("files");for(i in 0 until files.length()) {val path=files.getJSONObject(i).getString("path");File(stage,path).apply {parentFile!!.mkdirs();repo.snapshotFile(path).copyTo(this)}}
        val target=create();target.mergeSnapshot(snapshot,stage);assertEquals(12000,target.journal.agentPolicyDao().policy(c.id)!!.contextChars);assertEquals("PAUSED_REVIEW",target.journal.agentTaskDao().get(t.id)!!.state);assertEquals("INTERRUPTED",target.journal.agentPolicyDao().watchTraces(c.id).first().first {it.id==trace.id}.state);assertEquals("PROVIDER_REPORTED",JSONObject(target.journal.nextDao().messages(c.id).first().usageJson).getString("costKind"));target.recoverAgentTasks();assertEquals("PAUSED_REVIEW",target.journal.agentTaskDao().get(t.id)!!.state)
    }
    @Test fun populatedEightToNineMigrationPreservesIdentityHistoryAndPendingReview():Unit=runBlocking {
        val a=item();val c=chat();val message=ChatMessageRow(conversationId=c.id,role="assistant",body="Previous verified context",inputTokens=123,costUsd="0.0001");repo.journal.nextDao().insert(message);val approval=workflow(c,plan(a,JSONObject().put("description","Reviewed update")))
        val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());val schema=JSONObject(File(System.getProperty("snapims.schemaDir"),"ca.canadavhs.snapmobile.CaptureJournal/8.json").readText()).getJSONObject("database");val name="upgrade-eight-${newId()}.db"
        val helper=androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app).name(name).callback(object:androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase) {val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()) {val e=entities.getJSONObject(i);val table=e.getString("tableName");db.execSQL(e.getString("createSql").replace("${'$'}{TABLE_NAME}",table));val indices=e.optJSONArray("indices") ?: JSONArray();for(j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",table))};val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) db.execSQL(setup.getString(i))}
            override fun onUpgrade(db:androidx.sqlite.db.SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build());val oldDb=helper.writableDatabase
        snapshotTables.take(24).forEach {table->val rows=snapshot.getJSONObject("tables").getJSONArray(table);for(i in 0 until rows.length()) {val row=JSONObject(rows.getJSONObject(i).toString());if(table=="chat_messages") row.remove("usageJson");val columns=row.keys().asSequence().toList();oldDb.execSQL("INSERT INTO $table ("+columns.joinToString(",")+") VALUES ("+columns.joinToString(",") {"?"}+")",columns.map {row.get(it).takeUnless {v->v==JSONObject.NULL}}.toTypedArray())}}
        helper.close();val upgraded=Room.databaseBuilder(app,CaptureJournal::class.java,name).addMigrations(MIGRATION_8_9).allowMainThreadQueries().build();databases+=upgraded
        assertEquals(a.itemId,upgraded.inventoryDao().item(a.groupId)!!.itemId);assertEquals(repo.dao.photos(a.groupId).map {it.sha256},upgraded.dao().photos(a.groupId).map {it.sha256});assertEquals("Previous verified context",upgraded.nextDao().messages(c.id).single().body);assertEquals("{}",upgraded.nextDao().messages(c.id).single().usageJson);assertEquals("0.0001",upgraded.nextDao().messages(c.id).single().costUsd);assertEquals("PENDING",upgraded.nextDao().approval(approval.id)!!.state);assertNull(upgraded.agentPolicyDao().policy(c.id));assertEquals(AgentPermission.FULL_AGENT,AgentPermission.of(upgraded.nextDao().chat(c.id)!!))
    }
    @Test fun fullInventoryFieldsCustomSelectionPhotoMetadataAndPublicDiagnosticsUseBoundedTools():Unit=runBlocking {
        val a=item();val c=chat();for(field in AgentReadAccess.entities.getValue("inventory").fields) {val value=read(c,"record.field",JSONObject().put("entity","inventory").put("id",a.groupId).put("field",field));assertEquals(field,value.getString("field"));assertTrue(value.toString().length<=10000)}
        val projected=read(c,"inventory.list",JSONObject().put("item_id",a.itemId).put("fields",JSONArray(listOf("itemId","description","title"))));assertEquals(1,projected.getInt("returned"));assertEquals(setOf("itemId","description","title"),projected.getJSONArray("items").getJSONObject(0).keys().asSequence().toSet())
        val query=JSONObject().put("condition","Good").put("location","A1").put("product_type","VHS Tape").put("has_photos",true);assertEquals(1,read(c,"inventory.search",query).getInt("total"));assertEquals(0,read(c,"inventory.search",JSONObject().put("tags","%' OR 1=1 --")).getInt("total"));assertEquals(0,read(c,"inventory.search",JSONObject().put("has_photos",false)).getInt("total"))
        val photo=repo.dao.photos(a.groupId).first();val inspected=read(c,"photos.inspect",JSONObject().put("photo_id",photo.id));assertEquals(40,inspected.getInt("width"));assertEquals(60,inspected.getInt("height"));assertTrue(inspected.getBoolean("integrityVerified"));assertFalse(inspected.getBoolean("pixelsTransmitted"))
        repo.dao.update(photo.copy(error="Local scan failed"));assertTrue(read(c,"diagnostics.failures",JSONObject().put("module","capture")).getInt("total")>=1);assertEquals(snapshotTables.size+1,read(c,"database.health").getJSONArray("tables").length())
    }

    @Test fun reviewedBatchStartFinishUsesRealArchiveWorkerAndReportsActualCompletion():Unit=runBlocking {
        val c=chat();val start=propose(c,"propose_batch_change",JSONObject().put("operation","START").put("session_id","").put("name","Reviewed batch").put("location","A1"));assertNull(repo.dao.active());tools.decide(start.id,true);val session=repo.dao.active()!!;assertEquals("Reviewed batch",session.name);assertTrue(taskGroups(task(start)).isEmpty());assertEquals("COMPLETE",repo.agentTaskStatus(task(start)).state)
        val a=item();val photos=repo.dao.photos(a.groupId);val end=propose(c,"propose_batch_change",JSONObject().put("operation","FINISH").put("session_id",session.id).put("name","").put("location",""));assertFalse(repo.dao.session(session.id).finished);tools.decide(end.id,true);assertNull(repo.dao.active());val t=task(end);val archive=repo.dao.archives(session.id).single();assertNotEquals("COMPLETE",repo.agentTaskStatus(t).state)
        TestListenableWorkerBuilder<ArchiveWorker>(app).setInputData(workDataOf("id" to archive.id)).build().doWork();val actual=repo.dao.archive(archive.id);assertEquals("READY",actual.state);assertEquals(actual.sha256,hashFile(File(repo.archiveDir,actual.filename)));assertEquals("COMPLETE",repo.agentTaskStatus(t).state);photos.forEach {assertEquals(it.sha256,hashFile(repo.original(it.id)))};repo.recoverAgentTasks();assertEquals(1,repo.dao.archives(session.id).size)
    }

}
