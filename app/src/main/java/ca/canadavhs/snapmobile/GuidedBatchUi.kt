package ca.canadavhs.snapmobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.canadavhs.snapmobile.core.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.*

internal val guidedSteps=listOf("Capture","Identify","Price","Review Listings","Create Drafts")
internal fun phaseLabel(phase:String)=when(phase) {
 "NOT_STARTED"->"Not processed yet";"IDENTIFY_RUNNING"->"Identifying offline";"IDENTIFY_REVIEW"->"Confirm identity"
 "PRICE_RUNNING"->"Calculating from sold evidence";"PRICE_RECOMMENDED"->"Price recommended · approval needed";"PRICE_REVIEW"->"Price exception · review needed"
 "LISTING_REVIEW"->"Price approved · review listing";"LISTING_READY"->"Listing approved · ready to submit"
 "DRAFT_REVIEW"->"Draft operation needs review";"DRAFT_QUEUED"->"Draft queued · not yet verified";"DRAFT_VERIFIED"->"Shopify draft verified";"PUBLISHED"->"Published live"
 "DEFERRED"->"Deferred";else->phase.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProductionBatchScreen(vm:CaptureViewModel,session:SessionRow?,onPhotos:()->Unit,onBatch:(String)->Unit={},onInventory:(()->Unit)?=null,model:ProductionModel=viewModel(),shopify:ShopifyModel=viewModel(),onCapture:()->Unit=onPhotos) {
 var advanced by rememberSaveable(session?.id) {mutableStateOf(false)}
 if(advanced || session==null) {Column {if(session!=null) TextButton(onClick={advanced=false}) {Text("← Guided batch workflow")};Box(Modifier.weight(1f)) {AdvancedProductionBatchScreen(vm,session,onPhotos=onPhotos,onBatch=onBatch,onInventory=onInventory,model=model,shopify=shopify)}};return}
 BackHandler(onBack=onPhotos)
 val inventory by vm.inventory.collectAsStateWithLifecycle();val groups by vm.groups.collectAsStateWithLifecycle();val photos by vm.photos.collectAsStateWithLifecycle();val sessions by vm.sessions.collectAsStateWithLifecycle()
 val workflow by remember(session.id) {vm.repo.journal.productionDao().watchGuided(session.id)}.collectAsStateWithLifecycle(null)
 val results by vm.repo.journal.productionDao().watchItems().collectAsStateWithLifecycle(emptyList())
 val shopJobs by vm.repo.journal.shopifyDao().watchJobs().collectAsStateWithLifecycle(emptyList());val shopItems by vm.repo.journal.shopifyDao().watchItems().collectAsStateWithLifecycle(emptyList())
 val groupSet=groups.filter {it.sessionId==session.id}.map {it.id}.toSet();val records=inventory.filter {!it.archived && it.groupId in groupSet}.sortedBy {item->groups.firstOrNull {it.id==item.groupId}?.number};val ids=records.map {it.groupId}.toSet()
 val phases=results.filter {it.jobId==workflow?.id}.associateBy {it.groupId}
 val priceRows by remember(session.id) {vm.repo.journal.productionDao().watchPrices(session.id)}.collectAsStateWithLifecycle(emptyList())
 val prices=priceRows.associateBy {it.groupId}
 var step by rememberSaveable(session.id) {mutableIntStateOf(vm.repo.preferences.getInt("guidedStep.${session.id}",0).coerceIn(0,4))}
 fun go(value:Int) {step=value;vm.repo.preferences.edit().putInt("guidedStep.${session.id}",value).commit()}
 var inspector by rememberSaveable(session.id) {mutableStateOf<String?>(null)};var editor by rememberSaveable(session.id) {mutableStateOf<String?>(null)}
 var exceptionsOnly by rememberSaveable(session.id) {mutableStateOf(false)}
 LaunchedEffect(session.id,ids) {model.selection(session.id,ids);if(ids.isNotEmpty() && !vm.repo.preferences.contains("batchSelection.${session.id}")) model.setSelection(ids)}
 val selected=model.selected.intersect(ids)
 val priceEligible=prices.values.filter {it.groupId in selected && it.state=="HIGH_CONFIDENCE" && records.any {r->r.groupId==it.groupId && r.revision==it.revision} && guidedPhase(phases[it.groupId])!="DEFERRED"}.map {it.id}.toSet()
 var listingValid by remember {mutableStateOf<Set<String>>(emptySet())}
 LaunchedEffect(records,photos,phases) {listingValid=withContext(Dispatchers.IO) {records.filter {guidedPhase(phases[it.groupId])!="DEFERRED" && vm.repo.shopifyProblems(it.copy(ready=true),photos.filter {p->p.groupId==it.groupId && !p.deleted}).isEmpty() && it.description.isNotBlank()}.map {it.groupId}.toSet()}}
 val approved=records.filter {it.ready && it.groupId in listingValid}.map {it.groupId}.toSet();val draftIds=selected.intersect(approved)
 val exceptionIds=records.filter {val phase=guidedPhase(phases[it.groupId]);phase in setOf("IDENTIFY_REVIEW","PRICE_REVIEW","CAPTURE_REVIEW","LISTING_BLOCKED","PROCESSING_ERROR","REMOTE_LINK_REVIEW")}.map {it.groupId}.toSet()
 val active=workflow?.state in setOf("QUEUED","RUNNING")
 fun process(retry:Set<String> = emptySet()) {if(selected.isNotEmpty()) model.action("Process batch") {vm.repo.startGuidedBatch(session.id,selected,retry)}}
 fun approvePrices() {model.action("Approve eligible prices") {val applied=vm.repo.applyProductionPrices(priceEligible);check(applied.second.isEmpty()) {"${applied.first} approved; "+applied.second.values.joinToString()};vm.repo.startGuidedBatch(session.id,selected)}}
 if(editor!=null) {InventoryScreen(vm,reviewPhotos={},initialGroupId=editor,onExit={editor=null;if(selected.isNotEmpty()) process()});return}
 Scaffold(bottomBar={Surface(tonalElevation=3.dp) {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
  when(step) {
   0->Button(onClick={process();go(1)},enabled=selected.isNotEmpty() && !model.busy,modifier=Modifier.fillMaxWidth()) {Text("Identify & price ${selected.size} copies")}
   1->Button(onClick={if(workflow==null) process();go(2)},enabled=selected.isNotEmpty() && !model.busy,modifier=Modifier.fillMaxWidth()) {Text(if(workflow==null) "Start identification & pricing" else "Review prices")}
   2->if(priceEligible.isNotEmpty()) {Button(onClick=::approvePrices,enabled=!model.busy,modifier=Modifier.fillMaxWidth()) {Text("Approve all eligible prices (${priceEligible.size})")};TextButton(onClick={go(3)},modifier=Modifier.fillMaxWidth()) {Text("Continue with approved listings (${selected.intersect(listingValid).size})")}}
      else Button(onClick={go(3)},enabled=selected.intersect(listingValid).isNotEmpty() && !model.busy,modifier=Modifier.fillMaxWidth()) {Text("Continue with approved listings (${selected.intersect(listingValid).size})")}
   3->Button(onClick={model.action("Approve reviewed listings") {val result=vm.repo.approveGuidedListings(selected.intersect(listingValid));if(result.second.isNotEmpty()) error("${result.first} approved; "+result.second.values.joinToString());vm.repo.startGuidedBatch(session.id,selected);go(4)}},enabled=selected.intersect(listingValid).isNotEmpty() && !model.busy,modifier=Modifier.fillMaxWidth()) {Text("Approve listings & continue (${selected.intersect(listingValid).size})")}
   4->Button(onClick={model.drafts(draftIds,"",emptySet(),useSavedChannels=true)},enabled=draftIds.isNotEmpty() && !model.busy,modifier=Modifier.fillMaxWidth()) {Text("Create Shopify Drafts (${draftIds.size})")}
  }
  if(step==4) Text("Uploads chosen photographs and approved listings as drafts. Live publication is separate.",style=MaterialTheme.typography.bodySmall)
 }}}) {padding->LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp).testTag("guided-batch"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)) {
  item {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text(session.name,style=MaterialTheme.typography.headlineSmall);Text("Step ${step+1} of 5 · ${guidedSteps[step]}")};TextButton(onClick={advanced=true}) {Text("Advanced Tools")}}
   LinearProgressIndicator(progress={if(records.isEmpty()) 0f else records.count {it.ready || it.shopifyProductId.isNotBlank()}.toFloat()/records.size},modifier=Modifier.fillMaxWidth())
   Text("${records.size} copies · ${records.count {it.priceCents!=null && !it.priceSource.startsWith("STALE")}} priced · ${exceptionIds.size} exceptions · ${records.count {it.ready}} approved",style=MaterialTheme.typography.bodySmall)
  }
  item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {guidedSteps.forEachIndexed {i,label->FilterChip(step==i,onClick={go(i)},label={Text("${i+1} $label")})}}}
  if(sessions.size>1) item {Row(Modifier.horizontalScroll(rememberScrollState())) {sessions.forEach {s->FilterChip(s.id==session.id,onClick={onBatch(s.id)},label={Text(s.name)})}}}
  item {when(step) {
   0->{Text("One card is one physical VHS copy. All photographs on that card share its immutable SKU. Use Next tape in Capture only when moving to a different copy.");OutlinedButton(onClick=onCapture) {Text("Capture more photographs")};TextButton(onClick=onPhotos) {Text("Photo history & grouping")}}
   1->Text("Identifying tapes and preparing prices automatically on this phone. Confirm uncertain titles below.")
   2->Text("Approve strong sold evidence together. Review uncertain prices individually. All recommended prices are CAD.")
   3->Text("Check each copy’s photographs and listing details. Approve valid listings together; exceptions can wait.")
   4->Text("Submit approved copies together. Each upload is verified before it is marked complete.")
  }}
  if(step in 1..2) item {val rate=vm.repo.cachedFx();val override=vm.repo.autoPriceRules();Text(if(override.usdCad.isNotBlank()) "Manual USD/CAD override · 1 USD = ${override.usdCad} CAD · ${override.rateDate}" else if(rate==null) "USD/CAD: no verified cached rate yet. A started pricing job fetches Bank of Canada rates automatically." else "Bank of Canada · 1 USD = ${rate.rate} CAD · ${rate.date} · ${rate.age()} days old${if(rate.age()>7) " · rate age warning" else ""}",style=MaterialTheme.typography.bodySmall)}
  if(!workflow?.error.isNullOrBlank()) item {Text("Batch processing needs attention: ${workflow?.error}",color=MaterialTheme.colorScheme.error)}
  if(active || model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(model.busy) "Working on the selected copies…" else "Processing locally · progress is saved if you leave this screen")}
  if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
  if(model.message.isNotBlank()) item {Text(model.message)}
  if(model.skipped.isNotEmpty()) item {Disclosure("${model.skipped.size} skipped copies",initial=true) {model.skipped.forEach {(id,error)->Text("${records.firstOrNull {it.groupId==id}?.itemId ?: id}: $error")}}}
  item {Row(verticalAlignment=Alignment.CenterVertically) {TextButton(onClick={model.setSelection(ids)}) {Text("Select all")};TextButton(onClick={model.setSelection(emptySet())}) {Text("Clear")};FilterChip(exceptionsOnly,onClick={exceptionsOnly=!exceptionsOnly},label={Text("Exceptions ${exceptionIds.size}")})};if(step in 1..2) TextButton(enabled=selected.isNotEmpty() && !model.busy && !active,onClick={process(selected)}) {Text("Retry selected exceptions / refresh pricing")}}
  if(records.isEmpty()) item {EmptyState("Capture the first tape","Take front and back photographs, then use Next tape for the next physical copy.")}
  items(records.filter {!exceptionsOnly || it.groupId in exceptionIds},key={it.groupId}) {item->
   val row=prices[item.groupId];val result=row?.let {JSONObject(it.resultJson)};val shots=photos.filter {it.groupId==item.groupId && !it.deleted}.sortedBy {it.position};val flow=phases[item.groupId]
   val submitted=shopItems.filter {it.groupId==item.groupId}.maxByOrNull {it.updatedAt}
   val phase=when {guidedPhase(flow)=="DEFERRED"->"DEFERRED";item.shopifyState=="PUBLISHED"->"PUBLISHED";item.shopifyProductId.isNotBlank() && item.shopifyState in setOf("UPLOADED_AS_DRAFT","LINKED_DRAFT")->"DRAFT_VERIFIED";submitted!=null && submitted.state in setOf("ERROR","NEEDS_REVIEW","UNCERTAIN","RETRYABLE","PAUSED","CANCELLED")->"DRAFT_REVIEW";submitted!=null && submitted.state!="SUCCESS"->"DRAFT_QUEUED";item.ready->"LISTING_READY";item.priceCents!=null && !item.priceSource.startsWith("STALE")->"LISTING_REVIEW";row?.state=="HIGH_CONFIDENCE"->"PRICE_RECOMMENDED";else->guidedPhase(flow)}
   OutlinedCard(onClick={inspector=item.groupId},modifier=Modifier.fillMaxWidth().testTag("copy-${item.groupId}")) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment=Alignment.CenterVertically) {shots.firstOrNull()?.let {AsyncImage(vm.repo.original(it.id),"${item.title.ifBlank {"Unidentified tape"}} cover",Modifier.size(64.dp),contentScale=ContentScale.Crop)};Checkbox(item.groupId in selected,{checked->model.setSelection(if(checked) selected+item.groupId else selected-item.groupId)},Modifier.testTag("select-${item.groupId}"));Column(Modifier.weight(1f)) {Text(item.title.ifBlank {"Unidentified tape"},style=MaterialTheme.typography.titleMedium);Text(listOfNotNull(item.edition.takeIf {it.isNotBlank()},item.releaseYear?.toString()).joinToString(" · "));Text("${shots.size} photographs · one physical copy",style=MaterialTheme.typography.bodySmall)}}
    Text(item.itemId,style=MaterialTheme.typography.labelSmall);if(step==1 && flow!=null) Text(JSONObject(flow.resultJson).optString("reason"),style=MaterialTheme.typography.bodySmall);Text(phaseLabel(phase),color=if(item.groupId in exceptionIds) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if(step==0) Row(Modifier.horizontalScroll(rememberScrollState())) {shots.forEach {p->AsyncImage(vm.repo.original(p.id),"${p.role} photograph",Modifier.padding(end=6.dp).size(72.dp).clickable {editor=item.groupId},contentScale=ContentScale.Crop)}}
    if(step>=2) {Text(if(item.priceCents!=null) "Approved CAD ${InventoryRules.priceText(item.priceCents)}${if(protectedManualPrice(item)) " · manual price protected" else ""}" else if(result?.has("priceCents")==true) "Suggested CAD ${InventoryRules.priceText(result.getLong("priceCents"))}" else "No price approved",style=MaterialTheme.typography.titleMedium);if(result!=null) {Text("Evidence ${result.optInt("score")}/100 · ${result.optInt("count")} compatible sold records");Text(if(row?.state=="APPLIED") "Price decision recorded; source evidence retained." else result.optString("reason"),style=MaterialTheme.typography.bodySmall)}}
    if(step>=3) {Text("${item.condition} · ${item.weightGrams} g · SKU ${item.itemId}");Disclosure("Description & tags") {Text(android.text.Html.fromHtml(item.description,android.text.Html.FROM_HTML_MODE_LEGACY).toString().ifBlank {"Description not prepared"});Text("Tags: ${item.tags.ifBlank {"None"}}")};if(item.groupId !in listingValid && item.shopifyProductId.isBlank()) Text("Listing still needs review",color=MaterialTheme.colorScheme.error)}
    if(submitted!=null && step==4) {Text("${submitted.state} · ${submitted.step} · ${submitted.error}");if(submitted.state !in setOf("SUCCESS","RUNNING","QUEUED")) TextButton(onClick={model.action("Reconcile existing draft") {val h=shopifyGateway(vm.repo.context,vm.repo.shopifyEndpoint()).call(newId(),"health",JSONObject());vm.repo.resumeShopify(submitted.jobId,vm.repo.shopifyEndpoint(),h,setOf(item.groupId))}}) {Text("Reconcile / retry existing operation")}}
    Row {TextButton(onClick={inspector=item.groupId}) {Text(if(step==0) "Inspect copy" else "Review evidence & exceptions")};TextButton(onClick={editor=item.groupId}) {Text(if(step==0) "Photos / retake" else "Edit listing")}}
   }}
  }
 }}
 inspector?.let {id->records.firstOrNull {it.groupId==id}?.let {item->GuidedItemInspector(vm,item,prices[id],phases[id],model,onChanged={process()},onEdit={inspector=null;editor=id},dismiss={inspector=null})}}
}

