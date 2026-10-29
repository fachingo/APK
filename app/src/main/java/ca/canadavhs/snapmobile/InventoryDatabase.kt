package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.OffsetDateTime
import ca.canadavhs.snapmobile.core.newId

@Entity(tableName = "inventory_batches", foreignKeys = [ForeignKey(entity = SessionRow::class, parentColumns = ["id"], childColumns = ["sessionId"])], indices = [Index(value = ["batchId"], unique = true)])
data class InventoryBatchRow(@PrimaryKey val sessionId: String, val batchId: String)

@Entity(tableName = "inventory", foreignKeys = [ForeignKey(entity = GroupRow::class, parentColumns = ["id"], childColumns = ["groupId"])], indices = [Index(value = ["itemId"], unique = true), Index("batchId"), Index("title")])
data class InventoryRow(
    @PrimaryKey val groupId: String, val itemId: String, val batchId: String,
    val title: String = "", val releaseYear: Int? = null, val edition: String = "", val distributor: String = "",
    val condition: String = "Not Graded", val conditionNotes: String = "", val location: String = "",
    val barcode: String = "", val priceCents: Long? = null, val discountBasisPoints: Int = 0,
    val quantity: Int = 1, val poolMode: String = "POOLED", val vendor: String = "Canada VHS",
    val productType: String = "VHS Tape", val tags: String = "", val description: String = "",
    val weightGrams: Int = 250, val rare: Boolean = false, val review: Boolean = false,
    val ready: Boolean = false, val archived: Boolean = false, val priceSource: String = "UNPRICED",
    val priceEvidenceJson: String = "{}", val recognitionId: String = "", val captureNote: String = "",
    val shopifyProductId: String = "", val shopifyVariantId: String = "", val shopifyInventoryItemId: String = "",
    val shopifyState: String = "NOT_UPLOADED", val revision: Long = 0,
    val createdAt: String = OffsetDateTime.now().toString(), val updatedAt: String = createdAt
)
@Entity(tableName = "inventory_audit", indices = [Index("groupId")])
data class InventoryAuditRow(@PrimaryKey val id: String = newId(), val groupId: String,
    val action: String, val beforeJson: String, val afterJson: String, val occurredAt: String = OffsetDateTime.now().toString())
@Entity(tableName = "transfer_jobs")
data class TransferJobRow(@PrimaryKey val id: String = newId(), val kind: String, val state: String = "RUNNING",
    val completed: Int = 0, val total: Int = 0, val filename: String = "", val sha256: String = "", val error: String = "",
    val createdAt: String = OffsetDateTime.now().toString(), val updatedAt: String = createdAt)

@Dao interface InventoryDao {
    @Query("SELECT * FROM inventory WHERE groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') ORDER BY createdAt DESC,itemId") fun watchItems(): Flow<List<InventoryRow>>
    @Query("SELECT * FROM inventory WHERE groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') ORDER BY itemId") suspend fun items(): List<InventoryRow>
    @Query("SELECT * FROM inventory WHERE groupId=:id") suspend fun item(id: String): InventoryRow?
    @Query("SELECT * FROM inventory WHERE itemId=:id") suspend fun itemById(id: String): InventoryRow?
    @Query("SELECT * FROM inventory_batches WHERE sessionId=:sessionId") suspend fun batch(sessionId: String): InventoryBatchRow?
    @Insert suspend fun insert(row: InventoryBatchRow)
    @Insert suspend fun insert(row: InventoryRow)
    @Update suspend fun update(row: InventoryRow)
    @Insert suspend fun insert(row: InventoryAuditRow)
    @Query("SELECT * FROM inventory_audit WHERE groupId=:id ORDER BY occurredAt DESC,id DESC LIMIT 100") suspend fun history(id: String): List<InventoryAuditRow>
    @Query("SELECT * FROM transfer_jobs ORDER BY createdAt DESC LIMIT 40") fun watchJobs(): Flow<List<TransferJobRow>>
    @Query("SELECT * FROM transfer_jobs WHERE id=:id") suspend fun job(id: String): TransferJobRow?
    @Query("SELECT * FROM transfer_jobs WHERE state='RUNNING'") suspend fun unfinished(): List<TransferJobRow>
    @Insert suspend fun insert(row: TransferJobRow)
    @Update suspend fun update(row: TransferJobRow)
}

val MIGRATION_2_3 = object : Migration(2,3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS inventory_batches (sessionId TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_inventory_batches_batchId ON inventory_batches(batchId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS inventory (
            groupId TEXT NOT NULL PRIMARY KEY, itemId TEXT NOT NULL, batchId TEXT NOT NULL,
            title TEXT NOT NULL, releaseYear INTEGER, edition TEXT NOT NULL, distributor TEXT NOT NULL,
            condition TEXT NOT NULL, conditionNotes TEXT NOT NULL, location TEXT NOT NULL, barcode TEXT NOT NULL,
            priceCents INTEGER, discountBasisPoints INTEGER NOT NULL, quantity INTEGER NOT NULL,
            poolMode TEXT NOT NULL, vendor TEXT NOT NULL, productType TEXT NOT NULL, tags TEXT NOT NULL,
            description TEXT NOT NULL, weightGrams INTEGER NOT NULL, rare INTEGER NOT NULL, review INTEGER NOT NULL,
            ready INTEGER NOT NULL, archived INTEGER NOT NULL, priceSource TEXT NOT NULL, priceEvidenceJson TEXT NOT NULL,
            recognitionId TEXT NOT NULL, captureNote TEXT NOT NULL, shopifyProductId TEXT NOT NULL,
            shopifyVariantId TEXT NOT NULL, shopifyInventoryItemId TEXT NOT NULL, shopifyState TEXT NOT NULL,
            revision INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL,
            FOREIGN KEY(groupId) REFERENCES groups(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_inventory_itemId ON inventory(itemId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_batchId ON inventory(batchId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_title ON inventory(title)")
        db.execSQL("CREATE TABLE IF NOT EXISTS inventory_audit (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, action TEXT NOT NULL, beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL, occurredAt TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_audit_groupId ON inventory_audit(groupId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_jobs (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, filename TEXT NOT NULL, sha256 TEXT NOT NULL, error TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)")
        // Identity backfill uses the original local timestamp and stable UUID prefix.
        db.execSQL("""INSERT INTO inventory_batches SELECT id,
            replace(substr(createdAt,1,10),'-','')||'-'||replace(substr(createdAt,12,8),':','')||'-M'||substr(replace(id,'-',''),1,12) FROM sessions""")
        db.execSQL("""INSERT INTO inventory SELECT g.id,b.batchId||'-'||g.location||'-'||printf('%03d',g.number),b.batchId,
            '',NULL,'','','Not Graded','',g.location,'',NULL,0,1,CASE WHEN g.rare=1 THEN 'UNIQUE' ELSE 'POOLED' END,
            'Canada VHS','VHS Tape','','',250,g.rare,g.review,0,0,'UNPRICED','{}','',g.note,'','','','NOT_UPLOADED',0,g.createdAt,g.createdAt
            FROM groups g JOIN inventory_batches b ON b.sessionId=g.sessionId WHERE EXISTS(SELECT 1 FROM photos p WHERE p.groupId=g.id)""")
    }
}
