package ca.canadavhs.snapmobile

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Mock transport only. These tests do not establish eligible-account or physical Pixel acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class AiProtocolTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val messages=listOf(ChatMessageRow(conversationId="fixture",role="user",body="Hello"))
    private fun chat(provider:String="CHATGPT")=ChatConversationRow(id="fixture",title="Fixture",provider=provider,model="fixture-model",authMode=if(provider=="CHATGPT") "CHATGPT_PLAN" else "API_KEY",instructions="Be concise")
    private fun client(code:Int,body:String,requests:MutableList<Request> = mutableListOf()):AiClient=AiClient(context,OkHttpClient.Builder().addInterceptor {chain->requests+=chain.request();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("text/event-stream".toMediaType())).build()}.build()) {"fixture-token"}
    @Test fun siwcRequestUsesOnlyAllowedFieldsAndLocalContext() {
        val body=client(200,"").responsesBody(chat(),messages,JSONArray())
        assertEquals(setOf("model","store","stream","input","instructions"),body.keys().asSequence().toSet());assertFalse(body.getBoolean("store"));assertTrue(body.getBoolean("stream"))
        assertEquals("user",body.getJSONArray("input").getJSONObject(0).getString("role"))
        val history=client(200,"").responsesBody(chat(),messages+messages[0].copy(role="assistant",body="Earlier answer")+messages[0].copy(body="Follow-up"),JSONArray()).getJSONArray("input")
        assertEquals("assistant",history.getJSONObject(1).getString("role"));assertEquals("Earlier answer",history.getJSONObject(1).getString("content"));assertEquals("input_text",history.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
        val forbidden=setOf("background","conversation","previous_response_id","max_output_tokens","max_tool_calls","metadata","multi_agent","temperature","top_p","prompt","truncation","user")
        assertTrue(body.keys().asSequence().none {it in forbidden})
        try {client(200,"").responsesBody(chat(),listOf(messages[0].copy(role="system")),JSONArray());fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun streamRequiresCompletedEventAndKeepsDeltas():Unit=runBlocking {
        val fixture="data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"fixture-response\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}}\n\n"
        val output=StringBuilder();val result=client(200,fixture).stream(chat(),messages,JSONArray()) {output.append(it)}
        assertEquals("Hello",output.toString());assertEquals("Hello",result.text);assertEquals("fixture-response",result.responseId);assertEquals(2L,result.inputTokens)
        try {client(200,"data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n").stream(chat(),messages,JSONArray()) {};fail()} catch(e:IllegalStateException) {assertTrue(e.message!!.contains("before confirmed completion"))}
    }
    @Test fun subscriptionQuotaFailureNeverAttemptsApiFallback():Unit=runBlocking {
        val requests=mutableListOf<Request>()
        try {client(429,"{}",requests).stream(chat(),messages,JSONArray()) {};fail()} catch(e:AiRequestException) {assertEquals(429,e.statusCode);assertTrue(e.message!!.contains("No API-key billing"))}
        assertEquals(1,requests.size);assertEquals("https://api.openai.com/v1/responses",requests[0].url.toString());assertEquals("Bearer fixture-token",requests[0].header("Authorization"))
    }
    @Test fun modelCatalogUsesSubscriptionSlugsAndDoesNotTreatApiCatalogAsSubscription():Unit=runBlocking {
        val result=client(200,"{\"models\":[{\"slug\":\"eligible\",\"display_name\":\"Eligible model\"}]}").models(AiProvider.get("CHATGPT"))
        assertEquals("eligible",result.single().model);assertEquals("Eligible model",result.single().displayName);assertNull(result.single().supportsImages)
        try {client(200,"{\"data\":[{\"id\":\"api-only\"}]}").models(AiProvider.get("CHATGPT"));fail()} catch(_:JSONException) {}
    }
    @Test fun localCompletionAssemblesToolDeltasAndRequiresDone():Unit=runBlocking {
        val fixture="data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-fixture\",\"function\":{\"name\":\"inventory_search\",\"arguments\":\"{\\\"query\\\":\"}}]},\"finish_reason\":null}]}\n\n"+
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Alien\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        val result=client(200,fixture).stream(chat("LOCAL"),messages,JSONArray()) {}
        assertEquals("inventory_search",result.calls.getJSONObject(0).getString("name"));assertEquals("Alien",JSONObject(result.calls.getJSONObject(0).getString("arguments")).getString("query"))
    }
}
