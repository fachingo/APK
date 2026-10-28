package ca.canadavhs.snapmobile

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.canadavhs.snapmobile.core.InventoryRules
import kotlinx.coroutines.*
import org.json.*
import java.io.File

private fun money(cents:Long)="CAD ${java.math.BigDecimal.valueOf(cents,2).toPlainString()}"
@Composable fun PricingScreen(vm:CaptureViewModel,back:()->Unit) {
    var historicalReuse by remember {mutableStateOf(vm.repo.preferences.getBoolean("reuseHistoricalSold",false))}
    var marketOpen by remember {mutableStateOf(false)}
    if(marketOpen) MarketDialog(vm,dismiss={marketOpen=false})
    val rows by vm.inventory.collectAsStateWithLifecycle();val evidence by vm.repo.journal.nextDao().watchEvidence().collectAsStateWithLifecycle(emptyList())
    val context=LocalContext.current;var selected by rememberSaveable {mutableStateOf<String?>(null)};var search by rememberSaveable {mutableStateOf("")}
    var decision by remember {mutableStateOf<Pair<InventoryRow,PricingEvidenceRow>?>(null)};var method by remember {mutableStateOf(vm.repo.preferences.getString("pricingMethod","MEDIAN") ?: "MEDIAN")};var overrideManualConfirmed by remember {mutableStateOf(false)}
    var bulk by remember {mutableStateOf<List<Pair<InventoryRow,PricingEvidenceRow>>?>(null)}
    var selectedBulk by remember {mutableStateOf<Set<String>>(emptySet())}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->if(uri!=null) vm.transfer {
        withContext(Dispatchers.IO) {val file=File(context.cacheDir,"pricing-${java.util.UUID.randomUUID()}.sqlite")
            try {context.contentResolver.openInputStream(uri)?.use {input->file.outputStream().use {copyCancellable(input,it,512_000_000)}} ?: error("Cannot read pricing database.")
                val count=vm.repo.importPricingDatabase(file);vm.message="Imported $count new pricing collections. No inventory prices changed."
            } finally {file.delete()}
        }
    }}
    BackHandler {back()}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Back")};PageTitle("Sold comparisons","Actual imported evidence · reviewed prices")}
        item {Button(onClick={marketOpen=true}) {Text("Search Market database")}}
        item {Row {Checkbox(historicalReuse,{historicalReuse=it;vm.repo.preferences.edit().putBoolean("reuseHistoricalSold",it).apply()});Text("Allow reviewed reuse after online cache expiry",Modifier.weight(1f))}}
        item {Text("Import a consistent SQLite export from the desktop pricing module. Valid CAD evidence stays available offline; older evidence requires reviewed historical reuse. Prices include sold price and shipping; no AI-generated prices.")}
        item {OutlinedButton(enabled=!vm.maintenance,onClick={picker.launch(arrayOf("application/octet-stream","application/x-sqlite3","application/vnd.sqlite3","*/*"))}) {Text("Import pricing SQLite")};Text("${evidence.size} collections · ${evidence.count {it.fresh()}} fresh",style=MaterialTheme.typography.bodySmall)}
        item {OutlinedButton(enabled=!vm.maintenance,onClick={
            val choices=rows.filter {!it.archived && it.priceSource!="MANUAL"}.mapNotNull {item->evidence.firstOrNull {it.titleKey==InventoryRules.normalizedTitle(item.title) && it.usable()}?.let {item to it}}.take(200)
            bulk=choices;selectedBulk=choices.map {it.first.groupId}.toSet()
        }) {Text("Review a batch of prices")}}
        item {OutlinedTextField(search,{search=it.take(200)},label={Text("Find inventory title")},modifier=Modifier.fillMaxWidth())}
        items(rows.filter {!it.archived && it.title.contains(search,true)},key={it.groupId}) {item ->
            val matches=evidence.filter {it.titleKey==InventoryRules.normalizedTitle(item.title)}
            Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(item.title.ifBlank {"Untitled item"},fontWeight=FontWeight.Bold);Text(item.itemId,style=MaterialTheme.typography.bodySmall)
                Text("${item.priceCents?.let {money(it)} ?: "Unpriced"} · ${item.priceSource}")
                TextButton(onClick={selected=if(selected==item.groupId) null else item.groupId}) {Text("Review ${matches.size} collections")}
                if(selected==item.groupId) {
                    if(matches.isEmpty()) Text("No sold evidence for this exact title. Set a manual price in Inventory.")
                    matches.take(10).forEach {e ->val stats=runCatching {e.statistics()}.getOrNull()
                        HorizontalDivider();Text("${e.status} · ${if(e.fresh()) "Fresh" else if(e.usable()) "Older history · retained offline" else "Review source data"} · ${e.currency}")
                        Text("Collected ${e.collectedAt}\nExpires ${e.expiresAt}",style=MaterialTheme.typography.bodySmall)
                        stats?.let {Text("${it.count} valid matches · average ${money(it.averageCents)} · median ${money(it.medianCents)}\nRange ${money(it.lowestCents)}–${money(it.highestCents)}")}
                        runCatching {e.comparables()}.getOrDefault(emptyList()).take(20).forEach {c ->Text("${c.title}\n${c.sold} + ${c.shipping} shipping · ${c.soldDate}\n${c.url}",style=MaterialTheme.typography.bodySmall)}
                        Button(enabled=e.usable(),onClick={decision=item to e;overrideManualConfirmed=false}) {Text("Choose and review price")}
                    }
                }
            }}
        }
        if(rows.isEmpty()) item {EmptyState("No inventory yet","Capture or import physical copies first.")}
    }
    decision?.let {(item,e) ->val stats=e.statistics()!!
        AlertDialog(onDismissRequest={decision=null},title={Text("Apply reviewed sold price?")},text={Column {
            Text("${item.title}\n${item.itemId}\n${stats.count} valid comparisons. This clears listing readiness.")
            Row {FilterChip(selected=method=="MEDIAN",onClick={method="MEDIAN"},label={Text("Median ${money(stats.medianCents)}")});Spacer(Modifier.width(8.dp));FilterChip(selected=method=="AVERAGE",onClick={method="AVERAGE"},label={Text("Average ${money(stats.averageCents)}")})}
            if(item.priceSource=="MANUAL") Row {Checkbox(overrideManualConfirmed,{overrideManualConfirmed=it});Text("Replace my manual price")}
        }},confirmButton={TextButton(enabled=item.priceSource!="MANUAL" || overrideManualConfirmed,onClick={vm.action {vm.repo.applySoldPrice(item.groupId,e.id,method,item.revision,overrideManualConfirmed);vm.repo.preferences.edit().putString("pricingMethod",method).apply();decision=null;vm.message="Reviewed sold price applied."}}) {Text("Apply")}},dismissButton={TextButton(onClick={decision=null}) {Text("Cancel")}})
    }
    bulk?.let {choices ->AlertDialog(onDismissRequest={bulk=null},title={Text("Review batch prices")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())) {
        Text("Up to 200 exact-title CAD matches (older records require historical reuse enabled). Manual prices are excluded. Every selected change clears readiness; changed records stop the entire batch.")
        Row {FilterChip(selected=method=="MEDIAN",onClick={method="MEDIAN"},label={Text("Median")});FilterChip(selected=method=="AVERAGE",onClick={method="AVERAGE"},label={Text("Average")})}
        if(choices.isEmpty()) Text("No eligible fresh matches. Import evidence or review manual prices individually.")
        choices.forEach {(item,e)->val stats=e.statistics()!!;Row {Checkbox(item.groupId in selectedBulk,{checked->selectedBulk=if(checked) selectedBulk+item.groupId else selectedBulk-item.groupId});Column {Text(item.title);Text("${item.itemId} · ${money(if(method=="MEDIAN") stats.medianCents else stats.averageCents)} · ${stats.count} matches",style=MaterialTheme.typography.bodySmall)}}}
    }},confirmButton={TextButton(enabled=selectedBulk.isNotEmpty(),onClick={vm.action {val count=vm.repo.applySoldPrices(choices.filter {it.first.groupId in selectedBulk},method,false);vm.repo.preferences.edit().putString("pricingMethod",method).apply();bulk=null;vm.message="Applied $count reviewed prices atomically."}}) {Text("Apply ${selectedBulk.size}")}},dismissButton={TextButton(onClick={bulk=null}) {Text("Cancel")}})}

}
@Composable fun ListingPlansScreen(vm:CaptureViewModel,back:()->Unit) {ShopifyScreen(vm,back)}
@Composable fun AiWorkspaceScreen(vm:CaptureViewModel,back:()->Unit,onTasks:()->Unit={},ai:AiWorkspaceModel=viewModel()) {
    val context=LocalContext.current;val chats by ai.chats.collectAsStateWithLifecycle();val active by ai.active.collectAsStateWithLifecycle()
    var selected by rememberSaveable {mutableStateOf<String?>(null)};var connections by rememberSaveable {mutableStateOf(false)};var newChat by remember {mutableStateOf(false)}
    var search by rememberSaveable {mutableStateOf("")};var historyMatches by remember {mutableStateOf<Set<String>>(emptySet())}
    LaunchedEffect(search,chats) {historyMatches=if(search.isBlank()) emptySet() else vm.repo.journal.nextDao().searchMessages("%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%").toSet()}
    BackHandler {if(selected!=null) selected=null else if(connections) connections=false else back()}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->val id=selected;if(uri!=null && id!=null) ai.action {val json=ai.export(id);withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {it.write(json)} ?: error("Cannot save chat export.")};vm.message="Chat exported. Attachments remain in the photo-inclusive inventory backup."}}
    val chat=chats.firstOrNull {it.id==selected}
    Column(Modifier.fillMaxSize()) {
        if(ai.error.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.errorContainer) {Row(Modifier.fillMaxWidth().padding(12.dp)) {Text(ai.error,Modifier.weight(1f));TextButton(onClick=ai::clearError) {Text("Dismiss")}}}
        if(ai.notice.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {Row(Modifier.fillMaxWidth().padding(12.dp)) {Text(ai.notice,Modifier.weight(1f));TextButton(onClick=ai::clearNotice) {Text("Dismiss")}}}
        when {
            connections ->ConnectionsPanel(ai) {connections=false}
            chat!=null ->ChatPanel(ai,chat,onBack={selected=null},onExport={save.launch("snapims-chat-${chat.id}.json")},onDelete={ai.delete(chat.id);selected=null})
            else ->LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {TextButton(onClick=back) {Text("← Back")};PageTitle("AI workspace","Private local history · explicit provider billing")}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick={newChat=true}) {Text("New chat")};OutlinedButton(onClick={connections=true}) {Text("Connections")};TextButton(onClick=onTasks) {Text("Tasks")}}}
                item {OutlinedTextField(search,{search=it.take(200)},label={Text("Search chats and messages")},modifier=Modifier.fillMaxWidth())}
                items(chats.filter {search.isBlank() || it.title.contains(search,true) || it.id in historyMatches},key={it.id}) {c ->Surface(onClick={selected=c.id},shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {Column(Modifier.fillMaxWidth().padding(18.dp)) {Text(c.title,fontWeight=FontWeight.Bold);Text("${c.provider} · ${c.model}",style=MaterialTheme.typography.bodySmall);Text(if(c.id in active) "Streaming…" else if(c.authMode=="CHATGPT_PLAN") "ChatGPT plan · no API-key fallback" else "API connection · provider rates apply",style=MaterialTheme.typography.bodySmall)}}}
                if(chats.isEmpty()) item {EmptyState("A workspace for your collection","Connect a provider, discover its models, then start a conversation. Tools are optional and changes require review.")}
            }
        }
    }
    if(newChat) NewChatDialog(ai,dismiss={newChat=false}) {selected=it;newChat=false}
}
@Composable private fun ConnectionsPanel(ai:AiWorkspaceModel,back:()->Unit) {
    val context=LocalContext.current;var providerId by rememberSaveable {mutableStateOf("OPENAI")};var key by remember {mutableStateOf("")};var connected by remember(providerId) {mutableStateOf(if(providerId=="CHATGPT") SiwcConnection.connected(context) else providerId=="LOCAL" || providerKey(context,providerId).hasKey())}
    val status by SiwcConnection.status.collectAsStateWithLifecycle();val proofs by ai.proofs.collectAsStateWithLifecycle()
    LaunchedEffect(providerId,status) {if(providerId=="CHATGPT") connected=SiwcConnection.connected(context)}
    DisposableEffect(Unit) {val window=(context as? android.app.Activity)?.window
        val previous=(window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {if(!previous) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
    }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Conversations")};PageTitle("Connections","Choose who handles each conversation")}
        item {ProviderPicker(providerId) {providerId=it;key=""}}
        item {Text(if(connected) "Credentials saved on this device" else "Not connected")}
        if(providerId=="CHATGPT") {
            item {Text("Experimental official SIWC preview. Eligible Plus/Pro accounts only. Native Pixel compatibility is unverified until sign-in, discovery and streaming all pass on the device. Uses the system browser and an HTTP listener bound to 127.0.0.1; no mandatory home server.")}
            item {Column {
                Button(onClick={androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,SiwcSignInService::class.java))}) {Text("Continue with ChatGPT")}
                TextButton(onClick={context.stopService(Intent(context,SiwcSignInService::class.java));SiwcConnection.status.value="Sign-in cancelled."}) {Text("Cancel sign-in")}
            }}
            item {Text(status);Text("Usage totals and remaining plan allowance are not supplied by this preview contract. A plan-limit error stops the request. API-key mode is never selected automatically.",style=MaterialTheme.typography.bodySmall)}
            item {OutlinedButton(onClick={ai.discover("CHATGPT")},enabled=!ai.loading) {Text("Check connection & discover models")}}
            item {Row {TextButton(onClick={SiwcConnection.disconnect(context);connected=false}) {Text("Disconnect locally")};TextButton(onClick={SiwcConnection.disconnect(context,true);connected=false}) {Text("Forget account")}}}
            item {Text("Device proof log",fontWeight=FontWeight.Bold);Text("Physical acceptance requires LISTENER → SIGNED_IN → MODELS_DISCOVERED → STREAM_COMPLETED on a Pixel. Host tests do not establish this.",style=MaterialTheme.typography.bodySmall)}
            items(proofs,key={it.id}) {p ->Text("${p.stage} · ${if(p.physicalPixel) "Pixel hardware" else "not verified Pixel"}\n${p.detail}",style=MaterialTheme.typography.bodySmall)}
        } else if(providerId=="LOCAL") item {Text("Optional on-device OpenAI-compatible server at http://127.0.0.1:8080/v1. Start a compatible local-model Android app first. SnapIMS does not download or run a model itself. No off-device server is required for inventory.")}
        else {
            item {OutlinedTextField(key,{key=it.take(512)},label={Text("Provider API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {Row {Button(enabled=key.isNotBlank(),onClick={ai.action {providerKey(context,providerId).save(key);key="";connected=true}}) {Text("Save securely")};TextButton(onClick={ai.action {providerKey(context,providerId).remove();connected=false}}) {Text("Remove key")}}}
            item {Text("Keys are encrypted with Android Keystore and excluded from backup/export. Free tiers and :free models remain subject to provider limits. SnapIMS does not silently choose another provider.")}
        }
        if(providerId!="CHATGPT") item {OutlinedButton(enabled=!ai.loading,onClick={ai.discover(providerId)}) {Text("Discover models")}}
        if(ai.loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(ai.catalog.filter {it.provider==providerId}.take(100),key={it.id}) {model ->Text("${model.displayName}\n${model.model}",style=MaterialTheme.typography.bodySmall)}
    }
}
@Composable private fun ProviderPicker(selected:String,change:(String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {OutlinedButton(onClick={expanded=true}) {Text(AiProvider.get(selected).label)};DropdownMenu(expanded,{expanded=false}) {AiProvider.all.forEach {p ->DropdownMenuItem(text={Text(p.label)},onClick={change(p.id);expanded=false})}}}
}
@Composable private fun NewChatDialog(ai:AiWorkspaceModel,dismiss:()->Unit,created:(String)->Unit) {
    var provider by remember {mutableStateOf("OPENAI")};var model by remember {mutableStateOf<AiCapabilityRow?>(null)};var query by remember {mutableStateOf("")};var instructions by remember {mutableStateOf("")};var tools by remember {mutableStateOf(false)};var proposals by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=dismiss,title={Text("New conversation")},text={Column(Modifier.heightIn(max=550.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        ProviderPicker(provider) {provider=it;model=null;tools=false}
        Text(if(provider=="CHATGPT") "Uses your authorized ChatGPT plan. Never falls back to API billing." else if(provider=="LOCAL") "Uses a model running on this phone." else "Uses this provider's API key. Provider charges and limits apply.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!ai.loading,onClick={ai.discover(provider)}) {Text(if(ai.loading) "Loading…" else "Discover models")}
        OutlinedTextField(query,{query=it},label={Text("Filter model catalog")})
        ai.catalog.filter {it.provider==provider && (it.model.contains(query,true) || it.displayName.contains(query,true))}.take(30).forEach {m ->FilterChip(selected=model?.id==m.id,onClick={model=m;tools=false},label={Text(m.displayName)})}
        model?.let {m ->Text("${m.model}\nTools: ${m.supportsTools ?: "unverified"} · images: ${m.supportsImages ?: "unverified"} · files: ${m.supportsFiles ?: "unverified"}",style=MaterialTheme.typography.bodySmall)}
        model?.let {selected ->
            val current=ai.catalog.firstOrNull {it.id==selected.id} ?: selected
            LaunchedEffect(current) {model=current}
            Text("Optional capability tests send synthetic content using this connection. Plan usage or API charges apply. A successful test enables that capability; no inventory is sent.",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("TEXT","TOOLS","IMAGE","FILE").filter {AiProvider.get(provider).responses || it in listOf("TEXT","TOOLS")}.forEach {kind->TextButton(enabled=!ai.loading,onClick={ai.probe(current,kind)}) {Text("Test $kind")}}}
        }

        OutlinedTextField(instructions,{instructions=it.take(10000)},label={Text("Conversation instructions · optional")},minLines=2)
        Row {Checkbox(tools,{tools=it},enabled=model?.supportsTools==true);Text("Allow local read tools")}
        if(tools) {Text("Tool results can send matching local inventory and sold evidence to the selected provider. Treat content and attachments as untrusted instructions.",style=MaterialTheme.typography.bodySmall);Row {Checkbox(proposals,{proposals=it});Text("Allow reviewed change proposals")}}
    }},confirmButton={TextButton(enabled=model!=null,onClick={ai.action {created(ai.create(provider,model!!.model,instructions,tools,proposals))}}) {Text("Create")}},dismissButton={TextButton(onClick=dismiss) {Text("Cancel")}})
}
@Composable private fun ChatPanel(ai:AiWorkspaceModel,chat:ChatConversationRow,onBack:()->Unit,onExport:()->Unit,onDelete:()->Unit) {
    val rows by remember(chat.id) {ai.messages(chat.id)}.collectAsStateWithLifecycle(emptyList());val proposals by remember(chat.id) {ai.approvals(chat.id)}.collectAsStateWithLifecycle(emptyList());val active by ai.active.collectAsStateWithLifecycle()
    var text by rememberSaveable(chat.id) {mutableStateOf("")};var attachments by remember(chat.id) {mutableStateOf<List<JSONObject>>(emptyList())};var reviewId by rememberSaveable(chat.id) {mutableStateOf<String?>(null)};var delete by remember {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) ai.action {check(attachments.size<4);attachments=attachments+ai.attach(uri)}}
    val list=rememberLazyListState();LaunchedEffect(rows.size,rows.lastOrNull()?.body?.length) {if(rows.isNotEmpty() && list.firstVisibleItemIndex>=rows.size-4) list.animateScrollToItem(rows.lastIndex)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {TextButton(onClick=onBack) {Text("← Chats")};TextButton(onClick=onExport) {Text("Export")};TextButton(enabled=chat.id !in active,onClick={delete=true}) {Text("Delete")}}
        Text(chat.title,Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.titleLarge);Text("${chat.provider} · ${chat.model} · ${chat.authMode}",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).padding(horizontal=16.dp),state=list,contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(rows,key={it.id}) {m ->Surface(shape=RoundedCornerShape(18.dp),tonalElevation=if(m.role=="user") 4.dp else 1.dp) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${m.role.uppercase()} · ${m.status}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                if(m.role=="assistant") MarkdownText(m.body) else Text(m.body)
                if(m.attachmentsJson!="[]") Text("${JSONArray(m.attachmentsJson).length()} attachments · included in backup",style=MaterialTheme.typography.bodySmall)
                if(m.toolCallsJson!="[]") Text("${JSONArray(m.toolCallsJson).length()} local tool requests",style=MaterialTheme.typography.bodySmall)
                if(m.error.isNotEmpty()) Text(m.error,color=MaterialTheme.colorScheme.error)
                if(m.inputTokens!=null || m.outputTokens!=null) Text("Tokens ${m.inputTokens ?: "?"} in / ${m.outputTokens ?: "?"} out · ${m.costUsd?.let {"estimated USD $it"} ?: "cost unavailable"}",style=MaterialTheme.typography.bodySmall)
            }}}
            items(proposals,key={it.id}) {p ->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {Text("${p.action} · ${p.state}");if(p.state=="PENDING") TextButton(onClick={reviewId=p.id}) {Text("Review proposed change")};if(p.resultJson.isNotBlank()) Text(p.resultJson,style=MaterialTheme.typography.bodySmall)}}}
        }
        if(attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)) {attachments.forEach {a ->InputChip(selected=true,onClick={attachments=attachments-a},label={Text("${a.getString("mime")} ×")})}}
        OutlinedTextField(text,{text=it.take(100000)},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),label={Text("Message")},minLines=2,maxLines=5)
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=attachments.size<4 && AiProvider.get(chat.provider).responses,onClick={picker.launch(arrayOf("image/jpeg","image/png","application/pdf","text/plain"))}) {Text("Attach")}
            if(chat.id in active) Button(onClick={ai.stop(chat.id)}) {Text("Stop")}
            else Button(enabled=text.isNotBlank() || attachments.isNotEmpty(),onClick={ai.send(chat.id,text,JSONArray(attachments));text="";attachments=emptyList()}) {Text("Send")}
        }
    }
    proposals.firstOrNull {it.id==reviewId}?.let {p ->AgentApprovalDialog(p,ai.error,p.id in ai.deciding,dismiss={reviewId=null},decide={accept,options->ai.decide(p.id,accept,options) {reviewId=null}})}
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Delete this local conversation?")},text={Text("Its messages and approvals will be deleted. Approved tasks remain in Tasks. Original inventory and photographs are retained. Export the conversation first if needed.")},confirmButton={TextButton(onClick=onDelete) {Text("Delete")}},dismissButton={TextButton(onClick={delete=false}) {Text("Cancel")}})
}

@Composable fun BusinessDefaultsSettings(vm:CaptureViewModel) {
    val prefs=vm.repo.preferences
    var vendor by remember {mutableStateOf(prefs.getString("defaultVendor","Canada VHS") ?: "Canada VHS")}
    var type by remember {mutableStateOf(prefs.getString("defaultProductType","VHS Tape") ?: "VHS Tape")}
    var weight by remember {mutableStateOf(prefs.getInt("defaultWeightGrams",250).toString())}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("INVENTORY DEFAULTS",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Text("Applied to new physical copies. Existing records keep their reviewed values.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(vendor,{vendor=it.take(1000);prefs.edit().putString("defaultVendor",vendor).apply()},label={Text("Default vendor")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(type,{type=it.take(1000);prefs.edit().putString("defaultProductType",type).apply()},label={Text("Default product type")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(weight,{weight=it.filter(Char::isDigit).take(7);weight.toIntOrNull()?.takeIf {n->n in 1..1_000_000}?.let {n->prefs.edit().putInt("defaultWeightGrams",n).apply()}},label={Text("Default shipping weight · grams")},isError=weight.toIntOrNull() !in 1..1_000_000)
    }
}
