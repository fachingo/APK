package ca.canadavhs.snapmobile

import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import ca.canadavhs.snapmobile.core.Role

@Composable internal fun InventoryPhotoManager(vm: CaptureViewModel,item: InventoryRow, photos: List<PhotoRow>, all: List<InventoryRow>, capture: (String?)->Unit, back: ()->Unit) {
    var moving by remember {mutableStateOf<String?>(null)}
    var expanded by rememberSaveable {mutableStateOf<String?>(null)}
    val others=all.filter { it.groupId!=item.groupId && it.batchId==item.batchId && !it.archived }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) {IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to tape details")};Text("Original photographs",style=MaterialTheme.typography.titleLarge)}
        LazyColumn(Modifier.weight(1f).padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Text("Corrections update this inventory record. Previously exported ZIPs stay unchanged. Removed photos can be restored; original files are retained.",style=MaterialTheme.typography.bodySmall)}
            item {Button(enabled=!vm.pendingCapture && !item.archived,onClick={capture(null)}) {Text("Add photographs")}}
            items(photos.filter {!it.deleted}.sortedBy {it.position},key={it.id}) { photo ->
                Column {
                    AsyncImage(if(photo.state=="READY") vm.repo.original(photo.id) else vm.repo.pendingFile(photo.id),"${photo.role} original",Modifier.fillMaxWidth().height(if(expanded==photo.id) 320.dp else 160.dp).clickable {expanded=if(expanded==photo.id) null else photo.id},contentScale=androidx.compose.ui.layout.ContentScale.Fit)
                    Text("${photo.role} · ${if(photo.deleted) "Removed · original retained" else photo.state.lowercase()}",style=MaterialTheme.typography.labelLarge)
                    if(photo.error.isNotBlank()) Text(photo.error,color=MaterialTheme.colorScheme.error)
                    if(photo.deleted) TextButton(enabled=!vm.pendingCapture,onClick={vm.action {vm.repo.reviseInventoryPhoto(photo.id,"RESTORE")}}) {Text("Restore photo")}
                    else {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled=!vm.pendingCapture,onClick={vm.action {vm.repo.reviseInventoryPhoto(photo.id,"UP")}}) {Text("Move earlier")}
                            TextButton(enabled=!vm.pendingCapture,onClick={vm.action {vm.repo.reviseInventoryPhoto(photo.id,"DOWN")}}) {Text("Move later")}
                            TextButton(enabled=!vm.pendingCapture && photo.state=="READY",onClick={capture(photo.id)}) {Text("Retake")}
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled=!vm.pendingCapture,onClick={vm.action {vm.repo.reviseInventoryPhoto(photo.id,"ROLE",Role.entries[(Role.valueOf(photo.role).ordinal+1)%3].name)}}) {Text("Change role")}
                            TextButton(enabled=!vm.pendingCapture,onClick={vm.action {vm.repo.reviseInventoryPhoto(photo.id,"REMOVE")}}) {Text("Delete photo")}
                            if(others.isNotEmpty()) Box {
                                TextButton(enabled=!vm.pendingCapture,onClick={moving=photo.id}) {Text("Move to another item")}
                                DropdownMenu(expanded=moving==photo.id,onDismissRequest={moving=null}) {
                                    others.forEach { target -> DropdownMenuItem(text={Text("${target.itemId.takeLast(12)} · ${target.title.ifBlank { "Untitled tape" }}")},onClick={moving=null;vm.action {vm.repo.reviseInventoryPhoto(photo.id,"MOVE",target.groupId)}}) }
                                }
                            }
                        }
                        if(photo.state=="ERROR") TextButton(onClick={vm.action {vm.repo.complete(photo.id)}}) {Text("Recover retained file")}
                    }
                }
            }
        }
    }
}

@Composable internal fun InventoryItemCamera(vm: CaptureViewModel, item: InventoryRow, replaceId: String?, close: ()->Unit) {
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    val engine=remember {CameraEngine(context)}
    var front by rememberSaveable {mutableStateOf(false)}; var flash by rememberSaveable {mutableIntStateOf(ImageCapture.FLASH_MODE_OFF)}
    var resolution by remember {mutableStateOf(PhotoResolution.read(vm.repo.preferences))};var sizes by remember {mutableStateOf(false)}
    val wide=LocalConfiguration.current.screenWidthDp>LocalConfiguration.current.screenHeightDp
    val granted=androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED
    BackHandler {if(!vm.pendingCapture) close() else vm.message="Wait for the original photograph to finish saving."}
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(enabled=!vm.pendingCapture,onClick=close) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to original photos")}
            Text(if(replaceId==null) "Add to ${item.title.ifBlank { "this tape" }}" else "Retake · original kept",style=MaterialTheme.typography.titleMedium,maxLines=1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().padding(end=if(wide) 180.dp else 0.dp,bottom=if(wide) 0.dp else 180.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                if(granted) AndroidView(factory={ctx->PreviewView(ctx).apply {implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER;engine.attachGestures(this)}},
                    update={view->val key="$front:$resolution";if(view.tag!=key) {view.tag=key;engine.bind(view,owner,front,false,resolution,vm.repo.preferences.getBoolean("fastCapture",true))} else engine.updateRotation()},modifier=Modifier.fillMaxSize())
                else Text("Allow Camera access in Capture first.",Modifier.align(Alignment.Center),color=androidx.compose.ui.graphics.Color.White)
                if(engine.error.isNotBlank()) Text(engine.error,Modifier.align(Alignment.Center),color=androidx.compose.ui.graphics.Color.White)
            }
            Column((if(wide) Modifier.align(Alignment.CenterEnd).width(180.dp) else Modifier.align(Alignment.BottomCenter).fillMaxWidth()).padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box { TextButton(enabled=!vm.busy,onClick={sizes=true}) {Text("${resolution.label} ▾")};DropdownMenu(sizes,{sizes=false}) {
                    PhotoResolution.entries.forEach { option->DropdownMenuItem(text={Text(option.label)},onClick={if(!vm.busy) {resolution=option;vm.repo.preferences.edit().putString("photoResolution",option.name).apply()};sizes=false}) }
                } }
                Text(engine.resolution,style=MaterialTheme.typography.labelSmall)
                if(engine.maxZoom>engine.minZoom) Row(verticalAlignment=Alignment.CenterVertically) {Text("%.1f×".format(engine.zoomRatio));Slider(engine.zoomRatio.coerceIn(engine.minZoom,engine.maxZoom),{engine.zoom(it)},valueRange=engine.minZoom..engine.maxZoom,modifier=Modifier.width(100.dp))}
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    IconButton(enabled=!vm.busy,onClick={if(!vm.busy) front=!front}) {Icon(Icons.Outlined.Cameraswitch,"Switch lens")}
                    Button(enabled=granted && !vm.busy && vm.saving<3 && engine.imageCapture!=null,onClick={vm.flash=flash;vm.shootInventory(engine,item.groupId,replaceId) {if(replaceId!=null) close()}},shape=CircleShape,contentPadding=PaddingValues(0.dp),modifier=Modifier.size(72.dp)) {Icon(Icons.Outlined.CameraAlt,"Capture inventory photo")}
                    IconButton(enabled=!vm.busy,onClick={flash=if(flash==ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF}) {Icon(if(flash==ImageCapture.FLASH_MODE_OFF) Icons.Outlined.FlashOff else Icons.Outlined.FlashOn,"Flash")}
                }
                if(vm.pendingCapture) Text(if(vm.busy) "Capturing…" else "Saving original…",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    DisposableEffect(engine) {onDispose {engine.unbind()}}
}
