package ca.canadavhs.snapmobile
import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
internal class DirectShopifyGateway(private val context:Context,private val vault:SecureCredentialStore=SecureCredentialStore(context,namespace="shopify-direct"),private val http:OkHttpClient=OkHttpClient.Builder().followRedirects(false).build(),private val operationVault:ShopifyUploadVault=ShopifyUploadVault(context)):ShopifyGateway {
 companion object {private val credentialLock=Any()}
 @Volatile private var cachedHealth:Pair<Long,JSONObject>?=null
 internal suspend fun healthForWrite():JSONObject {val cached=cachedHealth;val value=if(cached!=null && System.currentTimeMillis()-cached.first<30000) JSONObject(cached.second.toString()) else call(ca.canadavhs.snapmobile.core.newId(),"health",JSONObject());val prefs=context.getSharedPreferences("capture-settings",0);value.put("allowProduction",prefs.getBoolean("shopifyOwnerWrites",false));value.put("allowPublish",value.getJSONArray("scopes").let {a->(0 until a.length()).any {a.getString(it)=="write_publications"}} && prefs.getBoolean("shopifyOwnerLive",false));return value}

 fun save(shop:String,client:String,secret:String)=synchronized(credentialLock) {require(shop.matches(Regex("[a-z0-9][a-z0-9-]*\\.myshopify\\.com")));require(client.isNotBlank() && secret.isNotBlank());cachedHealth=null;vault.save(JSONObject().put("shop",shop).put("client",client.trim()).put("secret",secret).put("installation",ca.canadavhs.snapmobile.core.newId()).toString())}
 fun clear()=synchronized(credentialLock) {cachedHealth=null;vault.remove()}
 fun endpoint():String="https://"+JSONObject(vault.read() ?: error("Save direct Shopify credentials first.")).getString("shop")
 fun fingerprint():String {val c=JSONObject(vault.read() ?: error("Direct credentials missing"));return ca.canadavhs.snapmobile.core.sha256((c.getString("shop")+"\n"+c.getString("client")+"\n"+c.optString("installation")).toByteArray())}
 private val engine by lazy {DirectShopifyEngine(context,this,operationVault)}
 internal fun credentialIdentity():String=endpoint()+"/"+ca.canadavhs.snapmobile.core.sha256(JSONObject(vault.read()!!).getString("client").toByteArray())
 internal fun safe(message:String):String {val c=runCatching {JSONObject(vault.read() ?: "{}")}.getOrDefault(JSONObject());return listOf("secret","client","token").map {c.optString(it)}.filter {it.isNotBlank()}.fold(message) {text,v->text.replace(v,"[redacted]")}.take(1500)}

