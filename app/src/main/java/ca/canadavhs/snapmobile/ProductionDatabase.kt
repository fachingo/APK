package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ca.canadavhs.snapmobile.core.newId

@Entity(tableName="production_jobs",indices=[Index("createdAt")])
data class ProductionJobRow(@PrimaryKey val id:String=newId(),val kind:String,val optionsJson:String="{}",val state:String="QUEUED",val error:String="",val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=createdAt)
@Entity(tableName="production_items",indices=[Index("jobId"),Index("groupId"),Index(value=["jobId","groupId"],unique=true)])
data class ProductionItemRow(@PrimaryKey val id:String=newId(),val jobId:String,val groupId:String,val revision:Long,val state:String="QUEUED",val resultJson:String="{}",val error:String="")
@Entity(tableName="shopify_channel_states",indices=[Index("productId")])
data class ShopifyChannelStateRow(@PrimaryKey val id:String,val productId:String,val publicationId:String,val name:String,val published:Boolean,val verifiedAt:Long,val error:String="")
@Dao interface ProductionDao {
 @Query("SELECT * FROM shopify_channel_states ORDER BY verifiedAt DESC") fun watchChannels():Flow<List<ShopifyChannelStateRow>>
 @Upsert suspend fun save(row:ShopifyChannelStateRow)

 @Query("SELECT DISTINCT j.* FROM production_jobs j JOIN production_items p ON p.jobId=j.id JOIN groups g ON g.id=p.groupId WHERE (:sessionId IS NULL OR g.sessionId=:sessionId) AND p.groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') ORDER BY j.createdAt DESC LIMIT 100") fun watchBatchJobs(sessionId:String?):Flow<List<ProductionJobRow>>
 @Query("SELECT * FROM production_items WHERE jobId=:id AND groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') ORDER BY rowid") fun watchJobItems(id:String):Flow<List<ProductionItemRow>>
 @Query("SELECT * FROM production_jobs ORDER BY createdAt DESC LIMIT 100") fun watchJobs():Flow<List<ProductionJobRow>>
 @Query("SELECT * FROM production_items WHERE groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') AND rowid IN (SELECT MAX(i.rowid) FROM production_items i JOIN production_jobs j ON j.id=i.jobId GROUP BY i.groupId,j.kind) ORDER BY rowid DESC") fun watchItems():Flow<List<ProductionItemRow>>
 @Query("SELECT * FROM production_jobs WHERE id=:id") suspend fun job(id:String):ProductionJobRow?
 @Query("SELECT * FROM production_jobs WHERE state IN ('QUEUED','RUNNING')") suspend fun unfinished():List<ProductionJobRow>
 @Query("SELECT * FROM production_items WHERE jobId=:id ORDER BY rowid") suspend fun items(id:String):List<ProductionItemRow>
 @Query("SELECT * FROM production_items WHERE id=:id") suspend fun item(id:String):ProductionItemRow?
 @Insert suspend fun insert(row:ProductionJobRow)
 @Insert suspend fun insert(row:ProductionItemRow)
 @Update suspend fun update(row:ProductionJobRow)
 @Update suspend fun update(row:ProductionItemRow)
}
val MIGRATION_10_11=object:Migration(10,11) {
 override fun migrate(db:SupportSQLiteDatabase) {
 db.execSQL("CREATE TABLE IF NOT EXISTS shopify_channel_states (id TEXT NOT NULL PRIMARY KEY,productId TEXT NOT NULL,publicationId TEXT NOT NULL,name TEXT NOT NULL,published INTEGER NOT NULL,verifiedAt INTEGER NOT NULL,error TEXT NOT NULL)")
 db.execSQL("CREATE INDEX IF NOT EXISTS index_shopify_channel_states_productId ON shopify_channel_states(productId)")
 db.execSQL("CREATE TABLE IF NOT EXISTS production_jobs (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,optionsJson TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
 db.execSQL("CREATE INDEX IF NOT EXISTS index_production_jobs_createdAt ON production_jobs(createdAt)")
 db.execSQL("CREATE TABLE IF NOT EXISTS production_items (id TEXT NOT NULL PRIMARY KEY,jobId TEXT NOT NULL,groupId TEXT NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,resultJson TEXT NOT NULL,error TEXT NOT NULL)")
 db.execSQL("CREATE INDEX IF NOT EXISTS index_production_items_jobId ON production_items(jobId)")
 db.execSQL("CREATE INDEX IF NOT EXISTS index_production_items_groupId ON production_items(groupId)")
 db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_production_items_jobId_groupId ON production_items(jobId,groupId)")
 }
}
