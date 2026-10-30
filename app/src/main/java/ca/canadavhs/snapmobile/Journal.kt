package ca.canadavhs.snapmobile

import android.app.Application
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import ca.canadavhs.snapmobile.core.newId
import java.time.OffsetDateTime

@Entity(tableName = "sessions")
data class SessionRow(@PrimaryKey val id: String = newId(), val name: String, val createdAt: String = OffsetDateTime.now().toString(), val location: String = "", val finished: Boolean = false)
@Entity(tableName = "groups", foreignKeys = [ForeignKey(entity = SessionRow::class, parentColumns = ["id"], childColumns = ["sessionId"])], indices = [Index("sessionId"), Index(value = ["sessionId", "number"], unique = true)])
data class GroupRow(@PrimaryKey val id: String = newId(), val sessionId: String, val number: Int, val createdAt: String = OffsetDateTime.now().toString(), val location: String = "", val closed: Boolean = false, val rare: Boolean = false, val review: Boolean = false, val note: String = "", val barcodeOverride: String? = null, val archiveId: String? = null)
@Entity(tableName = "photos", foreignKeys = [ForeignKey(entity = GroupRow::class, parentColumns = ["id"], childColumns = ["groupId"])], indices = [Index("groupId")])
data class PhotoRow(@PrimaryKey val id: String = newId(), val groupId: String, val position: Int, val role: String, val capturedAt: String = OffsetDateTime.now().toString(), val state: String = "RESERVED", val bytes: Long = 0, val sha256: String = "", val deleted: Boolean = false, val barcodeJson: String = "[]", val scanState: String = "PENDING", val error: String = "")
@Entity(tableName = "archives", indices = [Index("sessionId"), Index(value = ["sessionId", "part"], unique = true)])
data class ArchiveRow(@PrimaryKey val id: String = newId(), val sessionId: String, val part: Int, val filename: String, val groupIds: String, val targetBytes: Long = 500_000_000, val manifestJson: String = "", val state: String = "BUILDING", val bytes: Long = 0, val sha256: String = "", val error: String = "")
@Entity(tableName = "events")
data class EventRow(@PrimaryKey val id: String = newId(), val sessionId: String, val groupId: String?, val action: String, val createdAt: String = OffsetDateTime.now().toString())

