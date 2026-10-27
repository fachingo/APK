package ca.canadavhs.snapmobile

import android.app.Application
import androidx.room.*
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

@Dao
interface JournalDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun watchSessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM groups ORDER BY number") fun watchGroups(): Flow<List<GroupRow>>
    @Query("SELECT * FROM photos ORDER BY position") fun watchPhotos(): Flow<List<PhotoRow>>
    @Query("SELECT * FROM archives ORDER BY part") fun watchArchives(): Flow<List<ArchiveRow>>
    @Query("SELECT * FROM sessions WHERE finished=0 ORDER BY createdAt DESC LIMIT 1") suspend fun active(): SessionRow?
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
@Database(entities = [SessionRow::class, GroupRow::class, PhotoRow::class, ArchiveRow::class, EventRow::class], version = 1, exportSchema = true)
abstract class CaptureJournal : RoomDatabase() { abstract fun dao(): JournalDao }
class CaptureApplication : Application() {
    lateinit var repository: CaptureRepository
    override fun onCreate() {
        super.onCreate()
        val journal = Room.databaseBuilder(this, CaptureJournal::class.java, "capture-journal.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
            }).build()
        repository = CaptureRepository(this, journal)
    }
}
