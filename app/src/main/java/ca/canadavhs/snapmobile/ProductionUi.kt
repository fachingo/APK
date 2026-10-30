package ca.canadavhs.snapmobile

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*

internal data class RecognizedTitleReview(val groupId:String,val recognitionId:String,val title:String,val revision:Long)
internal class ProductionModel(app:Application):AndroidViewModel(app) {
 val repo=(app as CaptureApplication).repository
 var busy by mutableStateOf(false);private set
 var message by mutableStateOf("");private set
 var error by mutableStateOf("");private set
 var health by mutableStateOf<JSONObject?>(null);private set
 var skipped by mutableStateOf<Map<String,String>>(emptyMap());private set
 var review by mutableStateOf<String?>(null);private set
 var selected by mutableStateOf<Set<String>>(emptySet());private set
 private var scopeKey=""
 fun selection(scope:String,valid:Set<String>) {if(scopeKey!=scope) {scopeKey=scope;selected=repo.preferences.getStringSet("batchSelection.$scope",emptySet()).orEmpty().toSet()}}
 fun setSelection(ids:Set<String>) {selected=ids.toSet();repo.preferences.edit().putStringSet("batchSelection.$scopeKey",selected).commit()}
 fun action(block:suspend()->Unit) {if(busy) return;busy=true;viewModelScope.launch {try {block();error=""} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message.orEmpty()} finally {busy=false}}}
 fun connect()=action {health=shopifyGateway(repo.context,repo.shopifyEndpoint()).call(newId(),"health",JSONObject());message="Store, scopes, locations and available publications refreshed. No write performed."}
 fun drafts(ids:Set<String>,location:String,channels:Set<String>)=action {val configured=health!=null;val h=shopifyGateway(repo.context,repo.shopifyEndpoint()).call(newId(),"health",JSONObject());health=h;val result=repo.submitSelectedDrafts(ids,h,repo.shopifyEndpoint(),location.ifBlank {savedLocation(h)},if(configured) channels else savedChannels(h));skipped=result.skipped;message="${ids.size-result.skipped.size} selected copies queued in ${result.jobs.size} draft jobs; ${result.skipped.size} skipped. Drafts are not live."}
 fun publish(ids:Set<String>,location:String,channels:Set<String>)=action {val configured=health!=null;val gateway=shopifyGateway(repo.context,repo.shopifyEndpoint());val h=gateway.call(newId(),"health",JSONObject());health=h;review=repo.prepareShopifyOwnerBatch(ids,location.ifBlank {savedLocation(h)},if(configured) channels else savedChannels(h),h,gateway).id}
 private fun savedChannels(h:JSONObject):Set<String> {
  val available=h.getJSONArray("publications").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()}
  return (repo.preferences.getStringSet("batchPublications.${h.getString("shop")}.$scopeKey",null) ?: repo.savedPublicationDefaults(h)).intersect(available)
 }
 private fun savedLocation(h:JSONObject):String {val saved=repo.preferences.getString("shopifyDefaultLocation","").orEmpty();val a=h.getJSONArray("locations");val active=(0 until a.length()).map {a.getJSONObject(it)}.filter {it.optBoolean("isActive")};return active.firstOrNull {it.getString("id")==saved}?.getString("id") ?: active.firstOrNull()?.getString("id") ?: error("No active inventory location; open Shopify destinations")}
 fun closeReview() {review=null}
 fun run(ids:Set<String>,kind:String,options:JSONObject=JSONObject(),ai:Boolean=false)=action {val job=repo.queueProduction(ids,kind,options,ai);message="${ids.size} selected copies queued · $job. No item outside the selection is processed."}
 fun applyPrices(ids:Set<String>)=action {val result=repo.applyProductionPrices(ids);skipped=result.second;message="${result.first} recommended prices applied; ${result.second.size} skipped. Sold evidence and audit receipts retained."}
 fun applyContent(ids:Set<String>)=action {val result=repo.applyProductionContent(ids);skipped=result.second;message="${result.first} reviewed missing descriptions applied; ${result.second.size} protected/changed items skipped."}
 fun recognition(ids:Set<String>,approved:Boolean)=action {val settings=repo.recognitionSettings();check(settings.provider!="OPENAI" || approved) {"Authorize this recognition batch first"};val failures=linkedMapOf<String,String>();var queued=0
  for(id in ids) try {val row=repo.queueRecognition(id);repo.scheduleRecognition(row);queued++} catch(e:CancellationException) {throw e} catch(e:Exception) {failures[id]=e.message.orEmpty()};skipped=failures;message="$queued recognition jobs queued; ${failures.size} skipped. Inspect suggested titles before applying."}
 fun applyRecognized(reviewed:List<RecognizedTitleReview>)=action {var applied=0;val errors=linkedMapOf<String,String>();for(r in reviewed) try {repo.mutate {val row=repo.dao.recognition(r.recognitionId) ?: error("No completed recognition");check(row.groupId==r.groupId && row.state=="SUCCEEDED");val title=(row.acceptedTitle ?: JSONObject(row.resultJson).optString("title")).trim();check(title==r.title && title.isNotBlank() && !title.equals("UNKNOWN",true) && title.length<=240) {"Suggestion changed or has no reliable title"};val old=repo.journal.inventoryDao().items().firstOrNull {it.groupId==r.groupId && !it.archived} ?: error("Deleted record");check(old.revision==r.revision) {"Record changed after title review"};check(old.title.isBlank() || old.recognitionId==row.id) {"Existing operator title protected"};repo.dao.update(row.copy(acceptedTitle=title));repo.applyRecognitionLocked(row.id)};applied++} catch(e:CancellationException) {throw e} catch(e:Exception) {errors[r.groupId]=e.message.orEmpty()};skipped=errors;message="$applied reviewed titles applied; ${errors.size} protected/uncertain items skipped"}
}
internal fun productionFilter(item:InventoryRow,filter:String,price:ProductionItemRow?,recognition:List<RecognitionRow>,failed:Set<String>):Boolean=when(filter) {
 "Recognized"->item.title.isNotBlank();"Matched"->price?.state in setOf("HIGH_CONFIDENCE","APPLIED") || item.priceSource.startsWith("MARKET_");"Priced"->item.priceCents!=null
 "Needs review"->price?.state in setOf("NEEDS_REVIEW","ERROR","NO_RELIABLE_MATCH") || item.title.isBlank() || item.priceCents==null || item.review
 "Ready for drafts"->item.ready && item.shopifyProductId.isBlank();"Drafted"->item.shopifyState in setOf("UPLOADED_AS_DRAFT","LINKED_DRAFT");"Active"->item.shopifyState in setOf("ACTIVE","LINKED_ACTIVE","PUBLISHED")
 "Published"->item.shopifyState=="PUBLISHED";"Failed jobs"->item.groupId in failed || recognition.any {it.groupId==item.groupId && it.state=="ERROR"};"Unpriced"->item.priceCents==null;"Unlisted"->item.shopifyProductId.isBlank();else->true
}
@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable internal fun ProductionBatchScreen(vm:CaptureViewModel,session:SessionRow?,onPhotos:()->Unit,onBatch:(String)->Unit={},onInventory:(()->Unit)?=null,model:ProductionModel=viewModel(),shopify:ShopifyModel=viewModel()) {
 BackHandler(onBack=onPhotos)
 val inventory by vm.inventory.collectAsStateWithLifecycle();val groups by vm.groups.collectAsStateWithLifecycle();val recognition by vm.recognition.collectAsStateWithLifecycle();val photos by vm.photos.collectAsStateWithLifecycle();val sessions by vm.sessions.collectAsStateWithLifecycle()
 val jobs by remember(session?.id) {vm.repo.journal.productionDao().watchBatchJobs(session?.id)}.collectAsStateWithLifecycle(emptyList());val results by vm.repo.journal.productionDao().watchItems().collectAsStateWithLifecycle(emptyList())
 val channelStates by vm.repo.journal.productionDao().watchChannels().collectAsStateWithLifecycle(emptyList())
 val shopJobs by vm.repo.journal.shopifyDao().watchJobs().collectAsStateWithLifecycle(emptyList());val shopItems by vm.repo.journal.shopifyDao().watchItems().collectAsStateWithLifecycle(emptyList());val reviews by vm.repo.journal.shopifyDao().watchReviews().collectAsStateWithLifecycle(emptyList())
 val groupSet=groups.filter {session==null || it.sessionId==session.id}.map {it.id}.toSet();val records=inventory.filter {!it.archived && it.groupId in groupSet};val ids=records.map {it.groupId}.toSet()
 LaunchedEffect(session?.id,ids) {model.selection(session?.id ?: "ALL",ids)}
 var moreActions by rememberSaveable(session?.id) {mutableStateOf(false)}
 val listState=rememberLazyListState()
 var filter by rememberSaveable(session?.id) {mutableStateOf("All")};var search by rememberSaveable(session?.id) {mutableStateOf("")};var location by rememberSaveable {mutableStateOf(vm.repo.preferences.getString("shopifyDefaultLocation","").orEmpty())};var channels by remember(session?.id) {mutableStateOf<Set<String>>(emptySet())};var channelStore by rememberSaveable(session?.id) {mutableStateOf("")}
 var rulesOpen by remember {mutableStateOf(false)};var recognitionConsent by remember {mutableStateOf(false)};var applyTitles by remember {mutableStateOf(false)};var titleReviews by remember {mutableStateOf<List<RecognizedTitleReview>>(emptyList())};var aiKind by remember {mutableStateOf("")};var details by remember {mutableStateOf<ProductionItemRow?>(null)};var historyJob by remember {mutableStateOf<String?>(null)};var contentReview by remember {mutableStateOf<List<ProductionItemRow>>(emptyList())}
 var itemId by rememberSaveable {mutableStateOf<String?>(null)}
 val priceResults=results.filter {r->jobs.firstOrNull {it.id==r.jobId}?.kind=="MATCH_PRICE"}.associateBy {it.groupId}
 val failed=shopItems.filter {it.state in setOf("NEEDS_REVIEW","RETRYABLE","UNCERTAIN")}.map {it.groupId}.toSet()+results.filter {it.state in setOf("ERROR","UNCERTAIN")}.map {it.groupId}
 var readyIds by remember {mutableStateOf<Set<String>>(emptySet())}
 LaunchedEffect(records,photos) {readyIds=withContext(Dispatchers.IO) {records.filter {item->vm.repo.shopifyProblems(item.copy(ready=true),photos.filter {it.groupId==item.groupId && !it.deleted}).isEmpty()}.map {it.groupId}.toSet()}}
 val selected=model.selected.intersect(ids);val selectedResults=priceResults.values.filter {it.groupId in selected && it.state=="HIGH_CONFIDENCE"}.map {it.id}.toSet()
 LaunchedEffect(model.health,session?.id) {model.health?.let {h->val available=h.getJSONArray("publications").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()};val shop=h.getString("shop");if(channelStore!=shop) {channels=(vm.repo.preferences.getStringSet("batchPublications.$shop.${session?.id ?: "ALL"}",null) ?: vm.repo.savedPublicationDefaults(h)).intersect(available);channelStore=shop} else channels=channels.intersect(available);if(location.isBlank()) {val a=h.getJSONArray("locations");location=(0 until a.length()).map {a.getJSONObject(it)}.firstOrNull {it.optBoolean("isActive")}?.getString("id").orEmpty()}}}
 val shown=records.filter {(if(filter.startsWith("channel:")) channelStates.any {c->c.productId==it.shopifyProductId && c.publicationId==filter.removePrefix("channel:") && c.published && it.shopifyState in setOf("ACTIVE","PUBLISHED","LINKED_ACTIVE")} else if(filter=="Ready for drafts") it.groupId in readyIds else productionFilter(it,filter,priceResults[it.groupId],recognition,failed)) && (it.title.contains(search,true) || it.itemId.contains(search,true))}
 if(itemId!=null) {InventoryScreen(vm,reviewPhotos={},initialGroupId=itemId,onExit={itemId=null});return}
 val legacyActions:LazyListScope.()->Unit = {
  item {Text("Selection & channel filters",style=MaterialTheme.typography.titleMedium)}
  item {FlowRow {channelStates.map {it.publicationId to it.name}.distinct().forEach {(id,name)->val count=records.count {item->channelStates.any {it.productId==item.shopifyProductId && it.publicationId==id && it.published} && item.shopifyState in setOf("ACTIVE","LINKED_ACTIVE","PUBLISHED")};FilterChip(selected=filter=="channel:$id",onClick={filter="channel:$id"},label={Text("$name verified $count")})}}}
  item {Text("${selected.size} selected of ${records.size}",style=MaterialTheme.typography.titleMedium);FlowRow {listOf("Select All","Deselect All","Invert Selection","Select ready","Select unpriced","Select unlisted").forEach {action->TextButton(onClick={model.setSelection(when(action) {"Select All"->ids;"Deselect All"->emptySet();"Invert Selection"->ids-selected;"Select ready"->readyIds;"Select unpriced"->records.filter {it.priceCents==null}.map {it.groupId}.toSet();else->records.filter {it.shopifyProductId.isBlank()}.map {it.groupId}.toSet()})}) {Text(action)}}}}
  item {Text("Review & pricing",style=MaterialTheme.typography.titleMedium);Column {
   OutlinedButton(enabled=selected.isNotEmpty() && !model.busy,onClick={titleReviews=records.filter {it.groupId in selected}.map {item->val r=recognition.firstOrNull {it.groupId==item.groupId && it.state=="SUCCEEDED"};RecognizedTitleReview(item.groupId,r?.id.orEmpty(),r?.let {(it.acceptedTitle ?: JSONObject(it.resultJson).optString("title")).trim()}.orEmpty(),item.revision)};applyTitles=true}) {Text("Review recognized titles")}
   TextButton(onClick={rulesOpen=true}) {Text("Pricing rules")}
   Button(enabled=selectedResults.isNotEmpty() && !model.busy,onClick={model.applyPrices(selectedResults)}) {Text("Apply ${selectedResults.size} eligible prices")}
  }}
  item {Disclosure("Optional OpenAI assistance","Paid requests · explicit budget review") {FlowRow {listOf("AI_MATCH" to "Resolve difficult matches","AI_METADATA" to "Generate AI descriptions","AI_EDITION" to "Identify difficult editions").forEach {(kind,label)->TextButton(enabled=selected.isNotEmpty() && !model.busy,onClick={aiKind=kind}) {Text(label)}}};val content=results.filter {it.groupId in selected && it.state=="NEEDS_REVIEW" && jobs.firstOrNull {j->j.id==it.jobId}?.kind=="AI_METADATA"};if(content.isNotEmpty()) TextButton(onClick={contentReview=content.toList()},enabled=!model.busy) {Text("Review ${content.size} missing descriptions")}}}
  item {HorizontalDivider();Text("Shopify",style=MaterialTheme.typography.titleLarge);OutlinedButton(enabled=!model.busy,onClick=model::connect) {Text("Refresh saved Shopify connection")}}
  model.health?.let {h->
   item {Text(h.getString("name"));val locations=h.getJSONArray("locations");for(i in 0 until locations.length()) {val loc=locations.getJSONObject(i);if(loc.optBoolean("isActive")) FilterChip(selected=location==loc.getString("id"),onClick={location=loc.getString("id");vm.repo.preferences.edit().putString("shopifyDefaultLocation",location).apply()},label={Text(loc.getString("name"))})}
    Text("Intended sales channels · drafts remain unpublished");val pubs=h.getJSONArray("publications");for(i in 0 until pubs.length()) {val channel=pubs.getJSONObject(i);val id=channel.getString("id");FilterChip(selected=id in channels,onClick={channels=if(id in channels) channels-id else channels+id;vm.repo.preferences.edit().putStringSet("batchPublications.${h.getString("shop")}.${session?.id ?: "ALL"}",channels).commit()},label={Text(channel.getString("name"))})}
    TextButton(onClick={vm.repo.savePublicationDefaults(channels,h);vm.message="Default channels saved for future batches"}) {Text("Save selected channels as defaults")}
    OutlinedButton(enabled=selected.isNotEmpty() && selected.size<=100 && channels.isNotEmpty() && location.isNotBlank() && !model.busy,onClick={model.publish(selected,location,channels)}) {Text("Review batch publication")};Text("Create Drafts is your authorization to upload selected listing data and photos. Valid selected copies are submitted directly; invalid copies are skipped with reasons. Published products are never updated by this action.",style=MaterialTheme.typography.bodySmall)
   }
  }
  if(model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
  if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
  if(model.message.isNotBlank()) item {Text(model.message)}
  if(model.skipped.isNotEmpty()) item {Text("Skipped / needs attention",style=MaterialTheme.typography.titleSmall);model.skipped.forEach {(id,reason)->Text("${records.firstOrNull {it.groupId==id}?.itemId ?: id}: $reason",style=MaterialTheme.typography.bodySmall)}}
  if(session!=null) item {TextButton(onClick={model.action {vm.repo.trashLocal("BATCH",session.id);moreActions=false;onPhotos()}}) {Text("Delete batch · move to Trash")}}
  item {Text("Workflow jobs",style=MaterialTheme.typography.titleLarge)}
  items(jobs.take(15),key={"job-"+it.id}) {job->val rows=results.filter {it.jobId==job.id};Text("${job.kind} · ${job.state}");TextButton(onClick={historyJob=job.id}) {Text("View saved workflow results")};if(job.error.isNotBlank()) Text(job.error);if(job.state in setOf("QUEUED","RUNNING")) TextButton(onClick={model.action {vm.repo.cancelProduction(job.id)}}) {Text("Cancel remaining workflow")}}
  items(shopJobs.filter {j->shopItems.any {it.jobId==j.id && it.groupId in ids}}.take(15),key={"shop-"+it.id}) {job->Text("Shopify ${job.kind} · ${job.state}");shopItems.filter {it.jobId==job.id && it.error.isNotBlank()}.forEach {Text("${it.itemId}: ${it.error}",color=MaterialTheme.colorScheme.error)};if(job.state !in setOf("SUCCESS","CANCELLED")) TextButton(onClick={model.action {val h=shopifyGateway(vm.repo.context,vm.repo.shopifyEndpoint()).call(newId(),"health",JSONObject());vm.repo.resumeShopify(job.id,vm.repo.shopifyEndpoint(),h)}}) {Text("Reconcile / resume existing job")}}
 }
 Scaffold(bottomBar={if(selected.isNotEmpty()) Surface(tonalElevation=3.dp,shadowElevation=4.dp) {Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
  Row(verticalAlignment=Alignment.CenterVertically) {Text("${selected.size} selected of ${records.size}",style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f));TextButton(onClick={moreActions=true}) {Text("More Actions")}}
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   Button(enabled=!model.busy,onClick={model.run(selected,"MATCH_PRICE",vm.repo.autoPriceRules().json())}) {Text("Auto Match & Price")}
   Button(enabled=!model.busy,onClick={model.drafts(selected,location,channels)}) {Text("Create Drafts (${selected.size})")}
   TextButton(enabled=!model.busy,onClick={recognitionConsent=true}) {Text("Recognize")}
   TextButton(enabled=!model.busy,onClick={model.run(selected,"METADATA")}) {Text("Metadata")}
   TextButton(enabled=!model.busy && selected.size<=100,onClick={model.publish(selected,location,channels)}) {Text("Publish")}
  }
 }}}) {padding->
 LazyColumn(state=listState,modifier=Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp).testTag("production-batch"),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  stickyHeader {Surface(color=MaterialTheme.colorScheme.background) {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text(session?.name ?: "All eligible inventory",style=MaterialTheme.typography.titleLarge);Text("${records.size} physical copies · ${selected.size} selected",style=MaterialTheme.typography.bodySmall)};TextButton(onClick={moreActions=true}) {Text("More Actions")}}}}
  item {Row(Modifier.horizontalScroll(rememberScrollState())) {TextButton(onClick=onInventory ?: onPhotos) {Text("All inventory")};TextButton(onClick=onPhotos) {Text("← Photographs & capture history")};sessions.forEach {batch->FilterChip(selected=session?.id==batch.id,onClick={onBatch(batch.id)},label={Text(batch.name)})}}}
  item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf("All","Recognized","Priced","Needs review","Ready for drafts","Drafted","Published","Matched","Active","Failed jobs").forEach {label->val count=if(label=="Ready for drafts") readyIds.size else records.count {productionFilter(it,label,priceResults[it.groupId],recognition,failed)};FilterChip(selected=filter==label,onClick={filter=label},label={Text("${if(label=="All") "Total" else label} $count")})}}}
  item {OutlinedTextField(search,{search=it.take(200)},label={Text("Search title or SKU")},modifier=Modifier.fillMaxWidth(),singleLine=true);Row {TextButton(onClick={model.setSelection(ids)}) {Text("Select All")};TextButton(onClick={model.setSelection(emptySet())}) {Text("Deselect All")};TextButton(onClick={model.setSelection(ids-selected)}) {Text("Invert Selection")}}}
  if(selectedResults.isNotEmpty()) item {FilledTonalButton(enabled=!model.busy,onClick={model.applyPrices(selectedResults)}) {Text("Apply ${selectedResults.size} eligible prices")}}
  if(model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Working on your selection…",style=MaterialTheme.typography.bodySmall)}
  if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error);TextButton(onClick={moreActions=true}) {Text("Review settings & recovery")}}
  if(model.message.isNotBlank()) item {Text(model.message,style=MaterialTheme.typography.bodySmall)}
  if(model.skipped.isNotEmpty()) item {Disclosure("${model.skipped.size} skipped · review reasons",initial=true) {model.skipped.forEach {(id,reason)->Text("${records.firstOrNull {it.groupId==id}?.itemId ?: id}: $reason",style=MaterialTheme.typography.bodySmall)}}}
  if(jobs.any {it.state in setOf("QUEUED","RUNNING")}) item {TextButton(onClick={moreActions=true}) {Text("${jobs.count {it.state in setOf("QUEUED","RUNNING")}} workflow jobs running · View progress")}}
  if(shown.isEmpty()) item {EmptyState("No items in this view","Change the filter or capture a tape to add it to this batch.")}
  items(shown,key={it.groupId}) {item->val result=priceResults[item.groupId];val price=result?.let {runCatching {JSONObject(it.resultJson)}.getOrNull()};Surface(onClick={itemId=item.groupId},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),tonalElevation=1.dp) {Column(Modifier.padding(10.dp)) {
   Row(verticalAlignment=Alignment.CenterVertically) {val cover=photos.firstOrNull {it.groupId==item.groupId && !it.deleted && it.state=="READY"};if(cover!=null) AsyncImage(vm.repo.original(cover.id),"${item.title} cover",Modifier.size(52.dp),contentScale=androidx.compose.ui.layout.ContentScale.Crop);Checkbox(item.groupId in selected,{checked->model.setSelection(if(checked) selected+item.groupId else selected-item.groupId)},modifier=Modifier.testTag("select-${item.groupId}"));Column(Modifier.weight(1f)) {Text(item.title.ifBlank {"Unidentified tape"},style=MaterialTheme.typography.titleMedium);Text(item.itemId,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text(if(item.priceCents==null) "Unpriced" else "CAD ${InventoryRules.priceText(item.priceCents)}",style=MaterialTheme.typography.titleSmall);StatusLabel(item.shopifyState);if(result!=null) {Text("${operatorState(result.state)} · ${price?.optInt("count",0)} comps · suggested CAD ${price?.optLong("priceCents")?.takeIf {it>0}?.let {InventoryRules.priceText(it)} ?: "—"}");Text(price?.optString("reason").orEmpty(),style=MaterialTheme.typography.bodySmall)}}}
   Row {TextButton(onClick={itemId=item.groupId}) {Text("Open item")};if(result!=null) TextButton(onClick={details=result}) {Text("Evidence & explanation")}}
   results.filter {it.groupId==item.groupId && jobs.firstOrNull {j->j.id==it.jobId}?.kind?.startsWith("AI_")==true}.forEach {r->TextButton(onClick={details=r}) {Text("Review optional AI result · ${r.state}")}}
   reviews.filter {r->JSONObject(r.payloadJson).optJSONObject("before")?.optString("id")==item.shopifyProductId && r.state=="APPLIED"}.firstOrNull()?.let {r->val data=JSONObject(r.resultJson);val outcomes=data.optJSONObject("outcome")?.optJSONArray("channelOutcomes") ?: data.optJSONArray("channelOutcomes") ?: JSONArray();for(i in 0 until outcomes.length()) {val o=outcomes.getJSONObject(i);Text("${o.optString("name")}: ${if(o.optBoolean("published")) "Verified published" else "Not confirmed · "+o.optString("error")}",style=MaterialTheme.typography.bodySmall)}}
  }}}

 }
 }
 if(moreActions) ModalBottomSheet(onDismissRequest={moreActions=false}) {Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {Text("More Actions",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));IconButton(onClick={moreActions=false}) {Icon(Icons.Outlined.Close,"Close actions")}};LazyColumn(Modifier.fillMaxWidth().weight(1f,false).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=28.dp)) {legacyActions()}}

 if(contentReview.isNotEmpty()) AlertDialog(onDismissRequest={contentReview=emptyList()},title={Text("Review batch descriptions")},text={LazyColumn {items(contentReview,key={it.id}) {r->val data=JSONObject(r.resultJson);Text("${inventory.firstOrNull {it.groupId==r.groupId}?.itemId ?: r.groupId} → ${data.optString("description")}\nTags: ${data.optJSONArray("tags") ?: "[]"}",Modifier.padding(bottom=12.dp))}}},confirmButton={Button(enabled=!model.busy,onClick={model.applyContent(contentReview.map {it.id}.toSet());contentReview=emptyList()}) {Text("Apply reviewed missing descriptions")}},dismissButton={TextButton(onClick={contentReview=emptyList()}) {Text("Cancel")}})
 historyJob?.let {id->val history by remember(id) {vm.repo.journal.productionDao().watchJobItems(id)}.collectAsStateWithLifecycle(emptyList());AlertDialog(onDismissRequest={historyJob=null},title={Text("Saved workflow history")},text={LazyColumn {items(history,key={it.id}) {r->val value=runCatching {JSONObject(r.resultJson)}.getOrDefault(JSONObject());TextButton(onClick={details=r}) {Text("${inventory.firstOrNull {it.groupId==r.groupId}?.itemId ?: r.groupId} · ${r.state} · ${value.optString("reason")} ${r.error}")}}}},confirmButton={TextButton(onClick={historyJob=null}) {Text("Done")}})}
 model.review?.let {id->reviews.firstOrNull {it.id==id}?.let {ShopifyReviewDialog(it,shopify,model::closeReview)}}
 if(rulesOpen) AutoPriceRulesDialog(vm) {rulesOpen=false}
 if(recognitionConsent) AlertDialog(onDismissRequest={recognitionConsent=false},title={Text("Recognize ${selected.size} selected copies")},text={Text("Mode: ${recognitionLabel(vm.repo.recognitionMode())}. ${if(vm.repo.recognitionMode()=="OPENAI") "This authorizes up to ${selected.size} OpenAI photo recognition requests using ${vm.repo.taskModel(AiTask.RECOGNITION).model}. Compressed photo derivatives are sent; API fees apply. Actual costs may be unavailable. Interrupted requests are not automatically retried." else "Recognition runs on this device without API tokens."} Suggested titles remain available for one batch review.")},confirmButton={Button(onClick={recognitionConsent=false;model.recognition(selected,true)}) {Text("Start selected recognition")}},dismissButton={TextButton(onClick={recognitionConsent=false}) {Text("Cancel")}})
 if(applyTitles) AlertDialog(onDismissRequest={applyTitles=false},title={Text("Review selected recognized titles")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {titleReviews.forEach {r->Text("${records.firstOrNull {it.groupId==r.groupId}?.itemId ?: r.groupId}: ${r.title.ifBlank {"No completed suggestion"}}")};Text("Apply the shown suggestions only where the existing title is blank. Existing operator titles, unknown suggestions and changed photos are protected.")}},confirmButton={Button(onClick={applyTitles=false;model.applyRecognized(titleReviews)}) {Text("Apply reviewed titles")}},dismissButton={TextButton(onClick={applyTitles=false}) {Text("Cancel")}})
 if(aiKind.isNotBlank()) ProductionAiBudget(vm,aiKind,selected.size,onStart={options->model.run(selected,aiKind,options,true);aiKind=""},dismiss={aiKind=""})
 details?.let {r->AlertDialog(onDismissRequest={details=null},title={Text("Saved evidence / result")},text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState())) {Text(r.state);Text(r.error);val data=JSONObject(r.resultJson);Text(data.optString("reason"));if(data.has("description")) Text(data.getString("description"));if(data.has("matches")) Text(data.getJSONArray("matches").toString(2));if(data.has("edition")) Text(data.toString(2));Text("${data.optString("oldest")} – ${data.optString("newest")} · ${data.optInt("count",0)} compatible sales");ProductionEvidenceContent(vm.repo,data);val candidates=data.optJSONArray("candidates");if(candidates!=null) Text(candidates.toString(2));Text(data.optJSONArray("warnings")?.toString().orEmpty());Text(data.optString("usage"))}},confirmButton={TextButton(onClick={details=null}) {Text("Done")}})}
}
@Composable internal fun AutoPriceRulesDialog(vm:CaptureViewModel,dismiss:()->Unit) {
 var rules by rememberSaveable(stateSaver=androidx.compose.runtime.saveable.Saver<AutoPriceRules,String>(save={it.json().toString()},restore={AutoPriceRules.parse(JSONObject(it))})) {mutableStateOf(vm.repo.autoPriceRules())};var error by remember {mutableStateOf("")}
 androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {Surface(Modifier.fillMaxWidth(.95f).heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*0.9f).dp)) {Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Text("Offline Auto Match & Price rules",style=MaterialTheme.typography.headlineSmall);Text("Prices use strictly compatible current SOLD records. Approximate identities are routed to review, never assumed confirmed. No model requests.")
  FlowRow {TextButton(onClick={rules=rules.copy(method="MEDIAN",minimum=5,protectManual=true,automatic=false)}) {Text("Conservative")};TextButton(onClick={rules=rules.copy(method="MEDIAN",minimum=3,protectManual=true,automatic=false)}) {Text("Balanced")};TextButton(onClick={rules=rules.copy(ageDays=3650,minimum=3,automatic=false)}) {Text("Historical")}}
  Text("Presets keep your FX rate, price bounds and custom rounding. Applying prices remains an explicit action unless you enable automatic application.",style=MaterialTheme.typography.bodySmall)
  Text("${rules.minimum} comparable sales minimum · ${rules.ageDays} days · ${rules.method.lowercase().replace('_',' ')}",style=MaterialTheme.typography.titleSmall)
  Disclosure("Advanced pricing rules","Statistics, evidence age, FX, floors, rounding and automatic application") {
  ChoiceField("Statistic",rules.method,listOf("MEDIAN","AVERAGE","TRIMMED_MEAN")) {rules=rules.copy(method=it)};ChoiceField("Outliers",rules.outliers,listOf("IQR","NONE")) {rules=rules.copy(outliers=it)};ChoiceField("Shipping treatment",rules.shipping,listOf("INCLUDE","EXCLUDE")) {rules=rules.copy(shipping=it)}
  OutlinedTextField(rules.minimum.toString(),{it.toIntOrNull()?.let {n->rules=rules.copy(minimum=n)}},label={Text("Minimum compatible sales")});OutlinedTextField(rules.ageDays.toString(),{it.toIntOrNull()?.let {n->rules=rules.copy(ageDays=n)}},label={Text("Maximum historical evidence age · days")})
  OutlinedTextField(rules.floor?.let {InventoryRules.priceText(it)} ?: "",{runCatching {InventoryRules.cents(it)}.onSuccess {v->rules=rules.copy(floor=v)}},label={Text("Optional CAD price floor")});OutlinedTextField(rules.ceiling?.let {InventoryRules.priceText(it)} ?: "",{runCatching {InventoryRules.cents(it)}.onSuccess {v->rules=rules.copy(ceiling=v)}},label={Text("Optional CAD price ceiling")})
  OutlinedTextField(rules.adjustmentBasisPoints.toString(),{it.toIntOrNull()?.let {n->rules=rules.copy(adjustmentBasisPoints=n)}},label={Text("Adjustment · basis points (100 = 1%)")});OutlinedTextField(rules.roundingCents.toString(),{it.toIntOrNull()?.let {n->rules=rules.copy(roundingCents=n)}},label={Text("Round to CAD cents · 1 = cent, 100 = dollar")})
  OutlinedTextField(rules.usdCad,{rules=rules.copy(usdCad=it.take(24))},label={Text("USD → CAD rate · blank excludes USD")});OutlinedTextField(rules.rateDate,{rules=rules.copy(rateDate=it.take(10))},label={Text("Rate effective date YYYY-MM-DD")})
  Row {Checkbox(rules.protectManual,{rules=rules.copy(protectManual=it)});Text("Protect manual/operator prices",Modifier.weight(1f))};Row {Checkbox(rules.automatic,{rules=rules.copy(automatic=it)});Text("Automatically apply high-confidence prices within explicitly started jobs",Modifier.weight(1f))};Text("TRIMMED_MEAN removes 10% from each tail when at least 10 sales remain. IQR excludes totals outside 1.5× interquartile range when at least 4 sales exist. Unknown source condition is disclosed; no condition premium or FX rate is invented.",style=MaterialTheme.typography.bodySmall)
  }
  Text(error,color=MaterialTheme.colorScheme.error);Row {Button(onClick={try {vm.repo.saveAutoPriceRules(rules);dismiss()} catch(e:Exception) {error=e.message.orEmpty()}}) {Text("Save pricing rules")};TextButton(onClick=dismiss) {Text("Cancel")}}
 }}}
}
@Composable internal fun ProductionAiBudget(vm:CaptureViewModel,kind:String,count:Int,onStart:(JSONObject)->Unit,dismiss:()->Unit) {
 val task=when(kind) {"AI_MATCH"->AiTask.MARKET;"AI_EDITION"->AiTask.EDITION;else->AiTask.CONTENT};val choice=vm.repo.taskModel(task);var budget by remember {mutableStateOf("1.00")};var requests by remember {mutableStateOf(count.coerceAtMost(100).toString())}
 val bound=runCatching {boundedBatchCost(choice)}.getOrNull();val n=requests.toIntOrNull();val maximum=if(bound!=null && n!=null) bound*java.math.BigDecimal(n) else null
 AlertDialog(onDismissRequest=dismiss,title={Text("Authorize optional AI batch")},text={Column {Text("${task.label} · ${choice.model}. One consent covers this exact selection, up to the chosen request count and estimated budget. Pricing runs independently offline. Candidate-only text is used; no photographs are transmitted by these helpers.");OutlinedTextField(requests,{requests=it.filter(Char::isDigit).take(4)},label={Text("Maximum model requests")});OutlinedTextField(budget,{budget=it.take(16)},label={Text("Estimated USD budget ceiling")});Text("Conservative configured-tariff reservation: ${maximum?.toPlainString() ?: "unavailable; configure verified model input/output rates in AI Models & Tasks"} USD. Actual provider usage/costs are recorded when available. Model output is capped. Prices entered in settings determine estimates and cannot guarantee the provider's bill.",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button(enabled=maximum!=null && n!=null && n in 1..count && budget.toBigDecimalOrNull()?.let {it>java.math.BigDecimal.ZERO}==true,onClick={onStart(JSONObject().put("model",choice.json()).put("budgetUsd",budget).put("maxRequests",n).put("inputTokenBound",20000).put("authBinding",SecureApiKeyStore(vm.repo.context).bindingId()))}) {Text("Start authorized AI job")}},dismissButton={TextButton(onClick=dismiss) {Text("Cancel")}})
}

/** Load the actual immutable sold records in small local pages, rather than duplicating or rendering the whole evidence set. */
@Composable private fun ProductionEvidenceContent(repo:CaptureRepository,data:JSONObject) {
 val context=androidx.compose.ui.platform.LocalContext.current
 val evidence=remember(data.toString()) {data.optJSONArray("evidence") ?: JSONArray()};var offset by remember(evidence.toString()) {mutableStateOf(0)}
 val page by produceState<List<MarketSaleRow>>(emptyList(),evidence.toString(),offset) {value=withContext(Dispatchers.IO) {buildList {for(i in offset until minOf(offset+50,evidence.length())) repo.journal.marketDao().sale(evidence.getJSONObject(i).getString("id"))?.let {add(it)}}}}
 if(evidence.length()>0) {Text("Sold evidence ${offset+1}–${minOf(offset+50,evidence.length())} of ${evidence.length()}")
  page.forEach {sale->Text("${sale.title} · ${sale.soldAt.take(10)}\n${sale.currency} ${sale.soldDecimal} + shipping ${sale.shippingDecimal}${if(!sale.current) " · superseded source version" else ""}",style=MaterialTheme.typography.bodySmall);TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(sale.url)))}) {Text("Open sold source · ${sale.sourceItemId}")};Text(sale.id,style=MaterialTheme.typography.bodySmall)}
  Row {TextButton(enabled=offset>0,onClick={offset=maxOf(0,offset-50)}) {Text("Previous evidence")};TextButton(enabled=offset+50<evidence.length(),onClick={offset+=50}) {Text("Next evidence")}}
 }
}
