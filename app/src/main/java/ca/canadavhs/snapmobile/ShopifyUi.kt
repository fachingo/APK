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
    internal var gatewayFactory:(String)->ShopifyGateway={shopifyGateway(repo.context,it)}
    var catalog by mutableStateOf<JSONObject?>(null);private set
    var productDetail by mutableStateOf<JSONObject?>(null);private set
    var catalogFetchedAt by mutableStateOf("");private set
    var catalogQuery by mutableStateOf("");private set
    var catalogStatus by mutableStateOf("");private set
    var base by mutableStateOf(runCatching {repo.shopifyEndpoint()}.getOrDefault(""));private set
    var health by mutableStateOf<JSONObject?>(null);private set
    var paired by mutableStateOf(repo.preferences.getBoolean("shopifyPaired",false));private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    var message by mutableStateOf("");private set
    internal fun action(block:suspend ()->Unit) {if(busy) return;busy=true;viewModelScope.launch {try {block();error=""} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message.orEmpty()} finally {busy=false}}}
    fun saveDirect(shop:String,client:String,secret:String)=action {withContext(Dispatchers.IO) {repo.directShopifyFactory(repo.context).save(shop.trim().lowercase(),client,secret)};repo.preferences.edit().putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).commit();base=repo.directShopifyFactory(repo.context).endpoint();paired=true;checkConnection();catalog=gatewayFactory(base).call(ca.canadavhs.snapmobile.core.newId(),"browse",JSONObject().put("query","").put("status","").put("cursor",""));message="Direct connection verified. No products were changed."}
    fun clearDirect()=action {repo.journal.shopifyDao().active().filter {directShopifyEndpoint(it.broker)}.forEach {repo.pauseShopify(it.id)};repo.directShopifyFactory(repo.context).clear();repo.preferences.edit().putBoolean("shopifyDirect",false).putBoolean("shopifyPaired",false).commit();paired=false;health=null;catalog=null;productDetail=null}
    fun enableOwnerWrites(enabled:Boolean) {repo.preferences.edit().putBoolean("shopifyOwnerWrites",enabled).commit();health=null;if(!enabled) viewModelScope.launch {repo.journal.shopifyDao().active().filter {directShopifyEndpoint(it.broker)}.forEach {repo.pauseShopify(it.id)}}}
    fun enableOwnerLive(enabled:Boolean) {repo.preferences.edit().putBoolean("shopifyOwnerLive",enabled).commit();health=null}
    fun prepareOwnerAction(product:String,location:String,action:String,publication:String,prepared:(String)->Unit)=action {val h=checkConnection();prepared(repo.prepareShopifyOwnerAction(product,location,action,publication,h,gatewayFactory(base)).id)}
    fun changeBase(value:String) {if(value!=base) {base=value.take(2000);health=null;paired=false;catalog=null;productDetail=null}}
    fun pair(code:String)=action {withContext(Dispatchers.IO) {ShopifyBrokerClient(repo.context,base).pair(code)};check(repo.preferences.edit().putString("shopifyBroker",base).putBoolean("shopifyDirect",false).putBoolean("shopifyPaired",true).commit());paired=true;message="Device paired. Authorize and test the Shopify connection."}
    fun authorize(open:(String)->Unit)=action {val result=ShopifyBrokerClient(repo.context,base).authorize();val url=result.optString("url");if(url.isNotBlank()) {val uri=java.net.URI(url);check(uri.scheme=="https" && uri.host?.endsWith(".myshopify.com")==true && uri.path=="/admin/oauth/authorize");open(url)} else message=result.optString("message")}
    internal suspend fun checkConnection():JSONObject {check(paired) {"Pair this device first."};return gatewayFactory(base).call(newId(),"health",JSONObject()).also {health=it;message="Store identity and scopes checked against Shopify. No write was performed."}}
    fun test()=action {checkConnection()}
    fun disconnect(revoke:Boolean)=action {
        if(repo.preferences.getBoolean("shopifyDirect",false)) {
            repo.journal.shopifyDao().active().filter {directShopifyEndpoint(it.broker)}.forEach {repo.pauseShopify(it.id)};repo.directShopifyFactory(repo.context).clear();repo.preferences.edit().putBoolean("shopifyDirect",false).putBoolean("shopifyPaired",false).commit();health=null;paired=false;catalog=null;productDetail=null;message="Direct credentials cleared locally. Shopify app installation is unchanged.";return@action
        }
        if(revoke) ShopifyBrokerClient(repo.context,base).revoke()
        repo.journal.shopifyDao().active().forEach {repo.pauseShopify(it.id)}
        withContext(Dispatchers.IO) {ShopifyDeviceIdentity(repo.context).forget()};health=null;paired=false;check(repo.preferences.edit().putBoolean("shopifyPaired",false).commit());message=if(revoke) "Device access revoked at broker." else "Device key removed locally. Revoke the old device in the broker administration tool."
    }
    fun browse(query:String,status:String,cursor:String)=action {checkConnection();val page=gatewayFactory(base).call(newId(),"browse",JSONObject().put("query",query).put("status",status).put("cursor",cursor));check(page.getJSONArray("nodes").length()<=20);catalog=page;catalogQuery=query;catalogStatus=status;catalogFetchedAt=java.time.OffsetDateTime.now().toString()}
    fun inspect(product:String,location:String)=action {check(paired);productDetail=gatewayFactory(base).call(newId(),"product",JSONObject().put("productId",product).put("locationId",location)).also {check(!it.optBoolean("missing")) {"Product no longer exists."}}}
    fun submitDrafts(ids:Set<String>,location:String,channels:Set<String>)=action {val h=checkConnection();val result=repo.submitSelectedDrafts(ids,h,base,location,channels);message="${ids.size-result.skipped.size} copies queued as drafts; ${result.skipped.size} skipped. "+result.skipped.entries.joinToString("; ") {"${it.key}: ${it.value}"}}
    fun prepareMultiOwner(product:String,location:String,channels:Set<String>,prepared:(String)->Unit)=action {val h=checkConnection();prepared(repo.prepareShopifyOwnerAction(product,location,"PUBLISH_LIVE",channels.sorted().first(),h,gatewayFactory(base),channels).id)}
    fun prepareUpload(ids:Set<String>,location:String,live:Boolean,publication:String,prepared:(String)->Unit)=action {val h=checkConnection();prepared(repo.prepareShopifyUploadReview(ids,h,base,location,live,publication).id)}
    fun prepareManagement(group:String,product:String,location:String,fields:Set<String>,prepared:(String)->Unit)=action {val h=checkConnection();prepared(repo.prepareShopifyManagement(group,product,h,base,location,fields,gatewayFactory(base)).id)}
    fun decideReview(id:String,accept:Boolean,confirmation:String,done:()->Unit)=action {message=repo.decideShopifyReview(id,accept,confirmation);done()}
    fun cancel(id:String)=action {repo.cancelShopify(id)}
    fun pause(id:String)=action {repo.pauseShopify(id)}
    fun resume(id:String)=action {repo.resumeShopify(id,base,checkConnection())}
}
@Composable fun ShopifyScreen(vm:CaptureViewModel,back:()->Unit,model:ShopifyModel=viewModel()) {
    val context=LocalContext.current;val inventory by vm.inventory.collectAsStateWithLifecycle()
    val jobs by vm.repo.journal.shopifyDao().watchJobs().collectAsStateWithLifecycle(emptyList());val items by vm.repo.journal.shopifyDao().watchItems().collectAsStateWithLifecycle(emptyList())
    var showDirect by rememberSaveable {mutableStateOf(!model.paired || vm.repo.preferences.getBoolean("shopifyDirect",false))};var showLegacy by rememberSaveable {mutableStateOf(model.paired && !vm.repo.preferences.getBoolean("shopifyDirect",false))};var ownerWrites by remember {mutableStateOf(vm.repo.preferences.getBoolean("shopifyOwnerWrites",false))};var ownerLive by remember {mutableStateOf(vm.repo.preferences.getBoolean("shopifyOwnerLive",false))};var directShop by remember {mutableStateOf("")};var directClient by remember {mutableStateOf("")};var directSecret by remember {mutableStateOf("")};var code by remember {mutableStateOf("")};var selected by rememberSaveable {mutableStateOf<Set<String>>(emptySet())}
    var location by rememberSaveable {mutableStateOf("")};var publication by rememberSaveable {mutableStateOf("")};var consent by rememberSaveable {mutableStateOf(false)}
    var reviewId by rememberSaveable {mutableStateOf<String?>(null)}
    var manage by rememberSaveable {mutableStateOf(false)}
    val reviews by remember {vm.repo.journal.shopifyDao().watchReviews()}.collectAsStateWithLifecycle(emptyList())
    DisposableEffect(Unit) {val window=(context as? android.app.Activity)?.window;val previous=(window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_SECURE !=0;window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);onDispose {if(!previous) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}}
    LaunchedEffect(model.catalog) {if(model.catalog!=null && vm.repo.preferences.getBoolean("shopifyDirect",false)) manage=true}
    if(manage) {ShopifyListingsScreen(vm,model) {manage=false};return}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Back")};Text("Shopify",style=MaterialTheme.typography.headlineLarge);Text("Drafts and durable jobs",style=MaterialTheme.typography.titleMedium);OutlinedButton(onClick={manage=true}) {Text("Listings & saved reviews")}}
        item {Text("Choose Direct Shopify Connection for your owner-operated phone, or an optional hosted broker. Inventory stays on this phone. ")}
        item {TextButton(onClick={showDirect=!showDirect}) {Text("Direct Shopify Connection")};if(showDirect) {OutlinedTextField(directShop,{directShop=it},label={Text("Store domain · myshopify.com")});OutlinedTextField(directClient,{directClient=it},label={Text("Shopify Client ID")});OutlinedTextField(directSecret,{directSecret=it},label={Text("Shopify Client Secret")},keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password,autoCorrectEnabled=false),visualTransformation=PasswordVisualTransformation());Button(enabled=directShop.isNotBlank() && directClient.isNotBlank() && directSecret.isNotBlank() && !model.busy,onClick={model.saveDirect(directShop,directClient,directSecret);directSecret=""}) {Text("Save credentials & connect directly")};TextButton(onClick=model::clearDirect) {Text("Clear direct credentials")};if(vm.repo.preferences.getBoolean("shopifyDirect",false) && model.paired) {TextButton(onClick={model.browse("","","")}) {Text("Open saved Shopify connection")};TextButton(onClick=model::test) {Text("Test saved direct connection")}}
            Row {Checkbox(ownerWrites,{ownerWrites=it;model.enableOwnerWrites(it)});Text("Enable writes to my Shopify store · deliberate draft submission authorizes uploads",Modifier.weight(1f))}
            Row {Checkbox(ownerLive,{ownerLive=it;model.enableOwnerLive(it)});Text("Enable live publication controls · separate SUBMIT approval required",Modifier.weight(1f))}
            Text("Credentials stay encrypted on this phone. Approved jobs connect directly to Shopify. No broker or computer is required.")}}
        item {TextButton(onClick={showLegacy=!showLegacy}) {Text("Optional legacy connection")}}
        if(showLegacy) {
        item {OutlinedTextField(model.base,model::changeBase,label={Text("Hosted broker HTTPS origin")},enabled=!model.busy,modifier=Modifier.fillMaxWidth(),singleLine=true)}
        item {OutlinedTextField(code,{code=it.take(200)},label={Text("One-use pairing code from broker owner")},visualTransformation=PasswordVisualTransformation(),enabled=!model.busy,modifier=Modifier.fillMaxWidth());Button(onClick={model.pair(code);code=""},enabled=!model.busy && code.isNotBlank()) {Text("Pair this device")}}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick={model.authorize {url->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}},enabled=model.paired && !model.busy) {Text("Authorize in browser")};Button(onClick=model::test,enabled=model.paired && !model.busy) {Text("Test connection")}}}
        if(model.paired) item {FlowRow {TextButton(onClick={model.disconnect(true)},enabled=!model.busy) {Text("Revoke device")};TextButton(onClick={model.disconnect(false)},enabled=!model.busy) {Text("Forget locally")}}}
        }
        if(!showLegacy && model.paired && !showDirect) item {Button(onClick=model::test,enabled=!model.busy) {Text("Test connection")}}
        if(model.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(model.error.isNotBlank()) item {Text(model.error,color=MaterialTheme.colorScheme.error)}
        if(model.message.isNotBlank()) item {Text(model.message)}
        val health=model.health
        if(health!=null) {
            item {Text("${health.getString("name")} · ${health.getString("shop")}\n${health.getString("currency")} · ${if(health.getBoolean("developmentStore")) "development store" else "owner-operated store"} · API ${health.getString("apiVersion")}",fontWeight=FontWeight.Bold);Text("Scopes: ${health.getJSONArray("scopes")}",style=MaterialTheme.typography.bodySmall)}
            val locations=health.getJSONArray("locations")
            item {Text("Inventory location");for(i in 0 until locations.length()) {val loc=locations.getJSONObject(i);if(loc.optBoolean("isActive")) FilterChip(selected=location==loc.getString("id"),onClick={location=loc.getString("id")},label={Text(loc.getString("name"))})}}
            val publications=health.getJSONArray("publications")
            if(health.optBoolean("allowPublish")) item {Text("Sales channel for separately approved publication");for(i in 0 until publications.length()) {val p=publications.getJSONObject(i);FilterChip(selected=publication==p.getString("id"),onClick={publication=p.getString("id")},label={Text(p.getString("name"))})}}
        }
        item {Text("Select inventory",style=MaterialTheme.typography.titleLarge);Text("New uploads default to DRAFT. Existing linked products are protected. One item represents one physical copy with stock 1.")}
        items(inventory.filter {!it.archived},key={it.groupId}) {item->OutlinedCard(Modifier.fillMaxWidth()) {Row(Modifier.padding(12.dp)) {Checkbox(checked=item.groupId in selected,onCheckedChange={selected=if(it) selected+item.groupId else selected-item.groupId});Column(Modifier.weight(1f)) {Text(item.title.ifBlank {"Untitled"},fontWeight=FontWeight.Bold);Text(item.itemId,style=MaterialTheme.typography.bodySmall);Text("CAD ${InventoryRules.priceText(item.priceCents?.let {InventoryRules.discounted(it,item.discountBasisPoints)})} · ${item.weightGrams} g · stock ${item.quantity}");Text("${if(item.ready) "Ready" else "Review required"} · ${item.shopifyState}")}}}}
        item {Text("Create Shopify Drafts authorizes sending selected listing data and photos. Invalid records are skipped with reasons.");Button(enabled=selected.isNotEmpty() && model.health!=null && location.isNotBlank() && !model.busy,onClick={model.submitDrafts(selected,location,vm.repo.savedPublicationDefaults(model.health!!))}) {Text("Create Shopify Drafts (${selected.size})")};TextButton(enabled=selected.isNotEmpty() && model.health!=null && location.isNotBlank() && !model.busy,onClick={model.prepareUpload(selected,location,false,publication) {reviewId=it}}) {Text("Optional listing review")}}
        if(model.health?.optBoolean("allowPublish")==true) item {OutlinedButton(enabled=selected.isNotEmpty() && consent && publication.isNotBlank() && !model.busy,onClick={model.prepareUpload(selected,location,true,publication) {reviewId=it}}) {Text("Review live publication")}}
        item {Text("Publishing jobs",style=MaterialTheme.typography.titleLarge);Text("A paused or failed job can leave a remote draft. Retrying reconciles saved identities. Android work completion alone does not prove Shopify completion.")}
        items(jobs,key={it.id}) {job->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text("${job.kind} · ${job.state}",fontWeight=FontWeight.Bold);Text("${job.shop}\n${job.id}",style=MaterialTheme.typography.bodySmall);TextButton(onClick={vm.action {vm.repo.trashLocal("DRAFT",job.id);vm.message="Local draft record moved to Trash. Shopify is unchanged."}}) {Text("Delete local job")};if(job.error.isNotBlank()) Text(job.error,color=MaterialTheme.colorScheme.error)
            items.filter {it.jobId==job.id}.forEach {entry->TextButton(onClick={vm.action {vm.repo.trashLocal("DRAFT_ITEM",entry.id);vm.message="Draft removed locally. Shopify is unchanged."}}) {Text("Delete local draft · ${entry.itemId}")};Text("${entry.itemId}: ${entry.state} · ${entry.step}");if(entry.error.isNotBlank()) Text(entry.error,color=MaterialTheme.colorScheme.error);val remote=JSONObject(entry.remoteJson);if(remote.has("productId")) Text(remote.getString("productId"),style=MaterialTheme.typography.bodySmall)}
            if(job.state !in listOf("SUCCESS","CANCELLED")) FlowRow {TextButton(onClick={model.pause(job.id)},enabled=!model.busy) {Text("Pause")};TextButton(onClick={model.resume(job.id)},enabled=model.paired && !model.busy) {Text("Reconcile / resume")};TextButton(onClick={model.cancel(job.id)},enabled=!model.busy) {Text("Cancel remaining")}}
        }}}
    }
    reviews.firstOrNull {it.id==reviewId}?.let {ShopifyReviewDialog(it,model) {reviewId=null;selected=emptySet()}}
}
