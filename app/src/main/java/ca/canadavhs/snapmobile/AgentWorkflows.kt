package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*

internal val workflowActions=setOf("propose_recognition","propose_recognition_batch","propose_market_price","propose_market_price_batch","propose_shopify_draft","propose_shopify_batch","propose_batch_review")
internal suspend fun CaptureRepository.workflowBinding(groups:List<String>):String {
    val rows=JSONArray()
    groups.sorted().forEach {id->val item=journal.inventoryDao().item(id) ?: error("Item not found.");val photos=dao.photos(id).filter {!it.deleted}
        rows.put(JSONObject().put("barcodeOverride",dao.group(id).barcodeOverride ?: JSONObject.NULL).put("item",inventoryJson(item)).put("photos",JSONArray(photos.map {p->JSONObject().put("id",p.id).put("sha",p.sha256).put("position",p.position).put("role",p.role).put("state",p.state).put("bytes",p.bytes).put("barcodeJson",p.barcodeJson).put("scanState",p.scanState)})))
    };return sha256(canonicalJson(rows).toByteArray())
}
internal class AgentWorkflows(private val repo:CaptureRepository) {
    fun definitions(proposals:Boolean):JSONArray {
        fun str(description:String)=JSONObject().put("type","string").put("description",description)
        fun ids(max:Int=50)=JSONObject().put("type","array").put("items",str("Exact inventory group UUID")).put("minItems",1).put("maxItems",max)
        fun def(name:String,description:String,properties:Map<String,JSONObject>)=JSONObject().put("type","function").put("name",name).put("description",description).put("strict",true)
            .put("parameters",JSONObject().put("type","object").put("properties",JSONObject(properties)).put("required",JSONArray(properties.keys.toList())).put("additionalProperties",false))
        val result=JSONArray().put(def("capture_batches","Read up to 20 actual capture batches and their session UUIDs for batch_review. Local read only.",mapOf("offset" to JSONObject().put("type","integer").put("minimum",0).put("maximum",1000000)))).put(def("batch_review","Read 20 local physical copies from a capture batch, actual readiness and validation issues. Does not change anything.",mapOf("session_id" to str("Exact capture session UUID"),"offset" to JSONObject().put("type","integer").put("minimum",0).put("maximum",1000000))))
            .put(def("task_status","Read actual approved task outcomes and owning recognition/Shopify jobs. Android work completion alone is not remote success.",mapOf("task_id" to str("Exact task UUID"))))
            .put(def("shopify_connection","Read the paired broker's actual development-store connection and locations. No product write or credential is returned.",emptyMap()))
        if(proposals) {
            result.put(def("propose_recognition","Propose recognition with the configured Settings provider/model. Requires operator approval. OPENAI uses separately billed API-key recognition even if this conversation uses a ChatGPT plan. Never confirms/applies titles.",mapOf("group_id" to str("Exact group UUID"))))
                .put(def("propose_recognition_batch","Propose recognition for an exact list of up to 50 copies. Each configured API request requires explicit paid-recognition consent in the single batch review; no automatic title application.",mapOf("group_ids" to ids())))
            val price=mapOf("group_id" to str("Exact saved inventory group UUID"),"currency" to JSONObject().put("type","string").put("enum",JSONArray(listOf("CAD","USD"))),"method" to JSONObject().put("type","string").put("enum",JSONArray(listOf("MEDIAN","AVERAGE"))))
            result.put(def("propose_market_price","Propose applying a real local sold recommendation. Operator enters any FX rate/date and approves manual/historical overrides. Cannot invent a price or rate.",price))
                .put(def("propose_market_price_batch","Propose prices for up to 20 selected copies. Exact source facts are reviewed together; approval is atomic. No price changes before explicit approval; FX is operator input only.",price.filterKeys {it!="group_id"}+("group_ids" to ids(20))))
                .put(def("propose_shopify_batch","Propose a DRAFT-only upload of up to 50 ready physical copies to the paired development store. Requires review of every listing and photograph transfer; never publishes live.",mapOf("group_ids" to ids(),"location_id" to str("Active Shopify Location GID from shopify_connection"))))
                .put(def("propose_batch_review","Propose a local task to review an exact set of copies. Does not change inventory, bill a provider, upload or publish.",mapOf("group_ids" to ids())))
        };return result
    }
    suspend fun execute(chat:ChatConversationRow,name:String,args:JSONObject):JSONObject {
        check(chat.toolsEnabled)
        if(name=="task_status") {require(args.keys().asSequence().toSet()==setOf("task_id"));val row=repo.journal.agentTaskDao().get(requireUuid(args.getString("task_id"))) ?: error("Task not found.");val s=repo.agentTaskStatus(row)
            return JSONObject().put("taskId",row.id).put("kind",row.kind).put("state",s.state).put("completed",s.done).put("total",s.total).put("detail",s.detail).put("jobs",s.jobs).put("result",JSONObject(row.resultJson))}
        if(name=="shopify_connection") {require(args.length()==0);return shopifyHealth().second}
        if(name=="capture_batches") {
            require(args.keys().asSequence().toSet()==setOf("offset"));val offset=args.getInt("offset");require(offset in 0..1000000)
            val rows=JSONArray();repo.journal.openHelper.writableDatabase.query("SELECT s.id,s.name,s.finished,COUNT(i.groupId) AS copies FROM sessions s LEFT JOIN groups g ON g.sessionId=s.id LEFT JOIN inventory i ON i.groupId=g.id AND i.archived=0 WHERE s.id NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH') GROUP BY s.id ORDER BY s.createdAt DESC,s.id LIMIT 21 OFFSET ?",arrayOf(offset)).use {c->while(c.moveToNext()) rows.put(JSONObject().put("sessionId",c.getString(0)).put("name",c.getString(1)).put("finished",c.getInt(2)==1).put("copies",c.getLong(3)))}
            val more=rows.length()>20;if(more) rows.remove(20);return JSONObject().put("batches",rows).put("nextOffset",if(more) offset+20 else JSONObject.NULL)
        }
        if(name=="batch_review") {
            require(args.keys().asSequence().toSet()==setOf("session_id","offset"));val id=requireUuid(args.getString("session_id"));val offset=args.getInt("offset");require(offset in 0..1000000);val session=repo.dao.session(id);check(repo.journal.trashDao().target("BATCH",id)==null) {"Batch is in Trash."}
            val db=repo.journal.openHelper.writableDatabase;val ids=db.query("SELECT i.groupId FROM inventory i JOIN groups g ON g.id=i.groupId WHERE g.sessionId=? AND i.archived=0 ORDER BY g.number LIMIT 20 OFFSET ?",arrayOf<Any?>(id,offset)).use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
            val count=db.query("SELECT COUNT(*) FROM inventory i JOIN groups g ON g.id=i.groupId WHERE g.sessionId=? AND i.archived=0",arrayOf(id)).use {it.moveToFirst();it.getLong(0)}
            val items=JSONArray();ids.forEach {group->val item=repo.journal.inventoryDao().item(group)!!;items.put(reviewItem(item).put("problems",JSONArray(inventoryProblems(item,repo.dao.photos(group),repo))))}
            return JSONObject().put("batchName",session.name).put("total",count).put("offset",offset).put("nextOffset",if(offset+ids.size<count) offset+ids.size else JSONObject.NULL).put("items",items)
        }
        require(name in workflowActions);check(chat.proposalsEnabled) {"Change proposals are disabled for this chat."}
        val single=name in setOf("propose_recognition","propose_market_price","propose_shopify_draft")
        val expected=(if(single) setOf("group_id") else setOf("group_ids")) + when(name) {"propose_market_price","propose_market_price_batch"->setOf("currency","method");"propose_shopify_draft","propose_shopify_batch"->setOf("location_id");else->emptySet()}
        require(args.keys().asSequence().toSet()==expected) {"Unexpected workflow fields. Prices and conversion rates are operator-controlled."}
        val groups=if(single) listOf(requireUuid(args.getString("group_id"))) else jsonIds(args.getJSONArray("group_ids"),if(name=="propose_market_price_batch") 20 else 50)
        val shop=if(name.startsWith("propose_shopify")) shopifyHealth() else null
        return repo.mutate {
            val items=groups.map {repo.journal.inventoryDao().item(it) ?: error("Item not found.")};check(items.none {it.archived}) {"Restore archived copies before proposing work."}
            val payload=JSONObject().put("group_ids",JSONArray(groups)).put("bindingVersion",2).put("items",JSONArray(items.map {reviewItem(it)}))
            when {
                name.startsWith("propose_recognition")->{val settings=repo.recognitionSettings();items.forEach {item->val photos=repo.dao.photos(item.groupId).filter {!it.deleted};check(photos.isNotEmpty() && photos.all {it.state=="READY"}) {"Resolve unfinished photographs before recognition."}}
                    payload.put("recognition",settings.json()).put("billing",if(settings.provider=="OPENAI") "SEPARATE_OPENAI_API_KEY" else "OFFLINE_NO_PROVIDER_BILL")
                    payload.put("notice",if(settings.provider=="OPENAI") "Approval sends up to three image derivatives per selected copy to conventional OpenAI API-key recognition. API charges are separate from ChatGPT plan usage. No billing fallback is selected automatically." else "Recognition runs on this device. Results remain suggestions; confirm the printed title separately.")}
                name.startsWith("propose_market_price")->{val currency=args.getString("currency");val method=args.getString("method");require(currency in listOf("CAD","USD") && method in listOf("MEDIAN","AVERAGE"));repo.ensureMarketSearch()
                    val recommendations=items.map {repo.recommendMarketLocked(it,currency) ?: error("No compatible $currency sold evidence for ${it.itemId}. No price was invented.")}
                    payload.put("currency",currency).put("method",method).put("recommendations",JSONArray(recommendations.map(::marketRecommendationJson)))
                        .put("notice","Only reviewed sold facts support these prices. Any CAD conversion rate/date, manual override and historical reuse are entered or approved by the operator. The entire selection commits or rolls back together.")}
                name.startsWith("propose_shopify")->{val (base,health)=shop!!;check(repo.preferences.getBoolean("shopifyPaired",false) && repo.preferences.getString("shopifyBroker","")==base)
                    check(health.getBoolean("developmentStore") && health.getString("currency")=="CAD") {"Choose a CAD Shopify development store."}
                    val location=args.getString("location_id");check(health.getJSONArray("locations").let {a->(0 until a.length()).any {a.getJSONObject(it).getString("id")==location && a.getJSONObject(it).optBoolean("isActive")}}) {"Choose an active location from shopify_connection."}
                    val listings=JSONArray();items.forEach {item->val photos=repo.dao.photos(item.groupId).filter {!it.deleted}.sortedBy {it.position};check(repo.shopifyProblems(item,photos).isEmpty()) {"${item.itemId}: ${repo.shopifyProblems(item,photos).joinToString(". ")}"}
                        val listing=JSONObject().put("itemId",item.itemId).put("title",item.title).put("descriptionHtml",shopifyDescription(item))
                            .put("vendor",item.vendor).put("productType",item.productType).put("tags",JSONArray(shopifyTags(item)))
                            .put("priceCents",InventoryRules.discounted(item.priceCents!!,item.discountBasisPoints)).put("quantity",1).put("weightGrams",item.weightGrams)
                            .put("barcode",effectiveBarcode(item,repo.dao.group(item.groupId),photos))
                            .put("photos",JSONArray(photos.map {p->JSONObject().put("id",p.id).put("role",p.role)}))
                        listings.put(listing)
                    }
                    payload.put("broker",base).put("health",health).put("location_id",location).put("connectionFingerprint",shopifyConnectionFingerprint(repo.context,base)).put("listings",listings).put("notice","Approval sends every selected listing and its photographs to Shopify as DRAFT. No live publication.")}
                else->payload.put("notice","Create a local review task. Every physical copy still needs its own inventory review before listing.")
            }
            check(payload.toString().length<=900000) {"Review a smaller batch; this proposal exceeds the safe review/backup size."}
            val approval=AgentApprovalRow(conversationId=chat.id,action=name,payloadJson=payload.toString(),bindingHash=repo.workflowBinding(groups));repo.journal.nextDao().insert(approval)
            JSONObject().put("approval_id",approval.id).put("state","PENDING_OPERATOR_REVIEW").put("itemCount",groups.size).put("message","Nothing executed. Review the exact action, selected copies and any separate API charges in SnapIMS.")
        }
    }
    private suspend fun reviewItem(item:InventoryRow):JSONObject {
        val photos=repo.dao.photos(item.groupId).filter {!it.deleted};return JSONObject().put("groupId",item.groupId).put("itemId",item.itemId).put("title",item.title).put("revision",item.revision).put("ready",item.ready).put("priceCents",item.priceCents ?: JSONObject.NULL).put("priceSource",item.priceSource).put("discountBasisPoints",item.discountBasisPoints).put("edition",item.edition).put("releaseYear",item.releaseYear ?: JSONObject.NULL)
            .put("photoCount",photos.size).put("recognitionPhotoIds",JSONArray(recognitionSourcePhotos(photos.filter {it.state=="READY"}).map {it.id}))
    }
    private suspend fun shopifyHealth():Pair<String,JSONObject> {
        check(repo.preferences.getBoolean("shopifyPaired",false)) {"Pair Shopify in Settings first."};val base=repo.preferences.getString("shopifyBroker","").orEmpty();requireBrokerUrl(base)
        return base to ShopifyBrokerClient(repo.context,base).call(newId(),"health",JSONObject())
    }
    suspend fun applyLocked(approval:AgentApprovalRow,payload:JSONObject,options:AgentDecisionOptions):AgentTaskRow {
        val groups=jsonIds(payload.getJSONArray("group_ids"));val items=payload.getJSONArray("items");val refs=JSONArray();var state="SCHEDULE_PENDING";var kind="";var title="";val result=JSONObject()
        when {
            approval.action.startsWith("propose_recognition")->{val j=payload.getJSONObject("recognition");val config=RecognitionSettings(j.getString("provider"),j.getString("model"),j.getString("authBinding"));check(config==repo.recognitionSettings()) {"Recognition settings/credentials changed. Request a fresh proposal."}
                check(config.provider!="OPENAI" || options.allowPaidRecognition) {"Explicitly approve separately billed OpenAI API-key recognition, or choose an offline mode in Settings."}
                groups.forEach {refs.put(repo.queueRecognitionLocked(it,config,approval.id).id)};kind="RECOGNITION";title="Recognize ${groups.size} ${if(groups.size==1) "copy" else "copies"}";result.put("provider",config.provider).put("model",config.model).put("paidApiApproved",options.allowPaidRecognition)}
            approval.action.startsWith("propose_market_price")->{val recommendations=payload.getJSONArray("recommendations");check(recommendations.length()==groups.size);check(groups.size==1 || options.adjustedCents==null) {"Batch prices use the displayed deterministic recommendation; review adjustments individually."}
                val prices=JSONArray();for(i in groups.indices) {val r=marketRecommendationFromJson(recommendations.getJSONObject(i));check(r.groupId==groups[i]);val saved=repo.applyMarketPriceLocked(r,payload.getString("method"),options.adjustedCents,options.replaceManual,options.allowHistorical,options.rate,options.rateDate)
                    prices.put(JSONObject().put("itemId",saved.itemId).put("priceCents",saved.priceCents).put("currency","CAD").put("method",payload.getString("method")))}
                kind="MARKET_PRICE";title="Price ${groups.size} ${if(groups.size==1) "copy" else "copies"}";state="COMPLETE";result.put("prices",prices)}
            approval.action.startsWith("propose_shopify")->{val base=payload.getString("broker");check(repo.preferences.getBoolean("shopifyPaired",false) && repo.preferences.getString("shopifyBroker","")==base && shopifyConnectionFingerprint(repo.context,base)==payload.getString("connectionFingerprint")) {"Shopify connection changed. Request a fresh review."}
                refs.put(repo.queueShopifyLocked(groups.toSet(),payload.getJSONObject("health"),base,payload.getString("location_id")));kind="SHOPIFY_DRAFT";title="Create ${groups.size} Shopify drafts"}
            approval.action=="propose_batch_review"->{kind="REVIEW_BATCH";title="Review ${groups.size} physical copies";state="OPEN"}
            else->error("Unsupported workflow action.")
        }
        val task=AgentTaskRow(approvalId=approval.id,conversationId=approval.conversationId,kind=kind,title=title,groupIdsJson=JSONArray(groups).toString(),jobIdsJson=refs.toString(),state=state,resultJson=result.toString());repo.journal.agentTaskDao().insert(task);return task
    }
}
