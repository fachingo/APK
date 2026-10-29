package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*
import java.math.BigDecimal
internal val directShopifyFields=shopifyMetadataFields+listOf("tags","priceCents","weightGrams","quantity","sku")
internal fun directShopifyMetadata(item:InventoryRow,shopId:String)=shopifyMetadata(item).put("tags",JSONArray((shopifyTags(item)+"snapims-${operationKey("snapims-create:$shopId:${item.itemId}")}").distinct())).put("priceCents",item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)} ?: JSONObject.NULL).put("weightGrams",item.weightGrams).put("quantity",item.quantity).put("sku",item.itemId)
internal fun directProductFields(product:JSONObject):JSONObject {
 val variants=product.getJSONObject("variants").getJSONArray("nodes")
 if(variants.length()==1) {val v=variants.getJSONObject(0);val item=v.getJSONObject("inventoryItem");val weight=item.optJSONObject("measurement")?.optJSONObject("weight");val multiplier=when(weight?.optString("unit")) {"GRAMS"->"1";"KILOGRAMS"->"1000";"POUNDS"->"453.59237";"OUNCES"->"28.349523125";else->null}
  product.put("priceCents",BigDecimal(v.getString("price")).movePointRight(2).longValueExact()).put("sku",v.optString("sku")).put("weightGrams",if(multiplier!=null) BigDecimal(weight!!.getString("value")).multiply(BigDecimal(multiplier)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact() else JSONObject.NULL)
  product.put("quantity",item.optJSONObject("inventoryLevel")?.optJSONArray("quantities")?.optJSONObject(0)?.opt("quantity") ?: JSONObject.NULL)
 }
 return product
}
internal fun shopifyReviewNeedsSubmit(review:ShopifyReviewRow):Boolean {
 val p=JSONObject(review.payloadJson);val patch=p.optJSONObject("patch")
 return review.kind=="PUBLISH_LIVE" || (review.kind=="OWNER_ACTION" && p.optString("action")!="APPROVE_LOCAL") || (review.kind=="UPDATE_METADATA" && (p.optJSONObject("before")?.optString("status")=="ACTIVE" || patch?.keys()?.asSequence()?.any {it in listOf("priceCents","quantity","weightGrams")}==true))
}

internal suspend fun applyOwnedFields(gateway:ShopifyGateway,repo:CaptureRepository,job:ShopifyJobRow,row:ShopifyItemJobRow,args:JSONObject,patch:JSONObject,cp:JSONObject,save:suspend(String)->Unit) {
 val snapshot=JSONObject(row.snapshotJson);val before=snapshot.getJSONObject("before");val variant=before.getJSONObject("variants").getJSONArray("nodes").getJSONObject(0)
 suspend fun localCurrent() {val item=repo.journal.inventoryDao().item(row.groupId) ?: error("Item missing");check(item.revision==row.expectedRevision && shopifyFingerprint(item,repo.dao.photos(row.groupId).filter {!it.deleted}.sortedBy {it.position})==row.inputFingerprint) {"Local copy changed after approval."};check(repo.journal.shopifyDao().job(job.id)?.state in listOf("RUNNING","QUEUED","WAITING_REMOTE")) {"Job paused or cancelled."}}
 suspend fun read():JSONObject {localCurrent();val p=gateway.call(newId(),"product",JSONObject().put("productId",before.getString("id")).put("locationId",job.locationId));managementIdentity(p,repo.journal.inventoryDao().item(row.groupId)!!,job.shopId,true);return p}
 suspend fun send(name:String,kind:String,values:JSONObject) {if(!cp.optBoolean(name)) {localCurrent();gateway.call(operationKey("${row.id}:$name"),kind,values);cp.put(name,true);save(name)}}
 suspend fun already(name:String) {cp.put(name,true);save(name)}
 val metadata=JSONObject();patch.keys().asSequence().filter {it in shopifyMetadataFields || it=="tags"}.forEach {metadata.put(it,patch.get(it))}
 if(metadata.length()>0) send("OWNED_METADATA","metadata_update",JSONObject(args.toString()).put("patch",metadata))
 val key=row.creationKey;val product=before.getString("id");val inventory=variant.getJSONObject("inventoryItem").getString("id")
 if(patch.has("priceCents") && !cp.optBoolean("OWNED_PRICE")) {
  val now=read();val currentVariant=now.getJSONObject("variants").getJSONArray("nodes").getJSONObject(0);check(currentVariant.getBoolean("taxable")==variant.getBoolean("taxable")) {"Unselected tax settings changed after review."}
  if(now.getLong("priceCents")==patch.getLong("priceCents")) already("OWNED_PRICE") else {check(now.getLong("priceCents")==before.getLong("priceCents")) {"Remote price changed after review; no price overwrite was sent."};send("OWNED_PRICE","variant",JSONObject().put("creationKey",key).put("productId",product).put("variantId",variant.getString("id")).put("priceCents",patch.getLong("priceCents")).put("taxable",variant.getBoolean("taxable")))}
 }
 if((patch.has("sku") || patch.has("weightGrams")) && !cp.optBoolean("OWNED_INVENTORY")) {
  check(!patch.has("sku") || patch.getString("sku")==row.itemId) {"Immutable SKU cannot change."};val now=read();val current=now.getJSONObject("variants").getJSONArray("nodes").getJSONObject(0).getJSONObject("inventoryItem");val old=variant.getJSONObject("inventoryItem");check(current.getBoolean("tracked")==old.getBoolean("tracked") && current.getBoolean("requiresShipping")==old.getBoolean("requiresShipping")) {"Unselected tracking/shipping settings changed."};val weight=patch.optInt("weightGrams",before.getInt("weightGrams"))
  if(now.getInt("weightGrams")==weight) already("OWNED_INVENTORY") else {check(now.getInt("weightGrams")==before.getInt("weightGrams")) {"Remote weight changed after review."};send("OWNED_INVENTORY","inventory_item",JSONObject().put("creationKey",key).put("inventoryItemId",inventory).put("itemId",row.itemId).put("weightGrams",weight).put("tracked",old.getBoolean("tracked")).put("requiresShipping",old.getBoolean("requiresShipping")))}
 }
 if(patch.has("quantity") && !cp.optBoolean("OWNED_STOCK")) {
  check(patch.getInt("quantity") in 0..1);val now=read();if(now.opt("quantity")==patch.get("quantity")) already("OWNED_STOCK") else {
   check(canonicalJson(now.opt("quantity"))==canonicalJson(before.opt("quantity"))) {"Remote stock changed after review; prepare a fresh stock approval."};val level=variant.getJSONObject("inventoryItem").optJSONObject("inventoryLevel");val values=JSONObject().put("creationKey",key).put("inventoryItemId",inventory).put("locationId",job.locationId).put("quantity",patch.getInt("quantity")).put("idempotencyKey",operationKey("${row.id}:OWNED_STOCK"));if(level!=null) values.put("changeFromQuantity",level.getJSONArray("quantities").getJSONObject(0).getInt("quantity"));send("OWNED_STOCK",if(level==null) "stock_activate" else "stock_set",values)
  }
 }
}
