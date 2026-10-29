package ca.canadavhs.snapmobile
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import javax.crypto.KeyGenerator
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DirectShopifyTest {
 @Test fun encryptedPersistenceTokenReuseRenewalAndDisconnect():Unit=runBlocking {
  val app=RuntimeEnvironment.getApplication();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
  val vault=SecureCredentialStore(app,"direct-fixture") {key};var tokens=0;var reads=0
  val http=OkHttpClient.Builder().addInterceptor {chain->val request=chain.request();val body=if(request.url.encodedPath.endsWith("access_token")) {tokens++;val buffer=okio.Buffer();request.body!!.writeTo(buffer);val form=buffer.readUtf8();assertTrue(form.contains("grant_type=client_credentials"));assertTrue(form.contains("client_secret=fixture-secret"));"""{"access_token":"fixture-token","scope":"read_products","expires_in":86399}"""} else {reads++;assertEquals("fixture-token",request.header("X-Shopify-Access-Token"));"""{"data":{"products":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""};Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()}.build()
  val gateway=DirectShopifyGateway(app,vault,http);gateway.save("fixture.myshopify.com","fixture-client","fixture-secret")
  assertFalse(app.getSharedPreferences("direct-fixture-encrypted",0).all.toString().contains("fixture-secret"))
  val args=JSONObject().put("query","").put("status","ACTIVE").put("cursor","")
  gateway.call("fixture","browse",args);DirectShopifyGateway(app,vault,http).call("fixture","browse",args);assertEquals(1,tokens)
  vault.save(JSONObject(vault.read()!!).put("expiresAt",0).toString());gateway.call("fixture","browse",args);assertEquals(2,tokens);assertEquals(3,reads)
  gateway.clear();assertNull(vault.read());try {gateway.call("fixture","browse",args);fail()} catch(_:IllegalStateException) {};assertEquals(2,tokens)
 }
 @Test fun domainValidationAndUnsupportedOperationsFailBeforeNetwork():Unit=runBlocking {
  val app=RuntimeEnvironment.getApplication();val gateway=DirectShopifyGateway(app)
  try {gateway.save("https://evil.example","client","secret");fail()} catch(_:IllegalArgumentException) {}
  try {gateway.call(ca.canadavhs.snapmobile.core.newId(),"arbitrary_delete",JSONObject());fail()} catch(_:IllegalStateException) {}
 }
 @Test fun rejectedGrantReportsOrganizationErrorWithoutSecretsOrFallback():Unit=runBlocking {
  val app=RuntimeEnvironment.getApplication();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val vault=SecureCredentialStore(app,"rejected-direct-fixture") {key};var calls=0
  val http=OkHttpClient.Builder().addInterceptor {chain->calls++;assertTrue(chain.request().url.encodedPath.endsWith("access_token"));Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").body("""{"error":"invalid_request","error_description":"App must belong to same organization fixture-secret"}""".toResponseBody("application/json".toMediaType())).build()}.build()
  val gateway=DirectShopifyGateway(app,vault,http);gateway.save("fixture.myshopify.com","fixture-client","fixture-secret")
  try {gateway.call("fixture","health",JSONObject());fail()} catch(e:IllegalStateException) {assertTrue(e.message!!.contains("same organization"));assertFalse(e.message!!.contains("fixture-secret"));assertTrue(e.message!!.contains("401"))};assertEquals(1,calls)
 }

 @Test fun disconnectDuringTokenExchangeCannotRestoreRemovedCredentials():Unit=runBlocking {
  val app=RuntimeEnvironment.getApplication();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val vault=SecureCredentialStore(app,"disconnect-race-fixture") {key};lateinit var gateway:DirectShopifyGateway;var calls=0
  val http=OkHttpClient.Builder().addInterceptor {chain->calls++;gateway.clear();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("""{"access_token":"late-token","scope":"read_products","expires_in":86399}""".toResponseBody("application/json".toMediaType())).build()}.build();gateway=DirectShopifyGateway(app,vault,http);gateway.save("fixture.myshopify.com","fixture-client","fixture-secret")
  try {gateway.call("fixture","health",JSONObject());fail()} catch(_:IllegalStateException) {};assertNull(vault.read());assertEquals(1,calls)
 }

}
