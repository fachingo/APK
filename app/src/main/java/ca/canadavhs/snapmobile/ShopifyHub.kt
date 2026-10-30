package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ShopifyHub(vm:CaptureViewModel,back:()->Unit) {
 var tab by rememberSaveable {mutableIntStateOf(0)};val states=rememberSaveableStateHolder();val model:ShopifyModel=viewModel()
 Column(Modifier.fillMaxSize()) {
  ScrollableTabRow(selectedTabIndex=tab,edgePadding=12.dp) {listOf("Listings","Connection & jobs","Local preparation").forEachIndexed {i,label->Tab(selected=tab==i,onClick={tab=i},text={Text(label)})}}
  Box(Modifier.weight(1f)) {states.SaveableStateProvider(tab) {when(tab) {0->ShopifyListingsScreen(vm,model) {tab=1};1->ShopifyScreen(vm,back,model);else->LocalPreparation(vm)}}}
 }
}

@Composable private fun LocalPreparation(vm:CaptureViewModel) {
 val inventory by vm.inventory.collectAsStateWithLifecycle();val plans by vm.repo.journal.nextDao().watchPlans().collectAsStateWithLifecycle(emptyList())
 val active=inventory.filter {!it.archived}
 androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  item {PageTitle("Local preparation","Validate listing plans before uploading")}
  if(active.isEmpty()) item {EmptyState("No local inventory yet","Capture a batch or import your inventory to prepare listings.")}
  items(active.size) {index->val item=active[index];Column {Text(item.title.ifBlank {"Untitled copy"},style=MaterialTheme.typography.titleMedium);Text(item.itemId,style=MaterialTheme.typography.bodySmall);TextButton(onClick={vm.action {vm.repo.prepareListing(item.groupId);vm.message="Local listing plan saved; no upload started"}}) {Text("Prepare local listing")};plans.firstOrNull {it.groupId==item.groupId}?.let {p->StatusLabel(p.state);Text(org.json.JSONArray(p.errorsJson).toString(),style=MaterialTheme.typography.bodySmall)}}}
 }
}
