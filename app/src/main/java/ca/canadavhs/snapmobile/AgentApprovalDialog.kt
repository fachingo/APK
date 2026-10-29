package ca.canadavhs.snapmobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.canadavhs.snapmobile.core.InventoryRules
import org.json.*
import java.math.BigDecimal
import java.math.RoundingMode

internal fun approvalTitle(action:String):String=when(action) {
    "propose_recognition","propose_recognition_batch"->"Run recognition"
    "propose_market_price","propose_market_price_batch","propose_sold_price"->"Apply reviewed prices"
    "propose_shopify_draft","propose_shopify_batch"->"Create Shopify drafts"
    "propose_batch_review"->"Create review task"
    "propose_inventory_update"->"Update inventory details"
    "propose_apply_recognition"->"Apply confirmed title"
    "propose_inventory_workflow"->"Review inventory workflow"
    "propose_batch_change"->"Review batch change"
    "propose_task_change"->"Review task change"
    "propose_archive"->"Review archive / restore"
    "propose_settings"->"Review public settings"
    "propose_shopify_management"->"Review Shopify management"
    "propose_shopify_status"->"Review Shopify status transition"
    "propose_shopify_publish"->"Authorize live publication"
    else->"Prepare local listing"
}
@Composable internal fun AgentApprovalDialog(row:AgentApprovalRow,error:String,busy:Boolean,dismiss:()->Unit,decide:(Boolean,AgentDecisionOptions)->Unit) {
    val repo=(androidx.compose.ui.platform.LocalContext.current.applicationContext as CaptureApplication).repository
    val payload=remember(row.id) {JSONObject(row.payloadJson)}
    var replace by rememberSaveable(row.id) {mutableStateOf(false)}
    var historical by rememberSaveable(row.id) {mutableStateOf(false)}
    var paid by rememberSaveable(row.id) {mutableStateOf(false)}
    var rate by rememberSaveable(row.id) {mutableStateOf("")}
    var date by rememberSaveable(row.id) {mutableStateOf("")}
    var adjustment by rememberSaveable(row.id) {mutableStateOf("")}
    var financial by rememberSaveable(row.id) {mutableStateOf(false)}
    var destructive by rememberSaveable(row.id) {mutableStateOf(false)}
    var confirmation by rememberSaveable(row.id) {mutableStateOf("")}
    val mixed=row.action=="propose_inventory_workflow"
    val archive=row.action=="propose_archive"
    val live=row.action=="propose_shopify_publish" || payload.optBoolean("requires_submit")
    var localError by remember(row.id) {mutableStateOf("")}
    val items=payload.optJSONArray("items") ?: JSONArray()
    val recommendations=payload.optJSONArray("recommendations") ?: JSONArray()
    val market=recommendations.length()>0
    val currency=payload.optString("currency","CAD")
    val paidApi=payload.optString("billing")=="SEPARATE_OPENAI_API_KEY"
    val method=payload.optString("method","MEDIAN")
    androidx.compose.ui.window.Dialog(onDismissRequest={if(!busy) dismiss()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
      Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),tonalElevation=6.dp) {
       Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(approvalTitle(row.action),style=MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(payload.optString("notice","Review this exact change. Approval expires if the selected inventory or photographs change."))
            if(payload.optJSONObject("recognition")!=null) {val config=payload.getJSONObject("recognition");Text("${config.getString("provider")} · ${config.getString("model")}");Text("Results remain suggestions. Confirm each printed title before applying it to inventory.")}
            if(row.action.startsWith("propose_shopify")) {val health=payload.optJSONObject("health");Text("Store: ${health?.optString("shop","paired store")}\nLocation: ${payload.optString("location_id",payload.optString("location"))}");Text(if(live) "LIVE publication · ${payload.optString("publication")}" else "Draft / selected metadata only; no live publication")}
            if(row.action=="propose_shopify_status") Text("Requested status/channel action: ${payload.optString("action")} · publication ${payload.optString("publication")}. Local approval does not publish; Active may be visible on existing channels.")
            if(row.action in setOf("propose_shopify_management","propose_shopify_status")) payload.optJSONObject("before")?.let {remote->Text("Target Shopify product: ${remote.optString("id")}\n${remote.optString("title")} · ${remote.optString("status")}");Text("Selected operation: ${if(row.action=="propose_shopify_status") "Only the reviewed status/channel transition" else if(payload.optJSONObject("patch")?.length()==0) "Link owned draft; retain all remote metadata" else "Update only the fields displayed below"}")}
            if(payload.has("patch")) ReviewPatch(payload.optJSONObject("before") ?: JSONObject(),payload.getJSONObject("patch"))
            if(row.action=="propose_batch_change") Text("${payload.getString("operation")} · ${payload.optString("beforeName")} → ${payload.optString("name")}\nLocation: ${payload.optString("location")}\nBatch ${payload.optString("session_id")}")
            if(row.action=="propose_task_change") Text("${payload.getString("operation")} · ${payload.optString("beforeTitle")} → ${payload.optString("title")}\nCurrent state: ${payload.optString("beforeState")}\nTask ${payload.optString("task_id")}\nSelected copy IDs: ${payload.optJSONArray("group_ids")}")
            if(market && currency!="CAD") {
                Text("Sold evidence is in $currency. Enter your own CAD conversion rate; no exchange rate is fetched.")
                OutlinedTextField(rate,{rate=it.take(32)},label={Text("CAD per 1 $currency")},singleLine=true)
                OutlinedTextField(date,{date=it.take(10)},label={Text("Rate date · YYYY-MM-DD")},singleLine=true)
            }
            for(i in 0 until items.length()) {val item=items.getJSONObject(i)
                HorizontalDivider();Text(item.optString("title").ifBlank {"Untitled copy"},style=MaterialTheme.typography.titleMedium)
                Text("SKU ${item.getString("itemId")} · ${item.optInt("photoCount")} photographs",style=MaterialTheme.typography.bodySmall)
                if(payload.optJSONObject("recognition")!=null && (!mixed || item.optBoolean("recognize"))) {val selected=item.optJSONArray("recognitionPhotoIds") ?: JSONArray();Row(Modifier.horizontalScroll(rememberScrollState())) {for(j in 0 until selected.length()) coil.compose.AsyncImage(repo.original(selected.getString(j)),"Selected recognition photograph",Modifier.size(96.dp))}}
                if(row.action in setOf("propose_shopify_management","propose_shopify_publish","propose_shopify_status")) {
                    Text("Quantity ${item.optInt("quantity")} · ${item.optInt("weightGrams")} g · ${item.optString("condition")}\n${item.optString("vendor")} · ${item.optString("productType")}\nTags: ${item.optString("tags")}\nBarcode: ${item.optString("reviewedBarcode",item.optString("barcode"))}\nCurrent local Shopify state: ${item.optString("shopifyState")}\n${item.optString("shopifyProductId")}")
                    Text(item.optString("description"))
                    val selectedPhotos=payload.optJSONObject("photos")?.optJSONArray(item.optString("groupId")) ?: JSONArray()
                    Row(Modifier.horizontalScroll(rememberScrollState())) {for(j in 0 until selectedPhotos.length()) {val photo=selectedPhotos.getJSONObject(j);coil.compose.AsyncImage(repo.original(photo.getString("id")),photo.optString("role"),Modifier.size(96.dp))}}
                }
                if(!item.isNull("priceCents")) Text("Current base: CAD ${InventoryRules.priceText(item.getLong("priceCents"))} · ${item.optString("priceSource")}")
                item.optJSONObject("proposedPatch")?.let {ReviewPatch(item,it)}
                if(mixed) Text(listOfNotNull("Run recognition".takeIf {item.optBoolean("recognize")},"Mark ready after validation".takeIf {item.optBoolean("markReady")},"Prepare offline listing".takeIf {item.optBoolean("prepareListing")}).joinToString(" · "))
                val r=(0 until recommendations.length()).map {recommendations.getJSONObject(it)}.firstOrNull {it.optString("groupId")==item.optString("groupId")}
                val selectedMethod=r?.optString("method",method) ?: method
                if(r!=null) {val cents=r.getLong(if(selectedMethod=="MEDIAN") "medianCents" else "averageCents")
                    Text("$selectedMethod: $currency ${InventoryRules.priceText(cents)} · ${r.getInt("count")} compatible sold records")
                    Text("Sales: ${r.getString("oldest").take(10)} – ${r.getString("newest").take(10)}",style=MaterialTheme.typography.bodySmall)
                    val cad=runCatching {BigDecimal(cents).multiply(if(currency=="CAD") BigDecimal.ONE else BigDecimal(rate)).setScale(0,RoundingMode.HALF_UP).longValueExact()}.getOrNull()
                    Text("New base: ${cad?.let {"CAD ${InventoryRules.priceText(it)}"} ?: "Enter conversion rate"}")
                    Text("Existing discount: ${BigDecimal(item.optInt("discountBasisPoints")).movePointLeft(2)}%. Price approval clears listing readiness.",style=MaterialTheme.typography.bodySmall)
                }
            }
            val listings=payload.optJSONArray("listings") ?: payload.optJSONObject("reviewedListing")?.let {JSONArray().put(it)} ?: JSONArray()
            for(i in 0 until listings.length()) {val l=listings.getJSONObject(i);HorizontalDivider();Text(l.optString("title"),style=MaterialTheme.typography.titleMedium)
                Text("SKU ${l.optString("itemId")} · CAD ${InventoryRules.priceText(l.optLong("priceCents"))}")
                Text("Quantity ${l.optInt("quantity",1)} · ${l.optInt("weightGrams")} g · ${l.optString("vendor")} · ${l.optString("productType")}")
                Text(android.text.Html.fromHtml(l.optString("descriptionHtml"),android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                val tags=l.optJSONArray("tags") ?: JSONArray();Text("Tags: "+(0 until tags.length()).joinToString(", ") {tags.getString(it)}+" · Barcode: "+l.optString("barcode"))
                val photos=l.optJSONArray("photos") ?: JSONArray();Row(Modifier.horizontalScroll(rememberScrollState())) {for(j in 0 until photos.length()) {val photo=photos.getJSONObject(j);coil.compose.AsyncImage(repo.original(photo.getString("id")),photo.optString("role"),Modifier.size(96.dp))}};Text("Upload ${photos.length()} photographs: "+(0 until photos.length()).joinToString(", ") {photos.getJSONObject(it).optString("role")})
            }
            if(items.length()==0 && listings.length()==0) {Text(payload.optString("currentTitle", "Selected copy: ${payload.optString("group_id")}"));if(payload.has("itemId")) Text("SKU ${payload.getString("itemId")}")
                if(payload.has("currentPriceCents") && !payload.isNull("currentPriceCents")) Text("Current base: CAD ${InventoryRules.priceText(payload.getLong("currentPriceCents"))}")
                payload.optJSONObject("soldReview")?.let {e->Text("${e.getInt("count")} compatible sold records · ${payload.optString("method")}: CAD ${InventoryRules.priceText(e.getLong("priceCents"))}\nEvidence collected ${e.getString("collectedAt")} · expires ${e.getString("expiresAt")}")}
                payload.optString("patch_json").takeIf {it.isNotBlank()}?.let {raw->val patch=JSONObject(raw);ReviewPatch(payload.optJSONObject("before") ?: JSONObject().put("title",payload.optString("currentTitle")),patch)}
                if(payload.has("recognizedTitle")) Text("Confirmed title → ${payload.getString("recognizedTitle")}")
                if(payload.has("evidence_id")) Text("Saved sold evidence · ${payload.optString("method")}. The saved evidence must still match the item.")
            }
            if(market && recommendations.length()==1) OutlinedTextField(adjustment,{adjustment=it.take(20)},label={Text("Optional adjusted base price · CAD")},supportingText={Text("Leave empty to use the displayed recommendation.")},singleLine=true)
            if(market || row.action=="propose_sold_price") ApprovalCheck("Allow replacing manual prices",replace) {replace=it}
            if(market) ApprovalCheck("Allow reuse of evidence older than my configured limit",historical) {historical=it}
            if(paidApi) ApprovalCheck("I approve separate OpenAI API-key charges for this recognition selection",paid) {paid=it}
            if(mixed && market) ApprovalCheck("I explicitly approve the reviewed price changes",financial) {financial=it}
            if(archive) ApprovalCheck("I explicitly approve archiving / restoring these exact copies",destructive) {destructive=it}
            if(live) OutlinedTextField(confirmation,{confirmation=it.take(6)},label={Text("Type SUBMIT for live publication")},singleLine=true)
            Text("Approval applies once to this exact selection. Completed external operations remain recorded in Tasks.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank() || localError.isNotBlank()) Text(localError.ifBlank {error},color=MaterialTheme.colorScheme.error)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            TextButton(enabled=!busy,onClick={decide(false,AgentDecisionOptions())}) {Text("Reject")}
            TextButton(enabled=!busy,onClick=dismiss) {Text("Close")}
            TextButton(enabled=!busy && (!paidApi || paid) && (!mixed || !market || financial) && (!archive || destructive) && (!live || confirmation=="SUBMIT"),onClick={
                localError=""
                val adjusted=if(adjustment.isBlank()) null else runCatching {BigDecimal(adjustment).movePointRight(2).longValueExact().also {require(it in 1..InventoryRules.maxPriceCents)}}.getOrElse {localError="Enter a positive CAD price with at most two decimal places.";return@TextButton}
                decide(true,AgentDecisionOptions(replace,historical,paid,rate.trim(),date.trim(),adjusted,allowFinancial=financial,allowDestructive=destructive,confirmation=confirmation))
            }) {Text(if(busy) "Applying…" else "Approve once")}
        }
       }
      }
    }
}
@Composable private fun ApprovalCheck(label:String,value:Boolean,change:(Boolean)->Unit) {Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Checkbox,onValueChange=change)) {Checkbox(value,null);Text(label,Modifier.padding(top=12.dp))}}

@Composable private fun ReviewPatch(before:JSONObject,patch:JSONObject) {
    patch.keys().forEach {key->Text(key,style=MaterialTheme.typography.labelLarge);Text("Before: ${if(before.has(key)) before.get(key) else "Not recorded; inspect item before approving"}");Text("After: ${patch.get(key)}")}
}
