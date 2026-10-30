package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*
import kotlinx.coroutines.*

/** A single reviewed authorization binds every product snapshot and all selected channel IDs. */
internal suspend fun CaptureRepository.prepareShopifyOwnerBatch(ids:Set<String>,location:String,channels:Set<String>,health:JSONObject,gateway:ShopifyGateway):ShopifyReviewRow {
 require(ids.size in 1..100 && channels.isNotEmpty());requireShopifyWriteHealth(health);check(health.optBoolean("allowPublish"));check(directShopifyEndpoint(shopifyEndpoint()))
 val children=JSONArray();val selected=journal.inventoryDao().items().filter {it.groupId in ids && !it.archived};check(selected.size==ids.size);check(selected.map {it.shopifyProductId}.distinct().size==selected.size) {"Multiple local copies point at the same Shopify product; reconcile first"}
 for(item in selected) {check(item.shopifyProductId.isNotBlank()) {"${item.itemId}: create and verify a draft first"}
  val payload=shopifyOwnerPayload(item.shopifyProductId,location,"PUBLISH_LIVE",channels.sorted().first(),health,gateway,channels)
  managementIdentity(payload.getJSONObject("before"),item,health.getString("shopId"),true)
  children.put(JSONObject().put("groupId",item.groupId).put("revision",item.revision).put("payload",payload))
 }
 return mutate {val payload=JSONObject().put("batchReviews",children).put("group_ids",JSONArray(ids.sorted())).put("items",JSONArray(selected.map {inventoryJson(it)})).put("health",health).put("broker",shopifyEndpoint()).put("connectionFingerprint",shopifyConnectionFingerprint(context,shopifyEndpoint())).put("location",location).put("action","PUBLISH_LIVE").put("publications",JSONArray(channels.sorted()))
  ShopifyReviewRow(kind="OWNER_ACTION",payloadJson=payload.toString(),bindingHash=sha256(canonicalJson(payload).toByteArray())).also {journal.shopifyDao().insert(it)}
 }
}
internal suspend fun CaptureRepository.decideShopifyOwnerBatch(review:ShopifyReviewRow,accept:Boolean,confirmation:String):String {
 val jobs=mutableListOf<String>()
 val result=mutate {val dao=journal.shopifyDao();val latest=dao.review(review.id) ?: error("Review missing");check(latest.state=="PENDING");if(!accept) {dao.update(latest.copy(state="REJECTED",completedAt=System.currentTimeMillis()));return@mutate "Batch rejected; no Shopify changes queued"}
  check(confirmation=="SUBMIT") {"Type SUBMIT once to approve these products and channels"};val p=JSONObject(latest.payloadJson);check(sha256(canonicalJson(p).toByteArray())==latest.bindingHash);check(System.currentTimeMillis()-latest.createdAt<86400000)
  check(shopifyEndpoint()==p.getString("broker") && shopifyConnectionFingerprint(context,shopifyEndpoint())==p.getString("connectionFingerprint")) {"Connection changed; review again"}
  val visible=journal.inventoryDao().items().associateBy {it.groupId};val children=p.getJSONArray("batchReviews");require(children.length() in 1..100)
  for(i in 0 until children.length()) {val child=children.getJSONObject(i);val item=visible[child.getString("groupId")] ?: error("Deleted inventory record");check(!item.archived && item.revision==child.getLong("revision")) {"A selected copy changed; review again"}
   check(dao.unfinishedForGroup(item.groupId).isEmpty()) {"A selected copy has unfinished Shopify work"}
   val payload=JSONObject(child.getJSONObject("payload").toString()).put("localGroupId",item.groupId).put("localRevision",item.revision).put("group_ids",JSONArray().put(item.groupId));check(payload.getJSONObject("before").getString("id")==item.shopifyProductId);check(canonicalJson(payload.getJSONArray("publications"))==canonicalJson(p.getJSONArray("publications")))
   val receipt=ShopifyReviewRow(kind="OWNER_ACTION",payloadJson=payload.toString(),bindingHash=sha256(canonicalJson(payload).toByteArray()),state="APPLIED",createdAt=latest.createdAt,completedAt=System.currentTimeMillis())
   val health=payload.getJSONObject("health");val job=ShopifyJobRow(kind="OWNER_ACTION",shopId=health.getString("shopId"),shop=health.getString("shop"),broker=p.getString("broker"),connectionFingerprint=p.getString("connectionFingerprint"),locationId=p.getString("location"),publicationId=payload.getString("publication"))
   dao.insert(job);dao.insert(receipt.copy(resultJson=JSONObject().put("jobId",job.id).put("parentReviewId",review.id).put("status","QUEUED").toString()));jobs+=job.id
  }
  dao.update(latest.copy(state="APPLIED",completedAt=System.currentTimeMillis(),resultJson=JSONObject().put("jobIds",JSONArray(jobs)).put("status","QUEUED").toString()));"${jobs.size} publication jobs authorized once. Channel outcomes are verified separately; queued is not live."
 }
 jobs.forEach {enqueueShopify(it)};return result
}
