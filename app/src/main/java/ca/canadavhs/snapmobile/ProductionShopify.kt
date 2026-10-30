package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*

internal data class DraftSubmission(val jobs:List<String>,val skipped:Map<String,String>)
/** Called only by the operator's deliberate Create Shopify Drafts action. AI tools retain their review path. */
internal suspend fun CaptureRepository.submitSelectedDrafts(ids:Set<String>,health:JSONObject,base:String,location:String,channels:Set<String>):DraftSubmission {
 require(ids.size in 1..2000);requireShopifyWriteHealth(health)
 val allowed=health.getJSONArray("publications").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()};require(channels.all {it in allowed})
 val admitted=mutableListOf<String>();val skipped=linkedMapOf<String,String>()
 val jobIds=mutate {
  val visible=journal.inventoryDao().items().filter {!it.archived}.map {it.groupId}.toSet()
  for(id in ids) {currentCoroutineContext().ensureActive();try {
   check(id in visible) {"Deleted or archived record"};val item=journal.inventoryDao().item(id) ?: error("Record missing")
   val photos=dao.photos(id).filter {!it.deleted}.sortedBy {it.position}
   // The action authorizes validation/readiness for this draft; no per-item readiness checkbox is required.
   val problems=shopifyProblems(item.copy(ready=true),photos)
   check(problems.isEmpty()) {problems.joinToString(". ")};check(journal.shopifyDao().unfinishedForGroup(id).isEmpty()) {"An unfinished Shopify job owns this copy; resume it instead"}
   if(item.barcode.isBlank()) check(barcodeInfo(dao.group(id),photos).getString("status") !in listOf("SCANNING","SCAN_ERROR","AMBIGUOUS")) {"Resolve barcode recognition"}
   photos.forEach {check(hashFile(original(it.id))==it.sha256) {"Original photograph checksum changed"}}
   if(!item.ready) {val ready=item.copy(ready=true,revision=item.revision+1,updatedAt=java.time.OffsetDateTime.now().toString());journal.inventoryDao().update(ready);inventoryAudit(item,ready,"DRAFT_ACTION_VALIDATED")}
   preferences.edit().putStringSet("draftChannels.$id",channels).commit();admitted+=id
  } catch(e:CancellationException) {throw e} catch(e:Exception) {skipped[id]=e.message.orEmpty().take(700)}}
  admitted.chunked(100).map {queueShopifyLocked(it.toSet(),health,base,location)}
 }
 jobIds.forEach {enqueueShopify(it)};return DraftSubmission(jobIds,skipped)
}
internal fun CaptureRepository.savedPublicationDefaults(health:JSONObject):Set<String> {
 val pubs=health.getJSONArray("publications");val available=(0 until pubs.length()).map {pubs.getJSONObject(it)}
 val saved=preferences.getStringSet("shopifyDefaultPublications",null)
 return (saved ?: available.filter {it.getString("name").equals("Online Store",true)}.map {it.getString("id")}.toSet()).intersect(available.map {it.getString("id")}.toSet())
}
internal fun CaptureRepository.savePublicationDefaults(ids:Set<String>,health:JSONObject) {
 val all=health.getJSONArray("publications").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}.toSet()};require(ids.all {it in all});check(preferences.edit().putStringSet("shopifyDefaultPublications",ids.toSet()).commit())
}
