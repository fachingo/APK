package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun OperatorDashboard(vm:CaptureViewModel,capture:()->Unit,inventory:()->Unit,batch:(String)->Unit,market:()->Unit,tasks:()->Unit,backups:()->Unit) {
 val items by vm.inventory.collectAsStateWithLifecycle();val sessions by vm.sessions.collectAsStateWithLifecycle();val photos by vm.photos.collectAsStateWithLifecycle()
 val jobs by vm.repo.journal.productionDao().watchJobs().collectAsStateWithLifecycle(emptyList());val shopJobs by vm.repo.journal.shopifyDao().watchJobs().collectAsStateWithLifecycle(emptyList());val recognition by vm.recognition.collectAsStateWithLifecycle()
 val transfers by vm.transferJobs.collectAsStateWithLifecycle()
 val transferFailures=transfers.filter {it.state in setOf("ERROR","INTERRUPTED")};val recognitionFailures=recognition.filter {it.state in setOf("ERROR","STALE")}
 val copies=items.filter {!it.archived};val current=sessions.firstOrNull {!it.finished} ?: sessions.firstOrNull();val running=jobs.count {it.state in setOf("QUEUED","RUNNING")}+shopJobs.count {it.state in setOf("QUEUED","RUNNING","WAITING_REMOTE")}+recognition.count {it.state in setOf("QUEUED","RUNNING")}
 val draftReady by produceState(0,copies,photos) {value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {copies.count {item->vm.repo.shopifyProblems(item.copy(ready=true),photos.filter {it.groupId==item.groupId && !it.deleted}).isEmpty()}}}
 val failures=jobs.filter {it.error.isNotBlank()};val shopFailures=shopJobs.filter {it.error.isNotBlank()}
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {PageTitle("Dashboard","Your inventory, ready for the next step")}
  item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {DashboardMetric("Physical copies",copies.size,Modifier.weight(1f),inventory);DashboardMetric("Unpriced",copies.count {it.priceCents==null},Modifier.weight(1f),{current?.let {batch(it.id)} ?: inventory()});DashboardMetric("Ready for listing",draftReady,Modifier.weight(1f),{current?.let {batch(it.id)} ?: inventory()})}}
  item {if(current!=null) ElevatedCard(onClick={batch(current.id)},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(if(current.finished) "LATEST BATCH" else "CURRENT BATCH",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);Text(current.name,style=MaterialTheme.typography.titleLarge);Text("${if(current.finished) "Finished capture" else "Capture in progress"} · ${current.location.ifBlank {"No shelf assigned"}}",style=MaterialTheme.typography.bodyMedium);Text("Open active batch →",color=MaterialTheme.colorScheme.primary)}} else OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp)) {Text("Start your first batch",style=MaterialTheme.typography.titleLarge);Text("Photograph tapes, identify them and price them from your local sold history.");Button(onClick=capture) {Text("Photograph a collection")}}}}
  item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick=capture,modifier=Modifier.weight(1f)) {Text(if(sessions.any {!it.finished}) "Continue capturing" else "Photograph a collection")};OutlinedButton(onClick=inventory,modifier=Modifier.weight(1f)) {Text("Open inventory")}}}
  item {Text("Needs attention",style=MaterialTheme.typography.titleMedium);ListItem(headlineContent={Text("Needs review")},supportingContent={Text("Missing prices, titles or operator review")},trailingContent={Text(copies.count {it.priceCents==null || it.title.isBlank() || it.review}.toString())},modifier=Modifier.fillMaxWidth());TextButton(onClick={current?.let {batch(it.id)} ?: inventory()}) {Text("Review batches")}}
  item {Text("Work on this device",style=MaterialTheme.typography.titleMedium);OutlinedCard(onClick=tasks,modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {Text("$running ongoing jobs",style=MaterialTheme.typography.titleMedium);Text("${failures.size+shopFailures.size+recognitionFailures.size+transferFailures.size} jobs need attention · Open task history",style=MaterialTheme.typography.bodySmall)}}}
  items(failures.take(2),key={it.id}) {job->Text("${operatorState(job.kind)}: ${job.error}",color=MaterialTheme.colorScheme.error);TextButton(onClick={current?.let {batch(it.id)} ?: tasks()}) {Text("Review workflow problem")}}
  items(shopFailures.take(2),key={it.id}) {job->Text("Shopify: ${job.error}",color=MaterialTheme.colorScheme.error);TextButton(onClick=tasks) {Text("Review job recovery")}}
  items(transferFailures.take(1),key={"transfer-"+it.id}) {job->Text("Backup / import: ${job.error}",color=MaterialTheme.colorScheme.error);TextButton(onClick=backups) {Text("Review transfer history")}}
  item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick=market,modifier=Modifier.weight(1f)) {Text("Market & pricing")};OutlinedButton(onClick=backups,modifier=Modifier.weight(1f)) {Text("Backup & import")}};Text("${photos.count {it.state=="READY" && !it.deleted}} original photos · ${sessions.count {it.finished}} finished batches · ${"%.1f".format(photos.sumOf {it.bytes}/1_000_000.0)} MB · Inventory and local pricing work offline",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }
}
@Composable private fun DashboardMetric(label:String,value:Int,modifier:Modifier,open:()->Unit) {Surface(onClick=open,modifier=modifier,shape=MaterialTheme.shapes.medium,tonalElevation=2.dp) {Column(Modifier.padding(12.dp)) {Text(value.toString(),style=MaterialTheme.typography.headlineSmall);Text(label,style=MaterialTheme.typography.labelMedium)}}}
