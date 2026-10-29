package ca.canadavhs.snapmobile

import androidx.work.WorkManager
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.util.UUID

internal data class AgentDecisionOptions(val replaceManual:Boolean=false,val allowHistorical:Boolean=false,val allowPaidRecognition:Boolean=false,
    val rate:String="",val rateDate:String="",val adjustedCents:Long?=null,val allowFinancial:Boolean=false,val allowDestructive:Boolean=false,val confirmation:String="")
internal fun jsonIds(value:JSONArray,max:Int=50):List<String> {
    require(value.length() in 1..max) {"Select between 1 and $max items."}
    return (0 until value.length()).map {requireUuid(value.getString(it))}.also {require(it.distinct().size==it.size) {"Each physical copy may appear only once."}}
}
internal fun marketRecommendationJson(r:MarketRecommendation):JSONObject=JSONObject().put("groupId",r.groupId).put("revision",r.revision).put("titleKey",r.titleKey).put("edition",r.edition).put("year",r.year ?: JSONObject.NULL)
    .put("currency",r.currency).put("averageCents",r.stats.averageCents).put("medianCents",r.stats.medianCents).put("lowestCents",r.stats.lowestCents).put("highestCents",r.stats.highestCents).put("count",r.stats.count)
    .put("ids",JSONArray(r.ids)).put("oldest",r.oldest).put("newest",r.newest).put("hash",r.hash)
