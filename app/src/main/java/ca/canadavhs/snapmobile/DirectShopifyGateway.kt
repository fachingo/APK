package ca.canadavhs.snapmobile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
internal class DirectShopifyGateway(context:Context,private val vault:SecureCredentialStore=SecureCredentialStore(context,namespace="shopify-direct"),private val http:OkHttpClient=OkHttpClient.Builder().followRedirects(false).build()):ShopifyGateway {
 fun save(shop:String,client:String,secret:String) {require(shop.matches(Regex("[a-z0-9][a-z0-9-]*\\.myshopify\\.com")));require(client.isNotBlank() && secret.isNotBlank());vault.save(JSONObject().put("shop",shop).put("client",client.trim()).put("secret",secret).toString())}
 fun clear()=vault.remove()
 @Synchronized private fun token():Pair<String,String> {
  val c=JSONObject(vault.read() ?: error("Save direct Shopify credentials first."));val shop=c.getString("shop")
  if(c.optLong("expiresAt")>System.currentTimeMillis()+60000) return shop to c.getString("token")
  val body=FormBody.Builder().add("grant_type","client_credentials").add("client_id",c.getString("client")).add("client_secret",c.getString("secret")).build()
  http.newCall(Request.Builder().url("https://$shop/admin/oauth/access_token").post(body).build()).execute().use {r->
   val value=JSONObject(r.body?.string() ?: "{}")
   check(r.isSuccessful) {val detail=listOf(value.optString("error"),value.optString("error_description")).filter {it.isNotBlank()}.joinToString(": ");val safe=listOf(c.getString("secret"),c.getString("client"),c.optString("token")).filter {it.isNotBlank()}.fold(detail) {text,credential->text.replace(credential,"[redacted]")};"Shopify authentication HTTP ${r.code}: ${safe.take(1000)}. Check credentials, app installation and same-organization ownership."}
   val token=value.getString("access_token");c.put("token",token).put("scope",value.optString("scope")).put("expiresAt",System.currentTimeMillis()+value.getLong("expires_in")*1000);vault.save(c.toString());return shop to token
  }
 }
 override suspend fun call(operationId:String,operation:String,args:JSONObject):JSONObject=withContext(Dispatchers.IO) {
  check(operation in listOf("health","browse","product")) {"Direct writes are not yet enabled. No operation was sent."}
  val (shop,token)=token()
  val variables=JSONObject();val query=when(operation) {
   "health"->{"query SnapIMSConnection{shop{id name myshopifyDomain currencyCode plan{partnerDevelopment}} currentAppInstallation{accessScopes{handle}} locations(first:100){nodes{id name isActive}} }"}
   "browse"->{val status=args.optString("status");require(status in listOf("","DRAFT","ACTIVE","ARCHIVED"));val text=args.optString("query").take(200).replace("\\","\\\\").replace("\"","\\\"");variables.put("query",listOfNotNull(if(text.isBlank()) null else "(title:\"$text\" OR sku:\"$text\")",status.takeIf {it.isNotBlank()}?.let {"status:${it.lowercase()}"}).joinToString(" AND ")).put("after",args.optString("cursor").takeIf {it.isNotBlank()} ?: JSONObject.NULL);"query SnapIMSCatalog(${'$'}query:String!,${'$'}after:String){products(first:20,after:${'$'}after,query:${'$'}query,sortKey:UPDATED_AT,reverse:true){nodes{id title status vendor productType updatedAt identity:metafield(namespace:\"snapims\",key:\"item_id\"){value} metafield(namespace:\"snapims\",key:\"creation_key\"){value} variants(first:2){nodes{id sku price inventoryQuantity inventoryItem{id}} pageInfo{hasNextPage}}} pageInfo{hasNextPage endCursor}}}"}
   "product"->{variables.put("id",args.getString("productId")).put("location",args.getString("locationId"));"query SnapIMSVerify(${'$'}id:ID!,${'$'}location:ID!){product(id:${'$'}id){id title status vendor productType tags descriptionHtml updatedAt identity:metafield(namespace:\"snapims\",key:\"item_id\"){value} metafield(namespace:\"snapims\",key:\"creation_key\"){value} variants(first:2){nodes{id sku price barcode inventoryItem{id tracked requiresShipping measurement{weight{value unit}} inventoryLevel(locationId:${'$'}location){quantities(names:[\"available\"]){name quantity}}}} pageInfo{hasNextPage}} media(first:100){nodes{id alt preview{status}} pageInfo{hasNextPage}}}}"}
   else->error("Unsupported read")
  }
  val body=JSONObject().put("query",query).put("variables",variables).toString().toRequestBody("application/json".toMediaType())
  http.newCall(Request.Builder().url("https://$shop/admin/api/2026-10/graphql.json").header("X-Shopify-Access-Token",token).post(body).build()).execute().use {r->
   check(r.isSuccessful) {"Shopify HTTP ${r.code}. Check connection and granted scopes."};val response=JSONObject(r.body?.string() ?: "{}");check(!response.has("errors")) {"Shopify rejected the read. Verify read_products, read_inventory and read_locations scopes."};val data=response.getJSONObject("data")
   when(operation) {
    "browse"->data.getJSONObject("products")
    "product"->data.optJSONObject("product") ?: JSONObject().put("missing",true)
    else->{val store=data.getJSONObject("shop");JSONObject().put("shopId",store.getString("id")).put("name",store.getString("name")).put("shop",store.getString("myshopifyDomain")).put("currency",store.getString("currencyCode")).put("developmentStore",store.getJSONObject("plan").getBoolean("partnerDevelopment")).put("apiVersion","2026-10").put("scopes",JSONArray().apply {val scopes=data.getJSONObject("currentAppInstallation").getJSONArray("accessScopes");for(i in 0 until scopes.length()) put(scopes.getJSONObject(i).getString("handle"))}).put("locations",data.getJSONObject("locations").getJSONArray("nodes")).put("publications",JSONArray()).put("allowPublish",false)}
   }
  }
 }
 override suspend fun upload(target:JSONObject,file:java.io.File) {error("Direct uploads are not yet enabled.")}
}