@Composable internal fun GuidedItemInspector(vm:CaptureViewModel,item:InventoryRow,price:ProductionItemRow?,workflow:ProductionItemRow?,model:ProductionModel,onChanged:()->Unit,onEdit:()->Unit,dismiss:()->Unit) {
 var query by rememberSaveable(item.groupId) {mutableStateOf(item.title)};var chosenTitle by rememberSaveable(item.groupId) {mutableStateOf(item.title)};var chosenEdition by rememberSaveable(item.groupId) {mutableStateOf(item.edition)}
 var chosenYear by rememberSaveable(item.groupId) {mutableStateOf(item.releaseYear?.toString().orEmpty())}
 var manual by rememberSaveable(item.groupId) {mutableStateOf(InventoryRules.priceText(item.priceCents))};var page by remember {mutableStateOf<List<MarketGroup>>(emptyList())};var searchError by remember {mutableStateOf("")};var searching by remember {mutableStateOf(false)}
 val flowData=workflow?.let {JSONObject(it.resultJson)} ?: JSONObject()
 val identification=flowData.optJSONArray("identificationCandidates") ?: JSONArray()
 val recognition by produceState<RecognitionRow?>(null,flowData.optString("recognitionId")) {value=flowData.optString("recognitionId").takeIf {it.isNotBlank()}?.let {vm.repo.dao.recognition(it)}}
 val scope=rememberCoroutineScope();val data=price?.let {JSONObject(it.resultJson)} ?: JSONObject();val photos by vm.photos.collectAsStateWithLifecycle();val evidence=data.optJSONArray("evidence") ?: JSONArray()
 var offset by rememberSaveable(item.groupId) {mutableIntStateOf(0)}
 val sales by produceState<List<MarketSaleRow>>(emptyList(),price?.resultJson,offset) {value=withContext(Dispatchers.IO) {buildList {for(i in offset until minOf(offset+20,evidence.length())) vm.repo.journal.marketDao().sale(evidence.getJSONObject(i).getString("id"))?.let {add(it)}}}}
 androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {Surface(Modifier.fillMaxWidth(.96f).heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.9f).dp),shape=MaterialTheme.shapes.large) {LazyColumn(Modifier.padding(16.dp).testTag("guided-inspector"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  item {Text("Review this physical copy",style=MaterialTheme.typography.headlineSmall);Text(item.itemId);TextButton(onClick=dismiss) {Text("Close inspector")};photos.firstOrNull {it.groupId==item.groupId && !it.deleted}?.let {AsyncImage(vm.repo.original(it.id),"Original photograph",Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Fit)};Text(phaseLabel(guidedPhase(workflow)));Text(if(price?.state=="APPLIED") "Price decision recorded; source evidence retained." else data.optString("reason").ifBlank {flowData.optString("reason")});recognition?.let {r->Disclosure("Original recognition text") {val value=JSONObject(r.resultJson);Text(value.optString("raw_text").ifBlank {value.optJSONArray("text_lines")?.let {a->(0 until a.length()).joinToString("\n") {a.optString(it)}}.orEmpty()})}};for(i in 0 until minOf(5,identification.length())) {val candidate=identification.getJSONObject(i);TextButton(onClick={chosenTitle=InventoryRules.normalizedTitle(candidate.getString("title"));query=chosenTitle;chosenEdition=candidate.optString("edition");chosenYear=candidate.optInt("year").takeIf {it in 1800..2200}?.toString().orEmpty()}) {Text("Review candidate: ${candidate.optString("title")}")}}}
  item {OutlinedTextField(chosenTitle,{chosenTitle=it.take(240)},label={Text("Printed title")},modifier=Modifier.fillMaxWidth());OutlinedTextField(chosenEdition,{chosenEdition=it.take(1000)},label={Text("Confirmed edition · leave unknown blank")},modifier=Modifier.fillMaxWidth());OutlinedTextField(chosenYear,{chosenYear=it.filter(Char::isDigit).take(4)},label={Text("Confirmed release year · optional")},modifier=Modifier.fillMaxWidth());OutlinedButton(enabled=chosenTitle.isNotBlank() && (chosenYear.isBlank() || chosenYear.toIntOrNull()?.let {it in 1800..2200}==true) && !model.busy,onClick={model.action("Confirm printed identity") {vm.repo.saveInventory(item.copy(title=chosenTitle,edition=chosenEdition,releaseYear=chosenYear.toIntOrNull()),item.revision);vm.repo.startGuidedBatch(vm.repo.dao.group(item.groupId).sessionId,setOf(item.groupId),setOf(item.groupId))}}) {Text("Confirm identity & recalculate")}}
  item {Text("Ranked local candidates",style=MaterialTheme.typography.titleMedium);OutlinedTextField(query,{query=it.take(200)},label={Text("Search title, OCR spelling or UPC")},modifier=Modifier.fillMaxWidth());OutlinedButton(enabled=query.isNotBlank() && !searching,onClick={searching=true;scope.launch {try {page=vm.repo.searchMarket(MarketFilters(query=query),pageSize=20).groups;searchError=""} catch(e:CancellationException) {throw e} catch(e:Exception) {searchError=e.message.orEmpty()} finally {searching=false}}}) {Text("Search local database")};if(searching) LinearProgressIndicator(Modifier.fillMaxWidth());Text(searchError,color=MaterialTheme.colorScheme.error)}
  items(page,key={marketMatchId(it)}) {g->Column {Text("${g.title} · ${g.matchScore}/100 similarity");Text("${g.edition} · ${g.year ?: "Year unknown"} · ${g.samples} source observations");Text(g.matchReason,style=MaterialTheme.typography.bodySmall);TextButton(onClick={chosenTitle=InventoryRules.normalizedTitle(g.title);chosenEdition=g.edition;chosenYear=(g.year ?: filmYears(g.title).singleOrNull())?.toString().orEmpty()}) {Text("Use title for review")}}}
  item {Text("Evidence quality ${data.optInt("score")}/100",style=MaterialTheme.typography.titleMedium);val factors=data.optJSONObject("scoreFactors");factors?.keys()?.forEach {key->val factor=factors.optJSONObject(key);Text(if(factor!=null) "${factor.optInt("points")} · ${factor.optString("reason")}" else factors.optString(key),style=MaterialTheme.typography.bodySmall)}
   if(data.has("priceCents")) {Text("Suggested CAD ${InventoryRules.priceText(data.getLong("priceCents"))} · retained range ${InventoryRules.priceText(data.optLong("lowCadCents"))}–${InventoryRules.priceText(data.optLong("highCadCents"))}");Button(enabled=!model.busy && price?.state in setOf("HIGH_CONFIDENCE","NEEDS_REVIEW"),onClick={model.action("Approve reviewed price") {val applied=vm.repo.applyProductionPrices(setOf(price!!.id),reviewedIndividual=true);check(applied.second.isEmpty()) {applied.second.values.joinToString()};vm.repo.startGuidedBatch(vm.repo.dao.group(item.groupId).sessionId,setOf(item.groupId))}}) {Text("Approve this recommended price")}}
   val fx=data.optJSONObject("fx");if(fx!=null && fx.optString("rate").isNotBlank()) Text("USD → CAD ${fx.optString("rate")} · ${fx.optString("effectiveDate")}\n${fx.optString("source")}\n${fx.optString("warning")}",style=MaterialTheme.typography.bodySmall)
  }
  items(sales,key={it.id}) {sale->val rules=data.optJSONObject("rules");val rate=if(sale.currency=="USD") rules?.optString("usdCad")?.toBigDecimalOrNull() else java.math.BigDecimal.ONE;val total=marketMoney(sale.soldDecimal)+(if(rules?.optString("shipping")!="EXCLUDE") marketMoney(sale.shippingDecimal) else java.math.BigDecimal.ZERO);Text("${sale.title}\nSold ${sale.soldAt.take(10)} · ${sale.currency} ${sale.soldDecimal} + ${sale.shippingDecimal} shipping\nCAD ${rate?.let {(total*it).setScale(2,java.math.RoundingMode.HALF_UP)} ?: "conversion unavailable"}\n${sale.url}",style=MaterialTheme.typography.bodySmall)}
  if(evidence.length()>20) item {Row {TextButton(enabled=offset>0,onClick={offset=maxOf(0,offset-20)}) {Text("Previous sales")};TextButton(enabled=offset+20<evidence.length(),onClick={offset+=20}) {Text("More sales")}}}
  item {Disclosure("Excluded candidates & warnings") {val excluded=data.optJSONArray("exclusions") ?: JSONArray();for(i in 0 until excluded.length()) {val e=excluded.getJSONObject(i);Text("${e.optString("title")} · ${e.optString("reason")}",style=MaterialTheme.typography.bodySmall)};val warnings=data.optJSONArray("warnings") ?: JSONArray();for(i in 0 until warnings.length()) Text(warnings.getString(i));Text(data.optString("reasonCode"))}}
  item {OutlinedTextField(manual,{manual=it.take(20)},label={Text("Manual CAD price")});OutlinedButton(enabled=!model.busy && runCatching {InventoryRules.cents(manual)?.let {it>0}==true}.getOrDefault(false),onClick={model.action("Save manual price") {vm.repo.saveGuidedManualPrice(item,InventoryRules.cents(manual)!!);vm.repo.startGuidedBatch(vm.repo.dao.group(item.groupId).sessionId,setOf(item.groupId))}}) {Text("Save protected manual price")};TextButton(onClick=onEdit) {Text("Full listing & photograph editor")}}
  item {Row {TextButton(enabled=!model.busy,onClick={model.action("Retry copy") {vm.repo.startGuidedBatch(vm.repo.dao.group(item.groupId).sessionId,setOf(item.groupId),setOf(item.groupId))}}) {Text("Retry this copy")};TextButton(enabled=!model.busy,onClick={model.action("Defer copy") {vm.repo.deferGuided(item.groupId);dismiss()}}) {Text("Defer tape")}};if(model.error.isNotBlank()) Text(model.error,color=MaterialTheme.colorScheme.error);TextButton(onClick=dismiss) {Text("Done")}}
 }}}
}
