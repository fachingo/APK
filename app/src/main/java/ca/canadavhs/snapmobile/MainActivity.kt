package ca.canadavhs.snapmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private val Lime = Color(0xFFC4EC7B)
class MainActivity : ComponentActivity() {
    var volumeShutter: (() -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { MobileApp(this) } }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val repo = (application as CaptureApplication).repository
        if (repo.preferences.getBoolean("volume", true) && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP) && volumeShutter != null) {
            if (event.repeatCount == 0) volumeShutter?.invoke(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
@Composable
fun MobileApp(activity: MainActivity, vm: CaptureViewModel = viewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle(); val groups by vm.groups.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle(); val archives by vm.archives.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val destinations = listOf("Home" to Icons.Outlined.Home, "Capture" to Icons.Outlined.CameraAlt, "Inventory" to Icons.Outlined.Inventory2, "Batches" to Icons.Outlined.PhotoLibrary, "Settings" to Icons.Outlined.Tune)
    val destinationRoutes = listOf(4,0,5,1,3)
    var screen by rememberSaveable { mutableIntStateOf(4) }
    var light by remember { mutableStateOf(vm.repo.preferences.getBoolean("light", false)) }
    var startDialog by remember { mutableStateOf(false) }
    var finishDialog by remember { mutableStateOf(false) }
    val active = sessions.firstOrNull { !it.finished }
    val current = groups.firstOrNull { it.sessionId == active?.id && !it.closed }
    val currentPhotos = photos.filter { it.groupId == current?.id && !it.deleted }.sortedBy { it.position }
    val engine = remember { CameraEngine(context) }
    val colors = if (light) lightColorScheme(primary = Color(0xFF47651E), background = Color(0xFFF5F6F2), surface = Color.White) else darkColorScheme(primary = Lime, background = Color(0xFF101416), surface = Color(0xFF1A2023), surfaceVariant = Color(0xFF283034))
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { if (vm.message.isNotEmpty()) { snackbar.showSnackbar(vm.message, duration = SnackbarDuration.Long); vm.message = "" } }
    val view = LocalView.current
    LaunchedEffect(vm.shutterSignal) { if (vm.shutterSignal > 0 && vm.repo.preferences.getBoolean("haptic", true)) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
    LaunchedEffect(vm.shutterSignal) { if (vm.shutterSignal > 0 && vm.repo.preferences.getBoolean("tone", false)) { val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 35); tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80); kotlinx.coroutines.delay(120); tone.release() } }
    DisposableEffect(screen, active, vm.busy) { activity.volumeShutter = if (screen == 0 && active != null && !vm.busy) ({ vm.shoot(engine) }) else null; onDispose { activity.volumeShutter = null } }
    val share: (ArchiveRow) -> Unit = { a ->
        vm.action {
            val file = File(vm.repo.archiveDir, a.filename)
            withContext(Dispatchers.IO) { check(file.length() == a.bytes && hashFile(file) == a.sha256) { "Archive integrity check failed. Retry export before sharing." } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); clipData = android.content.ClipData.newRawUri("SnapIMS archive", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share SnapIMS archive"))
        }
    }
    var saveArchive by remember { mutableStateOf<ArchiveRow?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val archive = saveArchive
        if (uri != null && archive != null) vm.action {
            withContext(Dispatchers.IO) {
                val file = File(vm.repo.archiveDir, archive.filename)
                check(hashFile(file) == archive.sha256) { "Archive checksum mismatch." }
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Cannot write to selected destination.")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input -> val buffer = ByteArray(262144); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } } ?: error("Cannot verify exported file.")
                check(digest.digest().joinToString("") { "%02x".format(it) } == archive.sha256) { "Export verification failed. Internal archive and originals are safe; choose another destination." }
            }; vm.message = "Export saved and checksum verified. Originals retained."
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                destinations.forEachIndexed { i, entry ->
                    NavigationBarItem(selected = screen == destinationRoutes[i] || (destinationRoutes[i] == 3 && screen in listOf(2,6,7,8,9)), onClick = { if (!vm.busy && !vm.maintenance) screen = destinationRoutes[i] }, enabled = !vm.busy && !vm.maintenance, icon = { Icon(entry.second, entry.first) }, label = { Text(entry.first) })
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail {
                    destinations.forEachIndexed { i, entry -> NavigationRailItem(selected = screen == destinationRoutes[i] || (destinationRoutes[i] == 3 && screen in listOf(2,6,7,8,9)), enabled = !vm.busy && !vm.maintenance, onClick = { if (!vm.busy && !vm.maintenance) screen = destinationRoutes[i] }, icon = { Icon(entry.second, entry.first) }, label = { Text(entry.first, fontSize = 10.sp) }) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                when (screen) {
                    0 -> CaptureScreen(vm, engine, active, current, currentPhotos, onStart = { startDialog = true }, onFinish = { if (!vm.pendingCapture) finishDialog = true }, onReview = { if (!vm.busy) screen = 1 })
                    1 -> BatchesScreen(vm, sessions, groups, photos, archives, onStart = { startDialog = true })
                    2 -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                        PageTitle("Transfers", "Portable archives · originals stay on this device")
                        Text("Save verified capture ZIPs to Files or share them. Your inventory remains usable on this phone. Open Settings → Backup & import for protected backups and inventory packages.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(archives.reversed(), key = { it.id }) { a ->
                            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) { Column(Modifier.padding(16.dp)) {
                                Text(a.filename, fontWeight = FontWeight.SemiBold); Text("${a.state.lowercase().replaceFirstChar { it.uppercase() }} · ${"%.1f".format(a.bytes / 1_000_000.0)} MB")
                                if (a.bytes > a.targetBytes) Text("Above archive target · whole products kept together", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                if (a.error.isNotEmpty()) Text(a.error, color = MaterialTheme.colorScheme.error)
                                if (a.state == "READY") { Row { TextButton(onClick = { share(a) }) { Text("Share") }; TextButton(onClick = { saveArchive = a; save.launch(a.filename) }) { Text("Save to Files") } }; Text("SHA-256 ${a.sha256.take(16)}…", fontSize = 11.sp) }
                                else if (a.state == "ERROR") TextButton(onClick = { vm.repo.enqueueArchive(a.id) }) { Text("Retry archive") }
                            } }
                        }; if (archives.isEmpty()) item { EmptyState("No archives yet", "Next closes a product. Finish Batch or Finalize Part packages complete groups.") } }
                    }
                    3 -> SettingsScreen(vm, light, onBackups = { screen = 6 }, onExports = { screen = 2 }, onWorkspace={screen=7},onPricing={screen=8},onListings={screen=9}) { light = it }
                    4 -> InventoryHome(vm, capture = { screen = 0 }, inventory = { screen = 5 }, batches = { screen = 1 }, backups = { screen = 6 })
                    5 -> InventoryScreen(vm, reviewPhotos = { screen = 1 })
                    6 -> BackupScreen(vm) { screen = 3 }
                    7 -> AiWorkspaceScreen(vm,back={screen=3})
                    8 -> PricingScreen(vm) {screen=3}
                    9 -> ListingPlansScreen(vm) {screen=3}
                }
                if (!vm.initialized) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(contentAlignment = Alignment.Center) { Text("Recovering your capture journal…") } }
            }
        }
        }
        if (startDialog) StartBatchDialog(vm) { startDialog = false }
        if (finishDialog) AlertDialog(onDismissRequest = { finishDialog = false }, title = { Text("Finish this batch?") }, text = { Text("The open product will be finalized, including incomplete front/back sets. Archives will be verified in the background. Originals stay on this device. Inventory details and photo corrections remain available in Inventory.") }, confirmButton = { TextButton(enabled = !vm.pendingCapture, onClick = { if (!vm.pendingCapture) { finishDialog = false; vm.action { vm.repo.finish() } } }) { Text("Finish batch") } }, dismissButton = { TextButton(onClick = { finishDialog = false }) { Text("Keep capturing") } })
    }
}
@Composable fun PageTitle(title: String, subtitle: String) { Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(20.dp)) }
@Composable fun EmptyState(title: String, text: String) { Column(Modifier.padding(vertical = 36.dp)) { Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun StartBatchDialog(vm: CaptureViewModel, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("VHS ${LocalDate.now()}") }; var location by remember { mutableStateOf(vm.repo.preferences.getString("defaultLocation", "") ?: "") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("A new capture session") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("One tape at a time. Capture, then Next."); Spacer(Modifier.height(16.dp)); OutlinedTextField(name, { name = it.take(80) }, label = { Text("Batch name") }, singleLine = true); Spacer(Modifier.height(12.dp)); OutlinedTextField(location, { location = it.uppercase().take(3) }, label = { Text("Location · optional") }, supportingText = { Text("A1–J10 or Q1") }, singleLine = true, isError = !validLocation(location)) } }, confirmButton = { TextButton(enabled = name.isNotBlank() && validLocation(location), onClick = { vm.action { vm.repo.start(name, location); dismiss() } }) { Text("Start capturing") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun CaptureScreen(vm: CaptureViewModel, engine: CameraEngine, session: SessionRow?, group: GroupRow?, photos: List<PhotoRow>, onStart: () -> Unit, onFinish: () -> Unit, onReview: () -> Unit) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var front by rememberSaveable { mutableStateOf(false) }; var hdr by rememberSaveable { mutableStateOf(vm.repo.preferences.getBoolean("hdr", false)) }
    var torch by rememberSaveable { mutableStateOf(false) }; var exposure by rememberSaveable { mutableFloatStateOf(0f) }; var flash by rememberSaveable { mutableIntStateOf(vm.flash) }
    var photoSize by remember { mutableStateOf(PhotoResolution.read(vm.repo.preferences)) }
    var sizeMenu by remember { mutableStateOf(false) }
    val fast = vm.repo.preferences.getBoolean("fastCapture", true)
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    LaunchedEffect(engine.camera) { engine.torch(torch); engine.exposure(exposure) }
    var noteDialog by remember { mutableStateOf(false) }
    var locationDialog by remember { mutableStateOf(false) }; var barcodeDialog by remember { mutableStateOf(false) }
    var location by remember(session?.location) { mutableStateOf(session?.location ?: "") }
    val ready = photos.filter { it.state == "READY" }; val info = group?.let { barcodeInfo(it, photos) }
    val nextRole = defaultRole(photos.size)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (landscape) 4.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("SNAPIMS", fontSize = 12.sp, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(session?.name ?: "Ready when you are", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            if (session != null) { AssistChip(onClick = { locationDialog = true }, label = { Text(session.location.ifEmpty { "Location" }) }, leadingIcon = { Icon(Icons.Outlined.Place, null, Modifier.size(16.dp)) }); IconButton(onClick = onFinish, enabled = !vm.pendingCapture) { Icon(Icons.Outlined.DoneAll, "Finish batch") } }
        }
        // Keep the same AndroidView through orientation changes and move only controls.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val panelWidth = if (landscape) 260.dp else 0.dp
            val panelHeight = if (session == null) 102.dp else 268.dp
            Box(Modifier.fillMaxSize().padding(end = panelWidth, bottom = if (landscape) 0.dp else panelHeight).padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF080B0C))) {
            if (permitted) {
                AndroidView(factory = { ctx -> PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    engine.attachGestures(this)
                } }, update = {
                    val key = "$front:$hdr:$photoSize:$fast"
                    if (it.tag != key) { it.tag = key; engine.bind(it, owner, front, hdr, photoSize, fast) }
                    else engine.updateRotation()
                }, modifier = Modifier.fillMaxSize())
                DisposableEffect(engine) { onDispose { engine.unbind() } }
                if (vm.repo.preferences.getBoolean("grid", true)) Canvas(Modifier.fillMaxSize()) {
                    for (i in 1..2) { drawLine(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(size.width * i / 3, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 3, size.height)); drawLine(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, size.height * i / 3), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 3)) }
                }
            } else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.CameraAlt, null, tint = Lime, modifier = Modifier.size(42.dp)); Text("Your tapes, in focus", color = Color.White, fontSize = 24.sp); Text("Allow camera access to begin. Photos stay on this device.", color = Color.LightGray); Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
            if (engine.error.isNotEmpty()) Text(engine.error, Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = 0.8f)), color = Color.White)
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f)) { Text(if (session == null) "OFFLINE FIRST" else "ITEM %03d  ·  %d PHOTOS".format(group?.number ?: 1, ready.size), Modifier.padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                Surface(shape = RoundedCornerShape(12.dp), color = Lime) { Text(nextRole.name, Modifier.padding(12.dp), color = Color(0xFF17200C), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(4.dp).heightIn(max = if (landscape) 150.dp else 280.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                CameraControl(Icons.Outlined.QrCodeScanner, "Read CVHS QR command", !vm.pendingCapture) {
                    vm.scanCommand(engine) { command ->
                        when(command.kind) {
                            "START" -> if(session==null) onStart() else vm.message="Batch is already started; duplicate START ignored."
                            "END" -> if(session!=null) onFinish() else vm.message="Start a batch before END."
                            "NEXT" -> vm.action {if(session!=null && vm.repo.dao.photos(vm.repo.current().id).any {!it.deleted && it.state=="READY"}) vm.repo.next() else vm.message="No open photographed item; NEXT ignored."}
                            "CONT" -> vm.message="CONT compatibility card: continue this item; no boundary added."
                            "LOCATION" -> vm.action {vm.repo.applyQrLocation(command.value);vm.message="QR location saved. If an item has photos, it applies after NEXT."}
                            "RARE","REVIEW" -> vm.action {val current=vm.repo.current();vm.repo.flags(current.id,command.kind=="RARE" || current.rare,command.kind=="REVIEW" || current.review,current.note)}
                        }
                    }
                }
                CameraControl(Icons.Outlined.Cameraswitch, "Switch front/rear lens", !vm.busy) { front = !front }
                CameraControl(if (torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff, "Torch", !vm.busy) { torch = !torch; engine.torch(torch) }
                CameraControl(if (flash == ImageCapture.FLASH_MODE_OFF) Icons.Outlined.FlashOff else Icons.Outlined.FlashOn, "Flash", !vm.busy) { flash = if (flash == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF; vm.flash = flash }
                if (engine.hdrAvailable) CameraControl(if (hdr) Icons.Outlined.HdrOn else Icons.Outlined.HdrOff, if (hdr) "Disable HDR" else "Enable HDR", !vm.busy) { hdr = !hdr; vm.repo.preferences.edit().putBoolean("hdr", hdr).apply() }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp)) {
                if (!landscape && ready.isNotEmpty() && (ready.none { it.role == "FRONT" } || ready.none { it.role == "BACK" })) Text("Front/back incomplete · Next is still available", color = Color.White, fontSize = 12.sp, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(8.dp))
                if (!landscape && group != null && ready.size >= 2) Surface(onClick = { barcodeDialog = true }, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.75f)) { Text("${info?.optString("status")}  ${info?.optString("selected")?.takeUnless { it == "null" } ?: "Tap to correct"}", Modifier.padding(12.dp), color = Lime, fontSize = 13.sp) }
                if (!landscape && engine.camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true) Slider(value = exposure, onValueChange = { exposure = it; engine.exposure(it) }, valueRange = -1f..1f, modifier = Modifier.width(160.dp).height(30.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (engine.maxZoom > engine.minZoom) {
                        Text("%.1f×".format(engine.zoomRatio), color = Color.White, fontSize = 12.sp)
                        Slider(value = engine.zoomRatio.coerceIn(engine.minZoom, engine.maxZoom), onValueChange = { engine.zoom(it) }, valueRange = engine.minZoom..engine.maxZoom,
                            modifier = Modifier.semantics { contentDescription = "Camera zoom" }.width(if (landscape) 100.dp else 150.dp).height(28.dp))
                        TextButton(onClick = { engine.zoom(1f) }, contentPadding = PaddingValues(4.dp)) { Text("1×", color = Lime) }
                    }
                }
                Text(engine.resolution, color = Color.LightGray, fontSize = 10.sp)
            }
            if (vm.busy) Text("Capturing…", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp), color = Color.White)
            }
            Column((if (landscape) Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight() else Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight)).verticalScroll(rememberScrollState())) {
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    TextButton(enabled = !vm.busy, onClick = { sizeMenu = true }) { Text("${photoSize.label} ▾", fontSize = 12.sp) }
                    DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                        PhotoResolution.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { if (!vm.busy) { photoSize = option; vm.repo.preferences.edit().putString("photoResolution", option.name).apply() }; sizeMenu = false }) }
                    }
                }
                if (vm.saving > 0) Text(if (vm.saving < 3) "Saving ${vm.saving} · you can capture again" else "Saving 3 · please wait", Modifier.align(Alignment.CenterHorizontally), fontSize = 11.sp)

        if (session == null) Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).height(52.dp)) { Text("Start a batch", fontSize = 18.sp) }
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = group?.rare == true, onClick = { group?.let { vm.action { vm.repo.flags(it.id, !it.rare, it.review, it.note) } } }, enabled = !vm.pendingCapture, label = { Text("Rare") })
                    FilterChip(selected = group?.review == true, onClick = { group?.let { vm.action { vm.repo.flags(it.id, it.rare, !it.review, it.note) } } }, enabled = !vm.pendingCapture, label = { Text("Review") })
                }
                IconButton(onClick = { noteDialog = true }, enabled = !vm.pendingCapture) { Icon(Icons.Outlined.EditNote, "Product flags and notes") }
                Text("${"%.0f".format(photos.sumOf { it.bytes } / 1_000_000.0)} MB · local", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ready.isNotEmpty()) AsyncImage(vm.repo.original(ready.last().id), "Review last photo", Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onReview), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else IconButton(onClick = onReview) { Icon(Icons.Outlined.PhotoLibrary, "Review photos") }
                    IconButton(enabled = !vm.pendingCapture, onClick = { if (!vm.pendingCapture) vm.action { vm.repo.undoNext() } }) { Icon(Icons.Outlined.Undo, "Undo Next") }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(enabled = permitted && !vm.busy && vm.saving < 3 && engine.imageCapture != null, onClick = { vm.shoot(engine) }, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF18210D)), contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("shutter").size(84.dp).border(2.dp, Lime.copy(alpha = 0.4f), CircleShape).padding(5.dp)) { Icon(Icons.Outlined.CameraAlt, "Capture", Modifier.size(30.dp)) }
                    Text("CAPTURE", fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                    if (ready.isNotEmpty()) TextButton(enabled = !vm.pendingCapture, onClick = { vm.action { vm.repo.deletePhoto(ready.last().id); vm.shoot(engine) } }) { Text("Retake last", fontSize = 10.sp) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = { if (!vm.pendingCapture) vm.action { vm.repo.next() } }, enabled = !vm.pendingCapture && ready.isNotEmpty(), modifier = Modifier.size(64.dp)) { Icon(Icons.Outlined.ArrowForward, "Next product", Modifier.size(30.dp)) }
                    Text("NEXT", fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
                }
            }
        }
    }
    if (locationDialog) AlertDialog(onDismissRequest = { locationDialog = false }, title = { Text("Capture location") }, text = { OutlinedTextField(location, { location = it.uppercase().take(3) }, supportingText = { Text("A1–J10, Q1, or leave blank. Applies to this and following products.") }, isError = !validLocation(location)) }, confirmButton = { TextButton(enabled = validLocation(location), onClick = { vm.action { vm.repo.location(location); locationDialog = false } }) { Text("Save") } })
    if (noteDialog && group != null) NotesDialog(vm, group) { noteDialog = false }
    if (barcodeDialog && group != null) BarcodeDialog(vm, group, photos) { barcodeDialog = false }
}
@Composable private fun CameraControl(icon: ImageVector, label: String, enabled: Boolean, action: () -> Unit) { FilledIconButton(onClick = action, enabled = enabled, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)) { Icon(icon, label) } }

