package ca.canadavhs.snapmobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@Composable fun BackupScreen(vm: CaptureViewModel, back: () -> Unit) {
    val context=LocalContext.current; val jobs by vm.transferJobs.collectAsStateWithLifecycle()
    var mode by remember {mutableStateOf<String?>(null)}; var passphrase by remember {mutableStateOf("")}; var repeated by remember {mutableStateOf("")}
    var pendingUri by remember {mutableStateOf<Uri?>(null)}; var requestedKind by remember {mutableStateOf("IMPORT")}
    var saveFile by remember {mutableStateOf<File?>(null)}
    DisposableEffect(mode) {
        val window=(context as? Activity)?.window
        val wasSecure=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if(mode!=null) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if(!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file=saveFile
        if(uri!=null && file!=null) vm.transfer {
            withContext(Dispatchers.IO) {
                val expected=hashFile(file)
                context.contentResolver.openOutputStream(uri,"wt")?.use { out -> file.inputStream().use { copyCancellable(it,out,file.length()) } } ?: error("Cannot write the selected destination.")
                val hash=MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { source -> val bytes=ByteArray(262144); while(true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val n=source.read(bytes); if(n<0) break; hash.update(bytes,0,n) } } ?: error("Cannot verify the saved file.")
                check(hash.digest().joinToString("") { "%02x".format(it) }==expected) { "Saved file did not verify. Internal backup and originals retained." }
            }; vm.message="File saved and readback checksum verified."
        }
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            pendingUri=uri
            if(requestedKind=="RESTORE") {mode="RESTORE";passphrase="";repeated=""}
            else vm.transfer {
                val source=File(context.cacheDir,"selected-inventory-${java.util.UUID.randomUUID()}.zip")
                try {
                    withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { copyCancellable(input,it,MAX_PACKAGE_BYTES) } } ?: error("Cannot read the selected package.") }
                    val count=vm.repo.importInventoryPackage(source); vm.message="Imported $count new physical items. Existing matching records were reused."
                } finally { source.delete() }
            }
        }
    }
    fun share(file: File) {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type=if(file.extension=="csv") "text/csv" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM,uri); clipData=android.content.ClipData.newRawUri("SnapIMS inventory",uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },"Share SnapIMS file"))
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { TextButton(enabled=!vm.maintenance,onClick=back) {Text("← Settings")}; PageTitle("Backup & import","Your collection is independent of a desktop") }
        item { Text("A protected backup includes inventory, audit history, capture sessions, original photos and archive records. API keys are excluded. Keep the passphrase somewhere safe; it cannot be recovered.") }
        item { Button(enabled=!vm.maintenance && !vm.pendingCapture,onClick={mode="BACKUP";passphrase="";repeated=""},modifier=Modifier.fillMaxWidth()) {Text("Create protected backup")} }
        item { OutlinedButton(enabled=!vm.maintenance,onClick={requestedKind="RESTORE";open.launch(arrayOf("application/octet-stream","application/zip","*/*"))},modifier=Modifier.fillMaxWidth()) {Text("Restore protected backup")} }
        if(mode!=null) item { Surface(shape=RoundedCornerShape(18.dp),tonalElevation=3.dp) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(mode=="BACKUP") "Protect this backup" else "Unlock selected backup",fontWeight=FontWeight.SemiBold)
            OutlinedTextField(passphrase,{passphrase=it.take(256)},modifier=Modifier.fillMaxWidth(),label={Text("Passphrase · 12+ characters")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            if(mode=="BACKUP") OutlinedTextField(repeated,{repeated=it.take(256)},modifier=Modifier.fillMaxWidth(),label={Text("Repeat passphrase")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            Text("Restore merges new records. Different existing records/IDs are rejected; your current database and originals are never replaced.",style=MaterialTheme.typography.bodySmall)
            Row { Button(enabled=passphrase.length>=12 && (mode!="BACKUP" || passphrase==repeated) && !vm.maintenance,onClick={
                val selectedMode=mode; val secret=passphrase.toCharArray(); val uri=pendingUri
                passphrase="";repeated="";mode=null
                vm.transfer {
                    if(selectedMode=="BACKUP") { val file=vm.repo.createInventoryBackup(secret); vm.message="Protected backup created. Save ${file.name} to Files or share it below." }
                    else {
                        val source=File(context.cacheDir,"selected-backup-${java.util.UUID.randomUUID()}.snapbak")
                        try {
                            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri ?: error("Select a backup first."))?.use { input -> source.outputStream().use { copyCancellable(input,it,MAX_PACKAGE_BYTES) } } ?: error("Cannot read backup.") }
                            val count=vm.repo.restoreInventoryBackup(source,secret); vm.message="Restored $count new physical items. Existing data retained."
                        } finally { secret.fill('\u0000');source.delete() }
                    }
                }
            }) {Text(if(mode=="BACKUP") "Create backup" else "Restore inventory")}; TextButton(onClick={passphrase="";repeated="";mode=null}) {Text("Cancel")} }
        } } }
        item { HorizontalDivider(); Text("Portable exports",fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
        item { Text("Portable ZIPs include photos and inventory without encryption for interchange. CSV uses SnapIMS Item IDs and column names. Use protected backup for confidential storage.",style=MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled=!vm.maintenance,onClick={vm.transfer { val file=vm.repo.createPortableInventoryExport();vm.message="Photo-inclusive export created: ${file.name}" }},modifier=Modifier.weight(1f)) {Text("Photo ZIP")}
            OutlinedButton(enabled=!vm.maintenance,onClick={vm.transfer {vm.repo.exportInventoryCsv();vm.message="Inventory CSV created."}},modifier=Modifier.weight(1f)) {Text("Inventory CSV")}
        } }
        item { OutlinedButton(enabled=!vm.maintenance,onClick={requestedKind="IMPORT";open.launch(arrayOf("application/zip","application/octet-stream","*/*"))},modifier=Modifier.fillMaxWidth()) {Text("Import inventory / capture ZIP")} }
        item { Text("Supports native SnapIMS capture ZIPs, this app's portable inventory packages, and identity-preserving desktop exports from the included read-only export utility.",style=MaterialTheme.typography.bodySmall) }
        if(vm.maintenance) item { Column { LinearProgressIndicator(modifier=Modifier.fillMaxWidth()); Text("Working on this phone. Keep the app open until completion."); TextButton(onClick={vm.cancelTransfer()}) {Text("Cancel current transfer")} } }
        item { Text("Recent operations",fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
        items(jobs,key={it.id}) { job -> Surface(shape=RoundedCornerShape(18.dp),tonalElevation=2.dp) { Column(Modifier.padding(16.dp)) {
            Text("${job.kind.lowercase().replaceFirstChar { it.uppercase() }} · ${job.state.lowercase().replace('_',' ')}",fontWeight=FontWeight.SemiBold)
            if(job.total>0) Text("${job.completed} / ${job.total}",style=MaterialTheme.typography.bodySmall)
            if(job.error.isNotBlank()) Text(job.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            val file=File(vm.repo.archiveDir,job.filename)
            if(job.state=="SUCCEEDED" && file.isFile) {
                Text(job.filename,style=MaterialTheme.typography.labelSmall)
                Row {TextButton(enabled=!vm.maintenance,onClick={saveFile=file;save.launch(file.name)}) {Text("Save to Files")};TextButton(enabled=!vm.maintenance,onClick={share(file)}) {Text("Share")}}
            }
        } } }
    }
}
