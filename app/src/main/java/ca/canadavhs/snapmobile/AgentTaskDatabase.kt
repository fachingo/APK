package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ca.canadavhs.snapmobile.core.newId

/** Approved work survives chat deletion; external completion is read from the owning job. */
@Entity(tableName="agent_tasks",indices=[Index(value=["approvalId"],unique=true),Index("createdAt")])
data class AgentTaskRow(@PrimaryKey val id:String=newId(),val approvalId:String,val conversationId:String,val kind:String,val title:String,
    val groupIdsJson:String,val jobIdsJson:String="[]",val state:String="SCHEDULE_PENDING",val resultJson:String="{}",val error:String="",
    val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=createdAt)
@Dao interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC LIMIT 200") fun watch():Flow<List<AgentTaskRow>>
    @Query("SELECT * FROM agent_tasks WHERE id=:id") suspend fun get(id:String):AgentTaskRow?
    @Query("SELECT * FROM agent_tasks WHERE state='SCHEDULE_PENDING' ORDER BY createdAt") suspend fun pending():List<AgentTaskRow>
    @Query("SELECT * FROM agent_tasks WHERE state NOT IN ('CANCELLED','COMPLETE')") suspend fun unsettled():List<AgentTaskRow>
    @Insert suspend fun insert(row:AgentTaskRow)
    @Update suspend fun update(row:AgentTaskRow)
}
val MIGRATION_6_7=object:Migration(6,7) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recognition ADD COLUMN authBinding TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE recognition ADD COLUMN approvalId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE recognition SET state='ERROR',error='Upgrade requires a manual retry of this unbound API request; no billable request restarted.' WHERE provider='OPENAI' AND state IN ('QUEUED','RUNNING')")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY,approvalId TEXT NOT NULL,conversationId TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,groupIdsJson TEXT NOT NULL,jobIdsJson TEXT NOT NULL,state TEXT NOT NULL,resultJson TEXT NOT NULL,error TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_tasks_approvalId ON agent_tasks(approvalId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_tasks_createdAt ON agent_tasks(createdAt)")
    }
}
