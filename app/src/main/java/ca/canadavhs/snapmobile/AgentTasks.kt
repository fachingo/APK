package ca.canadavhs.snapmobile

import androidx.work.WorkManager
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.util.UUID

internal data class AgentDecisionOptions(val replaceManual:Boolean=false,val allowHistorical:Boolean=false,val allowPaidRecognition:Boolean=false,
    val rate:String="",val rateDate:String="",val adjustedCents:Long?=null)
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
internal data class AgentTaskStatus(val state:String,val done:Int,val total:Int,val detail:String,val jobs:JSONArray=JSONArray())
internal suspend fun CaptureRepository.agentTaskStatus(row:AgentTaskRow):AgentTaskStatus {
    val groups=jsonIds(JSONArray(row.groupIdsJson));val refs=JSONArray(row.jobIdsJson)
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
        for(i in 0 until refs.length()) {
            currentCoroutineContext().ensureActive();if(journal.agentTaskDao().get(id)?.state!="SCHEDULE_PENDING") return
            when(task.kind) {
                "RECOGNITION"->{val row=dao.recognition(refs.getString(i)) ?: error("Recognition job missing.");scheduleRecognition(row)}
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
        if(row.kind=="RECOGNITION") {val refs=JSONArray(row.jobIdsJson);for(i in 0 until refs.length()) dao.recognition(refs.getString(i))?.takeIf {it.state in listOf("RUNNING","QUEUED")}?.let {dao.update(it.copy(state="CANCELLED",error="Cancelled from Tasks; original photographs retained.",completedAt=System.currentTimeMillis()))}}
        saved}
    val refs=JSONArray(task.jobIdsJson)
    when(task.kind) {
        "RECOGNITION"->for(i in 0 until refs.length()) dao.recognition(refs.getString(i))?.workId?.takeIf {it.isNotBlank()}?.let {WorkManager.getInstance(context).cancelWorkById(UUID.fromString(it))}
        "SHOPIFY_DRAFT"->for(i in 0 until refs.length()) cancelShopify(refs.getString(i))
    }
    mutate {journal.agentTaskDao().get(id)?.let {journal.agentTaskDao().update(it.copy(state="CANCELLED",error="Remaining local work cancelled. Already-sent API requests and existing remote drafts are retained.",updatedAt=System.currentTimeMillis()))}}
}
internal suspend fun CaptureRepository.completeReviewTask(id:String)=mutate {
    val task=journal.agentTaskDao().get(id) ?: error("Task missing");check(task.kind=="REVIEW_BATCH" && task.state=="OPEN")
    val status=agentTaskStatus(task);check(status.done==status.total) {"Review and mark every selected copy ready in Inventory first."}
    journal.agentTaskDao().update(task.copy(state="COMPLETE",updatedAt=System.currentTimeMillis()))
}

internal suspend fun CaptureRepository.createLocalReviewTask(groups:List<String>):String=mutate {
    jsonIds(JSONArray(groups));groups.forEach {check(journal.inventoryDao().item(it)?.archived==false) {"Choose active saved copies."}}
    val task=AgentTaskRow(approvalId=newId(),conversationId="00000000-0000-0000-0000-000000000000",kind="REVIEW_BATCH",title="Review ${groups.size} physical copies",groupIdsJson=JSONArray(groups).toString(),state="OPEN",resultJson=JSONObject().put("createdBy","OPERATOR_UI").toString())
    journal.agentTaskDao().insert(task);task.id
}