internal fun marketRecommendationFromJson(j:JSONObject):MarketRecommendation {
    val ids=jsonIds(j.getJSONArray("ids"),5000);val currency=j.getString("currency");require(currency.matches(Regex("[A-Z]{3}")))
    return MarketRecommendation(requireUuid(j.getString("groupId")),j.getLong("revision"),j.getString("titleKey"),j.getString("edition"),if(j.isNull("year")) null else j.getInt("year"),currency,
        SoldStatistics(j.getLong("averageCents"),j.getLong("medianCents"),j.getLong("lowestCents"),j.getLong("highestCents"),j.getInt("count")),ids,j.getString("oldest"),j.getString("newest"),j.getString("hash"))
}
internal fun taskGroups(row:AgentTaskRow):List<String> = JSONArray(row.groupIdsJson).let {if(it.length()==0) emptyList() else jsonIds(it)}
internal data class AgentTaskStatus(val state:String,val done:Int,val total:Int,val detail:String,val jobs:JSONArray=JSONArray())
internal suspend fun CaptureRepository.agentTaskStatus(row:AgentTaskRow):AgentTaskStatus {
    val groups=taskGroups(row);val refs=JSONArray(row.jobIdsJson)
    if(row.kind=="INVENTORY_WORKFLOW" && refs.length()>0) {val base=agentTaskStatus(row.copy(kind="RECOGNITION"));return base.copy(done=base.done+groups.size-refs.length(),total=groups.size,detail="Local reviewed changes committed for ${groups.size} copies. "+base.detail)}
    if(row.kind=="SHOPIFY_MANAGEMENT" && refs.length()>0) return agentTaskStatus(row.copy(kind="SHOPIFY_DRAFT"))
    if(row.kind=="BATCH_OPERATION" && JSONObject(row.resultJson).optString("operation")=="FINISH" && row.state !in listOf("SCHEDULE_PENDING","CANCEL_PENDING","CANCELLED","PAUSED_REVIEW")) {val jobs=dao.archives(JSONObject(row.resultJson).getString("sessionId"));val done=jobs.count {it.state=="READY"};return AgentTaskStatus(if(jobs.all {it.state=="READY"}) "COMPLETE" else if(jobs.any {it.state=="ERROR"}) "NEEDS_REVIEW" else "RUNNING",done,jobs.size,"Batch finished; $done/${jobs.size} local archives verified. Originals retained.")}
    if(row.kind !in listOf("RECOGNITION","SHOPIFY_DRAFT") && row.state in listOf("SCHEDULE_PENDING","CANCEL_PENDING","CANCELLED","PAUSED_REVIEW","COMPLETE")) return AgentTaskStatus(row.state,if(row.state=="COMPLETE") groups.size else 0,groups.size,row.error.ifBlank {if(row.state=="PAUSED_REVIEW") "Restored task needs fresh review; no external work restarted." else ""})
    if(row.kind=="RECOGNITION") {
        val jobs=(0 until refs.length()).mapNotNull {dao.recognition(refs.getString(it))}
        val currentResults=jobs.filter {it.inputFingerprint==photoFingerprint(dao.photos(it.groupId).filter {p->!p.deleted && p.state=="READY"})}.map {it.id}.toSet()
        val done=jobs.count {it.state=="SUCCEEDED"};val failed=jobs.count {it.state in listOf("ERROR","CANCELLED","STALE")}
        val state=if(row.state in listOf("SCHEDULE_PENDING","CANCEL_PENDING","CANCELLED","PAUSED_REVIEW")) row.state else when {jobs.size!=refs.length()->"NEEDS_REVIEW";done==jobs.size && currentResults.size!=jobs.size->"NEEDS_REVIEW";done==jobs.size && jobs.all {!it.acceptedTitle.isNullOrBlank()}->"COMPLETE";done==jobs.size->"NEEDS_TITLE_REVIEW";failed>0 && jobs.none {it.state in listOf("RUNNING","QUEUED")}->"PARTIAL_REVIEW";else->"RUNNING"}
        return AgentTaskStatus(state,done,refs.length(),"$done recognition results complete. ${if(currentResults.size!=jobs.size) "Photographs changed; review and request fresh recognition. " else ""}Printed titles need operator confirmation before inventory changes.",JSONArray(jobs.map {r->JSONObject().put("id",r.id).put("groupId",r.groupId).put("state",r.state).put("provider",r.provider).put("model",r.model).put("photosCurrent",r.id in currentResults).put("confirmedTitle",r.acceptedTitle ?: JSONObject.NULL).put("error",r.error)}))
    }
    if(row.kind=="SHOPIFY_DRAFT") {
        val job=journal.shopifyDao().job(refs.getString(0)) ?: return AgentTaskStatus("NEEDS_REVIEW",0,groups.size,"Draft job is missing; review saved IDs before retrying.")
        if(job.kind=="OWNER_ACTION") {val receipt=ownerReviewForJob(job.id);val result=JSONObject(receipt.resultJson);val state=if(row.state in listOf("SCHEDULE_PENDING","CANCEL_PENDING","CANCELLED","PAUSED_REVIEW")) row.state else if(job.state=="SUCCESS") "COMPLETE" else job.state;return AgentTaskStatus(state,if(job.state=="SUCCESS") 1 else 0,1,job.error.ifBlank {if(job.state=="SUCCESS") "Verified owner-selected Shopify outcome; publication is checked only when explicitly requested." else "Awaiting verified remote outcome."},JSONArray().put(JSONObject().put("jobId",job.id).put("state",job.state).put("outcome",result.optJSONObject("outcome") ?: JSONObject.NULL)))}
        val items=journal.shopifyDao().items(job.id)
        return AgentTaskStatus(if(row.state in listOf("SCHEDULE_PENDING","CANCEL_PENDING","CANCELLED","PAUSED_REVIEW")) row.state else if(job.state=="SUCCESS") "COMPLETE" else job.state,items.count {it.state=="SUCCESS"},items.size,job.error,JSONArray(items.map {r->JSONObject().put("itemId",r.itemId).put("state",r.state).put("step",r.step).put("remote",JSONObject(r.remoteJson)).put("error",r.error)}))
    }
    if(row.kind=="REVIEW_BATCH") {
        val ready=groups.count {id->val item=journal.inventoryDao().item(id);item!=null && !item.archived && item.ready && inventoryProblems(item,dao.photos(id),this).isEmpty()}
        return AgentTaskStatus("OPEN",ready,groups.size,"$ready of ${groups.size} physical copies are ready. Review each item; readiness never publishes it.")
    }
    return AgentTaskStatus("NEEDS_REVIEW",0,groups.size,"Unknown task type. No operation was started.")
}
internal suspend fun CaptureRepository.scheduleAgentTask(id:String) {
    val task=journal.agentTaskDao().get(id) ?: return
    if(task.state!="SCHEDULE_PENDING") return
    try {
        val refs=JSONArray(task.jobIdsJson)
        val result=JSONObject(task.resultJson)
        if(task.kind in listOf("SETTINGS_CHANGE","TASK_CONTROL","BATCH_OPERATION","SHOPIFY_MANAGEMENT")) {
            when(task.kind) {
                "SETTINGS_CHANGE"->{val patch=result.getJSONObject("patch");val before=result.getJSONObject("before");val current=AgentApplicationActions.publicSettings(this)
                    patch.keys().forEach {key->check(current.get(key)==before.get(key) || current.get(key)==patch.get(key)) {"Settings changed after review; prepare a fresh proposal."}}
                    val edit=preferences.edit();patch.keys().forEach {key->when(val value=patch.get(key)) {is Boolean->edit.putBoolean(key,value);is Number->edit.putInt(key,value.toInt());else->edit.putString(key,value.toString())}};check(edit.commit()) {"Could not persist settings."}
                    if(patch.has("recognitionMode")) setRecognitionMode(patch.getString("recognitionMode"))}
                "TASK_CONTROL"->{val target=result.getString("targetTaskId");val old=journal.agentTaskDao().get(target) ?: error("Task missing");if(result.getString("operation")=="CANCEL") {if(old.state!="CANCELLED") cancelAgentTask(target)} else if(old.state!="COMPLETE") completeReviewTask(target)}
                "BATCH_OPERATION"->{if(result.getString("operation")=="FINISH") queueArchives(result.getString("sessionId"))}
                "SHOPIFY_MANAGEMENT"->{val reviewId=result.getString("nativeReviewId");var review=journal.shopifyDao().review(reviewId) ?: error("Native review missing");if(review.state=="PENDING") {decideShopifyReview(reviewId,true,result.optString("confirmation"));review=journal.shopifyDao().review(reviewId)!!};check(review.state=="APPLIED") {"Native Shopify review is no longer approved."};val job=JSONObject(review.resultJson).getString("jobId");refs.put(job)
                    if(journal.agentTaskDao().get(id)?.state!="SCHEDULE_PENDING") {cancelShopify(job);return}
                }
            }
            mutate {val current=journal.agentTaskDao().get(id);if(current?.state=="SCHEDULE_PENDING") journal.agentTaskDao().update(current.copy(jobIdsJson=refs.toString(),state=if(task.kind in listOf("SHOPIFY_MANAGEMENT","BATCH_OPERATION")) "SCHEDULED" else "COMPLETE",error="",updatedAt=System.currentTimeMillis()))};return
        }
        for(i in 0 until refs.length()) {
            currentCoroutineContext().ensureActive();if(journal.agentTaskDao().get(id)?.state!="SCHEDULE_PENDING") return
            when(task.kind) {
                "RECOGNITION","INVENTORY_WORKFLOW"->{val row=dao.recognition(refs.getString(i)) ?: error("Recognition job missing.");scheduleRecognition(row)}
                "SHOPIFY_DRAFT"->enqueueShopify(refs.getString(i)).result.get()
                else->error("This task has no background scheduler.")
            }
        }
        mutate {val current=journal.agentTaskDao().get(id);if(current?.state=="SCHEDULE_PENDING") journal.agentTaskDao().update(current.copy(state="SCHEDULED",error="",updatedAt=System.currentTimeMillis()))}
    } catch(e:CancellationException) {throw e} catch(e:Exception) {
        mutate {val current=journal.agentTaskDao().get(id);if(current?.state=="SCHEDULE_PENDING") journal.agentTaskDao().update(current.copy(error="Scheduling interrupted: ${e.message.orEmpty().take(500)}. Retry scheduling in Tasks; existing jobs are reused.",updatedAt=System.currentTimeMillis()))}
    }
}
internal suspend fun CaptureRepository.recoverAgentTasks() {
    journal.agentTaskDao().unsettled().filter {it.state=="CANCEL_PENDING"}.forEach {cancelAgentTask(it.id)}
    journal.agentTaskDao().pending().forEach {scheduleAgentTask(it.id)}
}
internal suspend fun CaptureRepository.cancelAgentTask(id:String) {
    val task=mutate {val row=journal.agentTaskDao().get(id) ?: error("Task missing");check(row.state!="COMPLETE") {"Completed history cannot be cancelled."};val saved=row.copy(state="CANCEL_PENDING",error="",updatedAt=System.currentTimeMillis());journal.agentTaskDao().update(saved)
        if(row.kind in listOf("RECOGNITION","INVENTORY_WORKFLOW")) {val refs=JSONArray(row.jobIdsJson);for(i in 0 until refs.length()) dao.recognition(refs.getString(i))?.takeIf {it.state in listOf("RUNNING","QUEUED")}?.let {dao.update(it.copy(state="CANCELLED",error="Cancelled from Tasks; original photographs retained.",completedAt=System.currentTimeMillis()))}}
        saved}
    val refs=JSONArray(task.jobIdsJson)
    when(task.kind) {
        "RECOGNITION","INVENTORY_WORKFLOW"->for(i in 0 until refs.length()) dao.recognition(refs.getString(i))?.workId?.takeIf {it.isNotBlank()}?.let {WorkManager.getInstance(context).cancelWorkById(UUID.fromString(it))}
        "SHOPIFY_DRAFT","SHOPIFY_MANAGEMENT"->for(i in 0 until refs.length()) cancelShopify(refs.getString(i))
    }
    if(task.kind=="SHOPIFY_MANAGEMENT" && refs.length()==0) {
        val review=journal.shopifyDao().review(JSONObject(task.resultJson).getString("nativeReviewId"))
        if(review?.state=="PENDING") decideShopifyReview(review.id,false)
        else if(review?.state=="APPLIED") cancelShopify(JSONObject(review.resultJson).getString("jobId"))
    }
    mutate {journal.agentTaskDao().get(id)?.let {journal.agentTaskDao().update(it.copy(state="CANCELLED",error="Remaining local work cancelled. Already-sent API requests and existing remote drafts are retained.",updatedAt=System.currentTimeMillis()))}}
}
internal suspend fun CaptureRepository.completeReviewTask(id:String)=mutate {
    val task=journal.agentTaskDao().get(id) ?: error("Task missing");check(task.kind=="REVIEW_BATCH" && task.state=="OPEN")
    val status=agentTaskStatus(task);check(status.done==status.total) {"Review and mark every selected copy ready in Inventory first."}
    journal.agentTaskDao().update(task.copy(state="COMPLETE",updatedAt=System.currentTimeMillis()))
}

internal suspend fun CaptureRepository.createLocalReviewTask(groups:List<String>):String=mutate {createLocalReviewTaskLocked(groups)}
internal suspend fun CaptureRepository.createLocalReviewTaskLocked(groups:List<String>,title:String="Review ${groups.size} physical copies",approvalId:String=newId(),conversationId:String="00000000-0000-0000-0000-000000000000"):String=run {
    jsonIds(JSONArray(groups));groups.forEach {check(journal.inventoryDao().item(it)?.archived==false) {"Choose active saved copies."}}
    val task=AgentTaskRow(approvalId=approvalId,conversationId=conversationId,kind="REVIEW_BATCH",title=title,groupIdsJson=JSONArray(groups).toString(),state="OPEN",resultJson=JSONObject().put("createdBy","OPERATOR_UI").toString())
    journal.agentTaskDao().insert(task);task.id
}
