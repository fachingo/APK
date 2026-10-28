package ca.canadavhs.snapmobile

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CaptureApplication::class)
class RecognitionClientTest {
    private fun suggestion(): JSONObject = JSONObject().put("title", "Alien").put("edition", "").put("distributor", "CBS/Fox")
        .put("year", 1986).put("confidence", 0.8).put("barcode_candidates", JSONArray(listOf("036000291452")))
        .put("uncertainty_reasons", JSONArray()).put("title_evidence", JSONArray(listOf("ALIEN")))
        .put("contradiction_flags", JSONArray()).put("requires_review", false)
        .put("field_evidence", JSONObject().apply { for (field in listOf("year", "edition", "distributor", "barcode")) put(field, JSONArray()) })
    private fun response(payload: JSONObject = suggestion()): JSONObject = JSONObject().put("status", "completed").put("id", "resp_test")
        .put("model", "gpt-4.1-mini").put("usage", JSONObject().put("input_tokens", 12).put("output_tokens", 9))
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", payload.toString())))))

    @Test fun structuredResponseAlwaysRemainsSuggestion() {
        val result = OpenAiRecognitionClient().parse(response())
        assertEquals("Alien", result.getString("title")); assertTrue(result.getBoolean("requires_review"))
        assertEquals("resp_test", result.getString("response_id")); assertFalse(result.has("suggested_price_cents"))
    }
    @Test fun requestUsesResponsesImagesStrictSchemaAndNoServerStorage() {
        val schema = JSONObject().put("type", "object")
        val request = OpenAiRecognitionClient().request("gpt-4.1-mini", listOf("AA==", "AQ=="), schema)
        assertFalse(request.getBoolean("store"))
        val format = request.getJSONObject("text").getJSONObject("format")
        assertTrue(format.getBoolean("strict")); assertEquals("json_schema", format.getString("type"))
        assertEquals(3, request.getJSONArray("input").getJSONObject(0).getJSONArray("content").length())
        assertFalse(request.toString().contains("api_key"))
    }
    @Test fun malformedOutputAndIncompleteResponsesAreRejected() {
        for (r in listOf(response().put("status", "incomplete"), response(suggestion().put("confidence", 1.7)), response(suggestion().put("year", "1986")), response(suggestion().removeField("title")))) {
            try { OpenAiRecognitionClient().parse(r); fail("Invalid recognition must be rejected") } catch (_: Exception) { }
        }
    }
    @Test fun refusalsAreNotParsedAsMetadata() {
        val r = response().put("output", JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "Cannot process")))))
        try { OpenAiRecognitionClient().parse(r); fail("Refusal must fail safely") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("declined")) }
    }
    @Test fun authenticationQuotaAndUnsupportedModelErrorsAreActionable(): Unit = runBlocking {
        for ((code, marker) in listOf(401 to "API key", 403 to "permissions", 429 to "quota", 400 to "vision model", 500 to "unavailable")) {
            val client = OpenAiRecognitionClient(OpenAiTransport { _, _, _ -> OpenAiHttpResult(code, "sensitive upstream body should never be shown") })
            try { client.models("not-a-real-key"); fail("HTTP $code must fail") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains(marker)); assertFalse(e.message!!.contains("sensitive")) }
        }
    }
    @Test fun modelsListsCandidateIdsWithoutPretendingToKnowCapabilities(): Unit = runBlocking {
        val response = JSONObject().put("data", JSONArray(listOf("gpt-4.1-mini", "gpt-5", "text-embedding-3-large", "gpt-4o-audio-preview", "gpt-5-codex").map { JSONObject().put("id", it) }))
        val client = OpenAiRecognitionClient(OpenAiTransport { _, path, payload -> assertEquals("models", path); assertNull(payload); OpenAiHttpResult(200, response.toString()) })
        assertEquals(listOf("gpt-4.1-mini", "gpt-5"), client.models("not-a-real-key"))
    }
    @Test fun realHttpTransportUsesBearerHeaderAndFixedHttpsOrigin(): Unit = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = OpenAiRecognitionClient(HttpsOpenAiTransport(client)).recognize("not-a-real-key", "gpt-4.1-mini", listOf("AA=="), JSONObject())
        assertEquals("Alien", result.getString("title")); assertEquals("https", captured!!.url.scheme)
        assertEquals("api.openai.com", captured!!.url.host); assertEquals("/v1/responses", captured!!.url.encodedPath)
        assertEquals("Bearer not-a-real-key", captured!!.header("Authorization"))
    }
    private fun JSONObject.removeField(name: String): JSONObject { remove(name); return this }
}
