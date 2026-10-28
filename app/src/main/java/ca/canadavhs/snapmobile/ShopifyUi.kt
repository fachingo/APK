package ca.canadavhs.snapmobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import kotlinx.coroutines.*
import org.json.*
import ca.canadavhs.snapmobile.core.*

class ShopifyModel(app:Application):AndroidViewModel(app) {
    private val repo=(app as CaptureApplication).repository
    var base by mutableStateOf(repo.preferences.getString("shopifyBroker","").orEmpty());private set
    var health by mutableStateOf<JSONObject?>(null);private set
    var paired by mutableStateOf(repo.preferences.getBoolean("shopifyPaired",false));private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    var message by mutableStateOf("");private set
    internal fun action(block:suspend ()->Unit) {if(busy) return;busy=true;viewModelScope.launch {try {block();error=""} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message.orEmpty()} finally {busy=false}}}
    fun changeBase(value:String) {if(value!=base) {base=value.take(2000);health=null;paired=false}}
    fun pair(code:String)=action {withContext(Dispatchers.IO) {ShopifyBrokerClient(repo.context,base).pair(code)};check(repo.preferences.edit().putString("shopifyBroker",base).putBoolean("shopifyPaired",true).commit());paired=true;message="Device paired. Authorize and test the Shopify connection."}
    fun authorize(open:(String)->Unit)=action {val result=ShopifyBrokerClient(repo.context,base).authorize();val url=result.optString("url");if(url.isNotBlank()) {val uri=java.net.URI(url);check(uri.scheme=="https" && uri.host?.endsWith(".myshopify.com")==true && uri.path=="/admin/oauth/authorize");open(url)} else message=result.optString("message")}
    internal suspend fun checkConnection():JSONObject {check(paired) {"Pair this device first."};return ShopifyBrokerClient(repo.context,base).call(newId(),"health",JSONObject()).also {health=it;message="Connection checked against Shopify. Development-store publishing acceptance is still unverified."}}
    fun test()=action {checkConnection()}
    fun disconnect(revoke:Boolean)=action {
        if(revoke) ShopifyBrokerClient(repo.context,base).revoke()
        repo.journal.shopifyDao().active().forEach {repo.pauseShopify(it.id)}
        withContext(Dispatchers.IO) {ShopifyDeviceIdentity(repo.context).forget()};health=null;paired=false;check(repo.preferences.edit().putBoolean("shopifyPaired",false).commit());message=if(revoke) "Device access revoked at broker." else "Device key removed locally. Revoke the old device in the broker administration tool."
    }
    fun queue(ids:Set<String>,location:String,live:Boolean,publication:String,confirmation:String)=action {val h=checkConnection();val id=repo.queueShopify(ids,h,base,location,live,publication,confirmation);message="Saved ${if(live) "publication" else "draft"} job $id"}
    fun cancel(id:String)=action {repo.cancelShopify(id)}
    fun pause(id:String)=action {repo.pauseShopify(id)}
    fun resume(id:String)=action {repo.resumeShopify(id,base,checkConnection())}
}
@Composable fun ShopifyScreen(vm:CaptureViewModel,back:()->Unit,model:ShopifyModel=viewModel()) {
    val context=LocalContext.current;val inventory by vm.inventory.collectAsStateWithLifecycle()
    val jobs by vm.repo.journal.shopifyDao().watchJobs().collectAsStateWithLifecycle(emptyList());val items by vm.repo.journal.shopifyDao().watchItems().collectAsStateWithLifecycle(emptyList())
    var code by remember {mutableStateOf("")};var selected by rememberSaveable {mutableStateOf<Set<String>>(emptySet())}
    var location by rememberSaveable {mutableStateOf("")};var publication by rememberSaveable {mutableStateOf("")};var consent by rememberSaveable {mutableStateOf(false)}
    var review by remember {mutableStateOf<List<InventoryRow>?>(null)};var live by remember {mutableStateOf(false)};var confirmation by remember {mutableStateOf("")}
    DisposableEffect(Unit) {val window=(context as? android.app.Activity)?.window;val previous=(window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_SECURE !=0;window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);onDispose {if(!previous) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Back")};Text("Shopify",style=MaterialTheme.typography.headlineLarge);Text("Drafts and durable jobs",style=MaterialTheme.typography.titleMedium)}
        item {Text("Integration preview · real device authorization and development-store publishing are UNVERIFIED. Inventory stays on this phone. Shopify requires a hosted credential broker; capture, pricing and backups work independently.")}
        item {OutlinedTextField(model.base,model::changeBase,label={Text("Hosted broker HTTPS origin")},enabled=!model.busy,modifier=Modifier.fillMaxWidth(),singleLine=true)}
        item {OutlinedTextField(code,{code=it.take(200)},label={Text("One-use pairing code from broker owner")},visualTransformation=PasswordVisualTransformation(),enabled=!model.busy,modifier=Modifier.fillMaxWidth());Button(onClick={model.pair(code);code=""},enabled=!model.busy && code.isNotBlank()) {Text("Pair this device")}}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick={model.authorize {url->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}},enabled=model.paired && !model.busy) {Text("Authorize in browser")};Button(onClick=model::test,enabled=model.paired && !model.busy) {Text("Test connection")}}}
        if(model.paired) item {FlowRow {TextButton(onClick={model.disconnect(true)},enabled=!model.busy) {Text("Revoke device")};TextButton(onClick={model.disconnect(false)},enabled=!model.busy) {Text("Forget locally")}}}
        if(model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
        if(model.message.isNotBlank()) item {Text(model.message)}
        val health=model.health
        if(health!=null) {
            item {Text("${health.getString("name")} · ${health.getString("shop")}\n${health.getString("currency")} · ${if(health.getBoolean("developmentStore")) "development store" else "production writes blocked"} · API ${health.getString("apiVersion")}",fontWeight=FontWeight.Bold);Text("Scopes: ${health.getJSONArray("scopes")}",style=MaterialTheme.typography.bodySmall)}
            val locations=health.getJSONArray("locations")
            item {Text("Inventory location");for(i in 0 until locations.length()) {val loc=locations.getJSONObject(i);if(loc.optBoolean("isActive")) FilterChip(selected=location==loc.getString("id"),onClick={location=loc.getString("id")},label={Text(loc.getString("name"))})}}
            val publications=health.getJSONArray("publications")
            if(health.optBoolean("allowPublish")) item {Text("Sales channel for separately approved publication");for(i in 0 until publications.length()) {val p=publications.getJSONObject(i);FilterChip(selected=publication==p.getString("id"),onClick={publication=p.getString("id")},label={Text(p.getString("name"))})}}
        }
        item {Text("Select inventory",style=MaterialTheme.typography.titleLarge);Text("New uploads default to DRAFT. Existing linked products are protected. One item represents one physical copy with stock 1.")}
        items(inventory.filter {!it.archived},key={it.groupId}) {item->OutlinedCard(Modifier.fillMaxWidth()) {Row(Modifier.padding(12.dp)) {Checkbox(checked=item.groupId in selected,onCheckedChange={selected=if(it) selected+item.groupId else selected-item.groupId});Column(Modifier.weight(1f)) {Text(item.title.ifBlank {"Untitled"},fontWeight=FontWeight.Bold);Text(item.itemId,style=MaterialTheme.typography.bodySmall);Text("CAD ${InventoryRules.priceText(item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)})} · ${item.weightGrams} g · stock ${item.quantity}");Text("${if(item.ready) "Ready" else "Review required"} · ${item.shopifyState}")}}}}
        item {Row {Checkbox(consent,{consent=it});Text("I approve sending the selected listing data and photographs to Shopify.",Modifier.weight(1f))};Button(enabled=selected.isNotEmpty() && consent && model.health!=null && location.isNotBlank() && !model.busy,onClick={live=false;review=inventory.filter {it.groupId in selected};confirmation=""}) {Text("Review draft upload (${selected.size})")}}
        if(model.health?.optBoolean("allowPublish")==true) item {OutlinedButton(enabled=selected.isNotEmpty() && consent && publication.isNotBlank() && !model.busy,onClick={live=true;review=inventory.filter {it.groupId in selected};confirmation=""}) {Text("Review live publication")}}
        item {Text("Publishing jobs",style=MaterialTheme.typography.titleLarge);Text("A paused or failed job can leave a remote draft. Retrying reconciles saved identities. Android work completion alone does not prove Shopify completion.")}
        items(jobs,key={it.id}) {job->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("${job.kind} · ${job.state}",fontWeight=FontWeight.Bold);Text("${job.shop}\n${job.id}",style=MaterialTheme.typography.bodySmall);if(job.error.isNotBlank()) Text(job.error,color=MaterialTheme.colorScheme.error)
            items.filter {it.jobId==job.id}.forEach {entry->Text("${entry.itemId}: ${entry.state} · ${entry.step}");if(entry.error.isNotBlank()) Text(entry.error,color=MaterialTheme.colorScheme.error);val remote=JSONObject(entry.remoteJson);if(remote.has("productId")) Text(remote.getString("productId"),style=MaterialTheme.typography.bodySmall)}
            if(job.state !in listOf("SUCCESS","CANCELLED")) FlowRow {TextButton(onClick={model.pause(job.id)},enabled=!model.busy) {Text("Pause")};TextButton(onClick={model.resume(job.id)},enabled=model.paired && !model.busy) {Text("Reconcile / resume")};TextButton(onClick={model.cancel(job.id)},enabled=!model.busy) {Text("Cancel remaining")}}
        }}}
    }
    review?.let {rows->AlertDialog(onDismissRequest={review=null},title={Text(if(live) "Publish selected drafts live?" else "Approve Shopify drafts?")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Text(if(live) "This makes verified drafts ACTIVE and publishes them to the chosen sales channel. Type SUBMIT to approve." else "Creates drafts with all selected photographs, immutable Item ID as SKU, reviewed CAD price and weight, and stock one.")}
        items(rows) {item->Text("${item.itemId}\n${item.title}\nCAD ${InventoryRules.priceText(item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)})} · ${item.weightGrams} g\n${item.description}")}
        if(live) item {OutlinedTextField(confirmation,{confirmation=it},label={Text("SUBMIT")})}
    }},confirmButton={Button(enabled=!live || confirmation=="SUBMIT",onClick={model.queue(rows.map {it.groupId}.toSet(),location,live,publication,confirmation);review=null;selected=emptySet()}) {Text(if(live) "Approve live publication" else "Create drafts")}},dismissButton={TextButton(onClick={review=null}) {Text("Cancel")}})}
}
