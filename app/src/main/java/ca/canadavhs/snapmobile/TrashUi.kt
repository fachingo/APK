package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import java.time.Instant

@Composable fun TrashScreen(vm:CaptureViewModel,back:()->Unit) {
    val rows by remember {vm.repo.journal.trashDao().watch()}.collectAsStateWithLifecycle(emptyList())
    BackHandler(onBack=back)
    LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("← Settings")};PageTitle("Trash","Recover deleted local records for seven days")}
        item {Text("Deletion only affects SnapIMS. Shopify products stay unchanged. Recovery does not restart uploads or recognition. Batches recover as finished batches.")}
        val visible=rows.filter {it.parentId.isBlank() && it.expiresAt>System.currentTimeMillis()}
        if(visible.isEmpty()) item {Text("Trash is empty")}
        items(visible,key={it.id}) {entry->OutlinedCard {Column(Modifier.fillMaxWidth().padding(16.dp)) {Text(entry.label,style=MaterialTheme.typography.titleMedium);Text("${entry.kind.lowercase()} · recover until ${Instant.ofEpochMilli(entry.expiresAt)}");TextButton(onClick={vm.action {vm.repo.restoreTrash(entry.id);vm.message="Recovered. Background operations remain stopped."}}) {Text("Recover")}}}}
    }
}
