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
    init {viewModelScope.launch {dao.interruptedMessages().forEach {dao.update(it.copy(status="INTERRUPTED",error="App stopped before completion. Retry manually; no request restarted."))}}}
    fun action(block:suspend()->Unit) {viewModelScope.launch {try {block()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Operation failed. Retry manually."}}}
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
            dao.save(verified);catalog=catalog.map {if(it.id==model.id) verified else it};error="$kind probe completed successfully using ${provider.label}."
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
        require(text.length<=100000 && attachments.length()<=4)
        active.value=active.value+id
        jobs[id]=viewModelScope.launch {
            var current:ChatMessageRow?=null
            try {
                val chat=dao.chat(id) ?: error("Chat no longer exists.")
                val cap=dao.capability(chat.provider,chat.model) ?: error("Discover models again before sending.")
                for(i in 0 until attachments.length()) {val mime=attachments.getJSONObject(i).getString("mime")
                    check(AiProvider.get(chat.provider).responses && if(mime.startsWith("image/")) cap.supportsImages==true else cap.supportsFiles==true) {"This model's attachment capability is unverified or unsupported. Use a compatible API-key model."}
                }
                dao.insert(ChatMessageRow(conversationId=id,role="user",body=text,attachmentsJson=attachments.toString(),provider=chat.provider,model=chat.model))
                dao.update(chat.copy(title=if(chat.title=="New conversation") text.take(70).ifBlank {"Attachment conversation"} else chat.title,updatedAt=System.currentTimeMillis()))
                var rounds=0
                while(true) {
                    check(++rounds<=4) {"Tool-round limit reached. Review results and send another message to continue."}
                    val history=dao.messages(id);check(history.sumOf {it.body.length+it.attachmentsJson.length+it.toolCallsJson.length}<=300000) {"Conversation is too long for a safe request. Export it and start a new chat."}
                    var row=ChatMessageRow(conversationId=id,role="assistant",status="STREAMING",provider=chat.provider,model=chat.model)
                    dao.insert(row);current=row;val output=StringBuilder();var lastSave=0L
                    val result=client.stream(chat,history,if(chat.toolsEnabled) tools.definitions(chat.proposalsEnabled) else JSONArray()) {delta ->
                        output.append(delta);row=row.copy(body=output.toString(),updatedAt=System.currentTimeMillis());current=row
                        if(System.currentTimeMillis()-lastSave>100) {dao.update(row);lastSave=System.currentTimeMillis()}
                    }
                    val cost=if(cap.inputUsdPerToken!=null && cap.outputUsdPerToken!=null && result.inputTokens!=null && result.outputTokens!=null)
                        runCatching {cap.inputUsdPerToken.toBigDecimal()*result.inputTokens.toBigDecimal()+cap.outputUsdPerToken.toBigDecimal()*result.outputTokens.toBigDecimal()}.getOrNull()?.toPlainString() else null
                    row=row.copy(body=result.text,toolCallsJson=result.calls.toString(),status="COMPLETE",responseId=result.responseId,inputTokens=result.inputTokens,outputTokens=result.outputTokens,costUsd=cost,updatedAt=System.currentTimeMillis());dao.update(row);current=null
                    if(chat.provider=="CHATGPT") SiwcConnection.proof(repo,"STREAM_COMPLETED","A ChatGPT-plan response reached response.completed. ${result.responseId.take(100)} · model ${chat.model.take(100)}")
                    if(result.calls.length()==0) break
                    check(result.calls.length()<=16)
                    for(i in 0 until result.calls.length()) {val call=result.calls.getJSONObject(i)
                        val value=try {tools.execute(chat,call.getString("name"),JSONObject(call.getString("arguments")))} catch(e:Exception) {JSONObject().put("error",e.message ?: "Tool failed")}
                        dao.insert(ChatMessageRow(conversationId=id,role="tool",body=value.toString(),toolCallId=call.getString("call_id"),provider=chat.provider,model=chat.model))
                    }
                }
            } catch(e:CancellationException) {withContext(NonCancellable) {current?.let {dao.update(it.copy(status="CANCELLED",error="Stopped by operator. Partial response retained.",updatedAt=System.currentTimeMillis()))}};throw e}
            catch(e:Exception) {current?.let {dao.update(it.copy(status="ERROR",error=e.message ?: "Request failed",updatedAt=System.currentTimeMillis()))};error=e.message ?: "Request failed. Retry manually."}
            finally {jobs.remove(id);active.value=active.value-id}
        }
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