 @Synchronized private fun token(rejected:String=""):Pair<String,String> {
  val c=synchronized(credentialLock) {JSONObject(vault.read() ?: error("Save direct Shopify credentials first."))};val shop=c.getString("shop")
  if(c.optLong("expiresAt")>System.currentTimeMillis()+60000 && (rejected.isBlank() || c.optString("token")!=rejected)) return shop to c.getString("token")
  val body=FormBody.Builder().add("grant_type","client_credentials").add("client_id",c.getString("client")).add("client_secret",c.getString("secret")).build()
  http.newCall(Request.Builder().url("https://$shop/admin/oauth/access_token").post(body).build()).execute().use {r->
   val value=JSONObject(r.body?.string() ?: "{}")
   check(r.isSuccessful) {val detail=listOf(value.optString("error"),value.optString("error_description")).filter {it.isNotBlank()}.joinToString(": ");val safe=listOf(c.getString("secret"),c.getString("client"),c.optString("token")).filter {it.isNotBlank()}.fold(detail) {text,credential->text.replace(credential,"[redacted]")};"Shopify authentication HTTP ${r.code}: ${safe.take(1000)}. Check credentials, app installation and same-organization ownership."}
   val token=value.getString("access_token");c.put("token",token).put("scope",value.optString("scope")).put("expiresAt",System.currentTimeMillis()+value.getLong("expires_in")*1000);synchronized(credentialLock) {val current=JSONObject(vault.read() ?: error("Shopify was disconnected during authentication."));check(current.optString("installation")==c.optString("installation") && current.getString("client")==c.getString("client") && current.getString("secret")==c.getString("secret")) {"Shopify credentials changed during authentication; retry using the saved connection."};vault.save(c.toString())};return shop to token
  }
 }
 internal suspend fun graphql(query:String,variables:JSONObject):JSONObject=withContext(Dispatchers.IO) {
  var credentials=token()
  for(attempt in 0..1) {
   val body=JSONObject().put("query",query).put("variables",variables).toString().toRequestBody("application/json".toMediaType())
   val request=Request.Builder().url("https://${credentials.first}/admin/api/2026-10/graphql.json").header("X-Shopify-Access-Token",credentials.second).post(body).build()
   val call=http.newCall(request)
   val guard=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
   try {call.execute().use {r->
    if(r.code==401 && attempt==0) {credentials=token(credentials.second);return@use}
    if(r.code==429) throw ShopifyBrokerException("THROTTLED","Shopify rate limit reached; retry the saved operation later.",429,60)
    if(!r.isSuccessful) throw ShopifyBrokerException("SHOPIFY_HTTP","Shopify HTTP ${r.code}. Verify app installation and granted scopes.",r.code)
    val raw=r.body?.byteStream()?.use {it.readBounded(2_000_000)} ?: error("Empty Shopify response")
    val response=JSONObject(String(raw,Charsets.UTF_8));val errors=response.optJSONArray("errors")
    if(errors!=null && errors.length()>0) {
     val throttled=(0 until errors.length()).all {errors.getJSONObject(it).optJSONObject("extensions")?.optString("code")=="THROTTLED"}
     throw ShopifyBrokerException(if(throttled) "THROTTLED" else "SHOPIFY_GRAPHQL",safe((0 until errors.length()).joinToString("; ") {errors.getJSONObject(it).optString("message")}),if(throttled) 429 else 422)
    }
    return@withContext response.getJSONObject("data")
   }} catch(e:java.io.IOException) {currentCoroutineContext().ensureActive();throw ShopifyBrokerException("NETWORK","Shopify connection interrupted. The saved operation must reconcile before repeating a creation.",502)} finally {guard.cancel()}
  }
  error("Shopify authentication rejected. Check installation and credentials.")
 }
 override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject=withContext(Dispatchers.IO) {
  if(operation !in listOf("health","browse","product")) return@withContext engine.call(operationId,operation,args)
  val variables=JSONObject();val query=when(operation) {
   "health"->{"query SnapIMSConnection{shop{id name myshopifyDomain currencyCode plan{partnerDevelopment}} currentAppInstallation{accessScopes{handle}} locations(first:100){nodes{id name isActive}} }"}
   "browse"->{val status=args.optString("status");require(status in listOf("","DRAFT","ACTIVE","ARCHIVED"));val text=args.optString("query").take(200).replace("\\","\\\\").replace("\"","\\\"");variables.put("query",listOfNotNull(if(text.isBlank()) null else "(title:\"$text\" OR sku:\"$text\")",status.takeIf {it.isNotBlank()}?.let {"status:${it.lowercase()}"}).joinToString(" AND ")).put("after",args.optString("cursor").takeIf {it.isNotBlank()} ?: JSONObject.NULL);"query SnapIMSCatalog(${'$'}query:String!,${'$'}after:String){products(first:20,after:${'$'}after,query:${'$'}query,sortKey:UPDATED_AT,reverse:true){nodes{id title status vendor productType updatedAt identity:metafield(namespace:\"snapims\",key:\"item_id\"){value} metafield(namespace:\"snapims\",key:\"creation_key\"){value} variants(first:2){nodes{id sku price inventoryQuantity inventoryItem{id}} pageInfo{hasNextPage}}} pageInfo{hasNextPage endCursor}}}"}
   "product"->{variables.put("id",args.getString("productId")).put("location",args.getString("locationId"));"query SnapIMSVerify(${'$'}id:ID!,${'$'}location:ID!){product(id:${'$'}id){id title status vendor productType tags descriptionHtml updatedAt identity:metafield(namespace:\"snapims\",key:\"item_id\"){value} metafield(namespace:\"snapims\",key:\"creation_key\"){value} variants(first:2){nodes{id sku price taxable barcode inventoryItem{id tracked requiresShipping measurement{weight{value unit}} inventoryLevel(locationId:${'$'}location){quantities(names:[\"available\"]){name quantity}}}} pageInfo{hasNextPage}} media(first:100){nodes{id alt preview{status}} pageInfo{hasNextPage}}}}"}
   else->error("Unsupported read")
  }
  val data=graphql(query,variables)
  when(operation) {
   "browse"->data.getJSONObject("products")
   "product"->data.optJSONObject("product")?.let {directProductFields(it)}?.also {it.put("reviewVersion",ca.canadavhs.snapmobile.core.sha256(canonicalJson(it).toByteArray()))} ?: JSONObject().put("missing",true)
   else->{val store=data.getJSONObject("shop");val scopes=data.getJSONObject("currentAppInstallation").getJSONArray("accessScopes");val names=(0 until scopes.length()).map {scopes.getJSONObject(it).getString("handle")};val canPublish=names.any {it in listOf("read_publications","write_publications")};val publications=if(canPublish) graphql("query SnapIMSPublications{publications(first:100){nodes{id name}}}",JSONObject()).getJSONObject("publications").getJSONArray("nodes") else JSONArray()
    JSONObject().put("shopId",store.getString("id")).put("name",store.getString("name")).put("shop",store.getString("myshopifyDomain")).put("currency",store.getString("currencyCode")).put("developmentStore",store.getJSONObject("plan").getBoolean("partnerDevelopment")).put("apiVersion","2026-10").put("scopes",JSONArray(names)).put("locations",data.getJSONObject("locations").getJSONArray("nodes")).put("publications",publications).put("allowPublish",names.contains("write_publications") && context.getSharedPreferences("capture-settings",0).getBoolean("shopifyOwnerLive",false)).put("allowProduction",context.getSharedPreferences("capture-settings",0).getBoolean("shopifyOwnerWrites",false)).put("direct",true).also {cachedHealth=System.currentTimeMillis() to JSONObject(it.toString())}
   }
  }
 }
 override suspend fun upload(target:JSONObject,file:java.io.File)=withContext(Dispatchers.IO) {
  requireShopifyUploadUrl(target.getString("url"));requireShopifyUploadUrl(target.getString("resourceUrl"));require(file.isFile && file.length() in 1..250000000)
  val parts=MultipartBody.Builder().setType(MultipartBody.FORM);val parameters=target.getJSONArray("parameters");require(parameters.length()<=50)
  for(i in 0 until parameters.length()) {val p=parameters.getJSONObject(i);parts.addFormDataPart(p.getString("name"),p.getString("value"))}
  parts.addFormDataPart("file",file.name,object:RequestBody() {override fun contentType()="image/jpeg".toMediaType();override fun contentLength()=file.length();override fun writeTo(sink:okio.BufferedSink) {file.inputStream().use {input->val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;sink.write(buffer,0,n)}}}})
  val call=http.newCall(Request.Builder().url(target.getString("url")).post(parts.build()).build());val guard=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
  try {call.execute().use {r->if(r.code !in listOf(200,201,204)) throw ShopifyBrokerException(if(r.code in listOf(400,401,403)) "STAGING_EXPIRED" else "UPLOAD_FAILED","Shopify photo transfer HTTP ${r.code}. Original remains on the phone.",r.code)}} catch(e:java.io.IOException) {currentCoroutineContext().ensureActive();throw ShopifyBrokerException("NETWORK","Photo transfer interrupted. Original retained; staged upload may retry.",502)} finally {guard.cancel()}
 }
}
