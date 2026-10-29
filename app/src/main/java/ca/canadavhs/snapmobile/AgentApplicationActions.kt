package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*
import java.time.OffsetDateTime

internal val agentMetadataFields=setOf("title","condition","conditionNotes","location","tags","description","quantity","edition","releaseYear","distributor","vendor","productType","weightGrams","barcode","rare","review","poolMode","captureNote")
internal fun agentPatched(item:InventoryRow,patch:JSONObject):InventoryRow {
    require(patch.length() in 1..agentMetadataFields.size && patch.keys().asSequence().all {it in agentMetadataFields}) {"Only validated metadata may be patched; price, identity, remote IDs and readiness need their dedicated actions."}
    val j=inventoryJson(item);patch.keys().forEach {j.put(it,patch.get(it))};j.put("ready",false);return inventoryFromJson(j)
}
internal class AgentApplicationActions(private val repo:CaptureRepository,private val gatewayFactory:(String)->ShopifyGateway={shopifyGateway(repo.context,it)}) {
    companion object {
        val newActions=setOf("propose_inventory_workflow","propose_batch_change","propose_task_change","propose_archive","propose_settings","propose_shopify_management","propose_shopify_publish","propose_shopify_status")
        val actions=newActions+workflowActions+setOf("propose_inventory_update","propose_apply_recognition","propose_sold_price","propose_listing_plan")
        val settingDefaults=mapOf<String,Any>("recognitionMode" to "OFF","openaiModel" to DEFAULT_OPENAI_MODEL,"barcode" to true,"fastCapture" to true,"grid" to true,"volume" to true,"haptic" to true,"tone" to false,"defaultVendor" to "Canada VHS","defaultProductType" to "VHS Tape","defaultWeightGrams" to 250,"marketAgeDays" to 180,"reuseOperatorPrices" to false,"light" to false,"archiveMb" to 500,"defaultLocation" to "","photoResolution" to "BALANCED","hdr" to false,"reuseHistoricalSold" to false)
        fun publicSettings(repo:CaptureRepository)=JSONObject().apply {settingDefaults.forEach {(k,v)->put(k,when(v) {is Boolean->repo.preferences.getBoolean(k,v);is Int->repo.preferences.getInt(k,v);else->repo.preferences.getString(k,v.toString())})};put("shopifyPaired",repo.preferences.getBoolean("shopifyPaired",false));put("shopifyMode",if(repo.preferences.getBoolean("shopifyDirect",false)) "DIRECT" else "LEGACY");put("ownerWritesEnabled",repo.preferences.getBoolean("shopifyOwnerWrites",false));put("liveControlsEnabled",repo.preferences.getBoolean("shopifyOwnerLive",false));put("credentials","Protected key/token/signing stores are never queried.");put("aiMonitoring",false)}
        fun schema(action:String):JSONObject {
            require(action in actions)
            val description=when(action) {
                "propose_inventory_workflow"->"items_json: JSON array1..20 of objects {group_id UUID, patch_json string JSON object or {}, market_currency CAD|USD|empty, price_method MEDIAN|AVERAGE, recognize boolean, prepare_listing boolean, mark_ready boolean}. At least one operation per item. One atomic local review; provider jobs schedule only after commit. Recognition suggestions never automatically confirm/apply titles. Prices use prospective corrected metadata; operator enters FX."
                "propose_batch_change"->"operation START|RENAME|FINISH; session_id UUID or empty for START; name string (1..80 for START/RENAME, empty for FINISH); location valid A1..J10/Q1/empty. START requires no active batch; FINISH closes the active batch and creates local archives. Immutable IDs remain unchanged."
                "propose_task_change"->"operation CREATE|RENAME|COMPLETE_REVIEW|CANCEL; task_id UUID or empty for CREATE; title string (1..120 for CREATE/RENAME); group_ids_json JSON UUID array for CREATE, [] otherwise. Creates/manages local inventory review tasks; not a calendar/AI monitoring job."
                "propose_archive"->"group_ids JSON array1..50; archived boolean. Archive/restore copies, never delete files or immutable IDs. Explicit destructive-operation checkbox required."
                "propose_settings"->"patch_json string JSON object. Allowed public keys: ${settingDefaults.keys.joinToString()}. No credentials, endpoints or protected keys."
                "propose_shopify_management"->"group_id UUID; product_id Shopify Product GID; location_id Location GID; fields_json JSON array of title/descriptionHtml/vendor/productType; direct mode also supports tags/priceCents/weightGrams/quantity/sku. [] proposes a marked-draft link. Reuses native reviewed management; no mutation until human approval."
                "propose_shopify_status"->"product_id Product GID; location_id Location GID; status APPROVE_LOCAL|DRAFT|ACTIVE|ARCHIVED|PUBLISH_LIVE; publication_id empty unless PUBLISH_LIVE. Native owner-operated status review; each remote transition requires SUBMIT. No mutation before operator approval."
                "propose_shopify_publish"->"group_ids JSON array1..50; location_id Location GID; publication_id Publication GID. Reuses existing native live review; explicit SUBMIT required."
                "propose_inventory_update"->"group_id UUID; patch_json string object with approved metadata fields. No prices/identity/remote IDs."
                "propose_apply_recognition"->"group_id UUID; recognition_id completed/current-photo/operator-confirmed UUID"
                "propose_listing_plan"->"group_id UUID. Offline preparation, no upload."
                "propose_sold_price"->"group_id UUID; evidence_id imported-evidence UUID; method AVERAGE|MEDIAN"
                "propose_recognition"->"group_id UUID. Settings provider/model; separately billed API recognition needs explicit consent."
                "propose_recognition_batch","propose_batch_review"->"group_ids JSON array1..50 exact group UUIDs"
                "propose_market_price"->"group_id UUID; currency CAD|USD; method MEDIAN|AVERAGE"
                "propose_market_price_batch"->"group_ids JSON array1..20; currency CAD|USD; method MEDIAN|AVERAGE"
                "propose_shopify_draft"->"group_id UUID; location_id Shopify Location GID"
                else->"group_ids JSON array1..50; location_id Shopify Location GID"
            };return JSONObject().put("action",action).put("arguments",description).put("approvalRequired",true)
        }
    }
    private fun keys(p:JSONObject,vararg fields:String) {require(p.keys().asSequence().toSet()==fields.toSet()) {"Unexpected action arguments. Discover the action schema."}}
    private suspend fun fingerprint(action:String,p:JSONObject):String=when(action) {
        "propose_settings"->sha256(canonicalJson(publicSettings(repo)).toByteArray())
        "propose_batch_change"->{val j=JSONArray();repo.journal.openHelper.writableDatabase.query("SELECT * FROM sessions ORDER BY id").use {c->while(c.moveToNext()) j.put(cursorObject(c))};repo.journal.openHelper.writableDatabase.query("SELECT id,groupId,state,deleted,sha256,position,role FROM photos ORDER BY id").use {c->while(c.moveToNext()) j.put(cursorObject(c))};sha256(canonicalJson(j).toByteArray())}
        "propose_task_change"->if(p.getString("operation")=="CREATE") repo.workflowBinding(jsonIds(p.getJSONArray("group_ids"))) else repo.journal.agentTaskDao().get(p.getString("task_id"))?.let {sha256((it.id+it.kind+it.state+it.title+it.groupIdsJson+it.jobIdsJson+it.updatedAt).toByteArray())} ?: error("Task missing")
        "propose_shopify_management","propose_shopify_publish","propose_shopify_status"->sha256((p.getString("native_review_id")+p.getString("native_binding")).toByteArray())
        else->repo.workflowBinding(jsonIds(p.getJSONArray("group_ids")))
    }
    suspend fun propose(chat:ChatConversationRow,action:String,args:JSONObject):JSONObject {
        check(chat.toolsEnabled && chat.proposalsEnabled)
        if(action in setOf("propose_shopify_management","propose_shopify_publish","propose_shopify_status")) {
            check(repo.preferences.getBoolean("shopifyPaired",false)) {"Pair Shopify first."};val base=repo.shopifyEndpoint();val gateway=gatewayFactory(base);val h=gateway.call(newId(),"health",JSONObject())
            val native=if(action=="propose_shopify_management") {keys(args,"group_id","product_id","location_id","fields_json");val fields=JSONArray(args.getString("fields_json"));repo.prepareShopifyManagement(requireUuid(args.getString("group_id")),args.getString("product_id"),h,base,args.getString("location_id"),(0 until fields.length()).map {fields.getString(it)}.toSet(),gateway)}
                else if(action=="propose_shopify_status") {keys(args,"product_id","location_id","status","publication_id");repo.prepareShopifyOwnerAction(args.getString("product_id"),args.getString("location_id"),args.getString("status"),args.getString("publication_id"),h,gateway)}
                else {keys(args,"group_ids","location_id","publication_id");repo.prepareShopifyUploadReview(jsonIds(args.getJSONArray("group_ids")).toSet(),h,base,args.getString("location_id"),true,args.getString("publication_id"))}
            return repo.mutate {val p=JSONObject(native.payloadJson).put("native_review_id",native.id).put("native_binding",native.bindingHash).put("requires_submit",shopifyReviewNeedsSubmit(native)).put("notice",if(native.kind=="PUBLISH_LIVE") "Live publication makes products visible. Explicit SUBMIT authorization is required." else "Approve this exact native Shopify review. Actual server results are reported by its owning job.")
                save(chat,action,p)}
        }
        return repo.mutate {
            val p=when(action) {
                "propose_inventory_workflow"->workflow(args)
                "propose_archive"->{keys(args,"group_ids","archived");val groups=jsonIds(args.getJSONArray("group_ids"));val archived=args.getBoolean("archived");JSONObject().put("group_ids",JSONArray(groups)).put("archived",archived).put("items",JSONArray(groups.map {id->inventoryJson(repo.journal.inventoryDao().item(id) ?: error("Item missing"))})).put("notice",if(archived) "Archive these exact copies and clear readiness. Originals and immutable IDs are retained." else "Restore these exact archived copies; readiness still needs review.")}
                "propose_settings"->{keys(args,"patch_json");val patch=JSONObject(args.getString("patch_json"));validateSettings(patch);JSONObject().put("patch",patch).put("before",publicSettings(repo)).put("notice","Change only these public settings. No model request starts and no credential is accessed.")}
                "propose_batch_change"->{keys(args,"operation","session_id","name","location");val op=args.getString("operation");require(op in listOf("START","RENAME","FINISH"));require(validLocation(args.getString("location")));if(op!="FINISH") require(args.getString("name").isNotBlank() && args.getString("name").length<=80)
                    val old=if(op=="START") {check(repo.dao.active()==null) {"Finish the active batch first."};null} else repo.dao.session(requireUuid(args.getString("session_id")))
                    if(op=="FINISH") check(repo.dao.active()?.id==old!!.id) {"Only the active batch can be finished."}
                    JSONObject(args.toString()).put("beforeName",old?.name ?: "No active batch").put("notice","$op batch. Existing physical-copy and batch identities remain unchanged. Finishing creates local archives, not AI requests.")}
                "propose_task_change"->{keys(args,"operation","task_id","title","group_ids_json");val op=args.getString("operation");require(op in listOf("CREATE","RENAME","COMPLETE_REVIEW","CANCEL"));val groups=if(op=="CREATE") jsonIds(JSONArray(args.getString("group_ids_json"))) else {val t=repo.journal.agentTaskDao().get(requireUuid(args.getString("task_id"))) ?: error("Task missing");check(t.state !in listOf("COMPLETE","CANCELLED"));taskGroups(t)}
                    if(op in listOf("CREATE","RENAME")) require(args.getString("title").isNotBlank() && args.getString("title").length<=120)
                    JSONObject().put("operation",op).put("task_id",args.getString("task_id")).put("title",args.getString("title")).put("beforeTitle",if(op=="CREATE") "No task" else repo.journal.agentTaskDao().get(args.getString("task_id"))!!.title).put("beforeState",if(op=="CREATE") "" else repo.journal.agentTaskDao().get(args.getString("task_id"))!!.state).put("group_ids",JSONArray(groups)).put("notice","$op local task. Already-sent provider requests or remote resources cannot be undone by cancellation.")}
                else->error("Unsupported new action")
            };save(chat,action,p)
        }
    }
    private suspend fun save(chat:ChatConversationRow,action:String,p:JSONObject):JSONObject {
        check(repo.journal.nextDao().chat(chat.id)?.let {it.toolsEnabled && it.proposalsEnabled}==true) {"Full Agent permission changed; prepare a fresh proposal."}
        check(p.toString().length<=900000) {"Review a smaller selection."};val row=AgentApprovalRow(conversationId=chat.id,action=action,payloadJson=p.toString(),bindingHash=fingerprint(action,p));repo.journal.nextDao().insert(row)
        return JSONObject().put("approval_id",row.id).put("state","PENDING_OPERATOR_REVIEW").put("message","Nothing changed. Review the complete selection once in SnapIMS.")
    }
    private suspend fun workflow(args:JSONObject):JSONObject {
        keys(args,"items_json");val input=JSONArray(args.getString("items_json"));require(input.length() in 1..20)
        val groups=mutableListOf<String>();val plans=JSONArray();val previews=JSONArray();val recommendations=JSONArray();var recognition:RecognitionSettings?=null;val currencies=mutableSetOf<String>()
        for(i in 0 until input.length()) {val r=input.getJSONObject(i);keys(r,"group_id","patch_json","market_currency","price_method","recognize","prepare_listing","mark_ready");val id=requireUuid(r.getString("group_id"));require(id !in groups);groups+=id
            val old=repo.journal.inventoryDao().item(id) ?: error("Item missing");check(!old.archived);val patch=JSONObject(r.getString("patch_json"));val prospective=if(patch.length()>0) agentPatched(old,patch).copy(title=patch.optString("title",old.title).trim(),revision=old.revision+1) else old
            val currency=r.getString("market_currency");require(currency in listOf("","CAD","USD"));require(r.getString("price_method") in listOf("MEDIAN","AVERAGE"));require(patch.length()>0 || currency.isNotBlank() || r.getBoolean("recognize") || r.getBoolean("prepare_listing") || r.getBoolean("mark_ready"))
            require(!r.getBoolean("recognize") || !r.getBoolean("mark_ready")) {"Recognition needs title review before marking the item ready."}
            if(r.getBoolean("recognize")) {recognition=repo.recognitionSettings();check(repo.dao.photos(id).any {it.state=="READY" && !it.deleted})}
            if(currency.isNotBlank()) {currencies+=currency;repo.ensureMarketSearch();val recommendation=repo.recommendMarketLocked(prospective,currency) ?: error("No compatible $currency sold evidence for ${old.itemId}; no price invented.");recommendations.put(marketRecommendationJson(recommendation).put("method",r.getString("price_method")))}
            plans.put(JSONObject(r.toString()).put("patch",patch));previews.put(inventoryJson(old).put("photoCount",repo.dao.photos(id).count {!it.deleted}).put("proposedPatch",patch).put("recognitionPhotoIds",JSONArray(recognitionSourcePhotos(repo.dao.photos(id).filter {!it.deleted && it.state=="READY"}).map {it.id})).put("recognize",r.getBoolean("recognize")).put("prepareListing",r.getBoolean("prepare_listing")).put("markReady",r.getBoolean("mark_ready")))
        };require(currencies.size<=1) {"Use one evidence currency per reviewed workflow; rates are operator-entered."}
        return JSONObject().put("group_ids",JSONArray(groups)).put("plans",plans).put("items",previews).put("recommendations",recommendations).put("currency",currencies.firstOrNull() ?: "CAD").put("method","MEDIAN")
            .put("recognition",recognition?.json() ?: JSONObject.NULL).put("billing",if(recognition?.provider=="OPENAI") "SEPARATE_OPENAI_API_KEY" else "OFFLINE_NO_PROVIDER_BILL")
            .put("notice","Review every copy's metadata, source-backed prices, recognition, readiness and offline listing preparation. Local changes commit together or roll back together. Recognition results remain suggestions; no automatic title application. Shopify uploads require their separate explicit review.")
    }
    private fun validateSettings(p:JSONObject) {require(p.length() in 1..settingDefaults.size && p.keys().asSequence().all {it in settingDefaults});p.keys().forEach {k->val value=p.get(k);when(val d=settingDefaults.getValue(k)) {is Boolean->require(value is Boolean);is Int->{require(value is Int || value is Long);val n=(value as Number).toLong();require(n in when(k) {"defaultWeightGrams"->1L..1_000_000L;"marketAgeDays"->1L..36500L;else->1L..10000L})};else->{require(value is String && value.length<=1000);if(k=="recognitionMode") require(value in RECOGNITION_MODES);if(k=="openaiModel") require(validOpenAiModelId(value));if(k=="defaultLocation") require(validLocation(value));if(k=="photoResolution") require(PhotoResolution.entries.any {it.name==value})}}}}
    private suspend fun pNativeReject(row:AgentApprovalRow) {
        if(row.action in setOf("propose_shopify_management","propose_shopify_publish","propose_shopify_status")) {
            val native=repo.journal.shopifyDao().review(JSONObject(row.payloadJson).getString("native_review_id"))
            if(native?.state=="PENDING") repo.journal.shopifyDao().update(native.copy(state="REJECTED",completedAt=System.currentTimeMillis()))
        }
    }
    suspend fun decide(id:String,accept:Boolean,o:AgentDecisionOptions):String {
        var taskId:String?=null
        val result=repo.mutate {
            val dao=repo.journal.nextDao();val row=dao.approval(id) ?: error("Approval missing");check(row.state=="PENDING");check(dao.chat(row.conversationId)?.let {it.toolsEnabled && it.proposalsEnabled}==true) {"Full Agent permission required."}
            if(!accept) {pNativeReject(row);dao.update(row.copy(state="REJECTED",completedAt=System.currentTimeMillis()));return@mutate "Proposal rejected; no changes made."}
            val p=JSONObject(row.payloadJson);if(fingerprint(row.action,p)!=row.bindingHash) {dao.update(row.copy(state="EXPIRED",resultJson="{\"error\":\"Reviewed state changed; prepare a fresh proposal\"}",completedAt=System.currentTimeMillis()));return@mutate "Reviewed state changed. Proposal expired."}
            val groups=p.optJSONArray("group_ids")?.let {if(it.length()==0) emptyList() else jsonIds(it)} ?: emptyList();val refs=JSONArray();val output=JSONObject();var kind="APP_OPERATION";var state="COMPLETE";var title=approvalTitle(row.action)
            when(row.action) {
                "propose_inventory_workflow"->{val plans=p.getJSONArray("plans");val rec=p.optJSONObject("recognition");val config=rec?.let {RecognitionSettings(it.getString("provider"),it.getString("model"),it.getString("authBinding"))};check(config==null || config==repo.recognitionSettings());check(config?.provider!="OPENAI" || o.allowPaidRecognition) {"Approve separately billed OpenAI recognition explicitly."}
                    val prices=p.getJSONArray("recommendations");check(prices.length()==0 || o.allowFinancial) {"Explicitly approve these reviewed financial changes."};check(prices.length()<=1 || o.adjustedCents==null)
                    val outcomes=JSONArray();for(i in 0 until plans.length()) {val plan=plans.getJSONObject(i);val group=plan.getString("group_id");var item=repo.journal.inventoryDao().item(group)!!;val patch=plan.getJSONObject("patch");if(patch.length()>0) item=repo.saveInventoryLocked(agentPatched(item,patch),item.revision)
                        val price=(0 until prices.length()).map {prices.getJSONObject(it)}.firstOrNull {it.getString("groupId")==group};if(price!=null) item=repo.applyMarketPriceLocked(marketRecommendationFromJson(price),plan.getString("price_method"),o.adjustedCents,o.replaceManual,o.allowHistorical,o.rate,o.rateDate)
                        if(plan.getBoolean("mark_ready")) item=repo.saveInventoryLocked(item.copy(ready=true),item.revision)
                        val outcome=JSONObject().put("groupId",group).put("itemId",item.itemId).put("revision",item.revision).put("ready",item.ready).put("priceCents",item.priceCents ?: JSONObject.NULL)
                        if(plan.getBoolean("prepare_listing")) {val listing=repo.prepareListingLocked(group);outcome.put("listingPlanId",listing.id).put("listingState",listing.state)}
                        if(plan.getBoolean("recognize")) {val r=repo.queueRecognitionLocked(group,config!!,row.id);refs.put(r.id);outcome.put("recognitionId",r.id).put("recognitionState",r.state)};outcomes.put(outcome)}
                    output.put("items",outcomes);kind="INVENTORY_WORKFLOW";title="Reviewed workflow · ${groups.size} copies";if(refs.length()>0) state="SCHEDULE_PENDING"}
                "propose_archive"->{check(o.allowDestructive) {"Explicitly approve archive/restore changes."};groups.forEach {repo.archiveInventoryLocked(it,p.getBoolean("archived"))};output.put("archived",p.getBoolean("archived"))}
                "propose_settings"->{validateSettings(p.getJSONObject("patch"));kind="SETTINGS_CHANGE";state="SCHEDULE_PENDING";output.put("before",p.getJSONObject("before")).put("patch",p.getJSONObject("patch"))}
                "propose_batch_change"->{val op=p.getString("operation");val session=when(op) {"START"->repo.startBatchLocked(p.getString("name"),p.getString("location")).id;"RENAME"->{val s=repo.dao.session(p.getString("session_id"));repo.dao.update(s.copy(name=p.getString("name").trim()));repo.dao.insert(EventRow(sessionId=s.id,groupId=null,action="BATCH_RENAMED:${s.name}->${p.getString("name")}"));s.id};else->repo.finishBatchLocked()};kind="BATCH_OPERATION";output.put("operation",op).put("sessionId",session);if(op=="FINISH") state="SCHEDULE_PENDING"}
                "propose_task_change"->{val op=p.getString("operation");if(op=="CREATE") {val task=repo.createLocalReviewTaskLocked(groups,p.getString("title"),row.id,row.conversationId);dao.update(row.copy(state="APPLIED",resultJson=JSONObject().put("taskId",task).toString(),completedAt=System.currentTimeMillis()));return@mutate "Local review task created. No model monitoring or background AI started."}
                    if(op=="RENAME") {output.put("targetTaskId",p.getString("task_id")).put("beforeTitle",p.getString("beforeTitle")).put("title",p.getString("title"));val old=repo.journal.agentTaskDao().get(p.getString("task_id"))!!;repo.journal.agentTaskDao().update(old.copy(title=p.getString("title"),updatedAt=System.currentTimeMillis()))} else {kind="TASK_CONTROL";state="SCHEDULE_PENDING";output.put("operation",op).put("targetTaskId",p.getString("task_id"))}}
                "propose_shopify_management","propose_shopify_publish","propose_shopify_status"->{if(shopifyReviewNeedsSubmit(repo.journal.shopifyDao().review(p.getString("native_review_id")) ?: error("Native review missing"))) check(o.confirmation=="SUBMIT") {"Type SUBMIT to explicitly authorize live publication."};kind="SHOPIFY_MANAGEMENT";state="SCHEDULE_PENDING";output.put("nativeReviewId",p.getString("native_review_id")).put("confirmation",o.confirmation)}
            }
            val taskGroups=if(groups.isNotEmpty()) groups else if(kind=="BATCH_OPERATION") repo.dao.groups(output.getString("sessionId")).mapNotNull {repo.journal.inventoryDao().item(it.id)?.groupId}.take(1) else emptyList()
            val task=AgentTaskRow(approvalId=row.id,conversationId=row.conversationId,kind=kind,title=title,groupIdsJson=JSONArray(taskGroups).toString(),jobIdsJson=refs.toString(),state=state,resultJson=output.toString());repo.journal.agentTaskDao().insert(task);taskId=task.id
            dao.update(row.copy(state="APPLIED",resultJson=JSONObject().put("taskId",task.id).put("state",state).toString(),completedAt=System.currentTimeMillis()));if(state=="COMPLETE") "Reviewed local changes completed and verified in task ${task.id}." else "Approval saved in task ${task.id}. Check Tasks for actual job completion; queued work is not completed."
        };taskId?.let {repo.scheduleAgentTask(it)};return result
    }
}
