package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class AgentOnDemandTest {
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private lateinit var repo:CaptureRepository;private lateinit var model:AiWorkspaceModel;private lateinit var store:ViewModelStore
    @Before fun setup() {Dispatchers.setMain(UnconfinedTestDispatcher());WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();repo=CaptureRepository(app,db) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit();model=AiWorkspaceModel(app);store=ViewModelStore().apply {put("ai",model)}}
    @After fun cleanup() {store.clear();repo.journal.close();Dispatchers.resetMain()}
    private suspend fun chat(mode:AgentPermission=AgentPermission.READ_ACCESS):ChatConversationRow {val c=ChatConversationRow(title="On-demand fixture",provider="LOCAL",model="fixture",authMode="API_KEY",toolsEnabled=mode!=AgentPermission.CHAT_ONLY,proposalsEnabled=mode==AgentPermission.FULL_AGENT);repo.journal.nextDao().save(AiCapabilityRow(newId(),c.provider,c.model,"Fixture",supportsTools=true,supportsText=true,inputUsdPerToken="0.000001",outputUsdPerToken="0.000002"));repo.journal.nextDao().insert(c);return c}
    private suspend fun idleUntil(done:suspend()->Boolean) {withTimeout(15000) {while(!done()) delay(10)}}
    private fun call(name:String,args:JSONObject=JSONObject())=JSONObject().put("type","function_call").put("call_id",newId()).put("name",name).put("arguments",args.toString())
    @Test fun initializationInventoryChangesHistoryAndPermissionChangesMakeZeroModelRequests():Unit=runBlocking {
        var requests=0;model.modelRequest={_,_,_,_->requests++;AiClient.Result("Unexpected",JSONArray(),"fixture",1,1)};val c=chat();repo.start("No monitoring","A1");val p=repo.reserve();val bitmap=Bitmap.createBitmap(60,80,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle();repo.complete(p.id)
        val old=repo.journal.inventoryDao().item(p.groupId)!!;repo.saveInventory(old.copy(title="Idle edit"),old.revision);repo.journal.nextDao().insert(ChatMessageRow(conversationId=c.id,role="user",body="Restored history"));model.setPermission(c.id,AgentPermission.FULL_AGENT);idleUntil {repo.journal.nextDao().chat(c.id)!!.proposalsEnabled};model.setBudget(c.id,true);idleUntil {repo.journal.agentPolicyDao().policy(c.id)?.contextChars==12000};delay(100);assertEquals(0,requests);assertTrue(model.active.value.isEmpty())
    }
    @Test fun explicitSendRunsBoundedLocalToolLoopWithActualContextAndPersistsUsageThenIdles():Unit=runBlocking {
        val c=chat();var requests=0;val sent=mutableListOf<List<ChatMessageRow>>();model.modelRequest={chat,history,definitions,delta->requests++;sent+=history;assertTrue(chat.instructions.contains("SnapIMS Next"));assertEquals(4,definitions.length());if(requests==1) AiClient.Result("Inspecting local counts",JSONArray().put(call("app_overview")),"first",10,3,"0.00002",5) else {val tool=history.last {it.role=="tool"};assertEquals(0,JSONObject(tool.body).getInt("inventory"));delta("No inventory copies yet.");AiClient.Result("No inventory copies yet.",JSONArray(),"second",20,6)}}
        assertEquals(0,requests);model.send(c.id,"What's going on in SnapIMS?",JSONArray());idleUntil {c.id !in model.active.value};assertEquals(2,requests);val rows=repo.journal.nextDao().messages(c.id);assertEquals(1,rows.count {it.role=="tool"});assertEquals("PROVIDER_REPORTED",JSONObject(rows.first {it.responseId=="first"}.usageJson).getString("costKind"));assertEquals("0.00002",rows.first {it.responseId=="first"}.costUsd);assertEquals("0.000032",rows.first {it.responseId=="second"}.costUsd);assertEquals("ESTIMATED",JSONObject(rows.first {it.responseId=="second"}.usageJson).getString("costKind"));delay(100);assertEquals(2,requests);assertEquals(2,sent.size)
    }
    @Test fun chatOnlyCannotExecuteHallucinatedToolsAndDoesNotInspectData():Unit=runBlocking {
        val c=chat(AgentPermission.CHAT_ONLY);var requests=0;model.modelRequest={chat,_,definitions,_->requests++;assertTrue(chat.instructions.contains("Chat Only"));assertEquals(0,definitions.length());AiClient.Result("",JSONArray().put(call("app_overview")),"fixture",1,1)};model.send(c.id,"Inspect inventory",JSONArray());idleUntil {c.id !in model.active.value};assertEquals(1,requests);assertTrue(model.error.contains("Chat Only"));assertEquals(0,repo.journal.nextDao().messages(c.id).count {it.role=="tool"});assertTrue(repo.journal.agentPolicyDao().interruptedTraces().isEmpty())
    }
    @Test fun requestsStopAtConfiguredBudgetAndNeverRetryAutomatically():Unit=runBlocking {
        val c=chat();repo.journal.agentPolicyDao().save(AgentPolicyRow(c.id,maxRounds=2,maxCalls=2));var requests=0;model.modelRequest={_,_,_,_->requests++;AiClient.Result("",JSONArray().put(call("app_overview")),"fixture-$requests",1,1)};model.send(c.id,"Inspect",JSONArray());idleUntil {c.id !in model.active.value};assertEquals(2,requests);assertTrue(model.error.contains("limit"));delay(100);assertEquals(2,requests)
    }
    @Test fun providerFailureRetainsPartialErrorWithoutRetryFallbackOrBackgroundRequests():Unit=runBlocking {
        val c=chat();var requests=0;model.modelRequest={_,_,_,delta->requests++;delta("Partial result");throw AiRequestException(429,"Quota reached. Retry manually.")};model.send(c.id,"Inspect",JSONArray());idleUntil {c.id !in model.active.value};assertEquals(1,requests);val response=repo.journal.nextDao().messages(c.id).last();assertEquals("ERROR",response.status);assertEquals("Partial result",response.body);assertEquals("LOCAL",response.provider);delay(100);assertEquals(1,requests)
    }
    @Test fun permissionDowngradeStopsStreamExpiresProposalsAndStartsFreshProviderContext():Unit=runBlocking {
        val c=chat(AgentPermission.FULL_AGENT);val pending=AgentApprovalRow(conversationId=c.id,action="propose_settings",payloadJson="{}",bindingHash="fixture");repo.journal.nextDao().insert(pending);var requests=0;val started=CompletableDeferred<Unit>();model.modelRequest={_,_,_,delta->requests++;delta("Inspected private records");started.complete(Unit);awaitCancellation()};model.send(c.id,"Inspect",JSONArray());started.await();model.setPermission(c.id,AgentPermission.CHAT_ONLY);idleUntil {repo.journal.nextDao().chat(c.id)!!.toolsEnabled==false};assertEquals("EXPIRED",repo.journal.nextDao().approval(pending.id)!!.state);assertEquals("CANCELLED",repo.journal.nextDao().messages(c.id).last().status);assertEquals(1,requests)
        model.modelRequest={chat,history,defs,_->requests++;assertFalse(chat.toolsEnabled);assertEquals(0,defs.length());assertEquals(1,history.size);assertEquals("Hello",history.single().body);AiClient.Result("Hello",JSONArray(),"new",1,1)};model.send(c.id,"Hello",JSONArray());idleUntil {c.id !in model.active.value};assertEquals(2,requests)
    }
    @Test fun inventoryPhotoDerivativeIsSmallAndStagedLocallyOnlyAfterExplicitAttachment():Unit=runBlocking {
        val c=chat();var requests=0;model.modelRequest={_,_,_,_->requests++;error("Must not send")};repo.start("Photo fixture","A1");val p=repo.reserve();val bitmap=Bitmap.createBitmap(1600,2400,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);repo.pendingFile(p.id).outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle();repo.complete(p.id);val before=hashFile(repo.original(p.id));val derivative=model.attachAppPhoto(c.id,p.id);assertTrue(derivative.getLong("bytes") in 1..500000);val opt=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(File(repo.dataRoot,"chat-attachments/${derivative.getString("path")}").path,opt);assertTrue(maxOf(opt.outWidth,opt.outHeight)<=960);assertEquals(before,hashFile(repo.original(p.id)));assertEquals(0,requests);assertTrue(repo.journal.nextDao().messages(c.id).isEmpty());model.setPermission(c.id,AgentPermission.CHAT_ONLY);idleUntil {!repo.journal.nextDao().chat(c.id)!!.toolsEnabled};try {model.attachAppPhoto(c.id,p.id);fail()} catch(_:IllegalStateException) {};assertEquals(0,requests)
    }
    @Test fun imageIsSentOncePerExplicitTurnAndNotRepeatedDuringToolLoop():Unit=runBlocking {
        val c=ChatConversationRow(title="Attachment budget",provider="OPENAI",model="fixture",authMode="API_KEY",toolsEnabled=true);repo.journal.nextDao().insert(c);repo.journal.nextDao().save(AiCapabilityRow(newId(),c.provider,c.model,"Fixture",supportsTools=true,supportsImages=true))
        var calls=0;val attachment=JSONArray().put(JSONObject().put("path","explicit-fixture.jpg").put("mime","image/jpeg"));model.modelRequest={_,history,_,_->calls++;assertEquals(if(calls==1) 1 else 0,history.sumOf {JSONArray(it.attachmentsJson).length()});if(calls==1) AiClient.Result("Inspecting",JSONArray().put(call("app_overview")),"first",1,1) else AiClient.Result("Done",JSONArray(),"second",1,1)}
        model.send(c.id,"Inspect this explicitly attached image",attachment);idleUntil {c.id !in model.active.value};assertEquals(2,calls);assertEquals(1,JSONArray(repo.journal.nextDao().messages(c.id).first().attachmentsJson).length());delay(100);assertEquals(2,calls)
    }
    @Test fun malformedToolReturnsActionableErrorAndNextRoundCanRecoverWithoutWrites():Unit=runBlocking {
        val c=chat();var calls=0;model.modelRequest={_,history,_,_->calls++;when(calls) {1->AiClient.Result("",JSONArray().put(call("app_read",JSONObject().put("resource","inventory.search").put("arguments_json","{\"sql\":\"not allowed\"}"))),"first",1,1);2->{assertTrue(JSONObject(history.last {it.role=="tool"}.body).has("error"));AiClient.Result("",JSONArray().put(call("app_overview")),"second",1,1)};else->AiClient.Result("Recovered using local overview",JSONArray(),"third",1,1)}}
        model.send(c.id,"Inspect",JSONArray());idleUntil {c.id !in model.active.value};assertEquals(3,calls);assertEquals(2,repo.journal.nextDao().messages(c.id).count {it.role=="tool"});assertEquals("Recovered using local overview",repo.journal.nextDao().messages(c.id).last().body);assertTrue(repo.journal.agentTaskDao().pending().isEmpty())
    }
    @Test fun oversizedUserInputIsBlockedBeforeAnyProviderCallAndDoesNotCrashUi():Unit=runBlocking {
        val c=chat();var requests=0;model.modelRequest={_,_,_,_->requests++;error("Must not send")};model.send(c.id,"X".repeat(100001),JSONArray());assertEquals(0,requests);assertTrue(model.error.contains("shorter"));assertTrue(model.active.value.isEmpty())
    }
}
