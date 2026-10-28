package ca.canadavhs.snapmobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.canadavhs.snapmobile.core.InventoryRules
import coil.compose.AsyncImage

@Composable fun InventoryHome(vm: CaptureViewModel, capture: () -> Unit, inventory: () -> Unit, batches: () -> Unit, backups: () -> Unit) {
    val rows by vm.inventory.collectAsStateWithLifecycle(); val sessions by vm.sessions.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle(); val jobs by vm.transferJobs.collectAsStateWithLifecycle()
    val active = rows.filter { !it.archived }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Text("CANADA VHS",color=MaterialTheme.colorScheme.primary,letterSpacing=3.sp,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text("Your collection,\nin your hands.",fontSize=32.sp,fontWeight=FontWeight.Bold)
            Text("Inventory stays on this phone, even offline.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SummaryTile("Physical copies",active.size.toString(),Modifier.weight(1f))
            SummaryTile("Ready for listing",active.count { it.ready }.toString(),Modifier.weight(1f))
        } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SummaryTile("Needs review",active.count { !it.ready }.toString(),Modifier.weight(1f))
            SummaryTile("Original photos",photos.count { it.state=="READY" }.toString(),Modifier.weight(1f))
        } }
        item { Button(onClick=capture,modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(18.dp)) { Icon(Icons.Outlined.CameraAlt,null); Spacer(Modifier.width(10.dp)); Text(if(sessions.any { !it.finished }) "Continue capturing" else "Photograph a collection") } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick=inventory,modifier=Modifier.weight(1f)) { Text("Open inventory") }
            OutlinedButton(onClick=batches,modifier=Modifier.weight(1f)) { Text("Review batches") }
        } }
        item { Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp,onClick=backups) { Column(Modifier.padding(20.dp)) {
            Text("Protect your collection",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
            Text("Create a passphrase-protected backup with original photographs.",style=MaterialTheme.typography.bodyMedium)
            Text("Backup & import →",Modifier.padding(top=12.dp),color=MaterialTheme.colorScheme.primary)
        } } }
        item { Text("${sessions.count { it.finished }} finished batches · ${"%.1f".format(photos.sumOf { it.bytes }/1_000_000.0)} MB of captured originals",style=MaterialTheme.typography.bodySmall) }
        jobs.firstOrNull()?.takeIf { it.state in listOf("ERROR","INTERRUPTED") }?.let { job -> item { Text("${job.kind.lowercase().replaceFirstChar { it.uppercase() }} needs attention: ${job.error}",color=MaterialTheme.colorScheme.error) } }
    }
}
@Composable private fun SummaryTile(label: String, value: String, modifier: Modifier) {
    Surface(modifier,shape=RoundedCornerShape(22.dp),tonalElevation=2.dp) { Column(Modifier.padding(18.dp)) { Text(value,fontSize=30.sp,fontWeight=FontWeight.Bold); Text(label,style=MaterialTheme.typography.bodySmall) } }
}
@Composable fun InventoryScreen(vm: CaptureViewModel, reviewPhotos: () -> Unit) {
    val rows by vm.inventory.collectAsStateWithLifecycle(); val groups by vm.groups.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle(); val sessions by vm.sessions.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }; var filter by rememberSaveable { mutableStateOf("All") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var managePhotos by rememberSaveable {mutableStateOf(false)}
    var cameraOpen by rememberSaveable {mutableStateOf(false)}
    var replacePhoto by rememberSaveable {mutableStateOf<String?>(null)}
    val selectedItem=rows.firstOrNull { it.groupId==selected }
    BackHandler(selectedItem!=null && !cameraOpen) { if(managePhotos) managePhotos=false else selected=null }
    if(selectedItem!=null) {
        if(cameraOpen) {InventoryItemCamera(vm,selectedItem,replacePhoto) {cameraOpen=false};return}
        if(managePhotos) {InventoryPhotoManager(vm,selectedItem,photos.filter {it.groupId==selectedItem.groupId},rows,capture={replacePhoto=it;cameraOpen=true},back={managePhotos=false});return}
        InventoryEditor(vm,selectedItem,groups.firstOrNull { it.id==selectedItem.groupId },photos.filter { it.groupId==selectedItem.groupId },rows,
            onClose={selected=null},reviewPhotos={managePhotos=true})
        return
    }
    val visible=rows.filter { item ->
        val group=groups.firstOrNull { it.id==item.groupId }
        val barcode=effectiveBarcode(item,group,photos.filter { it.groupId==item.groupId })
        val matches=listOf(item.title,item.edition,item.itemId,item.location,barcode,item.conditionNotes,item.captureNote).any { it.contains(search,ignoreCase=true) }
        matches && when(filter) { "Ready" -> item.ready && !item.archived; "Needs review" -> !item.ready && !item.archived; "Archived" -> item.archived; else -> !item.archived }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { PageTitle("Inventory","Every physical copy has its own permanent identity") }
        item { OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Search title, barcode, SKU or location")},leadingIcon={Icon(Icons.Outlined.Search,null)}) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Needs review","Ready","Archived").forEach { label -> FilterChip(selected=filter==label,onClick={filter=label},label={Text(label)}) }
        } }
        if(visible.isEmpty()) item { EmptyState(if(rows.isEmpty()) "Your collection starts here" else "No matching items",if(rows.isEmpty()) "Photograph a tape or import a SnapIMS inventory package. Records remain usable without a desktop." else "Change the search or filter.") }
        items(visible,key={it.groupId}) { item ->
            val list=photos.filter { it.groupId==item.groupId && !it.deleted && it.state=="READY" }.sortedBy { it.position }
            val group=groups.firstOrNull { it.id==item.groupId }
            Surface(onClick={selected=item.groupId},shape=RoundedCornerShape(22.dp),tonalElevation=2.dp) { Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                if(list.isNotEmpty()) AsyncImage(vm.repo.original(list.first().id),"${item.title.ifBlank { "Unidentified tape" }} cover",Modifier.size(76.dp),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
                else Icon(Icons.Outlined.ImageNotSupported,"No complete photo",Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title.ifBlank { "Untitled tape" },fontWeight=FontWeight.SemiBold,fontSize=18.sp,maxLines=2)
                    Text("${item.condition} · ${item.location.ifBlank { "No location" }}",style=MaterialTheme.typography.bodySmall)
                    Text("${list.size} photos · ${sessions.firstOrNull { it.id==group?.sessionId }?.name ?: item.batchId}",style=MaterialTheme.typography.bodySmall,maxLines=1)
                    Text(if(item.ready) "Ready for listing" else "Needs review",color=if(item.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
                }
                if(item.priceCents!=null) Text("$${InventoryRules.priceText(item.priceCents)}",fontWeight=FontWeight.SemiBold)
            } }
        }
    }
}
@Composable private fun InventoryEditor(vm: CaptureViewModel, item: InventoryRow, group: GroupRow?, photos: List<PhotoRow>, all: List<InventoryRow>, onClose: () -> Unit, reviewPhotos: () -> Unit) {
    val starting=remember(item.groupId) {item.copy(barcode=effectiveBarcode(item,group,photos))}
    var draft by remember(item.groupId) { mutableStateOf(starting) }
    var price by remember(item.groupId) { mutableStateOf(InventoryRules.priceText(item.priceCents)) }
    var discount by remember(item.groupId) { mutableStateOf(java.math.BigDecimal(item.discountBasisPoints).movePointLeft(2).toPlainString()) }
    var year by remember(item.groupId) { mutableStateOf(item.releaseYear?.toString() ?: "") }
    var quantity by remember(item.groupId) { mutableStateOf(item.quantity.toString()) }
    var weight by remember(item.groupId) { mutableStateOf(item.weightGrams.toString()) }
    var advanced by remember { mutableStateOf(false) }; var showRecognition by remember { mutableStateOf(false) }
    var archiveConfirmation by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<InventoryAuditRow>>(emptyList()) }; var showHistory by remember { mutableStateOf(false) }
    val openPhotos: () -> Unit = {
        val dirty=draft!=starting || price!=InventoryRules.priceText(starting.priceCents) || discount!=java.math.BigDecimal(starting.discountBasisPoints).movePointLeft(2).toPlainString() || year!=(starting.releaseYear?.toString() ?: "") || quantity!=starting.quantity.toString() || weight!=starting.weightGrams.toString()
        if(dirty) vm.message="Save inventory changes before managing photographs." else reviewPhotos()
    }
    val recognition by vm.recognition.collectAsStateWithLifecycle()
    val suggestions=recognition.filter { it.groupId==item.groupId }
    val duplicates=all.filter { it.groupId!=item.groupId && !it.archived && ((draft.title.isNotBlank() && InventoryRules.normalizedTitle(it.title)==InventoryRules.normalizedTitle(draft.title)) || (draft.barcode.isNotBlank() && it.barcode==draft.barcode)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to inventory") }
            Text("Tape details",fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            TextButton(enabled=!vm.maintenance,onClick={vm.action {
                val candidate=draft.copy(priceCents=InventoryRules.cents(price),discountBasisPoints=InventoryRules.discountBasisPoints(discount),
                    releaseYear=if(year.isBlank()) null else year.toIntOrNull() ?: error("Enter a valid release year."),
                    quantity=quantity.toIntOrNull() ?: error("Enter a whole quantity."),weightGrams=weight.toIntOrNull() ?: error("Enter a whole shipping weight."))
                vm.repo.saveInventory(candidate,draft.revision); vm.message="Inventory saved on this phone."; onClose()
            }}) { Text("Save changes",fontWeight=FontWeight.Bold) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            item { Text(item.itemId,style=MaterialTheme.typography.bodySmall); Text("SKU = Item ID · permanent physical-copy identity",style=MaterialTheme.typography.labelSmall) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                photos.filter { !it.deleted && it.state=="READY" }.sortedBy { it.position }.forEach { p -> AsyncImage(vm.repo.original(p.id),"${p.role} photo",Modifier.size(112.dp).clickable(onClick=openPhotos),contentScale=androidx.compose.ui.layout.ContentScale.Crop) }
            } }
            item { TextButton(onClick=openPhotos) { Text("Manage original photos, retakes & grouping") } }
            item { EditField("Title",draft.title) { draft=draft.copy(title=it.take(255)) } }
            item { EditField("Edition",draft.edition) { draft=draft.copy(edition=it.take(1000)) } }
            item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(year,{year=it.take(4)},modifier=Modifier.weight(1f),label={Text("Release year")},singleLine=true)
                OutlinedTextField(draft.location,{draft=draft.copy(location=it.take(120))},modifier=Modifier.weight(1f),label={Text("Location")},singleLine=true)
            } }
            item { ChoiceField("Condition",draft.condition,InventoryRules.conditions) { draft=draft.copy(condition=it) } }
            item { EditField("Condition & damage notes",draft.conditionNotes,multiline=true) { draft=draft.copy(conditionNotes=it.take(10000)) } }
            if(draft.captureNote.isNotBlank()) item { Text("Capture notes: ${draft.captureNote}",style=MaterialTheme.typography.bodySmall) }
            item { EditField("Barcode / UPC / EAN",draft.barcode) { draft=draft.copy(barcode=it.filter(Char::isDigit).take(14)) } }
            if(duplicates.isNotEmpty()) item { Text("${duplicates.size} other copies share this title or barcode. They keep separate Item IDs and condition records.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(price,{price=it.take(30)},modifier=Modifier.weight(1f),label={Text("Price · CAD")},singleLine=true)
                OutlinedTextField(discount,{discount=it.take(12)},modifier=Modifier.weight(1f),label={Text("Discount · %")},singleLine=true)
            } }
            item { Text("Source: ${draft.priceSource.lowercase().replace('_',' ')}. Entering a price records a manual decision.",style=MaterialTheme.typography.bodySmall) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(quantity,{quantity=it.take(10)},modifier=Modifier.weight(1f),label={Text("Quantity")},singleLine=true)
                OutlinedTextField(weight,{weight=it.take(7)},modifier=Modifier.weight(1f),label={Text("Weight · grams")},singleLine=true)
            } }
            item { Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(draft.rare,{draft=draft.copy(rare=it,poolMode=if(it) "UNIQUE" else draft.poolMode)}); Text("Rare")
                Checkbox(draft.review,{draft=draft.copy(review=it)}); Text("Review")
            } }
            item { Surface(shape=RoundedCornerShape(18.dp),tonalElevation=2.dp) { Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Ready for listing",fontWeight=FontWeight.SemiBold); Text("Saved only after validation. Creates no Shopify product.",style=MaterialTheme.typography.bodySmall) }; Switch(draft.ready,{draft=draft.copy(ready=it)},modifier=Modifier.testTag("inventory-ready")) }
                val candidate=runCatching { draft.copy(priceCents=InventoryRules.cents(price),discountBasisPoints=InventoryRules.discountBasisPoints(discount)) }.getOrNull()
                if(candidate!=null) inventoryProblems(candidate,photos,vm.repo).forEach { Text("• $it",style=MaterialTheme.typography.bodySmall) }
            } } }
            if(group!=null) item { OutlinedButton(onClick={showRecognition=true}) { Text("Recognize / review suggestions") } }
            suggestions.firstOrNull { it.state=="SUCCEEDED" && !it.acceptedTitle.isNullOrBlank() }?.let { suggestion -> item {
                Text("Confirmed suggestion: ${suggestion.acceptedTitle}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={vm.action { vm.repo.applyRecognitionToInventory(suggestion.id); vm.message="Confirmed title applied. Reopen the item to review and save."; onClose() }}) { Text("Use confirmed title in inventory") }
            } }
            item { TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "Hide listing details" else "Listing details & provenance") } }
            if(advanced) {
                item { EditField("Distributor",draft.distributor) { draft=draft.copy(distributor=it.take(1000)) } }
                item { EditField("Vendor",draft.vendor) { draft=draft.copy(vendor=it.take(1000)) } }
                item { EditField("Product type",draft.productType) { draft=draft.copy(productType=it.take(1000)) } }
                item { EditField("Tags · comma separated",draft.tags) { draft=draft.copy(tags=it.take(4000)) } }
                item { EditField("Description",draft.description,multiline=true) { draft=draft.copy(description=it.take(30000)) } }
                item { ChoiceField("Pooling",draft.poolMode,listOf("POOLED","UNIQUE")) { draft=draft.copy(poolMode=it) } }
                item { Text("Shopify: ${item.shopifyState}\nProduct: ${item.shopifyProductId.ifBlank { "Not linked" }}\nVariant: ${item.shopifyVariantId.ifBlank { "Not linked" }}",style=MaterialTheme.typography.bodySmall) }
                item { Text("Created ${item.createdAt}\nUpdated ${item.updatedAt}\nRevision ${item.revision}",style=MaterialTheme.typography.bodySmall) }
            }
            item { TextButton(onClick={showHistory=!showHistory; if(showHistory) vm.action { history=vm.repo.journal.inventoryDao().history(item.groupId) }}) { Text("${if(showHistory) "Hide" else "Show"} audit history") } }
            if(showHistory) items(history,key={it.id}) { audit -> Text("${audit.occurredAt.take(19)} · ${audit.action}",style=MaterialTheme.typography.bodySmall) }
            item { TextButton(onClick={archiveConfirmation=!archiveConfirmation}) { Text(if(item.archived) "Restore archived item" else "Archive this item") } }
            if(archiveConfirmation) item { Column {
                Text("${if(item.archived) "Restore" else "Hide"} this physical-copy record? Its identity and original photographs are retained.")
                Row { TextButton(onClick={vm.action { vm.repo.archiveInventory(item.groupId,!item.archived); onClose() }}) { Text("Confirm") }; TextButton(onClick={archiveConfirmation=false}) { Text("Cancel") } }
            } }
        }
    }
    if(showRecognition && group!=null) RecognitionDialog(vm,group,suggestions) {showRecognition=false}
}
@Composable internal fun EditField(label: String, value: String, multiline: Boolean=false, change: (String)->Unit) {
    OutlinedTextField(value,change,modifier=Modifier.fillMaxWidth(),label={Text(label)},singleLine=!multiline,minLines=if(multiline) 3 else 1)
}
@Composable internal fun ChoiceField(label: String, value: String, options: List<String>, change: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Column { Text(label,style=MaterialTheme.typography.labelMedium); Box {
        OutlinedButton(onClick={expanded=true}) {Text("$value ▾")}
        DropdownMenu(expanded,{expanded=false}) { options.forEach { option -> DropdownMenuItem(text={Text(option)},onClick={change(option);expanded=false}) } }
    } }
}
