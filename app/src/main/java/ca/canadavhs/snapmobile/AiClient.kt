package ca.canadavhs.snapmobile

import android.content.Context
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.concurrent.TimeUnit

internal class AiRequestException(val statusCode:Int,message:String):IllegalStateException(message)
internal data class AiProvider(val id:String,val label:String,val base:String,val responses:Boolean,val subscription:Boolean=false) {
    companion object {
        val all=listOf(AiProvider("CHATGPT","ChatGPT plan · experimental","https://api.openai.com/v1",true,true),
            AiProvider("OPENAI","OpenAI API · billed separately","https://api.openai.com/v1",true),
            AiProvider("LOCAL","On-device model · 127.0.0.1:8080","http://127.0.0.1:8080/v1",false),
            AiProvider("GEMINI","Gemini · provider limits apply","https://generativelanguage.googleapis.com/v1beta/openai",false),
            AiProvider("GROQ","Groq · provider limits apply","https://api.groq.com/openai/v1",false),
            AiProvider("OPENROUTER","OpenRouter · choose :free for free models","https://openrouter.ai/api/v1",false))
        fun get(id:String)=all.singleOrNull {it.id==id} ?: error("Unsupported provider. Select a configured connection.")
    }
}
internal fun providerKey(context:Context,id:String)=if(id=="OPENAI") SecureApiKeyStore(context) else SecureApiKeyStore(context,preferenceName="ai-key-$id",alias="snapims.ai.$id.v1")
internal class AiClient(private val context:Context,private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).followRedirects(false).build(),private val credentials:(suspend(AiProvider)->String)?=null) {
    private suspend fun bearer(provider:AiProvider):String=credentials?.invoke(provider) ?: if(provider.subscription) SiwcConnection.token(context) else if(provider.id=="LOCAL") "local-no-key" else providerKey(context,provider.id).read() ?: error("Save this provider's API key first. ChatGPT plan access is a separate connection.")
    private fun failure(code:Int,subscription:Boolean):AiRequestException=AiRequestException(code,when(code) {
        401 -> "Credentials expired or were rejected. Reconnect the selected provider."
        403 -> "This account or model is not eligible for the selected connection."
        429 -> if(subscription) "ChatGPT plan usage limit reached. Wait and retry manually." else "Provider quota or rate limit reached. Check your provider's limits and billing."
        else -> "Provider request failed (HTTP $code). Review the selected model and its supported inputs."
    }+if(subscription) " No API-key billing was used." else " No automatic retry was sent.")
    suspend fun models(provider:AiProvider):List<AiCapabilityRow> = withContext(Dispatchers.IO) {
        val request=Request.Builder().url("${provider.base}/models").header("Authorization","Bearer ${bearer(provider)}").build()
        val call=http.newCall(request)
        coroutineScope {val guard=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
            try {call.execute().use {response ->
                if(!response.isSuccessful) throw failure(response.code,provider.subscription)
                val bytes=response.body?.byteStream()?.use {it.readBounded(4_000_000)} ?: error("Empty model catalog")
                val json=JSONObject(String(bytes,Charsets.UTF_8));val list=json.getJSONArray(if(provider.subscription) "models" else "data")
                require(list.length()<=10000)
                (0 until list.length()).map {index ->val item=list.getJSONObject(index);val model=item.getString(if(provider.subscription) "slug" else "id");require(model.length in 1..300)
                    val verifiedOpenAi=provider.id=="OPENAI" && model in listOf("gpt-4.1-mini","gpt-4.1","gpt-4.1-nano")
                    val architecture=item.optJSONObject("architecture");val modalities=architecture?.optJSONArray("input_modalities")
                    val inputs=modalities?.let {(0 until it.length()).map {n->it.getString(n)}}
                    val params=item.optJSONArray("supported_parameters")?.let {(0 until it.length()).map {n->it.getString(n)}}
                    val pricing=item.optJSONObject("pricing")
                    AiCapabilityRow(stableRowId("${provider.id}/$model"),provider.id,model,item.optString(if(provider.subscription) "display_name" else "name",model),
                        supportsText=if(inputs!=null) "text" in inputs else if(verifiedOpenAi) true else null,
                        supportsTools=if(params!=null) "tools" in params else if(verifiedOpenAi) true else null,
                        supportsImages=if(inputs!=null) "image" in inputs else if(verifiedOpenAi) true else null,
                        supportsFiles=if(verifiedOpenAi) true else null,inputUsdPerToken=pricing?.optString("prompt")?.takeIf {it.toBigDecimalOrNull()?.signum()?.let {n->n>=0}==true},
                        outputUsdPerToken=pricing?.optString("completion")?.takeIf {it.toBigDecimalOrNull()?.signum()?.let {n->n>=0}==true},
                        contextWindow=item.optInt("context_length").takeIf {it>0},origin=if(verifiedOpenAi) "OPENAI_DOCUMENTED_REGISTRY" else "PROVIDER_CATALOG")
                }.sortedBy {it.displayName}
            }} finally {guard.cancel()}
        }
    }
    internal fun responsesBody(chat:ChatConversationRow,messages:List<ChatMessageRow>,tools:JSONArray):JSONObject {
        val input=JSONArray()
        messages.filter {it.status=="COMPLETE"}.forEach {message ->
            require(message.role in listOf("user","assistant","tool")) {"Unsupported local conversation role."}
            if(message.role=="tool") input.put(JSONObject().put("type","function_call_output").put("call_id",message.toolCallId).put("output",message.body))
            else {
                val content=JSONArray().put(JSONObject().put("type","input_text").put("text",message.body))
                val attachments=JSONArray(message.attachmentsJson)
                for(i in 0 until attachments.length()) {
                    val file=attachments.getJSONObject(i);val path=file.getString("path");require(path.matches(Regex("[a-f0-9-]{36}\\.(jpg|png|pdf|txt)")))
                    val source=java.io.File(context.filesDir,"chat-attachments/$path");require(source.length()<=8_000_000 && hashFile(source)==file.getString("sha256")) {"Chat attachment is missing or changed."}
                    val encoded=java.util.Base64.getEncoder().encodeToString(source.readBytes())
                    if(file.getString("mime").startsWith("image/")) content.put(JSONObject().put("type","input_image").put("image_url","data:${file.getString("mime")};base64,$encoded"))
                    else content.put(JSONObject().put("type","input_file").put("filename",file.getString("name")).put("file_data","data:${file.getString("mime")};base64,$encoded"))
                }
                if(message.body.isNotBlank() || attachments.length()>0) input.put(JSONObject().put("role",message.role).put("content",if(message.role=="assistant") message.body else content))
                val calls=JSONArray(message.toolCallsJson);for(i in 0 until calls.length()) input.put(calls.getJSONObject(i))
            }
        }
        return JSONObject().put("model",chat.model).put("store",false).put("stream",true).put("input",input).apply {
            if(chat.instructions.isNotBlank()) put("instructions",chat.instructions)
            if(tools.length()>0) put("tools",tools)
        }
    }
    private fun completionBody(chat:ChatConversationRow,messages:List<ChatMessageRow>,tools:JSONArray):JSONObject {
        val input=JSONArray()
        if(chat.instructions.isNotBlank()) input.put(JSONObject().put("role","system").put("content",chat.instructions))
        messages.filter {it.status=="COMPLETE"}.forEach {message ->
            check(JSONArray(message.attachmentsJson).length()==0) {"Attachments currently require an explicitly compatible OpenAI Responses model."}
            val item=JSONObject().put("role",message.role).put("content",message.body)
            if(message.role=="tool") item.put("tool_call_id",message.toolCallId)
            val calls=JSONArray(message.toolCallsJson)
            if(calls.length()>0) item.put("tool_calls",JSONArray((0 until calls.length()).map {i ->val call=calls.getJSONObject(i);JSONObject().put("id",call.getString("call_id")).put("type","function").put("function",JSONObject().put("name",call.getString("name")).put("arguments",call.getString("arguments")))}))
            input.put(item)
        }
        return JSONObject().put("model",chat.model).put("stream",true).put("messages",input).apply {
            if(tools.length()>0) put("tools",JSONArray((0 until tools.length()).map {index ->val tool=JSONObject(tools.getJSONObject(index).toString());tool.remove("type");JSONObject().put("type","function").put("function",tool)}))
        }
    }
    data class Result(val text:String,val calls:JSONArray,val responseId:String,val inputTokens:Long?,val outputTokens:Long?)
    suspend fun stream(chat:ChatConversationRow,messages:List<ChatMessageRow>,tools:JSONArray,onDelta:suspend(String)->Unit):Result=withContext(Dispatchers.IO) {
        val provider=AiProvider.get(chat.provider)
        check(chat.authMode==if(provider.subscription) "CHATGPT_PLAN" else "API_KEY") {"Conversation authentication does not match the selected provider."}
        val body=if(provider.responses) responsesBody(chat,messages,tools) else completionBody(chat,messages,tools)
        val request=Request.Builder().url("${provider.base}/${if(provider.responses) "responses" else "chat/completions"}").header("Authorization","Bearer ${bearer(provider)}")
            .header("Accept","text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call=http.newCall(request)
        coroutineScope {
            val guard=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
            try {call.execute().use {response ->
                if(!response.isSuccessful) throw failure(response.code,provider.subscription)
                val source=response.body?.source() ?: error("Empty streaming response")
                val text=StringBuilder();var completed=false;var done=false;var id="";var inputTokens:Long?=null;var outputTokens:Long?=null;var calls=JSONArray()
                val pendingCalls=sortedMapOf<Int,JSONObject>();var finishReason="";val data=StringBuilder();var eventBytes=0
                suspend fun event() {
                    if(data.isEmpty()) return
                    val raw=data.toString();data.clear();eventBytes=0
                    if(raw=="[DONE]") {done=true;return}
                    val item=JSONObject(raw)
                    if(item.has("error")) error("Provider reported a streaming error. Partial text is retained. Retry manually.")
                    if(provider.responses) when(item.optString("type")) {
                        "response.output_text.delta" -> {val delta=item.getString("delta");text.append(delta);onDelta(delta)}
                        "response.completed" -> {
                            val result=item.getJSONObject("response");check(result.optString("status")=="completed") {"Response did not complete."}
                            id=result.getString("id");val output=result.getJSONArray("output");calls=JSONArray()
                            for(i in 0 until output.length()) {val out=output.getJSONObject(i);if(out.optString("type")=="function_call") calls.put(out)}
                            val usage=result.optJSONObject("usage");inputTokens=usage?.optLong("input_tokens");outputTokens=usage?.optLong("output_tokens");completed=true;done=true
                        }
                        "response.failed","response.incomplete","error" -> error("Provider did not complete the response. Partial text is retained. Retry manually.")
                    } else {
                        id=item.optString("id",id);val choices=item.optJSONArray("choices") ?: JSONArray()
                        for(i in 0 until choices.length()) {val choice=choices.getJSONObject(i);if(choice.optInt("index")!=0) continue
                            if(!choice.isNull("finish_reason")) finishReason=choice.getString("finish_reason")
                            val delta=choice.optJSONObject("delta") ?: continue
                            if(!delta.isNull("content")) {val value=delta.getString("content");text.append(value);onDelta(value)}
                            val chunks=delta.optJSONArray("tool_calls") ?: continue
                            for(j in 0 until chunks.length()) {val chunk=chunks.getJSONObject(j);val target=pendingCalls.getOrPut(chunk.getInt("index")) {JSONObject().put("type","function_call").put("call_id","").put("name","").put("arguments","")}
                                if(chunk.has("id")) target.put("call_id",chunk.getString("id"))
                                chunk.optJSONObject("function")?.let {f ->for(k in listOf("name","arguments")) if(f.has(k)) target.put(k,target.getString(k)+f.getString(k))}
                            }
                        }
                    }
                    check(text.length<=1_000_000 && pendingCalls.size<=16) {"Response exceeds the local safety limit."}
                }
                while(!source.exhausted() && !done) {
                    currentCoroutineContext().ensureActive();val line=source.readUtf8LineStrict(2_000_000)
                    if(line.isEmpty()) event() else if(line.startsWith("data:")) {val value=line.substring(5).removePrefix(" ");eventBytes+=value.length;check(eventBytes<=2_000_000);if(data.isNotEmpty()) data.append('\n');data.append(value)}
                }
                if(data.isNotEmpty() && !done) event()
                if(!provider.responses) {completed=done && finishReason in listOf("stop","tool_calls");calls=JSONArray(pendingCalls.values.toList())}
                check(completed) {"Stream ended before confirmed completion. Partial text is retained. No automatic retry was sent."}
                Result(text.toString(),calls,id,inputTokens,outputTokens)
            }} catch(e:Exception) {currentCoroutineContext().ensureActive();throw e} finally {guard.cancel()}
        }
    }
}
