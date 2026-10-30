package ca.canadavhs.snapmobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.json.*

@Composable fun TasksScreen(vm:CaptureViewModel,back:()->Unit,inventory:(String)->Unit,shopify:()->Unit,initialTaskId:String?=null,batch:(String)->Unit={}) {
    BackHandler(onBack=back)
    val rows by remember {vm.repo.journal.agentTaskDao().watch()}.collectAsStateWithLifecycle(emptyList())
    val items by vm.inventory.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val recognition by vm.recognition.collectAsStateWithLifecycle()
    val archives by vm.archives.collectAsStateWithLifecycle()
    val shopifyJobs by remember {vm.repo.journal.shopifyDao().watchJobs()}.collectAsStateWithLifecycle(emptyList())
    val shopifyItems by remember {vm.repo.journal.shopifyDao().watchItems()}.collectAsStateWithLifecycle(emptyList())
    var statuses by remember {mutableStateOf<Map<String,AgentTaskStatus>>(emptyMap())}
    var create by rememberSaveable {mutableStateOf(false)}
    var selected by rememberSaveable {mutableStateOf(listOf<String>())}
    var query by rememberSaveable {mutableStateOf("")}
    var cancel by remember {mutableStateOf<AgentTaskRow?>(null)}
    var working by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun run(block:suspend()->Unit) {if(working) return;working=true;error="";scope.launch {try {block()} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Task action failed."} finally {working=false}}}
    LaunchedEffect(rows,items,photos,recognition,shopifyJobs,shopifyItems,archives) {try {statuses=withContext(Dispatchers.IO) {rows.associate {it.id to vm.repo.agentTaskStatus(it)}}} catch(e:CancellationException) {throw e} catch(e:Exception) {error=e.message ?: "Task status needs review."}}
    val listState=rememberLazyListState()
    LaunchedEffect(initialTaskId,rows) {val index=rows.indexOfFirst {it.id==initialTaskId};if(index>=0) listState.scrollToItem(index+3+(if(error.isNotBlank()) 1 else 0))}
    LazyColumn(state=listState,modifier=Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Back")};PageTitle("Tasks","Real job progress · inventory stays on this device")}
        item {Button(onClick={create=true;selected=emptyList()},enabled=!working) {Text("New inventory review task")};Text("AI proposals are reviewed in their conversation. Recognition needs printed-title confirmation. Shopify completion requires verified server results.",style=MaterialTheme.typography.bodySmall)}
        item {OperatorJobs(vm,batch,shopify)}
        if(error.isNotBlank()) item {Text(error,color=MaterialTheme.colorScheme.error)}
        if(rows.isEmpty()) item {EmptyState("No tasks yet","Select copies for an offline review task, or approve a recognition, pricing or Shopify proposal in the AI workspace.")}
        items(rows,key={it.id}) {row->val status=statuses[row.id]
            Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(row.title,style=MaterialTheme.typography.titleMedium)
                Text("${status?.state ?: row.state} · ${status?.done ?: 0}/${status?.total ?: JSONArray(row.groupIdsJson).length()}")
                if(status!=null) {LinearProgressIndicator(progress={if(status.total==0) 0f else status.done.toFloat()/status.total},modifier=Modifier.fillMaxWidth());Text(status.detail)}
                if(row.error.isNotBlank()) Text(row.error,color=MaterialTheme.colorScheme.error)
                val groups=remember(row.groupIdsJson) {taskGroups(row)}
                groups.forEach {id->val item=items.firstOrNull {it.groupId==id};TextButton(onClick={inventory(id)}) {Text("Review ${item?.title?.ifBlank {"Untitled copy"} ?: "missing copy"} · ${item?.itemId ?: id}")}}
                val jobs=status?.jobs ?: JSONArray()
                for(i in 0 until jobs.length()) {val job=jobs.getJSONObject(i);Text("${job.optString("state")} ${job.optString("step")}",style=MaterialTheme.typography.bodySmall);if(job.optString("error").isNotBlank()) Text(job.getString("error"),color=MaterialTheme.colorScheme.error)}
                val outcomes=JSONObject(row.resultJson).optJSONArray("items") ?: JSONArray()
                for(i in 0 until outcomes.length()) {val o=outcomes.getJSONObject(i);Text("${o.optString("itemId")} · revision ${o.optLong("revision")} · ready ${o.optBoolean("ready")}${if(o.has("recognitionState")) " · recognition ${o.optString("recognitionState")}" else ""}${if(o.has("listingState")) " · local listing ${o.optString("listingState")}" else ""}",style=MaterialTheme.typography.bodySmall)}
                val prices=JSONObject(row.resultJson).optJSONArray("prices") ?: JSONArray()
                for(i in 0 until prices.length()) {val p=prices.getJSONObject(i);Text("${p.getString("itemId")} · CAD ${ca.canadavhs.snapmobile.core.InventoryRules.priceText(p.getLong("priceCents"))}")}
                if(row.kind=="BATCH_OPERATION") TextButton(onClick={batch(JSONObject(row.resultJson).getString("sessionId"))}) {Text("Open batch")}
                if(row.kind in listOf("SHOPIFY_DRAFT","SHOPIFY_MANAGEMENT")) TextButton(onClick=shopify) {Text("Open Shopify jobs & server verification")}
                if(row.state=="SCHEDULE_PENDING") OutlinedButton(enabled=!working,onClick={run {vm.repo.scheduleAgentTask(row.id)}}) {Text("Retry scheduling existing jobs")}
                if(row.kind=="REVIEW_BATCH" && row.state=="OPEN") OutlinedButton(enabled=!working && status!=null && status.done==status.total,onClick={run {vm.repo.completeReviewTask(row.id)}}) {Text("Mark reviewed task complete")}
                if(row.state=="PAUSED_REVIEW") Text("Restored task is paused. Review inventory and request a fresh proposal; external requests will not restart automatically.")
                if(row.state !in listOf("CANCELLED","COMPLETE","PAUSED_REVIEW") && status?.state!="COMPLETE") TextButton(enabled=!working,onClick={cancel=row}) {Text("Cancel remaining work")}
            }}
        }
    }
    if(create) androidx.compose.ui.window.Dialog(onDismissRequest={if(!working) create=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
      Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),shape=RoundedCornerShape(24.dp),tonalElevation=6.dp) {
       Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Select physical copies",style=MaterialTheme.typography.headlineSmall)
        Text("${selected.size}/50 selected · offline review only")
        OutlinedTextField(query,{query=it.take(200)},label={Text("Search title or SKU")},modifier=Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {items(items.filter {!it.archived && (it.title.contains(query,true) || it.itemId.contains(query,true))},key={it.groupId}) {item->Row {Checkbox(item.groupId in selected,{checked->selected=if(checked) (selected+item.groupId).take(50) else selected-item.groupId},enabled=item.groupId in selected || selected.size<50);Text("${item.title.ifBlank {"Untitled copy"}}\n${item.itemId}",Modifier.padding(top=8.dp))}}}
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            TextButton(enabled=!working,onClick={create=false}) {Text("Cancel")}
            TextButton(enabled=selected.isNotEmpty() && !working,onClick={run {vm.repo.createLocalReviewTask(selected);create=false}}) {Text("Create review task")}
        }
       }
      }
    }

    cancel?.let {row->AlertDialog(onDismissRequest={if(!working) cancel=null},title={Text("Cancel remaining work?")},text={Text("Completed recognition results, prices, photographs and Shopify drafts remain available. An already-sent provider request can still consume usage. This does not delete remote products.")},confirmButton={TextButton(enabled=!working,onClick={run {vm.repo.cancelAgentTask(row.id);cancel=null}}) {Text("Cancel remaining work")}},dismissButton={TextButton(enabled=!working,onClick={cancel=null}) {Text("Keep task")}})}
}
