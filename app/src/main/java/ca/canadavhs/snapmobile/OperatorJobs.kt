package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun OperatorJobs(vm:CaptureViewModel,batch:(String)->Unit,shopify:()->Unit) {
 val local by vm.repo.journal.productionDao().watchJobs().collectAsStateWithLifecycle(emptyList());val remote by vm.repo.journal.shopifyDao().watchJobs().collectAsStateWithLifecycle(emptyList());val recognition by vm.recognition.collectAsStateWithLifecycle();val groups by vm.groups.collectAsStateWithLifecycle();val archives by vm.archives.collectAsStateWithLifecycle()
 Disclosure("All background jobs","${local.count {it.state in setOf("QUEUED","RUNNING")}} local workflows · ${remote.count {it.state in setOf("QUEUED","RUNNING","WAITING_REMOTE")}} Shopify jobs",initial=true) {
  local.take(12).forEach {job->Column(Modifier.fillMaxWidth().padding(vertical=6.dp)) {Text(operatorState(job.kind),style=MaterialTheme.typography.titleSmall);StatusLabel(job.state);if(job.error.isNotBlank()) Text(job.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);Row {TextButton(onClick={vm.action {val row=vm.repo.journal.productionDao().items(job.id).firstOrNull();row?.let {batch(vm.repo.dao.group(it.groupId).sessionId)}}}) {Text("Open batch & results")};if(job.state in setOf("QUEUED","RUNNING")) TextButton(onClick={vm.action {vm.repo.cancelProduction(job.id)}}) {Text("Cancel workflow")};if(job.state=="PAUSED_REVIEW" && !job.kind.startsWith("AI_")) TextButton(onClick={vm.action {vm.repo.resumeLocalProduction(job.id)}}) {Text("Resume local job")}}}}
  remote.take(12).forEach {job->Column {Text("Shopify · ${operatorState(job.kind)}",style=MaterialTheme.typography.titleSmall);StatusLabel(job.state);if(job.error.isNotBlank()) Text(job.error,color=MaterialTheme.colorScheme.error);Row {TextButton(onClick=shopify) {Text("Open Shopify recovery")};if(job.state in setOf("QUEUED","RUNNING","WAITING_REMOTE")) TextButton(onClick={vm.action {vm.repo.pauseShopify(job.id)}}) {Text("Pause job")}}}}
  recognition.filter {it.state in setOf("QUEUED","RUNNING","ERROR","STALE")}.take(8).forEach {job->Text("Recognition · ${operatorState(job.state)}",style=MaterialTheme.typography.titleSmall);if(job.error.isNotBlank()) Text(job.error,style=MaterialTheme.typography.bodySmall);TextButton(onClick={groups.firstOrNull {it.id==job.groupId}?.let {batch(it.sessionId)}}) {Text("Review recognition in batch")}}
  archives.filter {it.state!="READY"}.take(5).forEach {job->Text("Archive · ${operatorState(job.state)}");if(job.error.isNotBlank()) Text(job.error);if(job.state=="ERROR") TextButton(onClick={vm.repo.enqueueArchive(job.id)}) {Text("Retry archive")}}
  if(local.isEmpty() && remote.isEmpty() && recognition.none {it.state in setOf("QUEUED","RUNNING","ERROR")}) Text("No ongoing jobs. Background AI stays idle unless you explicitly start a task.",style=MaterialTheme.typography.bodySmall)
 }
}
