package ca.canadavhs.snapmobile

import org.json.*
import java.math.BigDecimal
import ca.canadavhs.snapmobile.core.*

internal data class DirectOperation(val query:String,val variables:JSONObject,val field:String="",val write:Boolean=false,val uncertain:Boolean=false)
/** Closed native registry. No caller-supplied GraphQL or arbitrary resource writes. */
internal object DirectShopifyOperations {
 private fun gid(a:JSONObject,key:String,kind:String):String=a.getString(key).also {require(it.matches(Regex("gid://shopify/$kind/[0-9]{1,30}"))) {"Invalid $kind identity."}}
 private fun text(a:JSONObject,k:String,max:Int=1000,min:Int=0)=a.getString(k).also {require(it.length in min..max && !it.contains('\u0000'))}
 private fun integer(a:JSONObject,k:String,min:Long,max:Long)=a.getLong(k).also {require((a.get(k) is Int || a.get(k) is Long) && it in min..max)}
 private fun key(a:JSONObject)=requireUuid(a.getString("creationKey"))
 private fun sku(a:JSONObject)=text(a,"itemId",200,1).also {require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")))}
 fun prepare(kind:String,a:JSONObject):DirectOperation {
  val variables=JSONObject();val write=kind !in setOf("find","quantity","files_find","file_status","remote_job","publication_status")
  val allowed=when(kind) {
   "find"->setOf("itemId","creationKey")
   "quantity"->setOf("inventoryItemId","locationId")
   "stage"->setOf("filename","bytes")
   "create"->setOf("creationKey","itemId","title","descriptionHtml","vendor","productType","tags")
   "variant"->setOf("creationKey","productId","variantId","priceCents","barcode","taxable")
   "inventory_item"->setOf("creationKey","inventoryItemId","itemId","weightGrams","tracked","requiresShipping")
   "stock_activate"->setOf("creationKey","inventoryItemId","locationId","idempotencyKey","quantity")
   "stock_set"->setOf("creationKey","inventoryItemId","locationId","idempotencyKey","quantity","changeFromQuantity")
   "file_create"->setOf("creationKey","source","filename","alt")
   "files_find"->setOf("filename")
   "file_status"->setOf("fileId")
   "attach"->setOf("creationKey","productId","fileId")
   "reorder"->setOf("creationKey","productId","fileIds")
   "remote_job"->setOf("jobId")
   "activate_product"->setOf("creationKey","productId")
   "publish"->setOf("creationKey","productId","publicationId")
   "publication_status"->setOf("creationKey","productId","publicationId")
   "metadata_update"->setOf("creationKey","itemId","productId","locationId","expectedVersion","patch")
   else->error("Unsupported native Shopify operation")
  }
  val required=when(kind) {"variant"->allowed-setOf("barcode","taxable");"inventory_item"->allowed-setOf("tracked","requiresShipping");else->allowed};require(a.keys().asSequence().all {it in allowed} && required.all {a.has(it)}) {"Unexpected Shopify operation arguments."}
  if(a.has("creationKey")) key(a)
  for(flag in listOf("tracked","requiresShipping")) if(a.has(flag)) require(a.get(flag) is Boolean)
  when(kind) {
   "find"->variables.put("sku","sku:"+sku(a)).put("marker","tag:snapims-"+key(a))
   "quantity"->variables.put("id",gid(a,"inventoryItemId","InventoryItem")).put("location",gid(a,"locationId","Location"))
   "stage"->{val name=text(a,"filename",240,1);require(name.matches(Regex("[A-Za-z0-9._-]+\\.jpg")));variables.put("input",JSONArray().put(JSONObject().put("filename",name).put("fileSize",integer(a,"bytes",1,250000000).toString()).put("mimeType","image/jpeg").put("httpMethod","POST").put("resource","IMAGE")))}
   "create"->{val tags=a.getJSONArray("tags");require(tags.length()<=99);val values=(0 until tags.length()).map {tags.getString(it).also {v->require(v.length in 1..255)}}.toMutableList();values+="snapims-"+key(a)
    variables.put("product",JSONObject().put("title",text(a,"title",255,1)).put("descriptionHtml",text(a,"descriptionHtml",60000)).put("vendor",text(a,"vendor",255)).put("productType",text(a,"productType",255)).put("tags",JSONArray(values.distinct())).put("status","DRAFT").put("metafields",JSONArray().put(JSONObject().put("namespace","snapims").put("key","item_id").put("type","single_line_text_field").put("value",sku(a))).put(JSONObject().put("namespace","snapims").put("key","creation_key").put("type","single_line_text_field").put("value",key(a)))))}
   "variant"->{val barcode=if(a.has("barcode")) text(a,"barcode",14) else null;require(barcode==null || barcode.isBlank() || barcode.matches(Regex("[0-9]{8}|[0-9]{12,14}")));if(a.has("taxable")) require(a.get("taxable") is Boolean);variables.put("id",gid(a,"productId","Product")).put("variants",JSONArray().put(JSONObject().put("id",gid(a,"variantId","ProductVariant")).put("price",BigDecimal.valueOf(integer(a,"priceCents",0,100000000),2).toPlainString()).put("taxable",a.optBoolean("taxable",true)).apply {if(barcode!=null) put("barcode",barcode)}))}
   "inventory_item"->variables.put("id",gid(a,"inventoryItemId","InventoryItem")).put("input",JSONObject().put("sku",sku(a)).put("tracked",a.optBoolean("tracked",true)).put("requiresShipping",a.optBoolean("requiresShipping",true)).put("measurement",JSONObject().put("weight",JSONObject().put("value",integer(a,"weightGrams",1,1000000)).put("unit","GRAMS"))))
   "stock_activate","stock_set"->{val id=gid(a,"inventoryItemId","InventoryItem");val location=gid(a,"locationId","Location");val quantity=integer(a,"quantity",0,1);val request=requireUuid(a.getString("idempotencyKey"));variables.put("key",request)
    if(kind=="stock_activate") variables.put("id",id).put("location",location).put("available",quantity)
    else variables.put("input",JSONObject().put("name","available").put("reason","correction").put("referenceDocumentUri","snapims://inventory/$id").put("quantities",JSONArray().put(JSONObject().put("inventoryItemId",id).put("locationId",location).put("quantity",quantity).put("changeFromQuantity",integer(a,"changeFromQuantity",Int.MIN_VALUE.toLong(),Int.MAX_VALUE.toLong())))))}
   "file_create"->{val source=text(a,"source",16000,1);requireShopifyUploadUrl(source);variables.put("input",JSONArray().put(JSONObject().put("originalSource",source).put("contentType","IMAGE").put("filename",text(a,"filename",240,1)).put("alt",text(a,"alt",500,1))))}
   "files_find"->{val name=text(a,"filename",240,1);require(name.matches(Regex("[A-Za-z0-9._-]+\\.jpg")));variables.put("query","filename:$name")}
   "file_status"->variables.put("id",gid(a,"fileId","MediaImage"))
   "attach"->variables.put("files",JSONArray().put(JSONObject().put("id",gid(a,"fileId","MediaImage")).put("referencesToAdd",JSONArray().put(gid(a,"productId","Product")))))
   "reorder"->{val ids=a.getJSONArray("fileIds");require(ids.length() in 1..100);val distinct=mutableSetOf<String>();val moves=JSONArray();for(i in 0 until ids.length()) {val id=ids.getString(i);require(id.matches(Regex("gid://shopify/MediaImage/[0-9]{1,30}")) && distinct.add(id));moves.put(JSONObject().put("id",id).put("newPosition",i.toString()))};variables.put("id",gid(a,"productId","Product")).put("moves",moves)}
   "remote_job"->variables.put("id",gid(a,"jobId","Job"))
   "activate_product"->variables.put("product",JSONObject().put("id",gid(a,"productId","Product")).put("status","ACTIVE"))
   "publish"->variables.put("id",gid(a,"productId","Product")).put("input",JSONArray().put(JSONObject().put("publicationId",gid(a,"publicationId","Publication"))))
   "publication_status"->variables.put("id",gid(a,"productId","Product")).put("publication",gid(a,"publicationId","Publication"))
   "metadata_update"->{sku(a);gid(a,"locationId","Location");require(a.getString("expectedVersion").matches(Regex("[a-f0-9]{64}")));val patch=a.getJSONObject("patch");require(patch.length()>0 && patch.keys().asSequence().all {it in listOf("title","descriptionHtml","vendor","productType","tags")});val product=JSONObject().put("id",gid(a,"productId","Product"));patch.keys().forEach {k->if(k=="tags") {val tags=patch.getJSONArray(k);require(tags.length()<=99);for(i in 0 until tags.length()) require(tags.getString(i).length in 1..255);product.put(k,tags)} else product.put(k,text(patch,k,if(k=="descriptionHtml") 60000 else 255,if(k=="title") 1 else 0))};variables.put("product",product)}
  }
  val query=when(kind) {
   "find"->"query SnapIMSFind(${'$'}sku:String!,${'$'}marker:String!){variants:productVariants(first:10,query:${'$'}sku){nodes{id sku inventoryItem{id} product{id title status}} pageInfo{hasNextPage}} products(first:10,query:${'$'}marker){nodes{id status metafield(namespace:\"snapims\",key:\"creation_key\"){value} identity:metafield(namespace:\"snapims\",key:\"item_id\"){value} variants(first:2){nodes{id sku inventoryItem{id}}}} pageInfo{hasNextPage}}}"
   "quantity"->"query SnapIMSStock(${'$'}id:ID!,${'$'}location:ID!){inventoryItem(id:${'$'}id){inventoryLevel(locationId:${'$'}location){quantities(names:[\"available\"]){name quantity}}}}"
   "stage"->"mutation SnapIMSStage(${'$'}input:[StagedUploadInput!]!){stagedUploadsCreate(input:${'$'}input){stagedTargets{url resourceUrl parameters{name value}} userErrors{field message}}}"
   "create"->"mutation SnapIMSCreate(${'$'}product:ProductCreateInput!){productCreate(product:${'$'}product){product{id status variants(first:1){nodes{id inventoryItem{id}}}} userErrors{field message}}}"
   "variant"->"mutation SnapIMSVariant(${'$'}id:ID!,${'$'}variants:[ProductVariantsBulkInput!]!){productVariantsBulkUpdate(productId:${'$'}id,variants:${'$'}variants){productVariants{id} userErrors{field message}}}"
   "inventory_item"->"mutation SnapIMSItem(${'$'}id:ID!,${'$'}input:InventoryItemInput!){inventoryItemUpdate(id:${'$'}id,input:${'$'}input){inventoryItem{id} userErrors{field message}}}"
   "stock_activate"->"mutation SnapIMSActivate(${'$'}id:ID!,${'$'}location:ID!,${'$'}available:Int!,${'$'}key:String!){inventoryActivate(inventoryItemId:${'$'}id,locationId:${'$'}location,available:${'$'}available) @idempotent(key:${'$'}key){inventoryLevel{id} userErrors{field message}}}"
   "stock_set"->"mutation SnapIMSQuantity(${'$'}input:InventorySetQuantitiesInput!,${'$'}key:String!){inventorySetQuantities(input:${'$'}input) @idempotent(key:${'$'}key){inventoryAdjustmentGroup{createdAt} userErrors{field message code}}}"
   "file_create"->"mutation SnapIMSFile(${'$'}input:[FileCreateInput!]!){fileCreate(files:${'$'}input){files{id alt fileStatus} userErrors{field message}}}"
   "files_find"->"query SnapIMSFiles(${'$'}query:String!){files(first:100,query:${'$'}query){nodes{id alt fileStatus} pageInfo{hasNextPage}}}"
   "file_status"->"query SnapIMSFileState(${'$'}id:ID!){node(id:${'$'}id){...on MediaImage{id alt fileStatus}}}"
   "attach"->"mutation SnapIMSAttach(${'$'}files:[FileUpdateInput!]!){fileUpdate(files:${'$'}files){files{id fileStatus} userErrors{field message}}}"
   "reorder"->"mutation SnapIMSOrder(${'$'}id:ID!,${'$'}moves:[MoveInput!]!){productReorderMedia(id:${'$'}id,moves:${'$'}moves){job{id done} userErrors{field message}}}"
   "remote_job"->"query SnapIMSRemoteJob(${'$'}id:ID!){job(id:${'$'}id){id done}}"
   "activate_product"->"mutation SnapIMSLive(${'$'}product:ProductUpdateInput!){productUpdate(product:${'$'}product){product{id status} userErrors{field message}}}"
   "publish"->"mutation SnapIMSPublish(${'$'}id:ID!,${'$'}input:[PublicationInput!]!){publishablePublish(id:${'$'}id,input:${'$'}input){publishable{availablePublicationsCount{count}} userErrors{field message}}}"
   "publication_status"->"query SnapIMSPublished(${'$'}id:ID!,${'$'}publication:ID!){product(id:${'$'}id){id status publishedOnPublication(publicationId:${'$'}publication)}}"
   "metadata_update"->"mutation SnapIMSMetadata(${'$'}product:ProductUpdateInput!){productUpdate(product:${'$'}product){product{id status updatedAt} userErrors{field message}}}"
   else->error("Unsupported operation")
  }
  val field=when(kind) {
   "find"->""
   "quantity"->"inventoryItem"
   "stage"->"stagedUploadsCreate"
   "create"->"productCreate"
   "variant"->"productVariantsBulkUpdate"
   "inventory_item"->"inventoryItemUpdate"
   "stock_activate"->"inventoryActivate"
   "stock_set"->"inventorySetQuantities"
   "file_create"->"fileCreate"
   "files_find"->"files"
   "file_status"->"node"
   "attach"->"fileUpdate"
   "reorder"->"productReorderMedia"
   "remote_job"->"job"
   "activate_product"->"productUpdate"
   "publish"->"publishablePublish"
   "publication_status"->"product"
   "metadata_update"->"productUpdate"
   else->""
  }
  return DirectOperation(query,variables,field,write,kind in setOf("create","file_create","metadata_update"))
 }
}
