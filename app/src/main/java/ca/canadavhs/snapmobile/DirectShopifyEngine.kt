package ca.canadavhs.snapmobile

import android.content.Context
import android.util.AtomicFile
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.File

/** Durable, device-encrypted operation/resource ledger. Outside all backup/export roots. */
internal class DirectShopifyEngine(private val context:Context,private val gateway:DirectShopifyGateway,private val vault:ShopifyUploadVault=ShopifyUploadVault(context)) {
 companion object {private val lock=Mutex()}
 private fun file()=AtomicFile(File(context.noBackupFilesDir,"shopify-operations-${sha256(gateway.credentialIdentity().toByteArray())}.json"))
 private fun load():JSONObject {val f=file();return if(!f.baseFile.exists()) JSONObject().put("operations",JSONObject()).put("resources",JSONObject()).put("copies",JSONObject()) else vault.decrypt(gateway.credentialIdentity(),String(f.readFully(),Charsets.UTF_8))}
 private fun save(state:JSONObject) {val f=file();val out=f.startWrite();try {out.write(vault.encrypt(gateway.credentialIdentity(),state).toByteArray());f.finishWrite(out)} catch(e:Exception) {f.failWrite(out);throw e}}
 private fun uncertain(message:String):Nothing=throw ShopifyBrokerException("UNCERTAIN",message,409)
 private suspend fun execute(op:DirectOperation):JSONObject {
  val data=gateway.graphql(op.query,op.variables)
  val result=if(op.field.isBlank()) data else data.optJSONObject(op.field) ?: error("Shopify did not return a confirmed resource.")
  val errors=result.optJSONArray("userErrors")
  if(errors!=null && errors.length()>0) throw ShopifyBrokerException("SHOPIFY_REJECTED",gateway.safe((0 until errors.length()).joinToString("; ") {errors.getJSONObject(it).optString("message")}),422)
  result.remove("userErrors");return result
 }
 private suspend fun lookup(a:JSONObject)=execute(DirectShopifyOperations.prepare("find",JSONObject().put("itemId",a.getString("itemId")).put("creationKey",a.getString("creationKey"))))
 private fun nodes(value:JSONObject,key:String):List<JSONObject> {val v=value.getJSONObject(key);check(!v.getJSONObject("pageInfo").getBoolean("hasNextPage")) {"Remote search is incomplete; reconcile duplicate identities first."};val a=v.getJSONArray("nodes");return (0 until a.length()).map {a.getJSONObject(it)}}
 private fun bind(state:JSONObject,result:JSONObject,key:String,itemId:String="") {
  val resources=state.getJSONObject("resources")
  fun put(id:String) {val old=resources.optString(id);check(old.isBlank() || old==key) {"Resource is bound to another physical copy."};resources.put(id,key)}
  result.optJSONObject("product")?.let {p->put(p.getString("id"));val variants=p.optJSONObject("variants")?.optJSONArray("nodes") ?: JSONArray();for(i in 0 until variants.length()) {val v=variants.getJSONObject(i);put(v.getString("id"));put(v.getJSONObject("inventoryItem").getString("id"))};if(itemId.isNotBlank()) state.getJSONObject("copies").put(key,itemId)}
  result.optJSONArray("files")?.let {a->for(i in 0 until a.length()) put(a.getJSONObject(i).getString("id"))}
 }
 private suspend fun management(a:JSONObject,version:Boolean):JSONObject {
  val p=gateway.call(newId(),"product",JSONObject().put("productId",a.getString("productId")).put("locationId",a.getString("locationId")))
  check(!p.optBoolean("missing") && p.getString("status") in listOf("DRAFT","ACTIVE")) {"Only a current draft or active marked listing may be managed."}
  check(p.optJSONObject("metafield")?.optString("value")==a.getString("creationKey") && p.optJSONObject("identity")?.optString("value")==a.getString("itemId")) {"SnapIMS ownership markers do not match the reviewed physical copy."}
  val variants=nodes(p,"variants");check(variants.size==1 && variants.single().optString("sku")==a.getString("itemId")) {"Immutable SKU or variant changed."};nodes(p,"media")
  val found=lookup(a);check(nodes(found,"variants").filter {it.optString("sku")==a.getString("itemId")}.map {it.getJSONObject("product").getString("id")}.toSet()==setOf(p.getString("id"))) {"Exact SKU is missing or duplicated."}
  check(nodes(found,"products").filter {it.optJSONObject("metafield")?.optString("value")==a.getString("creationKey")}.map {it.getString("id")}.toSet()==setOf(p.getString("id"))) {"Ownership marker is duplicated or missing."}
  if(version) check(p.getString("reviewVersion")==a.getString("expectedVersion")) {"Shopify changed after review. Prepare a fresh approval."}
  return p
 }
 private suspend fun recover(kind:String,a:JSONObject,entry:JSONObject):JSONObject=when(kind) {
  "create"->{val found=lookup(a);val matches=nodes(found,"products").filter {it.optJSONObject("metafield")?.optString("value")==a.getString("creationKey") && it.optJSONObject("identity")?.optString("value")==a.getString("itemId") && it.getString("status")=="DRAFT"};val sku=nodes(found,"variants").filter {it.optString("sku")==a.getString("itemId")};if(matches.size!=1 || sku.any {it.getJSONObject("product").getString("id")!=matches.single().getString("id")}) uncertain("Creation remains uncertain. No second product was sent; inspect the saved SKU/marker in Shopify.");JSONObject().put("product",matches.single()).put("reconciled",true)}
  "file_create"->{val found=execute(DirectShopifyOperations.prepare("files_find",JSONObject().put("filename",a.getString("filename"))));check(!found.getJSONObject("pageInfo").getBoolean("hasNextPage"));val files=found.getJSONArray("nodes");val matches=(0 until files.length()).map {files.getJSONObject(it)}.filter {it.optString("alt")==a.getString("alt")};if(matches.size!=1) uncertain("Photo creation remains uncertain. No duplicate file was sent; retry reconciliation after Shopify finishes indexing.");JSONObject().put("files",JSONArray(matches)).put("reconciled",true)}
  "metadata_update"->{val p=management(a,false);val patch=a.getJSONObject("patch");if(patch.keys().asSequence().any {canonicalJson(p.opt(it))!=canonicalJson(patch.get(it))} || protectedProduct(p,patch)!=entry.getString("protected")) uncertain("Remote fields differ from the reviewed outcome; no metadata update was repeated.");JSONObject().put("product",p).put("reconciled",true)}
  else->error("Unsupported uncertain operation")
 }
 suspend fun call(id:String,kind:String,a:JSONObject):JSONObject=lock.withLock {
  requireUuid(id)
  if(kind=="owner_action") {
   val repo=(context.applicationContext as CaptureApplication).repository;val state=load();val ops=state.getJSONObject("operations");val hash=sha256(canonicalJson(a).toByteArray());val stored=ops.optJSONObject(id);if(stored!=null) check(stored.getString("hash")==hash)
   return@withLock gateway.ownerAction(repo,id,a,stored) {entry->entry.put("hash",hash);ops.put(id,entry);save(state)}
  }
  if(kind=="recover_file") {
   require(a.keys().asSequence().toSet()==setOf("pendingOperationId","creationKey","filename","alt"));val state=load();val old=state.getJSONObject("operations").optJSONObject(requireUuid(a.getString("pendingOperationId"))) ?: uncertain("The file operation journal is missing; inspect Shopify before retrying.")
   val original=old.getJSONObject("arguments");check(old.getString("kind")=="file_create" && listOf("creationKey","filename","alt").all {original.getString(it)==a.getString(it)})
   if(old.getString("state")=="COMPLETE") return@withLock old.getJSONObject("result")
   val result=recover("file_create",original,old);bind(state,result,a.getString("creationKey"));old.put("state","COMPLETE").put("result",result);save(state);return@withLock result
  }
  if(kind=="claim") {
   require(a.keys().asSequence().toSet()==setOf("creationKey","itemId","productId","locationId","expectedVersion"));requireUuid(a.getString("creationKey"))
   val p=management(a,true);val result=JSONObject().put("product",p);val state=load();bind(state,result,a.getString("creationKey"),a.getString("itemId"));save(state);return@withLock result
  }
  val op=DirectShopifyOperations.prepare(kind,a)
  if(!op.write) return@withLock execute(op)
  val health=gateway.healthForWrite();check(health.optBoolean("allowProduction")) {"Enable owner-operated writes on the phone before approving Shopify changes."};if(kind in listOf("publish","activate_product")) check(health.optBoolean("allowPublish")) {"Enable live publication controls and grant write_publications."};check(health.getString("currency")=="CAD") {"No implicit conversion: Shopify store currency must be CAD."}
  val scopes=health.getJSONArray("scopes").let {s->(0 until s.length()).map {s.getString(it)}}
  val required=when(kind) {"inventory_item","stock_activate","stock_set"->"write_inventory";"stage","file_create","attach"->"write_files";"publish"->"write_publications";else->"write_products"}
  check(required in scopes) {"Grant $required to your existing Shopify app and reconnect."}
  val state=load();val resources=state.getJSONObject("resources");val key=a.optString("creationKey")
  for(field in listOf("productId","variantId","inventoryItemId","fileId")) if(a.has(field)) check(resources.optString(a.getString(field))==key && key.isNotBlank()) {"Resource is not bound to this reviewed SnapIMS copy. Link or reconcile ownership first."}
  if(kind=="reorder") {val ids=a.getJSONArray("fileIds");for(i in 0 until ids.length()) check(resources.optString(ids.getString(i))==key)}
  if(kind=="file_create") {val item=state.getJSONObject("copies").optString(key);check(item.isNotBlank() && a.getString("alt").startsWith("snapims:$item:photo:")) {"Photo ownership does not match a created or linked physical copy."}}
  val hash=sha256(canonicalJson(JSONObject().put("kind",kind).put("arguments",a)).toByteArray());val operations=state.getJSONObject("operations");var entry=operations.optJSONObject(id)
  if(entry!=null) {
   check(entry.getString("hash")==hash && entry.getString("kind")==kind) {"Operation ID was reused with different approved inputs."}
   if(entry.getString("state")=="COMPLETE") return@withLock entry.getJSONObject("result")
   check(entry.getString("state")!="REJECTED") {"Shopify rejected this operation. Review the saved error and prepare a new approval."}
   if(op.uncertain && entry.getString("state")=="SENT") {val result=recover(kind,a,entry);bind(state,result,key,a.optString("itemId"));entry.put("state","COMPLETE").put("result",result);save(state);return@withLock result}
  } else {
   entry=JSONObject().put("kind",kind).put("hash",hash).put("arguments",a).put("state","PREPARED");operations.put(id,entry);save(state)
  }
  if(kind=="create") {
   val found=lookup(a);val marked=nodes(found,"products");val skus=nodes(found,"variants").filter {it.optString("sku")==a.getString("itemId")}
   if(marked.isNotEmpty() || skus.isNotEmpty()) {val result=recover(kind,a,entry);bind(state,result,key,a.getString("itemId"));entry.put("state","COMPLETE").put("result",result);save(state);return@withLock result}
  }
  if(kind=="metadata_update") {val p=management(a,true);entry.put("protected",protectedProduct(p,a.getJSONObject("patch")))}
  entry.put("state","SENT");save(state)
  try {
   val result=execute(op)
   if(kind=="stage") {val targets=result.getJSONArray("stagedTargets");check(targets.length()==1);requireShopifyUploadUrl(targets.getJSONObject(0).getString("url"));requireShopifyUploadUrl(targets.getJSONObject(0).getString("resourceUrl"))}
   bind(state,result,key,a.optString("itemId"));entry.put("state","COMPLETE").put("result",result);save(state);result
  } catch(e:ShopifyBrokerException) {if(e.httpStatus in listOf(400,401,403,422,429)) {entry.put("state",if(e.httpStatus==429) "PREPARED" else "REJECTED");save(state)};throw e}
 }
}
