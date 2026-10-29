package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import ca.canadavhs.snapmobile.core.newId

internal const val TRASH_RETENTION=7L*24*60*60*1000
@Entity(tableName="trash_entries",indices=[Index(value=["kind","targetId"],unique=true)])
data class TrashRow(@PrimaryKey val id:String=newId(),val kind:String,val targetId:String,val parentId:String="",val label:String,val beforeJson:String="{}",val deletedAt:Long=System.currentTimeMillis(),val expiresAt:Long=deletedAt+TRASH_RETENTION,val state:String="ACTIVE")
@Dao interface TrashDao {
    @Query("SELECT * FROM trash_entries WHERE state='ACTIVE' ORDER BY deletedAt DESC") fun watch():Flow<List<TrashRow>>
    @Query("SELECT * FROM trash_entries") fun watchAll():Flow<List<TrashRow>>
    @Query("SELECT * FROM trash_entries WHERE id=:id") suspend fun get(id:String):TrashRow?
    @Query("SELECT * FROM trash_entries WHERE kind=:kind AND targetId=:target") suspend fun target(kind:String,target:String):TrashRow?
    @Insert suspend fun insert(row:TrashRow)
    @Query("DELETE FROM trash_entries WHERE id=:id") suspend fun remove(id:String)
    @Query("SELECT * FROM trash_entries WHERE parentId=:id") suspend fun children(id:String):List<TrashRow>
    @Query("UPDATE trash_entries SET state='EXPIRED' WHERE expiresAt<=:now") suspend fun expire(now:Long)
}
val MIGRATION_9_10=object:Migration(9,10) {override fun migrate(db:SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS trash_entries (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,targetId TEXT NOT NULL,parentId TEXT NOT NULL,label TEXT NOT NULL,beforeJson TEXT NOT NULL,deletedAt INTEGER NOT NULL,expiresAt INTEGER NOT NULL,state TEXT NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_trash_entries_kind_targetId ON trash_entries(kind,targetId)")
    // Previously removed photos remain recoverable after upgrading, with a fresh recovery window.
    val now=System.currentTimeMillis()
    db.execSQL("INSERT INTO trash_entries (id,kind,targetId,parentId,label,beforeJson,deletedAt,expiresAt,state) SELECT id,'PHOTO',id,'',role || ' photograph','{}',?,?,'ACTIVE' FROM photos WHERE deleted=1",arrayOf<Any>(now,now+TRASH_RETENTION))
}}
internal suspend fun CaptureRepository.trashLocal(kind:String,target:String) {
    mutate {check(dao.pending().isEmpty()) {"Wait for photographs to finish saving."};check(kind in listOf("INVENTORY","BATCH","PHOTO","DRAFT","DRAFT_ITEM"));check(journal.trashDao().target(kind,target)==null) {"Already in Trash."}
        val groups=when(kind) {"BATCH"->dao.groups(target).map {it.id};"INVENTORY"->listOf(target);"PHOTO"->listOf(dao.photo(target).groupId);else->emptyList()}
        for(group in groups) {dao.group(group).archiveId?.let {check(dao.archive(it).state!="BUILDING") {"Wait for archive creation before deleting photographs."}};check(dao.recognitions(group).none {it.state=="RUNNING"}) {"Stop recognition before deleting this record."};check(journal.shopifyDao().unfinishedForGroup(group).none {it.state in listOf("RUNNING","WAITING_REMOTE")}) {"Pause the Shopify operation before deleting this record."}}
        suspend fun save(k:String,id:String,parent:String=""):TrashRow {
            val before=JSONObject();val label=when(k) {
                "INVENTORY"->{val item=journal.inventoryDao().item(id) ?: error("Inventory missing");before.put("archived",item.archived);archiveInventoryLocked(id,true);item.title.ifBlank {item.itemId}}
                "PHOTO"->{val p=dao.photo(id);check(!p.deleted) {"Photo already removed."};before.put("deleted",p.deleted);dao.update(p.copy(deleted=true));invalidateInventory(p.groupId,"PHOTO_TRASHED");"${p.role} photograph"}
                "BATCH"->{val session=dao.session(id);check(dao.archives(id).none {it.state=="BUILDING"}) {"Wait for archive creation before deleting this batch."};dao.update(session.copy(finished=true));session.name}
                "DRAFT_ITEM"->{val entry=journal.shopifyDao().item(id) ?: error("Draft record missing");check(entry.state !in listOf("RUNNING","WAITING_REMOTE")) {"Pause this operation first."};before.put("state",entry.state);if(entry.state!="SUCCESS") journal.shopifyDao().update(entry.copy(state="CANCELLED"));entry.itemId}
                else->{val job=journal.shopifyDao().job(id) ?: error("Local draft job missing");check(job.state !in listOf("RUNNING","WAITING_REMOTE")) {"Pause this operation before deleting its local record."};before.put("state",job.state);journal.shopifyDao().update(job.copy(state="CANCELLED",updatedAt=System.currentTimeMillis()));journal.shopifyDao().items(id).filter {it.state!="SUCCESS"}.forEach {journal.shopifyDao().update(it.copy(state="CANCELLED"))};"${job.kind} · ${job.shop}"}
            }
            return TrashRow(kind=k,targetId=id,parentId=parent,label=label,beforeJson=before.toString()).also {journal.trashDao().insert(it)}
        }
        val root=save(kind,target)
        if(kind=="INVENTORY") {
            val group=dao.group(target)
            if(!group.closed && dao.active()?.id==group.sessionId) {
                dao.update(group.copy(closed=true))
                dao.insert(GroupRow(sessionId=group.sessionId,number=dao.groups(group.sessionId).maxOf {it.number}+1,location=dao.session(group.sessionId).location))
            }
        }
        if(kind in listOf("DRAFT","DRAFT_ITEM")) {
            val entries=if(kind=="DRAFT") journal.shopifyDao().items(target) else listOf(journal.shopifyDao().item(target)!!)
            entries.forEach {entry->val productId=JSONObject(entry.remoteJson).optString("productId");if(productId.isNotBlank()) {val productKey=stableRowId(productId);if(journal.trashDao().target("PRODUCT",productKey)==null) journal.trashDao().insert(TrashRow(kind="PRODUCT",targetId=productKey,parentId=root.id,label=entry.itemId,beforeJson=JSONObject().put("productId",productId).toString()))}}
        }
        if(kind=="BATCH") groups.forEach {group->if(journal.inventoryDao().item(group)!=null && journal.trashDao().target("INVENTORY",group)==null) save("INVENTORY",group,root.id)}
        if(kind in listOf("INVENTORY","BATCH")) groups.forEach {group->
            dao.photos(group).filter {!it.deleted}.forEach {if(journal.trashDao().target("PHOTO",it.id)==null) save("PHOTO",it.id,root.id)}
            dao.recognitions(group).filter {it.state=="QUEUED"}.forEach {dao.update(it.copy(state="CANCELLED",error="Moved to local Trash; no retry.",completedAt=System.currentTimeMillis()))}
            journal.shopifyDao().unfinishedForGroup(group).forEach {journal.shopifyDao().update(it.copy(state="CANCELLED",error="Local inventory moved to Trash; remote product retained."))}
        }
    }
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("trash-expiry",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<TrashExpiryWorker>(1,TimeUnit.DAYS).setInitialDelay(7,TimeUnit.DAYS).build())
}
internal suspend fun CaptureRepository.restoreTrash(id:String)=mutate {
    val root=journal.trashDao().get(id) ?: error("Trash entry missing");check(root.parentId.isBlank()) {"Recover the containing batch or record first."};check(root.state=="ACTIVE" && System.currentTimeMillis()<root.expiresAt) {"The seven-day recovery period has expired."}
    if(root.kind in listOf("PHOTO","INVENTORY")) {val group=if(root.kind=="PHOTO") dao.photo(root.targetId).groupId else root.targetId
        check(journal.trashDao().target("BATCH",dao.group(group).sessionId)==null) {"Recover the containing batch first."}
        if(root.kind=="PHOTO") check(journal.trashDao().target("INVENTORY",group)==null) {"Recover the containing inventory record first."}
    }
    if(root.kind=="DRAFT_ITEM") check(journal.trashDao().target("DRAFT",journal.shopifyDao().item(root.targetId)!!.jobId)==null) {"Recover the containing local job first."}
    val entries=listOf(root)+journal.trashDao().children(id)
    entries.filter {it.kind=="INVENTORY"}.forEach {archiveInventoryLocked(it.targetId,JSONObject(it.beforeJson).getBoolean("archived"))}
    entries.filter {it.kind=="PHOTO"}.forEach {val p=dao.photo(it.targetId);check(original(p.id).isFile) {"Original photograph is missing."};dao.update(p.copy(deleted=false));invalidateInventory(p.groupId,"PHOTO_RECOVERED")}
    entries.filter {it.kind=="DRAFT_ITEM"}.forEach {val entry=journal.shopifyDao().item(it.targetId)!!;if(JSONObject(it.beforeJson).optString("state")=="SUCCESS") journal.shopifyDao().update(entry.copy(state="SUCCESS"))}
    entries.filter {it.kind=="DRAFT"}.forEach {val job=journal.shopifyDao().job(it.targetId)!!;val old=JSONObject(it.beforeJson).optString("state");journal.shopifyDao().update(job.copy(state=if(old=="SUCCESS") "SUCCESS" else "PAUSED",updatedAt=System.currentTimeMillis()))}
    entries.forEach {journal.trashDao().remove(it.id)}
    // Recovery never restarts uploads, recognition or capture. Drafts remain cancelled history.
}
class TrashExpiryWorker(context:android.content.Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {val repo=(applicationContext as CaptureApplication).repository;repo.mutate {repo.journal.trashDao().expire(System.currentTimeMillis())};return Result.success()}
}

internal suspend fun CaptureRepository.hideShopifyDraft(product:JSONObject)=mutate {
    val id=product.getString("id");require(id.matches(Regex("gid://shopify/Product/[0-9]+")));require(product.getString("status")=="DRAFT")
    val target=stableRowId(id)
    if(journal.trashDao().target("PRODUCT",target)==null) journal.trashDao().insert(TrashRow(kind="PRODUCT",targetId=target,label=product.getString("title"),beforeJson=JSONObject().put("productId",id).toString()))
}

internal fun trashVisibility(table:String):String=when(table) {
    "inventory"->"groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY')"
    "sessions"->"id NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH')"
    "photos"->"id NOT IN (SELECT targetId FROM trash_entries WHERE kind='PHOTO')"
    "groups"->"id NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') AND sessionId NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH')"
    "recognition","listing_plans","price_decisions","inventory_audit"->"groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY')"
    "archives"->"sessionId NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH')"
    "shopify_jobs"->"id NOT IN (SELECT targetId FROM trash_entries WHERE kind='DRAFT')"
    "shopify_job_items"->"id NOT IN (SELECT targetId FROM trash_entries WHERE kind='DRAFT_ITEM') AND jobId NOT IN (SELECT targetId FROM trash_entries WHERE kind='DRAFT') AND groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY')"
    else->"1"
}
