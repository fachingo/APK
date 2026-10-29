package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ca.canadavhs.snapmobile.core.newId

@Entity(tableName="shopify_jobs")
data class ShopifyJobRow(@PrimaryKey val id:String=newId(),val kind:String,val shopId:String,val shop:String,val broker:String,val connectionFingerprint:String,
    val locationId:String,val publicationId:String="",val state:String="QUEUED",val error:String="",val workId:String="",val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=createdAt)
@Entity(tableName="shopify_job_items",foreignKeys=[ForeignKey(entity=ShopifyJobRow::class,parentColumns=["id"],childColumns=["jobId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=InventoryRow::class,parentColumns=["groupId"],childColumns=["groupId"])],indices=[Index("jobId"),Index("groupId")])
data class ShopifyItemJobRow(@PrimaryKey val id:String=newId(),val jobId:String,val groupId:String,val itemId:String,val expectedRevision:Long,
    val inputFingerprint:String,val snapshotJson:String,val creationKey:String,val state:String="QUEUED",val step:String="VALIDATE",val remoteJson:String="{}",val checkpointJson:String="{}",
    val error:String="",val attempts:Int=0,val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=createdAt)
@Dao interface ShopifyDao {
    @Query("SELECT * FROM shopify_reviews WHERE NOT EXISTS(SELECT 1 FROM trash_entries t WHERE (t.kind='INVENTORY' AND instr(shopify_reviews.payloadJson,t.targetId)>0) OR (t.kind='DRAFT' AND instr(shopify_reviews.resultJson,t.targetId)>0)) ORDER BY createdAt DESC LIMIT 100") fun watchReviews():Flow<List<ShopifyReviewRow>>
    @Query("SELECT * FROM shopify_reviews WHERE kind='OWNER_ACTION' AND state='APPLIED'") suspend fun ownerReviews():List<ShopifyReviewRow>
    @Query("SELECT * FROM shopify_reviews WHERE id=:id") suspend fun review(id:String):ShopifyReviewRow?
    @Insert suspend fun insert(row:ShopifyReviewRow)
    @Update suspend fun update(row:ShopifyReviewRow)
    @Query("SELECT * FROM shopify_jobs WHERE id NOT IN (SELECT targetId FROM trash_entries WHERE kind='DRAFT') ORDER BY createdAt DESC") fun watchJobs():Flow<List<ShopifyJobRow>>
    @Query("SELECT * FROM shopify_job_items WHERE id NOT IN (SELECT targetId FROM trash_entries WHERE kind='DRAFT_ITEM') AND jobId NOT IN (SELECT targetId FROM trash_entries WHERE kind='DRAFT') AND groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') ORDER BY createdAt DESC") fun watchItems():Flow<List<ShopifyItemJobRow>>
    @Query("SELECT * FROM shopify_jobs WHERE id=:id") suspend fun job(id:String):ShopifyJobRow?
    @Query("SELECT * FROM shopify_job_items WHERE id=:id") suspend fun item(id:String):ShopifyItemJobRow?
    @Query("SELECT * FROM shopify_job_items WHERE jobId=:id ORDER BY createdAt,id") suspend fun items(id:String):List<ShopifyItemJobRow>
    @Query("SELECT * FROM shopify_jobs WHERE state IN ('QUEUED','RUNNING','WAITING_REMOTE')") suspend fun active():List<ShopifyJobRow>
    @Query("SELECT * FROM shopify_job_items WHERE groupId=:id AND state NOT IN ('SUCCESS','CANCELLED','SKIPPED')") suspend fun unfinishedForGroup(id:String):List<ShopifyItemJobRow>
    @Insert suspend fun insert(row:ShopifyJobRow)
    @Insert suspend fun insert(row:ShopifyItemJobRow)
    @Update suspend fun update(row:ShopifyJobRow)
    @Update suspend fun update(row:ShopifyItemJobRow)
}
val MIGRATION_4_5=object:Migration(4,5) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS shopify_jobs (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,shopId TEXT NOT NULL,shop TEXT NOT NULL,broker TEXT NOT NULL,connectionFingerprint TEXT NOT NULL,locationId TEXT NOT NULL,publicationId TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL,workId TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS shopify_job_items (id TEXT NOT NULL PRIMARY KEY,jobId TEXT NOT NULL,groupId TEXT NOT NULL,itemId TEXT NOT NULL,expectedRevision INTEGER NOT NULL,inputFingerprint TEXT NOT NULL,snapshotJson TEXT NOT NULL,creationKey TEXT NOT NULL,state TEXT NOT NULL,step TEXT NOT NULL,remoteJson TEXT NOT NULL,checkpointJson TEXT NOT NULL,error TEXT NOT NULL,attempts INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(jobId) REFERENCES shopify_jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(groupId) REFERENCES inventory(groupId) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shopify_job_items_jobId ON shopify_job_items(jobId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shopify_job_items_groupId ON shopify_job_items(groupId)")
    }
}