@Composable fun BarcodeDialog(vm: CaptureViewModel, group: GroupRow, photos: List<PhotoRow>, dismiss: () -> Unit) {
    val info = barcodeInfo(group, photos)
    var value by remember { mutableStateOf(info.optString("selected").takeUnless { it == "null" } ?: "") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Product barcode") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${info.getString("status")} · barcode identifies a title, never a physical copy.")
        val values = info.getJSONArray("candidates")
        for (i in 0 until values.length()) { val c = values.getJSONObject(i); TextButton(onClick = { value = c.getString("raw") }) { Text("${c.getString("raw")} · ${c.getString("format")} ${if (c.getBoolean("valid")) "✓" else "invalid checksum"}") } }
        OutlinedTextField(value, { value = it.filter(Char::isDigit).take(14) }, label = { Text("UPC / EAN · blank for no barcode") }, singleLine = true)
        TextButton(onClick = { vm.action { vm.repo.barcode(group.id, null); photos.lastOrNull { it.role == "BACK" }?.let { vm.repo.rescan(it.id) }; dismiss() } }) { Text("Rescan back photo") }
    } }, confirmButton = { TextButton(onClick = { vm.action { vm.repo.barcode(group.id, value); dismiss() } }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable fun BatchesScreen(vm: CaptureViewModel, sessions: List<SessionRow>, groups: List<GroupRow>, photos: List<PhotoRow>, archives: List<ArchiveRow>, onStart: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val session = sessions.firstOrNull { it.id == selected } ?: sessions.firstOrNull()
    val recognitionRows by vm.recognition.collectAsStateWithLifecycle()
    var recognitionGroup by remember { mutableStateOf<GroupRow?>(null) }
    var batchRecognitionDialog by remember { mutableStateOf(false) }
    var notesGroup by remember { mutableStateOf<GroupRow?>(null) }
    var editBarcode by remember { mutableStateOf<GroupRow?>(null) }
    var inspected by remember { mutableStateOf<PhotoRow?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item { PageTitle("Your batches", "Every photograph stays yours") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { sessions.forEach { s -> FilterChip(selected = s.id == session?.id, onClick = { selected = s.id }, label = { Text(s.name) }) }; if (sessions.none { !it.finished }) AssistChip(onClick = onStart, label = { Text("New batch") }) } }
        if (session == null) item { EmptyState("Start with one tape", "Create a batch in Capture. Your photos and unfinished product are restored when you return.") }
        if (session != null) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (session.finished) "Finished · source photos retained" else "Capturing · ${groups.count { it.sessionId == session.id && it.closed }} products", Modifier.weight(1f)); TextButton(onClick = { vm.action { vm.repo.queueArchives(session.id) } }) { Text("Finalize part") } } }
            item { TextButton(onClick = { batchRecognitionDialog = true }) { Text("Recognize completed products") } }
            items(groups.filter { it.sessionId == session.id }.sortedByDescending { it.number }, key = { it.id }) { group ->
                val list = photos.filter { it.groupId == group.id }.sortedBy { it.position }; val visible = list.filter { !it.deleted }
                val locked = group.archiveId != null || session.finished
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) { Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Product %03d".format(group.number), fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("${group.location.ifEmpty { "No location" }} · ${visible.size} photos · ${if (group.archiveId != null) "Archiving / exported" else if (group.closed) "Closed" else "Open"}", style = MaterialTheme.typography.bodySmall) }; IconButton(enabled = !locked, onClick = { editBarcode = group }) { Icon(Icons.Outlined.QrCodeScanner, "Review barcode") } }
                    Row {
                        if (!locked) TextButton(onClick = { notesGroup = group }) { Text("Flags & notes") }
                        TextButton(enabled = visible.any { it.state == "READY" }, onClick = { recognitionGroup = group }) { Text("Recognize") }
                    }
                    recognitionRows.firstOrNull { it.groupId == group.id }?.let { recognition ->
                        Text("${recognitionLabel(recognition.provider)} · ${recognition.state}", fontSize = 12.sp)
                        recognition.acceptedTitle?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (group.note.isNotBlank()) Text(group.note, fontSize = 12.sp)
                    if (group.rare || group.review) Text(listOfNotNull("Rare".takeIf { group.rare }, "Review".takeIf { group.review }).joinToString(" · "), color = MaterialTheme.colorScheme.primary)
                    val barcode = barcodeInfo(group, visible); Text("${barcode.optString("status")} ${barcode.optString("selected").takeUnless { it == "null" } ?: ""}", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) { visible.forEach { photo ->
                        Column(Modifier.width(118.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(if (photo.state == "READY") vm.repo.original(photo.id) else vm.repo.pendingFile(photo.id), "${photo.role} photograph", Modifier.padding(top = 12.dp).size(118.dp).clip(RoundedCornerShape(12.dp)).clickable { inspected = photo }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            TextButton(enabled = !locked, onClick = { vm.action { vm.repo.role(photo.id, Role.entries[(Role.valueOf(photo.role).ordinal + 1) % 3]) } }) { Text(photo.role, fontSize = 11.sp) }
                            if (photo.error.isNotEmpty()) Text(photo.error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            if (photo.state == "ERROR" && !locked) TextButton(onClick = { vm.action { vm.repo.complete(photo.id) } }) { Text("Recover file", fontSize = 11.sp) }
                            if (!locked) {
                                Row { IconButton(onClick = { vm.action { vm.repo.reorder(photo.id, -1) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.KeyboardArrowLeft, "Move photo earlier") }; IconButton(onClick = { vm.action { vm.repo.reorder(photo.id, 1) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.KeyboardArrowRight, "Move photo later") } }
                                TextButton(onClick = { vm.action { vm.repo.deletePhoto(photo.id) } }) { Text("Remove", fontSize = 11.sp) }
                            }
                        }
                    } }
                    if (!locked && list.any { it.deleted }) TextButton(onClick = { vm.action { list.lastOrNull { it.deleted }?.let { vm.repo.restorePhoto(it.id) } } }) { Text("Restore last removed photo") }
                } }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    recognitionGroup?.let { g -> RecognitionDialog(vm, g, recognitionRows.filter { it.groupId == g.id }) { recognitionGroup = null } }
    if (batchRecognitionDialog && session != null) {
        val mode = vm.repo.recognitionMode()
        val eligible = groups.filter { g -> g.sessionId == session.id && g.closed }.filter { g ->
            val input = photos.filter { it.groupId == g.id && !it.deleted && it.state == "READY" }.sortedBy { it.position }
            input.isNotEmpty() && recognitionRows.none { it.groupId == g.id && it.provider == mode && it.state == "SUCCEEDED" && it.inputFingerprint == photoFingerprint(input) }
        }
        AlertDialog(onDismissRequest = { batchRecognitionDialog = false }, title = { Text("Recognize ${eligible.size} products?") }, text = { Text(
            if (mode == "OFF") "Choose a recognition mode in Settings first."
            else if (mode == "OPENAI") "This uploads up to three downsized photos per product to OpenAI and charges your API account. Current completed results are skipped. You can keep capturing while jobs run."
            else "Run ${recognitionLabel(mode)} in the background. Current completed results are skipped. No photos leave this device."
        ) }, confirmButton = { TextButton(enabled = mode != "OFF" && eligible.isNotEmpty(), onClick = { batchRecognitionDialog = false; vm.action { eligible.forEach { vm.repo.queueRecognition(it.id) }; vm.message = "Recognition jobs queued." } }) { Text("Run recognition") } }, dismissButton = { TextButton(onClick = { batchRecognitionDialog = false }) { Text("Cancel") } })
    }
    notesGroup?.let { g -> NotesDialog(vm, g) { notesGroup = null } }
    editBarcode?.let { g -> BarcodeDialog(vm, g, photos.filter { it.groupId == g.id }) { editBarcode = null } }
    inspected?.let { p ->
        AlertDialog(onDismissRequest = { inspected = null }, title = { Text("${p.role} · photo ${p.position + 1}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            AsyncImage(vm.repo.original(p.id), "Full photo preview", Modifier.fillMaxWidth().height(300.dp))
            Text("${p.capturedAt}\n${"%.2f".format(p.bytes / 1_000_000.0)} MB\nSHA-256 ${p.sha256.take(16)}…", fontSize = 12.sp)
            if (groups.firstOrNull { it.id == p.groupId }?.archiveId == null && session?.finished == false) {
                TextButton(onClick = { vm.action { vm.repo.move(p.id, -1); inspected = null } }) { Text("Move to previous product") }
                TextButton(onClick = { vm.action { vm.repo.move(p.id, 1); inspected = null } }) { Text("Move to next product") }
                TextButton(onClick = { vm.action { vm.repo.deletePhoto(p.id); inspected = null } }) { Text("Remove · original retained") }
            }
        } }, confirmButton = { TextButton(onClick = { inspected = null }) { Text("Done") } })
    }
}
@Composable fun SettingsScreen(vm: CaptureViewModel, light: Boolean, onBackups: () -> Unit = {}, onExports: () -> Unit = {}, onWorkspace:()->Unit={},onPricing:()->Unit={},onListings:()->Unit={}, appearance: (Boolean) -> Unit) {
    val prefs = vm.repo.preferences
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var archiveMb by remember { mutableStateOf(prefs.getInt("archiveMb", 500).toString()) }
    var location by remember { mutableStateOf(prefs.getString("defaultLocation", "") ?: "") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
        item { PageTitle("Settings", "Your collection, your controls") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onBackups) { Text("Backup & import") }; OutlinedButton(onClick = onExports) { Text("Transfers") } } }
        item { Row(Modifier.horizontalScroll(rememberScrollState())) {OutlinedButton(onClick=onWorkspace) {Text("AI workspace")};OutlinedButton(onClick=onPricing) {Text("Sold pricing")};OutlinedButton(onClick=onListings) {Text("Listing plans")}} }
        item { Text("CAMERA", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, letterSpacing = 2.sp) }
        item { ResolutionSettings(prefs) }
        item { PreferenceSwitch("Fast capture", prefs.getBoolean("fastCapture", true)) { prefs.edit().putBoolean("fastCapture", it).apply() } }
        item { Text("Fast capture uses zero shutter lag where supported, with a low-latency fallback and JPEG 95%. Turn it off for JPEG 100% and quality priority. Flash, HDR and low light can add delay. Pinch the preview or use the zoom slider.", style = MaterialTheme.typography.bodySmall) }
        item { PreferenceSwitch("Gridlines", prefs.getBoolean("grid", true)) { prefs.edit().putBoolean("grid", it).apply() } }
        item { PreferenceSwitch("Volume button shutter", prefs.getBoolean("volume", true)) { prefs.edit().putBoolean("volume", it).apply() } }
        item { PreferenceSwitch("Haptic shutter feedback", prefs.getBoolean("haptic", true)) { prefs.edit().putBoolean("haptic", it).apply() } }
        item { PreferenceSwitch("Optional shutter tone", prefs.getBoolean("tone", false)) { prefs.edit().putBoolean("tone", it).apply() } }
        item { Text("RECOGNITION", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, letterSpacing = 2.sp) }
        item { PreferenceSwitch("Offline barcode scanning", prefs.getBoolean("barcode", true)) { prefs.edit().putBoolean("barcode", it).apply() } }
        item { RecognitionSettings(vm) }
        item {BusinessDefaultsSettings(vm)}
        item { Text("ARCHIVES & RETENTION", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, letterSpacing = 2.sp) }
        item { OutlinedTextField(archiveMb, { value -> archiveMb = value.filter(Char::isDigit).take(5); val n = archiveMb.toIntOrNull(); if (n != null && n in 1..10000) prefs.edit().putInt("archiveMb", n).apply() }, label = { Text("Archive target · MB") }, supportingText = { Text("1–10,000 MB · whole products only; oversized products stay together") }, isError = archiveMb.toIntOrNull() !in 1..10000, singleLine = true) }
        item { OutlinedTextField(location, { location = it.uppercase().take(3); if (validLocation(location)) prefs.edit().putString("defaultLocation", location).apply() }, label = { Text("Default location") }, supportingText = { Text("A1–J10 or Q1, optional") }, isError = !validLocation(location), singleLine = true) }
        item { Text("Choose an export destination each time using Save to Files. Existing capture ZIPs remain unchanged; make inventory and photo corrections in Inventory. Removing a photo keeps its source file. No automatic deletion or cloud backup. Uninstalling Android apps removes private files: save and verify all ZIPs before uninstalling or clearing storage.", style = MaterialTheme.typography.bodySmall) }
        item { PreferenceSwitch("Light appearance", light) { prefs.edit().putBoolean("light", it).apply(); appearance(it) } }
        item { TextButton(onClick = { showLicenses = true }) { Text("Third-party notices & licenses") } }
        item { Text("SnapIMS Next 0.3.0 · standalone inventory & AI preview", style = MaterialTheme.typography.bodySmall) }
    }
    if (showLicenses) NoticesDialog(context) { showLicenses = false }

}
@Composable fun PreferenceSwitch(label: String, initial: Boolean, change: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, { value = it; change(it) }) }
}

@Composable fun NotesDialog(vm: CaptureViewModel, group: GroupRow, dismiss: () -> Unit) {
    var note by remember { mutableStateOf(group.note) }
    var rare by remember { mutableStateOf(group.rare) }; var review by remember { mutableStateOf(group.review) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Flags & notes") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rare, { rare = it }); Text("Rare"); Checkbox(review, { review = it }); Text("Review") }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Damaged", "Sealed", "Unusual").forEach { label -> AssistChip(onClick = { if (!note.contains(label, ignoreCase = true)) note = listOf(note.trim(), label).filter { it.isNotEmpty() }.joinToString(" · ") }, label = { Text(label) }) }
        }
        OutlinedTextField(note, { note = it.take(1000) }, label = { Text("Capture notes") }, minLines = 3)
    } }, confirmButton = { TextButton(onClick = { vm.action { vm.repo.flags(group.id, rare, review, note); dismiss() } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable fun NoticesDialog(context: android.content.Context, dismiss: () -> Unit) {
    val files = remember { context.assets.list("licenses")?.filter { it.endsWith(".txt") }?.sorted() ?: emptyList() }
    var filename by remember { mutableStateOf("NOTICE.txt") }; var page by remember { mutableIntStateOf(0) }
    val text = remember(filename) { context.assets.open("licenses/$filename").bufferedReader().use { it.readText() } }
    val pages = ((text.length + 7999) / 8000).coerceAtLeast(1)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Third-party notices") }, text = { Column(Modifier.height(420.dp)) {
        LazyColumn(Modifier.height(100.dp)) { items(files) { file -> TextButton(onClick = { filename = file; page = 0 }) { Text(file, fontSize = 11.sp, maxLines = 1) } } }
        Text("$filename · page ${page + 1}/$pages", fontSize = 11.sp)
        Text(text.substring((page * 8000).coerceAtMost(text.length), ((page + 1) * 8000).coerceAtMost(text.length)), Modifier.weight(1f).verticalScroll(rememberScrollState()), fontSize = 11.sp)
    } }, confirmButton = { Row {
        TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
        TextButton(enabled = page + 1 < pages, onClick = { page++ }) { Text("Next") }
        TextButton(onClick = dismiss) { Text("Done") }
    } })
}
