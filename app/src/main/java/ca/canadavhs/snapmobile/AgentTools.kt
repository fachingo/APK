package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*

/** Closed tool registry. No arbitrary SQL, shell, filesystem, publishing or messaging. */
internal class AgentTools(private val repo:CaptureRepository) {
    private val dao=repo.journal.nextDao()
    fun definitions(proposals:Boolean):JSONArray {
        fun tool(name:String,description:String,fields:Map<String,JSONObject>):JSONObject=JSONObject().put("type","function").put("name",name).put("description",description)
            .put("parameters",JSONObject().put("type","object").put("properties",JSONObject(fields)).put("required",JSONArray(fields.keys.toList())).put("additionalProperties",false)).put("strict",true)
        fun string(description:String)=JSONObject().put("type","string").put("description",description)
        val tools=JSONArray().put(tool("inventory_search","Search this device's inventory. Returns at most 10 records.",mapOf("query" to string("Title, Item ID, location or barcode"))))
            .put(tool("inventory_get","Read one local item, its revision, photo state and validation problems.",mapOf("group_id" to string("Exact inventory group UUID"))))
            .put(tool("recognition_results","Read existing recognition suggestions and operator-confirmed titles; does not run or bill a provider.",mapOf("group_id" to string("Exact inventory group UUID"))))
            .put(tool("pricing_evidence","Read imported sold comparisons for an exact title. Never invent prices.",mapOf("title" to string("Exact inventory title"))))
            .put(tool("approval_status","Read a local operator approval's current status.",mapOf("approval_id" to string("Approval UUID"))))
        if(proposals) tools.put(tool("propose_apply_recognition","Propose applying an already operator-confirmed title from current photos. Separate approval required; no new recognition request.",mapOf("group_id" to string("Exact group UUID"),"recognition_id" to string("Completed recognition UUID"))))
            .put(tool("propose_inventory_update","Propose metadata only; requires a separate operator review. Cannot set a price or publish.",mapOf("group_id" to string("Exact inventory group UUID"),"patch_json" to string("JSON object containing only title, condition, conditionNotes, location, tags, description or quantity"))))
            .put(tool("propose_sold_price","Propose applying imported fresh CAD sold evidence. Requires operator approval; cannot generate prices.",mapOf("group_id" to string("Exact group UUID"),"evidence_id" to string("Imported evidence UUID"),"method" to JSONObject().put("type","string").put("enum",JSONArray(listOf("AVERAGE","MEDIAN"))))))
            .put(tool("propose_listing_plan","Propose preparing an offline draft listing plan; no Shopify write. Requires operator review.",mapOf("group_id" to string("Exact group UUID"))))
        return tools
    }
    private suspend fun binding(id:String):String {
        val item=repo.journal.inventoryDao().item(id) ?: error("Item not found.")
        return sha256((canonicalJson(inventoryJson(item))+photoFingerprint(repo.dao.photos(id).filter {!it.deleted && it.state=="READY"})).toByteArray())
    }
    suspend fun execute(chat:ChatConversationRow,name:String,args:JSONObject):JSONObject {
        check(chat.toolsEnabled) {"Local tools are disabled for this chat."}
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
            "propose_inventory_update","propose_sold_price","propose_listing_plan","propose_apply_recognition" -> {
                check(chat.proposalsEnabled) {"Change proposals are disabled for this chat."}
                val expected=when(name) {"propose_inventory_update"->setOf("group_id","patch_json");"propose_sold_price"->setOf("group_id","evidence_id","method");"propose_apply_recognition"->setOf("group_id","recognition_id");else->setOf("group_id")}
                require(args.keys().asSequence().toSet()==expected)
                val item=repo.journal.inventoryDao().item(args.getString("group_id")) ?: error("Item not found.")
                val payload=JSONObject(args.toString()).put("expectedRevision",item.revision)
                if(name=="propose_apply_recognition") {
                    val r=repo.dao.recognition(args.getString("recognition_id")) ?: error("Recognition not found.")
                    check(r.groupId==item.groupId && r.state=="SUCCEEDED" && !r.acceptedTitle.isNullOrBlank()) {"Confirm this recognition title in Batches first."}
                    check(r.inputFingerprint==photoFingerprint(repo.dao.photos(item.groupId).filter {!it.deleted && it.state=="READY"})) {"Recognition photos changed."}
                    payload.put("recognizedTitle",r.acceptedTitle)
                }
                if(name=="propose_inventory_update") validatePatch(item,JSONObject(args.getString("patch_json")))
                if(name=="propose_sold_price") {val e=dao.evidence(args.getString("evidence_id")) ?: error("Evidence not found.");check(e.usable() && e.titleKey==InventoryRules.normalizedTitle(item.title));require(args.getString("method") in listOf("AVERAGE","MEDIAN"))}
                val row=AgentApprovalRow(conversationId=chat.id,action=name,payloadJson=payload.toString(),bindingHash=binding(item.groupId))
                dao.insert(row);JSONObject().put("approval_id",row.id).put("state","PENDING_OPERATOR_REVIEW").put("message","No change applied. Operator must review the proposal in SnapIMS.")
            }
            else -> error("Unsupported local tool.")
        }
    }
    private fun validatePatch(item:InventoryRow,patch:JSONObject):InventoryRow {
        val allowed=setOf("title","condition","conditionNotes","location","tags","description","quantity")
        require(patch.length() in 1..allowed.size && patch.keys().asSequence().all {it in allowed}) {"Only approved metadata fields may be proposed."}
        val json=inventoryJson(item);patch.keys().forEach {key->json.put(key,patch.get(key))};json.put("ready",false)
        return inventoryFromJson(json)
    }
    suspend fun decide(id:String,accept:Boolean,replaceManual:Boolean=false):String=repo.mutate {
        val row=dao.approval(id) ?: error("Approval no longer exists.");check(row.state=="PENDING") {"This approval was already handled."}
        if(!accept) {dao.update(row.copy(state="REJECTED",completedAt=System.currentTimeMillis()));return@mutate "Proposal rejected."}
        val payload=JSONObject(row.payloadJson);val group=payload.getString("group_id")
        if(binding(group)!=row.bindingHash) {dao.update(row.copy(state="EXPIRED",resultJson="{\"error\":\"Item or photos changed; request a new proposal\"}",completedAt=System.currentTimeMillis()));return@mutate "Item changed. This proposal expired; request a fresh one."}
        val revision=payload.getLong("expectedRevision")
            val result=when(row.action) {
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
}