@Entity(tableName = "recognition", indices = [Index("groupId"), Index("requestedAt")])
data class RecognitionRow(
    @PrimaryKey val id: String = newId(), val groupId: String, val provider: String,
    val model: String, val inputJson: String, val inputFingerprint: String,
    val state: String = "QUEUED", val resultJson: String = "", val error: String = "",
    val acceptedTitle: String? = null, val requestedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null, val workId: String = "",
    @ColumnInfo(defaultValue="''") val authBinding:String="", @ColumnInfo(defaultValue="''") val approvalId:String=""
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS recognition (
            id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, provider TEXT NOT NULL,
            model TEXT NOT NULL, inputJson TEXT NOT NULL, inputFingerprint TEXT NOT NULL,
            state TEXT NOT NULL, resultJson TEXT NOT NULL, error TEXT NOT NULL,
            acceptedTitle TEXT, requestedAt INTEGER NOT NULL, completedAt INTEGER, workId TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_groupId ON recognition(groupId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_requestedAt ON recognition(requestedAt)")
    }
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM recognition WHERE groupId NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') ORDER BY requestedAt DESC, id DESC") fun watchRecognition(): Flow<List<RecognitionRow>>
    @Query("SELECT * FROM recognition WHERE groupId=:group ORDER BY requestedAt DESC,id DESC") suspend fun recognitions(group: String): List<RecognitionRow>
    @Query("SELECT * FROM recognition WHERE id=:id") suspend fun recognition(id: String): RecognitionRow?
    @Query("SELECT * FROM recognition WHERE state IN ('QUEUED','RUNNING')") suspend fun unsettledRecognition(): List<RecognitionRow>
    @Insert suspend fun insert(row: RecognitionRow)
    @Update suspend fun update(row: RecognitionRow)

    @Query("SELECT * FROM sessions WHERE id NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH') ORDER BY createdAt DESC") fun watchSessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM groups WHERE id NOT IN (SELECT targetId FROM trash_entries WHERE kind='INVENTORY') AND sessionId NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH') ORDER BY number") fun watchGroups(): Flow<List<GroupRow>>
    @Query("SELECT * FROM photos WHERE id NOT IN (SELECT targetId FROM trash_entries WHERE kind='PHOTO') ORDER BY position") fun watchPhotos(): Flow<List<PhotoRow>>
    @Query("SELECT * FROM archives WHERE sessionId NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH') ORDER BY part") fun watchArchives(): Flow<List<ArchiveRow>>
    @Query("SELECT * FROM sessions WHERE id NOT IN (SELECT targetId FROM trash_entries WHERE kind='BATCH') AND finished=0 ORDER BY createdAt DESC LIMIT 1") suspend fun active(): SessionRow?
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id: String): SessionRow
    @Query("SELECT * FROM groups WHERE sessionId=:session ORDER BY number") suspend fun groups(session: String): List<GroupRow>
    @Query("SELECT * FROM groups WHERE id=:id") suspend fun group(id: String): GroupRow
    @Query("SELECT * FROM photos WHERE groupId=:group ORDER BY position, capturedAt, id") suspend fun photos(group: String): List<PhotoRow>
    @Query("SELECT * FROM photos WHERE id=:id") suspend fun photo(id: String): PhotoRow
    @Query("SELECT * FROM photos WHERE state='RESERVED'") suspend fun pending(): List<PhotoRow>
    @Query("SELECT * FROM photos WHERE state='READY' AND scanState='PENDING' AND deleted=0") suspend fun pendingScans(): List<PhotoRow>
    @Query("SELECT * FROM archives WHERE sessionId=:session ORDER BY part") suspend fun archives(session: String): List<ArchiveRow>
    @Query("SELECT * FROM archives WHERE state!='READY'") suspend fun unfinishedArchives(): List<ArchiveRow>
    @Query("SELECT * FROM archives WHERE id=:id") suspend fun archive(id: String): ArchiveRow
    @Query("SELECT * FROM events WHERE sessionId=:session ORDER BY createdAt,id") suspend fun events(session: String): List<EventRow>
    @Insert suspend fun insert(row: SessionRow)
    @Insert suspend fun insert(row: GroupRow)
    @Insert suspend fun insert(row: PhotoRow)
    @Insert suspend fun insert(row: ArchiveRow)
    @Insert suspend fun insert(row: EventRow)
    @Update suspend fun update(row: SessionRow)
    @Update suspend fun update(row: GroupRow)
    @Update suspend fun update(row: PhotoRow)
    @Update suspend fun update(row: ArchiveRow)
    @Query("DELETE FROM groups WHERE id=:id AND id NOT IN (SELECT groupId FROM photos)") suspend fun removeEmptyGroup(id: String)
}
@Database(entities = [SessionRow::class, GroupRow::class, PhotoRow::class, ArchiveRow::class, EventRow::class, RecognitionRow::class, InventoryBatchRow::class, InventoryRow::class, InventoryAuditRow::class, TransferJobRow::class, PricingEvidenceRow::class, ChatConversationRow::class, ChatMessageRow::class, AiCapabilityRow::class, AgentApprovalRow::class, ListingPlanRow::class, SiwcProofRow::class, ShopifyJobRow::class, ShopifyItemJobRow::class, MarketSaleRow::class, MarketImportRow::class, PriceDecisionRow::class, MarketSearchRow::class, AgentTaskRow::class, ShopifyReviewRow::class, AgentPolicyRow::class, AgentTraceRow::class, TrashRow::class, ProductionJobRow::class, ProductionItemRow::class, ShopifyChannelStateRow::class], version = 11, exportSchema = true)
abstract class CaptureJournal : RoomDatabase() { abstract fun dao(): JournalDao; abstract fun inventoryDao(): InventoryDao; abstract fun nextDao(): NextDao; abstract fun shopifyDao():ShopifyDao
    abstract fun marketDao():MarketDao; abstract fun agentTaskDao():AgentTaskDao; abstract fun agentPolicyDao():AgentPolicyDao; abstract fun trashDao():TrashDao; abstract fun productionDao():ProductionDao }
class CaptureApplication : Application() {
    lateinit var repository: CaptureRepository
    override fun onCreate() {
        super.onCreate()
        val journal = Room.databaseBuilder(this, CaptureJournal::class.java, "capture-journal.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
            }).build()
        repository = CaptureRepository(this, journal)
    }
}
