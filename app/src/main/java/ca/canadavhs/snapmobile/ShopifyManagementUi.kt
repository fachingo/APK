package ca.canadavhs.snapmobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.canadavhs.snapmobile.core.*
import org.json.*

internal fun shopifyReviewTitle(kind:String)=when(kind) {"LINK_EXISTING"->"Link existing draft";"UPDATE_METADATA"->"Update selected metadata";"PUBLISH_LIVE"->"Publish selected drafts live?";"OWNER_ACTION"->"Review Shopify status change";else->"Approve Shopify drafts?"}
internal fun shopifyReviewedChannels(payload:JSONObject,health:JSONObject):String {
 val channels=payload.optJSONArray("publications") ?: JSONArray().put(payload.optString("publication"));val catalog=health.optJSONArray("publications") ?: JSONArray()
 return (0 until channels.length()).joinToString("\n") {i->val id=channels.getString(i);val channel=(0 until catalog.length()).map {catalog.getJSONObject(it)}.firstOrNull {it.optString("id")==id};"${channel?.optString("name")?.ifBlank {"Selected publication"} ?: "Selected publication"} · $id"}
}
@Composable internal fun ShopifyReviewDialog(row:ShopifyReviewRow,model:ShopifyModel,dismiss:()->Unit) {
    val repo=(LocalContext.current.applicationContext as CaptureApplication).repository
    val p=remember(row.id) {JSONObject(row.payloadJson)};val items=p.getJSONArray("items");val health=p.getJSONObject("health")
    var confirmation by rememberSaveable(row.id) {mutableStateOf("")}
    androidx.compose.ui.window.Dialog(onDismissRequest={if(!model.busy) dismiss()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
      Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),shape=RoundedCornerShape(24.dp),tonalElevation=6.dp) {
       Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(shopifyReviewTitle(row.kind),style=MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${health.getString("shop")} · ${health.getString("currency")} · ${if(health.getBoolean("developmentStore")) "development store" else "owner-operated production store"}")
            Text("Location: ${p.getString("location")}",style=MaterialTheme.typography.bodySmall)
            if(row.state!="PENDING") Text("Review ${row.state.lowercase()}. ${if(row.state=="APPLIED") "Open Shopify jobs for actual server outcomes." else "Prepare a fresh review to continue."}")
            val before=p.optJSONObject("before");val patch=p.optJSONObject("patch") ?: JSONObject()
            if(row.kind=="OWNER_ACTION" && p.has("batchReviews")) {
                Text("Publish ${p.getJSONArray("batchReviews").length()} exact selected products to every selected channel. One SUBMIT authorizes this entire set.")
                Text(shopifyReviewedChannels(p,health))
                val snapshots=p.getJSONArray("batchReviews");for(i in 0 until snapshots.length()) {val product=snapshots.getJSONObject(i).getJSONObject("payload").getJSONObject("before");Text("${product.getString("title")} · ${product.getString("id")} · ${product.getString("status")} → ACTIVE + selected channels")}
            } else if(row.kind=="OWNER_ACTION") {
                Text(before!!.getString("title"),style=MaterialTheme.typography.titleMedium);Text(before.getString("id"));Text("Current: ${before.getString("status")}")
                Text(when(p.getString("action")) {"APPROVE_LOCAL"->"Approve locally for review. Shopify remains unchanged.";"PUBLISH_LIVE"->"Set Active and publish to all selected channels:\n${shopifyReviewedChannels(p,health)}\nThis can make the product live on the website.";"ACTIVE"->"Set Active. This may become visible on channels where the product is already published. Active alone does not guarantee website publication.";"DRAFT"->"Set Draft. This removes the product from customer purchase availability.";else->"Archive this product in Shopify. It will stop being available for purchase; it is not deleted."})
                Text("Only this reviewed product and status/channel are changed. A remote change after review stops the job.")
            } else if(before!=null) {
                Text("${before.getString("title")} · ${before.getString("status")}",style=MaterialTheme.typography.titleMedium)
                Text(before.getString("id"),style=MaterialTheme.typography.bodySmall)
                Text(if(row.kind=="LINK_EXISTING") "Link this exact marked SKU to the selected physical copy. Local inventory metadata remains separate." else "Update only the fields listed below. Unselected fields remain protected. Images and publication require their separate workflows.")
                val variant=before.getJSONObject("variants").getJSONArray("nodes").getJSONObject(0)
                Text("Remote SKU: ${variant.getString("sku")} · CAD ${variant.getString("price")}")
                Text("${before.getJSONObject("media").getJSONArray("nodes").length()} remote images · remote updated ${before.optString("updatedAt")}",style=MaterialTheme.typography.bodySmall)
                patch.keys().forEach {key->HorizontalDivider();Text(metadataLabel(key),style=MaterialTheme.typography.titleSmall);Text("Shopify: ${before.optString(key)}");Text("Phone → ${patch.getString(key)}")}
            } else {
                Text(if(row.kind=="PUBLISH_LIVE") "Activate verified drafts and publish to the selected sales channel. Type SUBMIT to approve." else "Create drafts with the reviewed photographs, immutable SKU, CAD price, shipping weight and stock one.")
                if(row.kind=="PUBLISH_LIVE") Text("Publication: ${p.getString("publication")}")
            }
            for(i in 0 until items.length()) {val item=inventoryFromJson(items.getJSONObject(i));HorizontalDivider();Text(item.title.ifBlank {"Untitled copy"},style=MaterialTheme.typography.titleMedium);Text("SKU ${item.itemId}")
                if(before==null) {Text("CAD ${InventoryRules.priceText(item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)})} · ${item.weightGrams} g · stock ${item.quantity}");Text("${item.vendor} · ${item.productType}");Text(item.description);Text("Tags: ${item.tags}");Text("Barcode: ${items.getJSONObject(i).optString("reviewedBarcode").ifBlank {"None"}}")
                    val photos=p.optJSONObject("photos")?.optJSONArray(item.groupId) ?: JSONArray()
                    Row(Modifier.horizontalScroll(rememberScrollState())) {for(j in 0 until photos.length()) {val photo=photos.getJSONObject(j);coil.compose.AsyncImage(repo.original(photo.getString("id")),photo.getString("role"),Modifier.size(100.dp))}}
                    Text("${photos.length()} original-linked photographs in reviewed order")
                }
            }
            Text("One-use approval · local revision/photo binding · changes after review require another review.",style=MaterialTheme.typography.bodySmall)
            if(shopifyReviewNeedsSubmit(row) && row.state=="PENDING") OutlinedTextField(confirmation,{confirmation=it.take(20)},label={Text("SUBMIT")},singleLine=true)
            if(model.error.isNotBlank()) Text(model.error,color=MaterialTheme.colorScheme.error)
        }
        if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            if(row.state=="PENDING") TextButton(enabled=!model.busy,onClick={model.decideReview(row.id,false,"") {dismiss()}}) {Text("Reject")}
            TextButton(enabled=!model.busy,onClick=dismiss) {Text("Close")}
            if(row.state=="PENDING") Button(enabled=!model.busy && (!shopifyReviewNeedsSubmit(row) || confirmation=="SUBMIT"),onClick={model.decideReview(row.id,true,confirmation) {dismiss()}}) {Text("Approve once")}
        }
       }
      }
    }
}
internal fun metadataLabel(key:String)=when(key) {"descriptionHtml"->"Description";"productType"->"Product type";"vendor"->"Vendor";"tags"->"Tags";"priceCents"->"Price · CAD cents";"quantity"->"Inventory quantity";"weightGrams"->"Weight · grams";"sku"->"Immutable SKU";else->"Title"}
@Composable internal fun ShopifyListingsScreen(vm:CaptureViewModel,model:ShopifyModel,back:()->Unit) {
    BackHandler(onBack=back)
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val trash by remember {vm.repo.journal.trashDao().watchAll()}.collectAsStateWithLifecycle(emptyList())
    val hiddenProducts=trash.filter {it.kind=="PRODUCT"}.map {it.targetId}.toSet()
    val reviews by remember {vm.repo.journal.shopifyDao().watchReviews()}.collectAsStateWithLifecycle(emptyList())
    val isDirect=vm.repo.preferences.getBoolean("shopifyDirect",false)
    var ownerPublications by rememberSaveable {mutableStateOf(listOf<String>())};var publicationStore by rememberSaveable {mutableStateOf("")}
    LaunchedEffect(model.health) {model.health?.let {h->val all=h.getJSONArray("publications").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()};if(publicationStore!=h.getString("shop")) {ownerPublications=vm.repo.savedPublicationDefaults(h).sorted();publicationStore=h.getString("shop")} else ownerPublications=ownerPublications.filter {it in all}}}
    var query by rememberSaveable {mutableStateOf("")};var status by rememberSaveable {mutableStateOf("")};var location by rememberSaveable {mutableStateOf("")}
    var localSearch by rememberSaveable {mutableStateOf("")};var selected by rememberSaveable {mutableStateOf("")}
    var fields by rememberSaveable {mutableStateOf(listOf<String>())};var reviewId by rememberSaveable {mutableStateOf<String?>(null)}
    val selectedItem=inventory.firstOrNull {it.groupId==selected};val detail=model.productDetail
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Uploads & connection")};PageTitle("Shopify listings","Search, inspect and review exact physical-copy links")}
        if(!model.paired) item {Text("Connect and test your saved Shopify connection in Settings. Local inventory remains available offline.")}
        item {OutlinedTextField(query,{query=it.take(200)},label={Text("Search listing title")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("" to "All","DRAFT" to "Draft","ACTIVE" to "Active","ARCHIVED" to "Archived").forEach {(value,label)->FilterChip(selected=status==value,onClick={status=value},label={Text(label)})}}}
        item {Button(enabled=model.paired && !model.busy,onClick={model.browse(query,status,"")}) {Text("Search Shopify")}}
        if(model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
        if(model.message.isNotBlank()) item {Text(model.message)}
        model.health?.let {h->item {Text("${h.getString("shop")} · ${if(h.getBoolean("developmentStore")) "development store" else "owner-operated production store"}")
            Text("Inventory location for inspection");val locations=h.getJSONArray("locations");for(i in 0 until locations.length()) {val l=locations.getJSONObject(i);if(l.optBoolean("isActive")) FilterChip(selected=location==l.getString("id"),onClick={location=l.getString("id")},label={Text(l.getString("name"))})}
        }}
        model.catalog?.let {page->
            item {Text("${model.catalogQuery.ifBlank {"All titles"}} · ${model.catalogStatus.ifBlank {"all statuses"}} · ${model.catalogFetchedAt}",style=MaterialTheme.typography.bodySmall);Text("Results are a read snapshot. Preparing a review fetches the listing again.",style=MaterialTheme.typography.bodySmall)}
            val nodes=page.getJSONArray("nodes")
            if(nodes.length()==0) item {EmptyState("No matching listings","Change the title or status filter.")}
            items((0 until nodes.length()).map {nodes.getJSONObject(it)}.filter {stableRowId(it.getString("id")) !in hiddenProducts},key={it.getString("id")}) {product->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {
                Text("${product.getString("title")} · ${product.getString("status")}",style=MaterialTheme.typography.titleMedium)
                if(reviews.any {review->review.kind=="OWNER_ACTION" && JSONObject(review.resultJson).optJSONObject("outcome")?.optString("state")=="APPROVED_LOCALLY" && JSONObject(review.payloadJson).getJSONObject("before").let {it.getString("id")==product.getString("id") && it.optString("updatedAt")==product.optString("updatedAt")}}) Text("Approved locally · publishing still requires a separate review")
                val variants=product.getJSONObject("variants").getJSONArray("nodes");Text((0 until variants.length()).joinToString(" · ") {variants.getJSONObject(it).let {v->"${v.optString("sku").ifBlank {"No SKU"}} · ${v.optString("price")} · stock ${v.opt("inventoryQuantity") ?: "unknown"}"}})
                if(product.getString("status")=="DRAFT") TextButton(onClick={vm.action {vm.repo.hideShopifyDraft(product);vm.message="Draft hidden in SnapIMS. Shopify is unchanged."}}) {Text("Delete draft from app")}
                TextButton(enabled=location.isNotBlank() && !model.busy,onClick={model.inspect(product.getString("id"),location);fields=emptyList();selected=""}) {Text("Inspect listing")}
            }}}
            if(page.getJSONObject("pageInfo").optBoolean("hasNextPage")) item {OutlinedButton(enabled=!model.busy,onClick={model.browse(model.catalogQuery,model.catalogStatus,page.getJSONObject("pageInfo").getString("endCursor"))}) {Text("Next 20 listings")}}
        }
        if(detail!=null && stableRowId(detail.getString("id")) !in hiddenProducts) {
            item {HorizontalDivider();Text("Listing details",style=MaterialTheme.typography.titleLarge);Text("${detail.getString("title")} · ${detail.getString("status")}");Text(detail.getString("id"),style=MaterialTheme.typography.bodySmall)
                TextButton(enabled=!model.busy && location.isNotBlank(),onClick={model.inspect(detail.getString("id"),location)}) {Text("Refresh this listing")};Text(detail.getString("descriptionHtml"));val variants=detail.getJSONObject("variants").getJSONArray("nodes");for(i in 0 until variants.length()) {val v=variants.getJSONObject(i);Text("SKU ${v.getString("sku")} · CAD ${v.getString("price")}")}
                Text("${detail.getJSONObject("media").getJSONArray("nodes").length()} images · updated ${detail.getString("updatedAt")}")
            }
            if(isDirect) item {
                Text("Workflow: Draft → Approved locally → Active → Live on selected channel",style=MaterialTheme.typography.bodySmall)
                TextButton(enabled=!model.busy && location.isNotBlank(),onClick={model.prepareOwnerAction(detail.getString("id"),location,"APPROVE_LOCAL","") {reviewId=it}}) {Text("Review local approval")}
                Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("DRAFT" to "Set Draft","ACTIVE" to "Set Active","ARCHIVED" to "Archive on Shopify").forEach {(action,label)->TextButton(enabled=!model.busy && location.isNotBlank(),onClick={model.prepareOwnerAction(detail.getString("id"),location,action,"") {reviewId=it}}) {Text(label)}}}
                val publications=model.health?.optJSONArray("publications") ?: JSONArray()
                for(i in 0 until publications.length()) {val channel=publications.getJSONObject(i);FilterChip(selected=channel.getString("id") in ownerPublications,onClick={val id=channel.getString("id");ownerPublications=if(id in ownerPublications) ownerPublications-id else ownerPublications+id},label={Text(channel.getString("name"))})}
                TextButton(enabled=model.health!=null,onClick={vm.repo.savePublicationDefaults(ownerPublications.toSet(),model.health!!)}) {Text("Save default channels")}
                Button(enabled=!model.busy && location.isNotBlank() && ownerPublications.isNotEmpty(),onClick={model.prepareMultiOwner(detail.getString("id"),location,ownerPublications.toSet()) {reviewId=it}}) {Text("Review publication to selected channels")}
                Text("Online Store publication plus Active status is required for website availability. Changes always require the exact review and SUBMIT.",style=MaterialTheme.typography.bodySmall)
            }
            item {OutlinedTextField(localSearch,{localSearch=it.take(200)},label={Text("Find local copy by title or Item ID")},modifier=Modifier.fillMaxWidth());Text("Showing up to 30 matching copies. Refine the search to find a specific Item ID.",style=MaterialTheme.typography.bodySmall)}
            items(inventory.filter {!it.archived && (it.title.contains(localSearch,true) || it.itemId.contains(localSearch,true))}.take(30),key={"local-"+it.groupId}) {item->Row(Modifier.fillMaxWidth().selectable(selected=selected==item.groupId,enabled=!model.busy,role=Role.RadioButton,onClick={selected=item.groupId;fields=emptyList()})) {RadioButton(selected=selected==item.groupId,onClick=null);Text("${item.title.ifBlank {"Untitled copy"}}\n${item.itemId}",Modifier.padding(top=8.dp))}}
            if(selectedItem!=null) {
                item {Text("Selected: ${selectedItem.itemId}");Text("Linking requires this exact SKU and both existing SnapIMS identity markers. No marker is added to an unrelated product.",style=MaterialTheme.typography.bodySmall)
                    Button(enabled=model.paired && !model.busy && location.isNotBlank() && (detail.optString("status")=="DRAFT" || (isDirect && detail.optString("status")=="ACTIVE")),onClick={model.prepareManagement(selected,detail.getString("id"),location,emptySet()) {reviewId=it}}) {Text("Prepare link review")}}
                if(selectedItem.shopifyProductId==detail.getString("id") && isDirect && model.health==null) item {Text("Refresh the Shopify connection before selecting update fields.")}
                if(selectedItem.shopifyProductId==detail.getString("id") && (!isDirect || model.health!=null)) {
                    item {Text("Select metadata fields to update",style=MaterialTheme.typography.titleMedium)}
                    items(if(isDirect) directShopifyFields else shopifyMetadataFields) {key->val value=(if(isDirect) directShopifyMetadata(selectedItem,model.health!!.getString("shopId")) else shopifyMetadata(selectedItem)).opt(key).toString();Column {Row {Checkbox(key in fields,{fields=if(it) fields+key else fields-key});Text(metadataLabel(key),Modifier.padding(top=12.dp))};Text("Shopify: ${detail.optString(key)}\nPhone: $value")}}
                    item {Button(enabled=fields.isNotEmpty() && !model.busy && location.isNotBlank() && (detail.optString("status")=="DRAFT" || (isDirect && detail.optString("status")=="ACTIVE")),onClick={model.prepareManagement(selected,detail.getString("id"),location,fields.toSet()) {reviewId=it}}) {Text("Prepare metadata review")}}
                }
            }
        }
        item {Text("Saved reviews",style=MaterialTheme.typography.titleLarge)}
        items(reviews,key={"review-"+it.id}) {row->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)) {Text("${shopifyReviewTitle(row.kind)} · ${row.state}");TextButton(onClick={reviewId=row.id}) {Text(if(row.state=="PENDING") "Review saved proposal" else "View review receipt")};if(row.state=="APPLIED") {val result=JSONObject(row.resultJson);val channels=result.optJSONObject("outcome")?.optJSONArray("channelOutcomes") ?: result.optJSONArray("channelOutcomes") ?: JSONArray();for(i in 0 until channels.length()) {val c=channels.getJSONObject(i);Text("${c.optString("name")}: ${if(c.optBoolean("published")) "Verified published" else "Not confirmed"}")};Text("Job ${result.optString("jobId")} · ${result.optString("status","QUEUED")}",style=MaterialTheme.typography.bodySmall);result.optJSONObject("outcome")?.let {outcome->Text(if(outcome.optString("state")=="APPROVED_LOCALLY") "Approved locally; Shopify unchanged" else "Verified status ${outcome.optString("status")} · published to selected channel: ${if(outcome.optBoolean("publicationChecked")) outcome.optBoolean("publishedToSelectedChannel").toString() else "not checked by this status operation"}",style=MaterialTheme.typography.bodySmall)}}}}}
    }
    reviews.firstOrNull {it.id==reviewId}?.let {ShopifyReviewDialog(it,model) {reviewId=null}}
}
