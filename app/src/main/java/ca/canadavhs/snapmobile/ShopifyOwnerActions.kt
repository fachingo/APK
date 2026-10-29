package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import androidx.work.ListenableWorker

internal suspend fun CaptureRepository.prepareShopifyOwnerAction(productId:String,location:String,action:String,publication:String,health:JSONObject,gateway:ShopifyGateway):ShopifyReviewRow {
 require(action in setOf("APPROVE_LOCAL","DRAFT","ACTIVE","ARCHIVED","PUBLISH_LIVE"));check(preferences.getBoolean("shopifyDirect",false)) {"Connect directly to Shopify first."}
 if(action!="APPROVE_LOCAL") requireShopifyWriteHealth(health)
 if(action in listOf("ACTIVE","PUBLISH_LIVE")) check(health.optBoolean("allowPublish")) {"Enable live publication controls before activating or publishing."}
 if(action=="PUBLISH_LIVE") check(health.getJSONArray("publications").let {a->(0 until a.length()).any {a.getJSONObject(it).getString("id")==publication}}) {"Choose the Online Store publication you intend to make live."}
 require(location.matches(Regex("gid://shopify/Location/[0-9]{1,30}")))
 val before=gateway.call(newId(),"product",JSONObject().put("productId",productId).put("locationId",location));check(!before.optBoolean("missing"));check(before.getString("status") in listOf("DRAFT","ACTIVE","ARCHIVED"))
 return mutate {val payload=JSONObject().put("group_ids",JSONArray()).put("items",JSONArray()).put("health",health).put("broker",shopifyEndpoint()).put("connectionFingerprint",shopifyConnectionFingerprint(context,shopifyEndpoint())).put("location",location).put("publication",publication).put("action",action).put("before",before)
  ShopifyReviewRow(kind="OWNER_ACTION",payloadJson=payload.toString(),bindingHash=sha256(canonicalJson(payload).toByteArray())).also {journal.shopifyDao().insert(it)}
 }
}
internal suspend fun CaptureRepository.decideShopifyOwnerReview(review:ShopifyReviewRow,accept:Boolean,confirmation:String):String {
 var jobId=""
 val result=mutate {val dao=journal.shopifyDao();val latest=dao.review(review.id) ?: error("Review missing");check(latest.state=="PENDING");if(!accept) {dao.update(latest.copy(state="REJECTED",completedAt=System.currentTimeMillis()));return@mutate "Review rejected; Shopify unchanged."}
  val p=JSONObject(latest.payloadJson);check(sha256(canonicalJson(p).toByteArray())==latest.bindingHash);check(preferences.getBoolean("shopifyDirect",false) && shopifyEndpoint()==p.getString("broker") && shopifyConnectionFingerprint(context,p.getString("broker"))==p.getString("connectionFingerprint")) {"Connection changed. Prepare a new review."}
  check(p.getString("action")=="APPROVE_LOCAL" || confirmation=="SUBMIT") {"Type SUBMIT for status or publication changes."}
  val job=ShopifyJobRow(kind="OWNER_ACTION",shopId=p.getJSONObject("health").getString("shopId"),shop=p.getJSONObject("health").getString("shop"),broker=p.getString("broker"),connectionFingerprint=p.getString("connectionFingerprint"),locationId=p.getString("location"),publicationId=p.optString("publication"));dao.insert(job);jobId=job.id
  dao.update(latest.copy(state="APPLIED",completedAt=System.currentTimeMillis(),resultJson=JSONObject().put("jobId",job.id).put("status","QUEUED").toString()));"Approved status job saved. Check the verified result; queued does not mean published."
 }
 if(jobId.isNotBlank()) enqueueShopify(jobId);return result
}
internal suspend fun CaptureRepository.ownerReviewForJob(id:String)=journal.shopifyDao().ownerReviews().firstOrNull {JSONObject(it.resultJson).optString("jobId")==id} ?: error("Owner approval receipt missing.")
internal suspend fun CaptureRepository.runOwnerJob(job:ShopifyJobRow,gateway:ShopifyGateway):ListenableWorker.Result {
 val jobs=journal.shopifyDao();val review=ownerReviewForJob(job.id);val p=JSONObject(review.payloadJson)
 try {
  check(review.state=="APPLIED" && p.getString("connectionFingerprint")==job.connectionFingerprint) {"Owner review connection changed. Prepare fresh approval."}
  check(System.currentTimeMillis()-review.createdAt<24*60*60*1000L) {"Owner review expired after 24 hours; prepare a fresh status review."}
  mutate {check(jobs.job(job.id)?.state in listOf("QUEUED","RUNNING","WAITING_REMOTE"));jobs.update(job.copy(state="RUNNING"))}
  val result=gateway.call(operationKey("owner:${review.id}"),"owner_action",JSONObject().put("reviewId",review.id).put("jobId",job.id))
  mutate {val current=jobs.job(job.id)!!;if(current.state !in listOf("CANCELLED","PAUSED")) {
    jobs.update(current.copy(state="SUCCESS",error="",updatedAt=System.currentTimeMillis()))
    if(result.optString("state")!="APPROVED_LOCALLY") journal.inventoryDao().items().filter {it.shopifyProductId==result.getString("productId")}.forEach {old->
     val state=when {result.optBoolean("publicationChecked") && result.optBoolean("publishedToSelectedChannel")->"PUBLISHED";result.optString("status")=="DRAFT"->if(old.shopifyState=="UPLOADED_AS_DRAFT") old.shopifyState else "LINKED_DRAFT";result.optString("status")=="ARCHIVED"->"ARCHIVED_REMOTE";else->"ACTIVE"}
     if(old.shopifyState!=state) {val saved=old.copy(shopifyState=state,revision=old.revision+1,updatedAt=java.time.OffsetDateTime.now().toString());journal.inventoryDao().update(saved);inventoryAudit(old,saved,"SHOPIFY_OWNER_STATUS_VERIFIED")}
    }
   };val latest=jobs.review(review.id)!!;jobs.update(latest.copy(resultJson=JSONObject(latest.resultJson).put("outcome",result).put("status","VERIFIED").toString()))}
  return ListenableWorker.Result.success()
 } catch(e:CancellationException) {throw e} catch(e:Exception) {val retry=e is ShopifyBrokerException && (e.httpStatus>=500 || e.httpStatus==429)
  mutate {val latest=jobs.job(job.id)!!;if(latest.state !in listOf("CANCELLED","PAUSED")) jobs.update(latest.copy(state=if(retry) "WAITING_REMOTE" else "PARTIAL_REVIEW",error=e.message.orEmpty().take(1500),updatedAt=System.currentTimeMillis()))}
  return if(retry) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
 }
}
