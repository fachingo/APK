package ca.canadavhs.snapmobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private fun marketMoneyText(value:Long,currency:String)="$currency ${java.math.BigDecimal.valueOf(value,2).toPlainString()}"
class MarketModel(app:Application):AndroidViewModel(app) {
    private val repo=(app as CaptureApplication).repository
    internal var matchAiEnabled by mutableStateOf(repo.preferences.getBoolean("marketMatchAiEnabled",false));private set
    internal var matchProvider by mutableStateOf(repo.preferences.getString("marketMatchProvider","OPENAI").orEmpty());private set
    internal var matchModel by mutableStateOf(repo.preferences.getString("marketMatchModel","").orEmpty());private set
    internal var aiMatches by mutableStateOf<Map<String,MarketAiMatch>>(emptyMap());private set
    internal var aiUsage by mutableStateOf("");private set
    internal var matchRequest:suspend(ChatConversationRow,List<ChatMessageRow>,JSONArray,suspend(String)->Unit)->AiClient.Result={chat,messages,tools,delta->AiClient(repo.context).stream(chat,messages,tools,delta)}
    fun setMatchAi(enabled:Boolean) {matchAiEnabled=enabled;repo.preferences.edit().putBoolean("marketMatchAiEnabled",enabled).commit();if(!enabled) aiMatches=emptyMap()}
    fun setMatchConnection(provider:String,model:String) {require(provider in AiProvider.all.filter {!it.subscription}.map {it.id});matchProvider=provider;matchModel=model.take(200);repo.preferences.edit().putString("marketMatchProvider",provider).putString("marketMatchModel",matchModel).commit();aiMatches=emptyMap()}
    fun rankMatches(approved:Boolean)=action {check(matchAiEnabled && !searching);val provider=matchProvider;val selectedModel=matchModel;val query=filters.query;val candidates=page.groups.take(20);val item=targetId?.let {repo.journal.inventoryDao().item(it)};val result=repo.rankMarketMatches(query,item,candidates,provider,selectedModel,approved,matchRequest);aiUsage=result.second;if(matchAiEnabled && provider==matchProvider && selectedModel==matchModel && query==filters.query && candidates.map(::marketMatchId)==page.groups.take(20).map(::marketMatchId)) aiMatches=result.first else note="Search or match model changed; AI match scores were discarded. Usage remains in history."}
    internal fun adoptMatch(group:MarketGroup,title:String,identity:Boolean,revision:Long)=action {val id=targetId ?: error("Select an inventory copy first");val old=repo.journal.inventoryDao().item(id) ?: error("Item missing");check(old.revision==revision);repo.saveInventory(old.copy(title=title,edition=if(identity) group.edition else old.edition,releaseYear=if(identity) group.year else old.releaseYear,ready=false),revision);selectTarget(id);note="Reviewed title saved. Request a fresh evidence-based price; approximate similarity alone never approves a price."}
    internal var filters by mutableStateOf(MarketFilters());private set
    internal var page by mutableStateOf(MarketPage(emptyList(),"INDEXED"));private set
    internal var preview by mutableStateOf<MarketPreview?>(null);private set
    internal var recommendation by mutableStateOf<MarketRecommendation?>(null);private set
    internal var history by mutableStateOf<List<PriceDecisionRow>>(emptyList());private set
    internal var listings by mutableStateOf<List<JSONObject>>(emptyList());private set
    internal var bins by mutableStateOf<List<MarketBin>>(emptyList());private set
    internal var askingHint by mutableStateOf<PriceDecisionRow?>(null);private set
    internal var selectedGroup by mutableStateOf<MarketGroup?>(null);private set
    var targetId by mutableStateOf<String?>(null);private set
    var busy by mutableStateOf(false);private set
    var searching by mutableStateOf(false);private set
    var progress by mutableStateOf("");private set
    var error by mutableStateOf("");private set
    var note by mutableStateOf("");private set
    private var historyOffset=0
    private var queryTask:Job?=null;private var transferTask:Job?=null;private var queryVersion=0;private var offset=0
    private fun action(block:suspend ()->Unit) {if(busy) return;busy=true;transferTask=viewModelScope.launch {try {block();error=""} catch(e:CancellationException) {note="Operation cancelled. Permanent records were retained.";throw e} catch(e:Exception) {error=e.message.orEmpty()} finally {busy=false;progress=""}}}
    internal fun change(value:MarketFilters) {filters=value;aiMatches=emptyMap();refresh()}
    fun moreHistory()=action {historyOffset=history.size;history=history+repo.journal.marketDao().decisions("%"+InventoryRules.normalizedTitle(filters.query)+"%",50,historyOffset)}
    fun refresh(more:Boolean=false) {queryTask?.cancel();val version=++queryVersion;val selected=filters;if(!more) offset=0 else offset+=40
        queryTask=viewModelScope.launch {searching=true;try {delay(120);val result=repo.searchMarket(selected,offset);if(version==queryVersion) {page=if(more) result.copy(groups=page.groups+result.groups) else result;history=repo.journal.marketDao().decisions("%"+InventoryRules.normalizedTitle(selected.query).replace("%","\\%").replace("_","\\_")+"%",50);error=""}} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message.orEmpty()} finally {if(version==queryVersion) searching=false}}
    }
    fun selectTarget(id:String?) {targetId=id;recommendation=null;if(id!=null) viewModelScope.launch {val item=repo.journal.inventoryDao().item(id);item?.let {change(filters.copy(query=it.title,year=it.releaseYear,edition=it.edition))}}}
    fun remember(favorite:Boolean?=null) {viewModelScope.launch {repo.rememberMarketSearch(filters.query,favorite)}}
    fun load(uri:Uri)=action {
        preview?.file?.delete();preview=null
        withContext(Dispatchers.IO) {val source=File(repo.context.cacheDir,"market-source-${newId()}")
            try {progress="Copying selected file…";repo.context.contentResolver.openInputStream(uri)?.use {input->source.outputStream().use {copyCancellable(input,it,2_000_000_000)}} ?: error("Cannot open selected market database.")
                var name=uri.lastPathSegment.orEmpty();repo.context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst()) name=it.getString(0)}
                preview=repo.previewMarket(source,name) {count->progress="Validated $count source records…"}
            } finally {source.delete()}
        }
    }
    fun commit()=action {val value=preview ?: error("Preview missing");val result=repo.commitMarket(value) {done,total->progress="Importing $done / $total records"};value.file.delete();preview=null;note="Imported ${result.added} new observations; ${result.duplicates} unchanged/repeated rows; ${result.versions} preserved revisions.";refresh()}
    fun dismissPreview() {if(!busy) {preview?.file?.delete();preview=null}}
    fun cancel() {transferTask?.cancel()}
    internal fun detail(group:MarketGroup) {selectedGroup=group;listings=emptyList();bins=emptyList();action {listings=repo.marketListings(group);bins=repo.marketDistribution(group)}}
    fun moreListings()=action {val group=selectedGroup ?: return@action;listings=listings+repo.marketListings(group,listings.size)}
    fun closeDetail() {selectedGroup=null;listings=emptyList();bins=emptyList()}
    fun recommend(currency:String)=action {val id=targetId ?: error("Choose an inventory item first.");recommendation=repo.recommendMarket(id,currency);askingHint=repo.journal.inventoryDao().item(id)?.let {repo.operatorPriceHint(it.title)};if(recommendation==null) note="No compatible sold evidence for this saved title/edition/year in $currency. Approximate matches remain browsing results; manual pricing is available."}
    fun reuseHint(revision:Long,overrideManual:Boolean,applied:()->Unit)=action {val id=targetId ?: error("Choose an item");val hint=askingHint ?: error("Hint missing");repo.applyOperatorPriceHint(id,hint.id,revision,overrideManual);askingHint=null;note="Prior approved asking price reused. Sold evidence was unchanged.";refresh();applied()}
    fun clearRecommendation() {recommendation=null}
    fun approve(method:String,adjusted:String,overrideManual:Boolean,stale:Boolean,rate:String,date:String,applied:()->Unit)=action {
        val value=recommendation ?: error("Recommendation missing");val price=if(adjusted.isBlank()) null else InventoryRules.cents(adjusted)
        repo.applyMarketPrice(value,method,price,overrideManual,stale,rate,date);recommendation=null;note="Price decision saved locally. Asking prices are kept separate from sold-market statistics.";refresh();applied()
    }
    fun verify()=action {withContext(Dispatchers.IO) {repo.journal.openHelper.writableDatabase.query("PRAGMA integrity_check").use {check(it.moveToFirst() && it.getString(0)=="ok") {"Database integrity check failed. Retain backups before repair."}}};note="SQLite integrity check passed."}
    fun rebuild()=action {progress="Rebuilding searchable indexes…";repo.rebuildMarketSearch();note="Search indexes rebuilt; evidence retained.";refresh()}
    fun export(save:(File)->Unit)=action {progress="Preparing consistent market archive…";val file=repo.exportMarketDatabase();save(file)}
}
@Composable internal fun MarketDialog(vm:CaptureViewModel,groupId:String?=null,dismiss:()->Unit,applied:()->Unit={}) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {Surface(Modifier.fillMaxSize()) {MarketDatabaseScreen(vm,dismiss,groupId,applied)}}
}
@Composable fun MarketDatabaseScreen(vm:CaptureViewModel,back:()->Unit,initialGroupId:String?=null,onApplied:()->Unit={},model:MarketModel=viewModel()) {
    val repo=vm.repo;val count by repo.journal.marketDao().watchCount().collectAsStateWithLifecycle(0L);val decisionCount by repo.journal.marketDao().watchDecisionCount().collectAsStateWithLifecycle(0L);val imports by repo.journal.marketDao().watchImports().collectAsStateWithLifecycle(emptyList());val searches by repo.journal.marketDao().watchSearches().collectAsStateWithLifecycle(emptyList());val inventory by vm.inventory.collectAsStateWithLifecycle()
    val context=androidx.compose.ui.platform.LocalContext.current;var advanced by rememberSaveable {mutableStateOf(false)};var historyTab by rememberSaveable {mutableStateOf(false)};var chooseTarget by remember {mutableStateOf(false)};var reuseHint by remember {mutableStateOf(false)};var reuseOverride by remember {mutableStateOf(false)}
    var exportFile by remember {mutableStateOf<File?>(null)};var stalePolicy by remember {mutableStateOf(repo.preferences.getBoolean("reuseOperatorPrices",false))}
    var rankDialog by remember {mutableStateOf(false)};var rankConsent by remember {mutableStateOf(false)};var adopt by remember {mutableStateOf<MarketGroup?>(null)};var adoptTitle by remember {mutableStateOf("")};var adoptIdentity by remember {mutableStateOf(false)}
    var ageText by remember {mutableStateOf(repo.preferences.getInt("marketAgeDays",180).toString())}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) model.load(uri)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) {uri->val file=exportFile;if(uri!=null && file!=null) vm.transfer {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.use {out->file.inputStream().use {copyCancellable(it,out,2_000_000_000)}} ?: error("Cannot save market archive")};vm.message="Market archive saved. Source inventory and photographs remain on this phone."};exportFile=null}
    LaunchedEffect(initialGroupId) {if(initialGroupId!=null) model.selectTarget(initialGroupId) else model.refresh()}
    LaunchedEffect(count,decisionCount) {model.refresh()}
    val target=inventory.firstOrNull {it.groupId==model.targetId}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Back")};Text("Market database",style=MaterialTheme.typography.headlineLarge);Text("Your offline pricing library",style=MaterialTheme.typography.titleMedium)}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Column {Text("$count sold observations",style=MaterialTheme.typography.titleLarge);Text("${imports.size} recent imports · schema 1 · ${model.page.index}",style=MaterialTheme.typography.bodySmall)};if(model.searching) CircularProgressIndicator(Modifier.size(24.dp))};imports.firstOrNull()?.let {Text("Last import ${it.observedAt.take(19)}",style=MaterialTheme.typography.bodySmall)}}
        item {OutlinedTextField(model.filters.query,{model.change(model.filters.copy(query=it))},label={Text("Search title, words or barcode")},modifier=Modifier.fillMaxWidth(),singleLine=true,trailingIcon={TextButton(onClick={model.remember(true)},enabled=model.filters.query.isNotBlank()) {Text("Save")}})}
        item {Text("Local matching: SQLite full-text/prefix indexes, trigram spelling candidates and Apache Commons Text similarity. Match scores are heuristics, not probabilities or approved prices.",style=MaterialTheme.typography.bodySmall)
            Row {Checkbox(model.matchAiEnabled,model::setMatchAi);Text("Optional AI match ranking · separate model · only when I request it",Modifier.weight(1f))}
            if(model.matchAiEnabled) {ChoiceField("Match provider",model.matchProvider,AiProvider.all.filter {!it.subscription}.map {it.id}) {model.setMatchConnection(it,model.matchModel)};OutlinedTextField(model.matchModel,{model.setMatchConnection(model.matchProvider,it)},label={Text("Match AI model · independent from photo recognition")},modifier=Modifier.fillMaxWidth());Text("Configure this provider's key in AI workspace Connections. No photos are sent for matching.",style=MaterialTheme.typography.bodySmall);OutlinedButton(enabled=!model.busy && !model.searching && model.matchModel.isNotBlank() && model.filters.query.isNotBlank() && model.page.groups.isNotEmpty(),onClick={rankConsent=false;rankDialog=true}) {Text("Review AI ranking request")}}
            if(model.aiUsage.isNotBlank()) Text(model.aiUsage,style=MaterialTheme.typography.bodySmall)
        }
        if(searches.isNotEmpty()) item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {searches.take(12).forEach {entry->SuggestionChip(onClick={model.change(model.filters.copy(query=entry.query))},label={Text((if(entry.favorite) "★ " else "")+entry.query.take(40))})}}}
        item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=!historyTab,onClick={historyTab=false},label={Text("Historical sold evidence")});FilterChip(selected=historyTab,onClick={historyTab=true},label={Text("Operator price history")});TextButton(onClick={advanced=!advanced}) {Text("Filters & tools")}}}
        if(advanced) {
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedTextField(model.filters.currency,{model.change(model.filters.copy(currency=it.uppercase().take(3)))},label={Text("Currency · blank = all")},modifier=Modifier.weight(1f));OutlinedTextField(model.filters.edition,{model.change(model.filters.copy(edition=it.take(100)))},label={Text("Evidence edition")},modifier=Modifier.weight(1f))}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedTextField(model.filters.year?.toString() ?: "",{model.change(model.filters.copy(year=it.take(4).toIntOrNull()))},label={Text("Year / title year")},modifier=Modifier.weight(1f));ChoiceField("Sort",model.filters.sort,listOf("RELEVANCE","PRICE_LOW","PRICE_HIGH","NEWEST","SAMPLES")) {model.change(model.filters.copy(sort=it))}}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedTextField(model.filters.minCents?.let {InventoryRules.priceText(it)} ?: "",{v->runCatching {InventoryRules.cents(v)}.onSuccess {model.change(model.filters.copy(minCents=it))}},label={Text("Min · sale + shipping")},modifier=Modifier.weight(1f));OutlinedTextField(model.filters.maxCents?.let {InventoryRules.priceText(it)} ?: "",{v->runCatching {InventoryRules.cents(v)}.onSuccess {model.change(model.filters.copy(maxCents=it))}},label={Text("Max · sale + shipping")},modifier=Modifier.weight(1f))}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedTextField(model.filters.from,{model.change(model.filters.copy(from=it.take(10)))},label={Text("Sold from YYYY-MM-DD")},modifier=Modifier.weight(1f));OutlinedTextField(model.filters.until,{model.change(model.filters.copy(until=it.take(10)))},label={Text("Sold through YYYY-MM-DD")},modifier=Modifier.weight(1f))}}
            item {Row {Checkbox(model.filters.history,{model.change(model.filters.copy(history=it))});Text("Include superseded source observations")};Text("Source revisions are preserved. Recommendations use only eligible current records and keep currencies separate.",style=MaterialTheme.typography.bodySmall)}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(enabled=!model.busy,onClick={picker.launch(arrayOf("text/*","application/octet-stream","application/vnd.sqlite3","application/x-sqlite3","*/*"))}) {Text("Import CSV / SQLite")};OutlinedButton(enabled=!model.busy && count>0,onClick={model.export {exportFile=it;save.launch("SnapIMS-market.sqlite")}}) {Text("Export sold evidence")};TextButton(onClick=model::verify,enabled=!model.busy) {Text("Verify integrity")};TextButton(onClick=model::rebuild,enabled=!model.busy) {Text("Rebuild search")}}}
            item {Row {Checkbox(stalePolicy,{stalePolicy=it;repo.preferences.edit().putBoolean("reuseOperatorPrices",it).apply()});Text("Show prior approved asking-price hints (never sold evidence)",Modifier.weight(1f))};OutlinedTextField(ageText,{value->ageText=value.filter(Char::isDigit).take(5);ageText.toIntOrNull()?.takeIf {it in 1..36500}?.let {repo.preferences.edit().putInt("marketAgeDays",it).apply()}},label={Text("Warn about evidence older than days")})}
        }
        if(count==0L && !model.busy) item {OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp)) {Text("Bring your market history",style=MaterialTheme.typography.titleLarge);Text("Import your Harvester CSV, supported SQLite export or SnapIMS market archive. Existing records stay searchable without a network connection.");Button(onClick={picker.launch(arrayOf("*/*"))}) {Text("Import market records")}}}}
        if(model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text(model.progress.ifBlank {"Working locally…"});TextButton(onClick=model::cancel) {Text("Cancel")}}
        if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
        if(model.note.isNotBlank()) item {Text(model.note)}
        item {OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(target?.let {"Price ${it.title.ifBlank {it.itemId}}"} ?: "Price an inventory item",fontWeight=FontWeight.Bold);target?.let {Text("${it.itemId} · current CAD ${InventoryRules.priceText(it.priceCents)} · ${it.edition.ifBlank {"edition not specified"}}")};TextButton(onClick={chooseTarget=true}) {Text(if(target==null) "Choose saved item" else "Change item")};if(target!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick={model.recommend("CAD")},enabled=!model.busy) {Text("Recommend · CAD")};OutlinedButton(onClick={model.recommend("USD")},enabled=!model.busy) {Text("USD evidence")}};Text("Only conservative compatible sold matches support a recommendation. Approximate results and asking prices are labeled separately.",style=MaterialTheme.typography.bodySmall)}}}
        if(stalePolicy && target!=null && model.askingHint!=null) item {val hint=model.askingHint!!;OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {Text("Prior approved asking price",fontWeight=FontWeight.Bold);Text("${marketMoneyText(hint.priceCents,hint.currency)} · ${hint.createdAt.take(10)}");Text("Operator decision, not a completed market sale.",style=MaterialTheme.typography.bodySmall);TextButton(onClick={reuseHint=true;reuseOverride=false}) {Text("Review reuse")}}}}
        if(model.page.approximate && !historyTab) item {Text("Possible matches for “${model.page.correctedQuery}”. These are approximate browsing results and are not automatically accepted price evidence.",color=MaterialTheme.colorScheme.tertiary)}
        if(historyTab) {
            item {Text("Your decisions are asking prices, not completed market sales.",fontWeight=FontWeight.Bold)}
            items(model.history,key={it.id}) {record->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {Text(record.title,fontWeight=FontWeight.Bold);Text("${marketMoneyText(record.priceCents,record.currency)} · ${record.recordType}");Text("${record.itemId} · ${record.method}\n${record.createdAt.take(19)}",style=MaterialTheme.typography.bodySmall);Text("${JSONArray(record.evidenceJson).length()} evidence references",style=MaterialTheme.typography.bodySmall)}}}
            if(model.history.size>=50) item {TextButton(onClick=model::moreHistory,enabled=!model.busy) {Text("More price decisions")}}
        } else {
            items(if(model.aiMatches.isEmpty()) model.page.groups else model.page.groups.sortedWith(compareByDescending<MarketGroup> {model.aiMatches[marketMatchId(it)]?.confidence ?: -1.0}.thenByDescending {it.matchScore}),key={it.titleKey+"/"+it.currency+"/"+it.edition+"/"+it.year}) {group->Surface(onClick={model.remember();model.detail(group)},shape=MaterialTheme.shapes.large,tonalElevation=2.dp) {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text(group.title,fontWeight=FontWeight.SemiBold,maxLines=3);Text("${group.samples} source sales · ${marketMoneyText(group.low,group.currency)} – ${marketMoneyText(group.high,group.currency)}");Text("Latest ${group.newest.take(10)}${if(group.edition.isNotBlank()) " · ${group.edition}" else ""}${group.year?.let {" · $it"} ?: ""}",style=MaterialTheme.typography.bodySmall);if(model.filters.query.isNotBlank()) Text(if(model.searching) "Updating local match scores…" else "Local match score ${group.matchScore}/100 · review film, edition and year",style=MaterialTheme.typography.bodySmall);model.aiMatches[marketMatchId(group)]?.let {match->Text("AI estimate ${(match.confidence*100).toInt()}% · ${match.reason}",style=MaterialTheme.typography.bodySmall)};if(group.approximate) Text("Possible match · review identity",color=MaterialTheme.colorScheme.tertiary);if(target!=null) TextButton(enabled=!model.searching && !model.busy,onClick={adopt=group;adoptTitle=group.title;adoptIdentity=false}) {Text("Review title from this match")}}}}
            if(model.page.groups.isNotEmpty()) item {TextButton(onClick={model.refresh(true)},enabled=!model.searching) {Text("Load more")}}
            if(count>0 && model.page.groups.isEmpty() && !model.searching) item {Text("No matching records. Try fewer words, another currency, or a barcode.")}
        }
    }
    if(rankDialog) AlertDialog(onDismissRequest={rankDialog=false},title={Text("Approve one AI match request")},text={Column {Text("Send the current title and up to 20 compact candidate identities to ${model.matchProvider} / ${model.matchModel}. No photos or full database upload. This provider may charge API fees; there is no automatic fallback.");Row {Checkbox(rankConsent,{rankConsent=it});Text("I authorize this one provider request and any applicable API charges.",Modifier.weight(1f))}}},confirmButton={Button(enabled=rankConsent,onClick={rankDialog=false;model.rankMatches(true)}) {Text("Send ranking request")}},dismissButton={TextButton(onClick={rankDialog=false}) {Text("Cancel")}})
    adopt?.let {group->val item=target;if(item!=null) AlertDialog(onDismissRequest={adopt=null},title={Text("Review matched title")},text={Column {Text("Current: ${item.title}\nCandidate: ${group.title}\n${group.currency} · ${group.samples} source sales");OutlinedTextField(adoptTitle,{adoptTitle=it.take(255)},label={Text("Confirmed inventory title · edit listing clutter out")});Row {Checkbox(adoptIdentity,{adoptIdentity=it});Text("Also use candidate edition '${group.edition}' and year ${group.year ?: "unknown"}",Modifier.weight(1f))};Text("This changes local metadata and clears readiness. It does not apply a price or publish anything.")}},confirmButton={Button(enabled=adoptTitle.isNotBlank() && !model.busy,onClick={adopt=null;model.adoptMatch(group,adoptTitle,adoptIdentity,item.revision)}) {Text("Save reviewed title")}},dismissButton={TextButton(onClick={adopt=null}) {Text("Cancel")}})}
    model.preview?.let {preview->AlertDialog(onDismissRequest=model::dismissPreview,title={Text("Import preview")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)};if(model.busy) item {Text(model.progress)};item {Text("${preview.filename}\n${preview.rows} source rows · ${preview.distinctRows} distinct observations\n${preview.format}");Text(preview.currencies.entries.joinToString {"${it.key}: ${it.value}"});Text("Existing evidence will be retained. Changed observations become preserved revisions.")};items(preview.warnings) {Text(it,color=MaterialTheme.colorScheme.tertiary)}}},confirmButton={Button(onClick=model::commit,enabled=!model.busy) {Text("Approve import")}},dismissButton={TextButton(onClick=model::dismissPreview,enabled=!model.busy) {Text("Cancel")}})}
    if(chooseTarget) AlertDialog(onDismissRequest={chooseTarget=false},title={Text("Choose inventory item")},text={LazyColumn {items(inventory.filter {!it.archived},key={it.groupId}) {item->TextButton(onClick={model.selectTarget(item.groupId);chooseTarget=false}) {Text("${item.title.ifBlank {"Untitled"}}\n${item.itemId}")}}}},confirmButton={TextButton(onClick={chooseTarget=false}) {Text("Close")}})
    model.selectedGroup?.let {group->AlertDialog(onDismissRequest=model::closeDetail,title={Text(group.title)},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("${group.samples} observations in ${group.currency}. Amounts include shipping. Grouping uses the complete normalized listing identity; no inferred film/edition merge.")}
        if(model.bins.isNotEmpty()) item {MarketDistribution(model.bins,group.currency)}
        items(model.listings,key={it.getString("id")}) {row->Column {Text("${marketMoneyText(row.getLong("totalCents"),row.getString("currency"))} · ${row.getString("soldAt").take(10)}",fontWeight=FontWeight.Bold);Text("Sale ${row.getString("soldDecimal")} + shipping ${row.getString("shippingDecimal")}");Text("${row.getString("recordType")} · ${row.getString("site")}\n${row.getString("sourceItemId")}",style=MaterialTheme.typography.bodySmall);Text("Barcode: ${row.getString("barcode").ifBlank {"not supplied"}} · edition: ${row.getString("edition").ifBlank {"not supplied"}}\nCondition: ${JSONObject(row.getString("rawJson")).optString("condition","not supplied")}",style=MaterialTheme.typography.bodySmall);Text("Collected: ${row.getString("collectedAt").ifBlank {"unknown"}}\nImported: ${row.getString("observedAt").take(19)}",style=MaterialTheme.typography.bodySmall);if(row.getInt("eligible")==0) Text("Conflicting observation · excluded from statistics",color=MaterialTheme.colorScheme.error);TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(row.getString("url"))))}) {Text("Open source listing · requires internet")};HorizontalDivider()}}
        if(model.listings.size>=50) item {TextButton(onClick=model::moreListings,enabled=!model.busy) {Text("More source listings")}}
    }},confirmButton={TextButton(onClick=model::closeDetail) {Text("Close")}})}
    if(reuseHint && target!=null) AlertDialog(onDismissRequest={reuseHint=false},title={Text("Reuse an approved asking price?")},text={Column {Text("This replaces ${target.title}'s current price with a prior operator asking price. It does not use sold-market statistics.");Row {Checkbox(reuseOverride,{reuseOverride=it});Text("Approve replacing an existing manual price")}}},confirmButton={TextButton(enabled=target.priceSource!="MANUAL" || reuseOverride,onClick={model.reuseHint(target.revision,reuseOverride,onApplied);reuseHint=false}) {Text("Approve asking price")}},dismissButton={TextButton(onClick={reuseHint=false}) {Text("Cancel")}})
    model.recommendation?.let {recommendation->MarketPriceApproval(model,recommendation,target,onApplied)}
}
@Composable private fun MarketPriceApproval(model:MarketModel,value:MarketRecommendation,item:InventoryRow?,applied:()->Unit) {
    var method by remember {mutableStateOf("MEDIAN")};var adjusted by remember {mutableStateOf("")};var rate by remember {mutableStateOf("")};var date by remember {mutableStateOf(LocalDate.now().toString())};var manual by remember {mutableStateOf(false)};var historical by remember {mutableStateOf(false)}
    val original=if(method=="MEDIAN") value.stats.medianCents else value.stats.averageCents
    val converted=runCatching {if(value.currency=="CAD") original else BigDecimal(original).multiply(BigDecimal(rate)).setScale(0,RoundingMode.HALF_UP).longValueExact()}.getOrNull()
    AlertDialog(onDismissRequest=model::clearRecommendation,title={Text("Review evidence-based price")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
        item {Text(item?.title.orEmpty(),fontWeight=FontWeight.Bold);Text("${value.stats.count} compatible sold records\n${value.oldest.take(10)} to ${value.newest.take(10)}\nOldest ${marketEvidenceOldestAge(value.oldest)} days · newest ${marketEvidenceOldestAge(value.newest)} days");Text("Median ${marketMoneyText(value.stats.medianCents,value.currency)}\nAverage ${marketMoneyText(value.stats.averageCents,value.currency)}\nRange ${marketMoneyText(value.stats.lowestCents,value.currency)} – ${marketMoneyText(value.stats.highestCents,value.currency)}\nIncludes original shipping charges.")}
        item {ChoiceField("Method",method,listOf("MEDIAN","AVERAGE")) {method=it}}
        if(value.currency!="CAD") item {Text("Evidence is ${value.currency}, inventory is CAD. Enter your explicit CAD per ${value.currency} rate; SnapIMS has not fetched an exchange rate.");OutlinedTextField(rate,{rate=it.take(20)},label={Text("CAD per ${value.currency}")});OutlinedTextField(date,{date=it.take(10)},label={Text("Rate effective date YYYY-MM-DD")})}
        item {Text(converted?.let {"Suggested ${marketMoneyText(it,"CAD")}"} ?: "Enter a conversion rate to calculate CAD.");OutlinedTextField(adjusted,{adjusted=it.take(20)},label={Text("Optional adjusted price · CAD")});Row {Checkbox(manual,{manual=it});Text("Approve replacing a manual price",Modifier.weight(1f))};Row {Checkbox(historical,{historical=it});Text("Approve reuse if evidence is older than my configured age",Modifier.weight(1f))}}
        item {Text("Saves an operator-approved asking price and exact evidence references. It does not create a sold-market record or publish to Shopify.",style=MaterialTheme.typography.bodySmall)}
    }},confirmButton={Button(enabled=!model.busy && converted!=null,onClick={model.approve(method,adjusted,manual,historical,rate,date,applied)}) {Text("Approve price")}},dismissButton={TextButton(onClick=model::clearRecommendation) {Text("Cancel")}})
}

@Composable private fun MarketDistribution(bins:List<MarketBin>,currency:String) {
    val maximum=bins.maxOf {it.count}.toFloat().coerceAtLeast(1f)
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Eligible sold-price distribution · includes shipping",fontWeight=FontWeight.SemiBold)
        bins.forEach {bin->Column {Text("${marketMoneyText(bin.startCents,currency)}–${marketMoneyText(bin.endCents,currency)} · ${bin.count} sales",style=MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress={bin.count/maximum},modifier=Modifier.fillMaxWidth().height(8.dp),drawStopIndicator={})
        }}
    }
}
