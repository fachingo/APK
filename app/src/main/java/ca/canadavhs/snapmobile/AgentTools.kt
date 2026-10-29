package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*

/** Closed tools. Approved draft jobs only; live publication stays in the operator screen. */
internal class AgentTools(private val repo:CaptureRepository) {
    private val dao=repo.journal.nextDao()
    private val workflows=AgentWorkflows(repo)
    private val application by lazy {AgentApplicationTools(repo,this)}
    fun applicationDefinitions(chat:ChatConversationRow):JSONArray=if(!chat.toolsEnabled) JSONArray() else application.definitions(chat.proposalsEnabled)
    fun definitions(proposals:Boolean):JSONArray {
        fun tool(name:String,description:String,fields:Map<String,JSONObject>):JSONObject=JSONObject().put("type","function").put("name",name).put("description",description)
            .put("parameters",JSONObject().put("type","object").put("properties",JSONObject(fields)).put("required",JSONArray(fields.keys.toList())).put("additionalProperties",false)).put("strict",true)
        fun string(description:String)=JSONObject().put("type","string").put("description",description)
        val tools=JSONArray().put(tool("inventory_search","Search this device's inventory. Returns at most 10 records.",mapOf("query" to string("Title, Item ID, location or barcode"))))
            .put(tool("inventory_get","Read one local item, its revision, photo state and validation problems.",mapOf("group_id" to string("Exact inventory group UUID"))))
            .put(tool("recognition_results","Read existing recognition suggestions and operator-confirmed titles; does not run or bill a provider.",mapOf("group_id" to string("Exact inventory group UUID"))))
            .put(tool("pricing_evidence","Read imported sold comparisons for an exact title. Never invent prices.",mapOf("title" to string("Exact inventory title"))))
            .put(tool("approval_status","Read a local operator approval's current status.",mapOf("approval_id" to string("Approval UUID"))))
        tools.put(tool("market_search","Search the offline sold-market library. Approximate matches are browsing only. Amounts keep original currency and include shipping; never invent FX rates.",mapOf("query" to string("Title or barcode"),"currency" to string("ISO currency code, or empty for all"))))
            .put(tool("market_recommendation","Read a conservative offline sold-evidence recommendation for a saved item. Does not apply it. Operator must review in Market database, including any currency conversion.",mapOf("group_id" to string("Inventory group UUID"),"currency" to string("Original evidence ISO currency"))))
        tools.put(tool("shopify_job_status","Read saved Shopify job/item checkpoints on this phone. Does not contact Shopify.",mapOf("job_id" to string("Exact Shopify job UUID"))))
        if(proposals) tools.put(tool("propose_shopify_draft","Propose one Shopify DRAFT upload to the paired development store. Requires a separate operator approval of listing data and photo transfer. No live publication.",mapOf("group_id" to string("Exact ready inventory group UUID"),"location_id" to string("Selected Shopify Location GID"))))
        if(proposals) tools.put(tool("propose_apply_recognition","Propose applying an already operator-confirmed title from current photos. Separate approval required; no new recognition request.",mapOf("group_id" to string("Exact group UUID"),"recognition_id" to string("Completed recognition UUID"))))
            .put(tool("propose_inventory_update","Propose metadata only; requires a separate operator review. Cannot set a price or publish.",mapOf("group_id" to string("Exact inventory group UUID"),"patch_json" to string("JSON object containing only title, condition, conditionNotes, location, tags, description or quantity"))))
            .put(tool("propose_sold_price","Propose applying imported fresh CAD sold evidence. Requires operator approval; cannot generate prices.",mapOf("group_id" to string("Exact group UUID"),"evidence_id" to string("Imported evidence UUID"),"method" to JSONObject().put("type","string").put("enum",JSONArray(listOf("AVERAGE","MEDIAN"))))))
            .put(tool("propose_listing_plan","Propose preparing an offline draft listing plan; no Shopify write. Requires operator review.",mapOf("group_id" to string("Exact group UUID"))))
        val extra=workflows.definitions(proposals);for(i in 0 until extra.length()) tools.put(extra.getJSONObject(i))
        return tools
    }
    private suspend fun binding(id:String):String {
        val item=repo.journal.inventoryDao().item(id) ?: error("Item not found.")
        return sha256((canonicalJson(inventoryJson(item))+photoFingerprint(repo.dao.photos(id).filter {!it.deleted && it.state=="READY"})).toByteArray())
    }
    suspend fun execute(chat:ChatConversationRow,name:String,args:JSONObject):JSONObject {
        val current=dao.chat(chat.id) ?: error("Conversation no longer exists.")
        check(current.toolsEnabled) {"Chat Only has no application tool access."}
        val isWrite=name=="app_propose" || name.startsWith("propose_")
        check(!isWrite || current.proposalsEnabled) {"Full Agent is required to propose modifications."}
        val start=System.currentTimeMillis();val label=if(name=="app_read") "$name:${args.optString("resource")}" else if(name=="app_propose") "$name:${args.optString("action")}" else name
        val trace=AgentTraceRow(conversationId=current.id,tool=label,argumentsJson=(safeAgentJson(args) as JSONObject).toString().take(2500));repo.journal.agentPolicyDao().insert(trace)
        try {
            val result=compactAgentJson(if(name.startsWith("app_")) application.execute(current,name,args) else executeRaw(current,name,args))
            repo.journal.agentPolicyDao().update(trace.copy(state="COMPLETE",summary="Returned ${result.optJSONArray("items")?.length() ?: result.optJSONArray("groups")?.length() ?: 1} result entries. ${result.optString("message").take(180)}",resultChars=result.toString().length,durationMs=System.currentTimeMillis()-start));return result
        } catch(e:kotlinx.coroutines.CancellationException) {kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {repo.journal.agentPolicyDao().update(trace.copy(state="INTERRUPTED",summary="Stopped by operator; no provider retry.",durationMs=System.currentTimeMillis()-start))};throw e}
        catch(e:Exception) {repo.journal.agentPolicyDao().update(trace.copy(state="ERROR",summary=safeAgentText(e.message.orEmpty()).take(1000),durationMs=System.currentTimeMillis()-start));throw e}
    }
    internal suspend fun executeRaw(chat:ChatConversationRow,name:String,args:JSONObject):JSONObject {
        check(chat.toolsEnabled) {"Local tools are disabled for this chat."}
        if(name in workflowActions || name in setOf("capture_batches","batch_review","task_status","shopify_connection")) return workflows.execute(chat,name,args)
        return when(name) {
            "inventory_search" -> {
                require(args.keys().asSequence().toSet()==setOf("query"));val q=args.getString("query").trim();require(q.length in 1..200)
                val rows=repo.journal.inventoryDao().items().filter {!it.archived && listOf(it.title,it.itemId,it.location,it.barcode).any {field->field.contains(q,true)}}.take(10)
                JSONObject().put("items",JSONArray(rows.map {inventoryJson(it)})).put("limit",10)
            }
            "inventory_get" -> {
                require(args.keys().asSequence().toSet()==setOf("group_id"));val item=repo.journal.inventoryDao().item(args.getString("group_id")) ?: error("Item not found.")
                inventoryJson(item).put("problems",JSONArray(inventoryProblems(item,repo.dao.photos(item.groupId),repo)))
            }
            "recognition_results" -> {
                require(args.keys().asSequence().toSet()==setOf("group_id"));val id=args.getString("group_id")
                require(repo.journal.inventoryDao().item(id)!=null)
                JSONObject().put("suggestions",JSONArray(repo.dao.recognitions(id).take(10).map {r->JSONObject().put("id",r.id).put("provider",r.provider).put("state",r.state).put("acceptedTitle",r.acceptedTitle ?: JSONObject.NULL)}))
            }
            "pricing_evidence" -> {
                require(args.keys().asSequence().toSet()==setOf("title"));val rows=dao.evidenceForTitle(InventoryRules.normalizedTitle(args.getString("title"))).take(5)
                JSONObject().put("evidence",JSONArray(rows.map {e ->JSONObject().put("id",e.id).put("title",e.queriedTitle).put("currency",e.currency).put("usable",e.usable()).put("expiresAt",e.expiresAt).put("listings",JSONArray(e.listingsJson))}))
            }
            "approval_status" -> {
                require(args.keys().asSequence().toSet()==setOf("approval_id"));val row=dao.approval(args.getString("approval_id")) ?: error("Approval not found.")
                require(row.conversationId==chat.id);JSONObject().put("state",row.state).put("result",JSONObject(row.resultJson.ifEmpty {"{}"}))
            }
            "market_search" -> {
                require(args.keys().asSequence().toSet()==setOf("query","currency"));val q=args.getString("query");val currency=args.getString("currency");require(q.length in 1..200 && (currency.isBlank() || currency.matches(Regex("[A-Z]{3}"))))
                val page=repo.searchMarket(MarketFilters(query=q,currency=currency),0,10)
                JSONObject().put("approximate",page.approximate).put("correctedQuery",page.correctedQuery).put("groups",JSONArray(page.groups.map {r->JSONObject().put("title",r.title).put("currency",r.currency).put("sampleCount",r.samples).put("lowCentsIncludingShipping",r.low).put("highCentsIncludingShipping",r.high).put("newestSale",r.newest).put("edition",r.edition)}))
            }
            "market_recommendation" -> {
                require(args.keys().asSequence().toSet()==setOf("group_id","currency"));val currency=args.getString("currency");require(currency.matches(Regex("[A-Z]{3}")))
                val r=repo.recommendMarket(args.getString("group_id"),currency)
                if(r==null) JSONObject().put("compatibleSoldCount",0).put("message","No compatible evidence; no price invented.") else JSONObject().put("currency",r.currency).put("count",r.stats.count).put("medianCents",r.stats.medianCents).put("averageCents",r.stats.averageCents).put("newestSale",r.newest).put("oldestSale",r.oldest).put("message","Original currency including shipping. Review and approve locally; no inventory change or FX inference occurred.")
            }
            "shopify_job_status" -> {
                require(args.keys().asSequence().toSet()==setOf("job_id"));val job=repo.journal.shopifyDao().job(args.getString("job_id")) ?: error("Shopify job not found")
                JSONObject().put("jobId",job.id).put("kind",job.kind).put("shop",job.shop).put("state",job.state).put("error",job.error).put("items",JSONArray(repo.journal.shopifyDao().items(job.id).map {r->JSONObject().put("itemId",r.itemId).put("state",r.state).put("step",r.step).put("remote",JSONObject(r.remoteJson)).put("error",r.error)}))
            }
            "propose_inventory_update","propose_sold_price","propose_listing_plan","propose_apply_recognition","propose_shopify_draft" -> {
                check(chat.proposalsEnabled) {"Change proposals are disabled for this chat."}
                val expected=when(name) {"propose_shopify_draft"->setOf("group_id","location_id");"propose_inventory_update"->setOf("group_id","patch_json");"propose_sold_price"->setOf("group_id","evidence_id","method");"propose_apply_recognition"->setOf("group_id","recognition_id");else->setOf("group_id")}
                require(args.keys().asSequence().toSet()==expected)
                val item=repo.journal.inventoryDao().item(args.getString("group_id")) ?: error("Item not found.")
                val payload=JSONObject(args.toString()).put("expectedRevision",item.revision).put("currentTitle",item.title).put("itemId",item.itemId).put("currentPriceCents",item.priceCents ?: JSONObject.NULL)
                if(name=="propose_shopify_draft") {
                    check(repo.preferences.getBoolean("shopifyPaired",false)) {"Pair Shopify in Settings first."}
                    val base=repo.preferences.getString("shopifyBroker","").orEmpty();requireBrokerUrl(base)
                    check(repo.shopifyProblems(item,repo.dao.photos(item.groupId).filter {!it.deleted}).isEmpty()) {"Review and mark the item ready first."}
                    val health=ShopifyBrokerClient(repo.context,base).call(newId(),"health",JSONObject())
                    check(health.getBoolean("developmentStore")) {"Only development-store drafts are supported."}
                    payload.put("broker",base).put("health",health).put("connectionFingerprint",shopifyConnectionFingerprint(repo.context,base))
                    payload.put("reviewedListing",shopifySnapshot(item,repo.dao.photos(item.groupId).filter {!it.deleted}.sortedBy {it.position},effectiveBarcode(item,repo.dao.group(item.groupId),repo.dao.photos(item.groupId))))
                    payload.put("approvalNotice","Approval sends listing data and all selected photographs to Shopify as a DRAFT. No live publication.")
                }
                if(name=="propose_apply_recognition") {
                    val r=repo.dao.recognition(args.getString("recognition_id")) ?: error("Recognition not found.")
                    check(r.groupId==item.groupId && r.state=="SUCCEEDED" && !r.acceptedTitle.isNullOrBlank()) {"Confirm this recognition title in Batches first."}
                    check(r.inputFingerprint==photoFingerprint(repo.dao.photos(item.groupId).filter {!it.deleted && it.state=="READY"})) {"Recognition photos changed."}
                    payload.put("recognizedTitle",r.acceptedTitle)
                }
                if(name=="propose_inventory_update") {validatePatch(item,JSONObject(args.getString("patch_json")));payload.put("before",inventoryJson(item))}
                if(name=="propose_sold_price") {val e=dao.evidence(args.getString("evidence_id")) ?: error("Evidence not found.");check(e.usable() && e.titleKey==InventoryRules.normalizedTitle(item.title));require(args.getString("method") in listOf("AVERAGE","MEDIAN"));val stats=e.statistics() ?: error("No sold statistics");payload.put("soldReview",JSONObject().put("count",stats.count).put("priceCents",if(args.getString("method")=="MEDIAN") stats.medianCents else stats.averageCents).put("collectedAt",e.collectedAt).put("expiresAt",e.expiresAt))}
                val row=AgentApprovalRow(conversationId=chat.id,action=name,payloadJson=payload.toString(),bindingHash=binding(item.groupId))
                dao.insert(row);JSONObject().put("approval_id",row.id).put("state","PENDING_OPERATOR_REVIEW").put("message","No change applied. Operator must review the proposal in SnapIMS.")
            }
            else -> error("Unsupported local tool.")
        }
    }
    internal fun validatePatch(item:InventoryRow,patch:JSONObject):InventoryRow {
        val allowed=agentMetadataFields
        require(patch.length() in 1..allowed.size && patch.keys().asSequence().all {it in allowed}) {"Only approved metadata fields may be proposed."}
        val json=inventoryJson(item);patch.keys().forEach {key->json.put(key,patch.get(key))};json.put("ready",false)
        return inventoryFromJson(json)
    }
    suspend fun decide(id:String,accept:Boolean,replaceManual:Boolean=false):String=decide(id,accept,AgentDecisionOptions(replaceManual=replaceManual))
    suspend fun decide(id:String,accept:Boolean,options:AgentDecisionOptions):String {
        val replaceManual=options.replaceManual
        val pending=dao.approval(id) ?: error("Approval missing");check(dao.chat(pending.conversationId)?.let {it.toolsEnabled && it.proposalsEnabled}==true) {"Full Agent permission is required to approve application changes."}
        if(pending.action in AgentApplicationActions.newActions) return AgentApplicationActions(repo).decide(id,accept,options)
        var taskId:String?=null
        var scheduled:String?=null
        val message=repo.mutate {
        val row=dao.approval(id) ?: error("Approval no longer exists.");check(row.state=="PENDING") {"This approval was already handled."}
        if(!accept) {dao.update(row.copy(state="REJECTED",completedAt=System.currentTimeMillis()));return@mutate "Proposal rejected."}
        val payload=JSONObject(row.payloadJson)
        if(row.action in workflowActions && payload.optInt("bindingVersion")==2) {
            val groups=jsonIds(payload.getJSONArray("group_ids"))
            if(repo.workflowBinding(groups)!=row.bindingHash) {dao.update(row.copy(state="EXPIRED",resultJson="{\"error\":\"A selected item or photograph changed; request a new proposal\"}",completedAt=System.currentTimeMillis()));return@mutate "Selected items changed. Request a new proposal."}
            val task=workflows.applyLocked(row,payload,options);taskId=task.id
            dao.update(row.copy(state="APPLIED",resultJson=JSONObject().put("taskId",task.id).put("state",task.state).put("itemCount",groups.size).toString(),completedAt=System.currentTimeMillis()))
            return@mutate if(task.state=="COMPLETE") "Reviewed prices applied. Task history records the exact outcome." else "Approved task saved. Tasks shows actual job progress and review needs."
        }
        val group=payload.getString("group_id")
        if(binding(group)!=row.bindingHash) {dao.update(row.copy(state="EXPIRED",resultJson="{\"error\":\"Item or photos changed; request a new proposal\"}",completedAt=System.currentTimeMillis()));return@mutate "Item changed. This proposal expired; request a fresh one."}
        val revision=payload.getLong("expectedRevision")
            val result=when(row.action) {
                "propose_shopify_draft" -> {
                    val base=payload.getString("broker")
                    check(repo.preferences.getBoolean("shopifyPaired",false) && repo.preferences.getString("shopifyBroker","")==base && shopifyConnectionFingerprint(repo.context,base)==payload.getString("connectionFingerprint")) {"Shopify connection changed. Request a new proposal."}
                    val job=repo.queueShopifyLocked(setOf(group),payload.getJSONObject("health"),base,payload.getString("location_id"))
                    scheduled=job;JSONObject().put("shopifyJobId",job).put("state","QUEUED_DRAFT")
                }
                "propose_inventory_update" -> {val item=repo.journal.inventoryDao().item(group) ?: error("Item not found.");repo.saveInventoryLocked(validatePatch(item,JSONObject(payload.getString("patch_json"))),revision);JSONObject().put("updated",group)}
                "propose_sold_price" -> {repo.applySoldPriceLocked(group,payload.getString("evidence_id"),payload.getString("method"),revision,replaceManual);JSONObject().put("priced",group)}
                "propose_apply_recognition" -> {
                    val r=repo.dao.recognition(payload.getString("recognition_id")) ?: error("Recognition not found.")
                    check(r.groupId==group && r.acceptedTitle==payload.getString("recognizedTitle")) {"Confirmed recognition title changed. Request a new proposal."}
                    repo.applyRecognitionLocked(r.id);JSONObject().put("recognitionApplied",r.id)
                }
                "propose_listing_plan" -> {check(binding(group)==row.bindingHash);val plan=repo.prepareListingLocked(group);JSONObject().put("planId",plan.id).put("state",plan.state)}
                else -> error("Unsupported approved action.")
            }
            dao.update(row.copy(state="APPLIED",resultJson=result.toString(),completedAt=System.currentTimeMillis()));"Approved action completed locally."
        }
        scheduled?.let {repo.enqueueShopify(it)}
        taskId?.let {repo.scheduleAgentTask(it)}
        return if(scheduled!=null) "Approved Shopify draft job queued. Check Shopify jobs for verified remote completion." else message
    }
}
