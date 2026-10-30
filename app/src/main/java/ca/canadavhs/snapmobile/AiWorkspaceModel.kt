package ca.canadavhs.snapmobile

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*
import java.io.File

class AiWorkspaceModel(application:Application):AndroidViewModel(application) {
    val repo=(application as CaptureApplication).repository
    private val dao=repo.journal.nextDao()
    private val client=AiClient(application)
    internal var modelRequest:suspend(ChatConversationRow,List<ChatMessageRow>,JSONArray,suspend(String)->Unit)->AiClient.Result={chat,history,definitions,delta->(if(chat.provider=="OPENAI") AiClient(repo.context,taskOptions=repo.taskModel(if(chat.toolsEnabled) AiTask.AGENT else AiTask.CHAT).takeIf {it.model==chat.model}) else client).stream(chat,history,definitions,delta)}
    private val tools=AgentTools(repo)
    val chats=dao.watchChats().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val proofs=dao.watchProofs().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val active=MutableStateFlow<Set<String>>(emptySet())
    private val jobs=mutableMapOf<String,Job>()
    var error by mutableStateOf("");private set
    var notice by mutableStateOf("");private set
    var deciding by mutableStateOf<Set<String>>(emptySet());private set
    var catalog by mutableStateOf<List<AiCapabilityRow>>(emptyList());private set
    var loading by mutableStateOf(false);private set
    val messages:(String)->Flow<List<ChatMessageRow>> = {dao.watchMessages(it)}
    val approvals:(String)->Flow<List<AgentApprovalRow>> = {dao.watchApprovals(it)}
    val policies:(String)->Flow<AgentPolicyRow?> = {repo.journal.agentPolicyDao().watchPolicy(it)}
    val traces:(String)->Flow<List<AgentTraceRow>> = {repo.journal.agentPolicyDao().watchTraces(it)}
    init {viewModelScope.launch {repo.journal.agentPolicyDao().interruptedTraces().forEach {repo.journal.agentPolicyDao().update(it.copy(state="INTERRUPTED",summary="Application stopped; no tool or model request restarted."))};dao.interruptedMessages().forEach {dao.update(it.copy(status="INTERRUPTED",error="App stopped before completion. Retry manually; no request restarted."))}}}
    fun action(block:suspend()->Unit) {viewModelScope.launch {try {block()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Operation failed. Retry manually."}}}
    internal suspend fun configuredTask(task:AiTask):AiCapabilityRow {val choice=repo.taskModel(task);return dao.capability("OPENAI",choice.model) ?: AiCapabilityRow(stableRowId("OPENAI/${choice.model}"),"OPENAI",choice.model,choice.model).also {dao.save(it)}}
    fun clearError() {error=""}
    fun clearNotice() {notice=""}
    fun discover(providerId:String) {if(loading) return;loading=true;catalog=emptyList();action {try {
        val rows=client.models(AiProvider.get(providerId));rows.forEach {dao.save(it)};catalog=rows
        if(providerId=="CHATGPT") SiwcConnection.proof(repo,"MODELS_DISCOVERED","Discovered ${rows.size} eligible model entries using the ChatGPT connection. Stream proof is next.")
    } finally {loading=false}}}
    fun probe(model:AiCapabilityRow,kind:String) {if(loading) return;loading=true;action {try {
        require(kind in listOf("TEXT","TOOLS","IMAGE","FILE"))
        val provider=AiProvider.get(model.provider)
        check(kind in listOf("TEXT","TOOLS") || provider.responses) {"Attachment probes currently require Responses support."}
        val chat=ChatConversationRow(title="Synthetic capability test",provider=model.provider,model=model.model,authMode=if(provider.subscription) "CHATGPT_PLAN" else "API_KEY")
        val attachments=JSONArray();var file:File?=null
        try {
            val prompt=when(kind) {
                "TOOLS" ->"Call snapims_capability_probe exactly once with value PING."
                "IMAGE" ->"Read the single black digit in this image. Reply only with that digit."
                "FILE" ->"Read the attached document. Reply only with its exact probe code."
                else ->"Reply only with OK."
            }
            if(kind in listOf("IMAGE","FILE")) {
                val mime=if(kind=="IMAGE") "image/png" else "application/pdf";val extension=if(kind=="IMAGE") "png" else "pdf"
                file=File(getApplication<Application>().filesDir,"chat-attachments/${newId()}.$extension").apply {parentFile!!.mkdirs()}
                val paint=android.graphics.Paint().apply {color=android.graphics.Color.BLACK;textSize=48f}
                if(kind=="IMAGE") {val image=android.graphics.Bitmap.createBitmap(100,100,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas(image).apply {drawColor(android.graphics.Color.WHITE);drawText("7",30f,70f,paint)};file.outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
                else {val document=android.graphics.pdf.PdfDocument();val page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600,200,1).create());paint.textSize=20f;page.canvas.drawText("SNAPIMS_PROBE_9274",20f,70f,paint);document.finishPage(page);file.outputStream().use {document.writeTo(it)};document.close()}
                attachments.put(JSONObject().put("path",file.name).put("name",file.name).put("mime",mime).put("bytes",file.length()).put("sha256",hashFile(file)))
            }
            val definitions=if(kind=="TOOLS") JSONArray().put(JSONObject().put("type","function").put("name","snapims_capability_probe").put("description","Synthetic local capability test; no inventory access.").put("strict",true)
                .put("parameters",JSONObject("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"enum\":[\"PING\"]}},\"required\":[\"value\"],\"additionalProperties\":false}"))) else JSONArray()
            val response=client.stream(chat,listOf(ChatMessageRow(conversationId=chat.id,role="user",body=prompt,attachmentsJson=attachments.toString())),definitions) {}
            val passed=when(kind) {
                "TOOLS" ->response.calls.length()==1 && response.calls.getJSONObject(0).optString("name")=="snapims_capability_probe" && JSONObject(response.calls.getJSONObject(0).getString("arguments")).optString("value")=="PING"
                "IMAGE" ->response.text.trim()=="7"
                "FILE" ->response.text.trim()=="SNAPIMS_PROBE_9274"
                else ->response.text.trim().equals("OK",true)
            }
            check(passed) {"Capability probe was inconclusive. Capability stays unverified; review provider/model compatibility."}
            val verified=when(kind) {"TOOLS"->model.copy(supportsTools=true);"IMAGE"->model.copy(supportsImages=true);"FILE"->model.copy(supportsFiles=true);else->model.copy(supportsText=true)}.copy(origin="EXPLICIT_DEVICE_PROBE",checkedAt=System.currentTimeMillis())
            dao.save(verified);catalog=if(catalog.any {it.id==model.id}) catalog.map {if(it.id==model.id) verified else it} else catalog+verified;error="$kind probe completed successfully using ${provider.label}."
            if(provider.subscription) SiwcConnection.proof(repo,"STREAM_COMPLETED","Explicit $kind capability probe reached response.completed · ${response.responseId.take(100)} · ${model.model.take(100)}")
        } finally {file?.delete()}
    } finally {loading=false}}}
    suspend fun create(provider:String,model:String,instructions:String,localTools:Boolean,proposals:Boolean):String {
        require(model.isNotBlank() && instructions.length<=10000)
        val cap=dao.capability(provider,model) ?: error("Discover models and select one first.")
        check(!localTools || cap.supportsTools==true) {"This model's function-tool capability is unverified. Choose a model with explicit tool support."}
        val row=ChatConversationRow(title="New conversation",provider=provider,model=model,authMode=if(provider=="CHATGPT") "CHATGPT_PLAN" else "API_KEY",instructions=instructions,toolsEnabled=localTools,proposalsEnabled=localTools && proposals)
        dao.insert(row);return row.id
    }
    suspend fun attach(uri:Uri):JSONObject=withContext(Dispatchers.IO) {
        val context=getApplication<Application>();val resolver=context.contentResolver;val mime=resolver.getType(uri) ?: error("Unknown attachment type.")
        require(mime in listOf("image/jpeg","image/png","application/pdf","text/plain")) {"Choose JPEG, PNG, PDF or plain text."}
        val extension=when(mime) {"image/jpeg"->"jpg";"image/png"->"png";"application/pdf"->"pdf";else->"txt"}
        val name="${newId()}.$extension";val dir=File(repo.dataRoot,"chat-attachments").apply {mkdirs()};val pending=File(dir,"$name.pending");val file=File(dir,name)
        try {resolver.openInputStream(uri)?.use {input ->java.io.FileOutputStream(pending).use {out ->copyCancellable(input,out,8_000_000);out.fd.sync()}} ?: error("Cannot read attachment.")
            if(mime.startsWith("image/")) {val opt=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(pending.path,opt);require(opt.outWidth in 1..20000 && opt.outHeight in 1..20000)}
            if(mime=="application/pdf") require(pending.inputStream().use {String(it.readNBytesCompat(5),Charsets.US_ASCII)}=="%PDF-") {"Invalid PDF file."}
            atomicMove(pending,file)
            JSONObject().put("path",name).put("name",name).put("mime",mime).put("bytes",file.length()).put("sha256",hashFile(file))
        } finally {pending.delete()}
    }
    fun send(id:String,text:String,attachments:JSONArray) {
        if(id in jobs || (text.isBlank() && attachments.length()==0)) return
        if(text.length>100000 || attachments.length()>4) {error="Use a shorter message and at most four attachments.";return}
        active.value=active.value+id
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            var current:ChatMessageRow?=null
            try {
                val chat=dao.chat(id) ?: error("Chat no longer exists.")
                val cap=dao.capability(chat.provider,chat.model) ?: error("Discover models again before sending.")
                for(i in 0 until attachments.length()) {val mime=attachments.getJSONObject(i).getString("mime")
                    check(AiProvider.get(chat.provider).responses && if(mime.startsWith("image/")) cap.supportsImages==true else cap.supportsFiles==true) {"This model's attachment capability is unverified or unsupported. Use a compatible API-key model."}
                }
                val policy=repo.journal.agentPolicyDao().policy(id) ?: AgentPolicyRow(id)
                var lastMessageTime=maxOf(policy.changedAt,dao.messages(id).maxOfOrNull {it.createdAt} ?: 0)
                fun nextMessageTime():Long=maxOf(System.currentTimeMillis(),lastMessageTime+1).also {lastMessageTime=it}
                dao.insert(ChatMessageRow(conversationId=id,role="user",createdAt=nextMessageTime(),body=text,attachmentsJson=attachments.toString(),provider=chat.provider,model=chat.model))
                dao.update(chat.copy(title=if(chat.title=="New conversation") text.take(70).ifBlank {"Attachment conversation"} else chat.title,updatedAt=System.currentTimeMillis()))
                var rounds=0;var toolCalls=0;var toolChars=0
                while(true) {
                    check(++rounds<=policy.maxRounds) {"Tool-round limit reached. Review results and send another message to continue."}
                    val latest=dao.chat(id) ?: error("Chat deleted");check(AgentPermission.of(latest)==AgentPermission.of(chat)) {"Permission changed. Send a fresh request in the selected mode."}
                    val history=AgentContext.window(dao.messages(id),policy).let {rows->if(rounds==1) rows else rows.map {r->if(r.attachmentsJson!="[]") r.copy(attachmentsJson="[]",body=r.body+"\n[Attachments were sent on this turn’s first request and are omitted from later tool rounds to avoid repeated image/file charges. Ask the operator to send again if needed.]") else r}}
                    var row=ChatMessageRow(conversationId=id,role="assistant",createdAt=nextMessageTime(),status="STREAMING",provider=chat.provider,model=chat.model)
                    dao.insert(row);current=row;val output=StringBuilder();var lastSave=0L
                    val result=modelRequest(chat.copy(instructions=AgentContext.instructions(chat)),history,tools.applicationDefinitions(chat)) {delta ->
                        output.append(delta);row=row.copy(body=output.toString(),updatedAt=System.currentTimeMillis());current=row
                        if(System.currentTimeMillis()-lastSave>100) {dao.update(row);lastSave=System.currentTimeMillis()}
                    }
                    val cost=if(cap.inputUsdPerToken!=null && cap.outputUsdPerToken!=null && result.inputTokens!=null && result.outputTokens!=null)
                        runCatching {cap.inputUsdPerToken.toBigDecimal()*result.inputTokens.toBigDecimal()+cap.outputUsdPerToken.toBigDecimal()*result.outputTokens.toBigDecimal()}.getOrNull()?.toPlainString() else null
                    row=row.copy(body=result.text,toolCallsJson=result.calls.toString(),status="COMPLETE",responseId=result.responseId,inputTokens=result.inputTokens,outputTokens=result.outputTokens,costUsd=result.reportedCostUsd ?: cost,usageJson=JSONObject().put("costKind",if(result.reportedCostUsd!=null) "PROVIDER_REPORTED" else if(cost!=null) "ESTIMATED" else "UNAVAILABLE").put("cachedInputTokens",result.cachedInputTokens ?: JSONObject.NULL).put("contextCharacters",history.sumOf {it.body.length+it.toolCallsJson.length}).toString(),updatedAt=System.currentTimeMillis());dao.update(row);current=null
                    if(chat.provider=="CHATGPT") SiwcConnection.proof(repo,"STREAM_COMPLETED","A ChatGPT-plan response reached response.completed. ${result.responseId.take(100)} · model ${chat.model.take(100)}")
                    if(result.calls.length()==0) break
                    check(chat.toolsEnabled) {"Chat Only cannot execute application tools."}
                    check(result.calls.length()<=16 && toolCalls+result.calls.length()<=policy.maxCalls) {"Tool-call budget reached. Results are retained; continue explicitly if needed."};toolCalls+=result.calls.length()
                    for(i in 0 until result.calls.length()) {val call=result.calls.getJSONObject(i)
                        val value=try {tools.execute(chat,call.getString("name"),JSONObject(call.getString("arguments")))} catch(e:CancellationException) {throw e} catch(e:Exception) {JSONObject().put("error",safeAgentText(e.message ?: "Tool failed"))}
                        toolChars+=value.toString().length;check(toolChars<=48000) {"Tool-result budget reached. Use narrower queries."}
                        dao.insert(ChatMessageRow(conversationId=id,role="tool",createdAt=nextMessageTime(),body=value.toString(),toolCallId=call.getString("call_id"),provider=chat.provider,model=chat.model))
                    }
                }
            } catch(e:CancellationException) {withContext(NonCancellable) {current?.let {dao.update(it.copy(status="CANCELLED",error="Stopped by operator. Partial response retained.",updatedAt=System.currentTimeMillis()))}};throw e}
            catch(e:Exception) {current?.let {dao.update(it.copy(status="ERROR",error=e.message ?: "Request failed",updatedAt=System.currentTimeMillis()))};error=e.message ?: "Request failed. Retry manually."}
            finally {jobs.remove(id);active.value=active.value-id}
        };jobs[id]=job;job.start()
    }
    internal fun setPermission(id:String,mode:AgentPermission)=action {
        jobs[id]?.cancelAndJoin()
        val selected=dao.chat(id) ?: error("Conversation missing");check(mode==AgentPermission.CHAT_ONLY || dao.capability(selected.provider,selected.model)?.supportsTools==true) {"Verify function-tool support with a connection test first."}
        repo.mutate {val row=dao.chat(id) ?: error("Conversation missing");dao.update(row.copy(toolsEnabled=mode!=AgentPermission.CHAT_ONLY,proposalsEnabled=mode==AgentPermission.FULL_AGENT,updatedAt=System.currentTimeMillis()))
            val policy=repo.journal.agentPolicyDao().policy(id) ?: AgentPolicyRow(id);repo.journal.agentPolicyDao().save(policy.copy(changedAt=maxOf(System.currentTimeMillis(),(dao.messages(id).maxOfOrNull {it.createdAt} ?: 0)+1)))
            repo.journal.openHelper.writableDatabase.execSQL("UPDATE agent_approvals SET state='EXPIRED',resultJson='{\"error\":\"Permission changed; request a fresh proposal\"}' WHERE conversationId=? AND state='PENDING'",arrayOf(id))}
        notice="${mode.label} selected. A fresh provider context starts on your next Send; local history is retained."
    }
    internal fun setBudget(id:String,economy:Boolean)=action {
        jobs[id]?.cancelAndJoin()
        repo.mutate {check(dao.chat(id)!=null);val p=repo.journal.agentPolicyDao().policy(id) ?: AgentPolicyRow(id)
            repo.journal.agentPolicyDao().save(p.copy(contextChars=if(economy) 12000 else 24000,maxRounds=if(economy) 4 else 8,maxCalls=if(economy) 12 else 24))}
        notice="${if(economy) "Economy" else "Standard"} request budget selected. No model request started."
    }
    internal suspend fun attachAppPhoto(chatId:String,photoId:String):JSONObject=withContext(Dispatchers.IO) {
        val chat=dao.chat(chatId) ?: error("Chat missing");check(chat.toolsEnabled) {"Choose Read Access or Full Agent before requesting inventory photos."}
        val photo=repo.dao.photo(requireUuid(photoId));check(!photo.deleted && photo.state=="READY");val original=repo.original(photo.id);check(hashFile(original)==photo.sha256) {"Photo changed; review the original first."}
        val dir=File(repo.dataRoot,"chat-attachments").apply {mkdirs()};val target=File(dir,"${newId()}.jpg")
        try {for(size in listOf(960,720,512)) {val bitmap=recognitionBitmap(original,size)
            try {target.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,it))}} finally {bitmap.recycle()}
            if(target.length()<=500000) break
        };check(target.length()<=500000) {"Photo derivative exceeds the cost budget."}} catch(e:Exception) {target.delete();throw e}
        JSONObject().put("path",target.name).put("name",target.name).put("mime","image/jpeg").put("bytes",target.length()).put("sha256",hashFile(target)).put("sourcePhotoId",photo.id)
    }
    fun stop(id:String) {jobs[id]?.cancel()}
    fun delete(id:String) {check(id !in jobs) {"Stop the response before deleting this chat."};action {dao.deleteChat(id)}}
    internal fun decide(id:String,accept:Boolean,options:AgentDecisionOptions,done:()->Unit) {
        if(id in deciding) return
        deciding=deciding+id;error=""
        viewModelScope.launch {try {notice=tools.decide(id,accept,options);done()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Approval failed. Review and retry."} finally {deciding=deciding-id}}
    }
    suspend fun export(id:String):String {val chat=dao.chat(id) ?: error("Chat no longer exists.");return JSONObject().put("format","snapims.chat").put("version",1).put("title",chat.title).put("provider",chat.provider).put("model",chat.model).put("authMode",chat.authMode)
        .put("instructions",chat.instructions).put("messages",JSONArray(dao.messages(id).map {JSONObject().put("role",it.role).put("body",it.body).put("status",it.status).put("attachments",JSONArray(it.attachmentsJson)).put("toolCalls",JSONArray(it.toolCallsJson)).put("toolCallId",it.toolCallId).put("error",it.error)})).toString(2)}
}
private fun java.io.InputStream.readNBytesCompat(count:Int):ByteArray {val out=java.io.ByteArrayOutputStream();repeat(count) {val b=read();if(b>=0) out.write(b)};return out.toByteArray()}
