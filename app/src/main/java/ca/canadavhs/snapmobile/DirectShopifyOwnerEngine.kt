package ca.canadavhs.snapmobile
import org.json.*
import ca.canadavhs.snapmobile.core.*

/** Called only with an APPLIED, one-use, store-bound native approval receipt. */
internal suspend fun DirectShopifyGateway.ownerAction(repo:CaptureRepository,id:String,args:JSONObject,entry:JSONObject?,persist:suspend(JSONObject)->Unit):JSONObject {
 require(args.keys().asSequence().toSet()==setOf("reviewId","jobId"));val review=repo.journal.shopifyDao().review(requireUuid(args.getString("reviewId"))) ?: error("Approval missing");val job=repo.journal.shopifyDao().job(requireUuid(args.getString("jobId"))) ?: error("Job missing")
 check(id==operationKey("owner:${review.id}") && review.kind=="OWNER_ACTION" && review.state=="APPLIED" && JSONObject(review.resultJson).getString("jobId")==job.id && job.state in listOf("RUNNING","WAITING_REMOTE")) {"Status operation requires its exact approved running job."}
 check(System.currentTimeMillis()-review.createdAt<24*60*60*1000L);val p=JSONObject(review.payloadJson);check(sha256(canonicalJson(p).toByteArray())==review.bindingHash)
 check(endpoint()==job.broker && fingerprint()==job.connectionFingerprint && p.getString("connectionFingerprint")==job.connectionFingerprint)
 val health=healthForWrite();check(health.getString("shopId")==job.shopId);val action=p.getString("action");if(action!="APPROVE_LOCAL") repo.requireShopifyWriteHealth(health)
 if(action in listOf("ACTIVE","PUBLISH_LIVE")) check(health.optBoolean("allowPublish")) {"Live publication controls or scopes are disabled."}
 if(p.has("localGroupId")) {val item=repo.journal.inventoryDao().items().firstOrNull {it.groupId==p.getString("localGroupId") && !it.archived} ?: error("Selected local copy was deleted or archived; no further publication authorized");check(item.revision==p.getLong("localRevision") && item.shopifyProductId==p.getJSONObject("before").getString("id")) {"Selected local copy changed after approval; review again"}}
 val before=p.getJSONObject("before");val product=before.getString("id");val readArgs=JSONObject().put("productId",product).put("locationId",p.getString("location"));var now=call(newId(),"product",readArgs)
 val stage=entry ?: JSONObject().put("phase","PREPARED");if(stage.optString("phase")=="COMPLETE") return stage.getJSONObject("result")
 if(stage.getString("phase")=="PREPARED") {check(now.getString("reviewVersion")==before.getString("reviewVersion")) {"Shopify changed after review; no status mutation was sent."};stage.put("phase","SENT");persist(stage)}
 val target=if(action=="PUBLISH_LIVE") "ACTIVE" else action
 val ignored=JSONObject().put("status",target)
 check(protectedProduct(now,ignored)==protectedProduct(before,ignored)) {"Unapproved Shopify fields changed. Inspect the product before resuming."}
 if(action=="APPROVE_LOCAL") {val result=JSONObject().put("productId",product).put("state","APPROVED_LOCALLY").put("verifiedAt",System.currentTimeMillis()).put("reviewVersion",now.getString("reviewVersion"));stage.put("phase","COMPLETE").put("result",result);persist(stage);return result}
 if(now.getString("status")!=target) {check(now.getString("status")==before.getString("status")) {"Remote status conflicts with the reviewed transition."}
  val result=graphql("mutation SnapIMSOwnerStatus(${'$'}product:ProductUpdateInput!){productUpdate(product:${'$'}product){product{id status} userErrors{message}}}",JSONObject().put("product",JSONObject().put("id",product).put("status",target))).getJSONObject("productUpdate")
  val errors=result.getJSONArray("userErrors");check(errors.length()==0) {safe(errors.toString())}
 }
 var published=false;val outcomes=JSONArray()
 if(action=="PUBLISH_LIVE") {
  val selected=p.optJSONArray("publications") ?: JSONArray().put(p.getString("publication"));check(selected.length() in 1..50)
  val available=health.getJSONArray("publications").let {a->(0 until a.length()).associate {a.getJSONObject(it).getString("id") to a.getJSONObject(it).getString("name")}}
  val missing=JSONArray()
  suspend fun isPublished(publication:String):Boolean {val op=DirectShopifyOperations.prepare("publication_status",JSONObject().put("creationKey",review.id).put("productId",product).put("publicationId",publication));return graphql(op.query,op.variables).getJSONObject("product").getBoolean("publishedOnPublication")}
  for(i in 0 until selected.length()) {val publication=selected.getString(i);check(publication in available) {"Selected publication no longer available: $publication"};if(!isPublished(publication)) missing.put(JSONObject().put("publicationId",publication))}
  var error=""
  if(missing.length()>0) {
   val query="mutation SnapIMSPublish(${'$'}id:ID!,${'$'}input:[PublicationInput!]!){publishablePublish(id:${'$'}id,input:${'$'}input){userErrors{field message}}}"
   val result=graphql(query,JSONObject().put("id",product).put("input",missing)).getJSONObject("publishablePublish");error=safe(result.getJSONArray("userErrors").toString()).takeIf {it!="[]"}.orEmpty()
  }
  for(i in 0 until selected.length()) {val publication=selected.getString(i);val verified=isPublished(publication);outcomes.put(JSONObject().put("publicationId",publication).put("name",available.getValue(publication)).put("published",verified).put("error",if(verified) "" else error.ifBlank {"Not confirmed published"}))}
  published=(0 until outcomes.length()).all {outcomes.getJSONObject(it).getBoolean("published")}
  stage.put("channelOutcomes",outcomes);persist(stage)
  check(published) {"Publication incomplete. "+outcomes.toString()+". Resume the same job to reconcile only unconfirmed channels."}
 }
 now=call(newId(),"product",readArgs);check(now.getString("status")==target && protectedProduct(now,ignored)==protectedProduct(before,ignored)) {"Remote outcome differs from the reviewed transition."}
 val result=JSONObject().put("productId",product).put("status",target).put("verifiedAt",System.currentTimeMillis()).put("publicationChecked",action=="PUBLISH_LIVE").put("publishedToSelectedChannel",if(action=="PUBLISH_LIVE") published else JSONObject.NULL).put("publication",p.optString("publication")).put("channelOutcomes",outcomes);stage.put("phase","COMPLETE").put("result",result);persist(stage);return result
}
