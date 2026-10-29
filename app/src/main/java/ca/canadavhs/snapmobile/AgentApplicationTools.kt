package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*

internal class AgentApplicationTools(private val repo:CaptureRepository,private val legacy:AgentTools) {
    private val reads=AgentReadAccess(repo)
    fun definitions(full:Boolean):JSONArray {
        fun s(description:String)=JSONObject().put("type","string").put("description",description)
        fun tool(name:String,description:String,props:Map<String,JSONObject>)=JSONObject().put("type","function").put("name",name).put("description",description).put("strict",true).put("parameters",JSONObject().put("type","object").put("properties",JSONObject(props)).put("required",JSONArray(props.keys.toList())).put("additionalProperties",false))
        val result=JSONArray().put(tool("app_overview","Inspect current local SnapIMS counts, latest batch and actual queues. No model/broker call. This is an observation; static capabilities are not.",emptyMap()))
            .put(tool("app_schema","Discover compact typed query/action schemas. resource=all lists names; use a read resource name or action:<name> for arguments. No inventory is returned.",mapOf("resource" to s("all, a read resource, or action:<name>"))))
            .put(tool("app_read","Read/search/aggregate local non-secret data using an allowlisted resource and exact JSON arguments from app_schema. Paginate; never request raw SQL. Shopify resources are explicit broker reads.",mapOf("resource" to s("Typed resource from app_schema(all)"),"arguments_json" to s("JSON object matching its discovered schema; use {} for no arguments"))))
            .put(tool("app_navigate","Return a validated native navigation shortcut. It opens when the human taps it. For a photo, offer a compressed derivative for explicit attachment and Send; no image bytes leave the device automatically.",mapOf("screen" to JSONObject().put("type","string").put("enum",JSONArray(listOf("home","inventory","batches","market","recognition","shopify","tasks","settings","capture","pricing","backup","photo"))),"record_id" to s("Canonical group/session/task/photo UUID, or empty for a top-level screen"))))
        if(full) result.put(tool("app_propose","Prepare a one-use human review using an existing validated app service. Never executes changes. Discover action:<name> first. A mixed inventory workflow reviews metadata/prices/recognition/listing readiness for up to20 copies together. Only the human approves.",mapOf("action" to s("Allowlisted action from app_schema(all)"),"arguments_json" to s("Exact JSON object for that action"))))
        return result
    }
    suspend fun execute(chat:ChatConversationRow,name:String,args:JSONObject):JSONObject {
        fun fields(vararg names:String) {require(args.keys().asSequence().toSet()==names.toSet()) {"Unexpected tool arguments"}}
        return when(name) {
            "app_overview"->{fields();reads.overview().put("observedAt",java.time.OffsetDateTime.now().toString()).put("resource","app_overview")}
            "app_schema"->{fields("resource");val value=args.getString("resource");if(value.startsWith("action:")) AgentApplicationActions.schema(value.substringAfter(':')) else AgentReadAccess.schema(value)}
            "app_read"->{fields("resource","arguments_json");val raw=args.getString("arguments_json");require(raw.length<=6000);reads.read(chat,args.getString("resource"),JSONObject(raw))}
            "app_propose"->{fields("action","arguments_json");check(AgentPermission.of(chat)==AgentPermission.FULL_AGENT);val action=args.getString("action");require(action in AgentApplicationActions.actions);val raw=args.getString("arguments_json");require(raw.length<=120000)
                if(action in AgentApplicationActions.newActions) AgentApplicationActions(repo).propose(chat,action,JSONObject(raw)) else legacy.executeRaw(chat,action,JSONObject(raw))}
            "app_navigate"->{fields("screen","record_id");val screen=args.getString("screen");val id=args.getString("record_id");require(screen in listOf("home","inventory","batches","market","recognition","shopify","tasks","settings","capture","pricing","backup","photo"));require(screen!="photo" || id.isNotBlank()) {"Select an actual photo UUID before requesting an attachment shortcut."};if(id.isNotBlank()) {require(screen in listOf("inventory","recognition","market","batches","tasks","photo")) {"Use an empty record_id for this top-level screen."};requireUuid(id);when(screen) {
                "inventory","recognition","market"->check(repo.journal.inventoryDao().item(id)!=null) {"Local copy not found"}
                "batches"->repo.dao.session(id)
                "tasks"->check(repo.journal.agentTaskDao().get(id)!=null) {"Task not found"}
                "photo"->check(repo.dao.photo(id).state=="READY" && !repo.dao.photo(id).deleted) {"Photo unavailable"}
            }};JSONObject().put("navigation",JSONObject().put("screen",screen).put("recordId",id)).put("message",if(screen=="photo") "Photo metadata can be queried now. Human attachment of a compressed derivative and Send are required to expose pixels to the provider." else "Native shortcut prepared; tap to open. No record changed.")}
            else->error("Unknown application tool")
        }
    }
}
