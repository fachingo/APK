package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ca.canadavhs.snapmobile.core.newId

@Entity(tableName="pricing_evidence_native",indices=[Index("titleKey"),Index("barcode"),Index(value=["sourceId","collectionId"],unique=true)])
data class PricingEvidenceRow(@PrimaryKey val id:String,val sourceId:String,val collectionId:String,val datasetVersion:Int,
    val queriedTitle:String,val titleKey:String,val barcode:String="",val currency:String="CAD",val status:String,
    val searchUrl:String,val collectedAt:String,val expiresAt:String,val listingsJson:String,val evidenceHash:String,val importedAt:Long=System.currentTimeMillis())
@Entity(tableName="chat_conversations")
data class ChatConversationRow(@PrimaryKey val id:String=newId(),val title:String,val provider:String,val model:String,val authMode:String,
    val instructions:String="",val toolsEnabled:Boolean=false,val proposalsEnabled:Boolean=false,val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=createdAt)
@Entity(tableName="chat_messages",foreignKeys=[ForeignKey(entity=ChatConversationRow::class,parentColumns=["id"],childColumns=["conversationId"],onDelete=ForeignKey.CASCADE)],indices=[Index("conversationId"),Index("createdAt")])
data class ChatMessageRow(@PrimaryKey val id:String=newId(),val conversationId:String,val role:String,val body:String="",
    val attachmentsJson:String="[]",val toolCallsJson:String="[]",val toolCallId:String="",val status:String="COMPLETE",
    val provider:String="",val model:String="",val responseId:String="",val inputTokens:Long?=null,val outputTokens:Long?=null,
    val costUsd:String?=null,@ColumnInfo(defaultValue="'{}'") val usageJson:String="{}",val error:String="",val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=createdAt)
@Entity(tableName="ai_capabilities",indices=[Index(value=["provider","model"],unique=true)])
data class AiCapabilityRow(@PrimaryKey val id:String,val provider:String,val model:String,val displayName:String,
    val supportsText:Boolean?=null,val supportsTools:Boolean?=null,val supportsImages:Boolean?=null,val supportsFiles:Boolean?=null,
    val inputUsdPerToken:String?=null,val outputUsdPerToken:String?=null,val contextWindow:Int?=null,val origin:String="UNVERIFIED",val checkedAt:Long=System.currentTimeMillis())
@Entity(tableName="agent_approvals",foreignKeys=[ForeignKey(entity=ChatConversationRow::class,parentColumns=["id"],childColumns=["conversationId"],onDelete=ForeignKey.CASCADE)],indices=[Index("conversationId")])
data class AgentApprovalRow(@PrimaryKey val id:String=newId(),val conversationId:String,val action:String,val payloadJson:String,
    val bindingHash:String,val state:String="PENDING",val resultJson:String="",val createdAt:Long=System.currentTimeMillis(),val completedAt:Long?=null)
@Entity(tableName="listing_plans",foreignKeys=[ForeignKey(entity=InventoryRow::class,parentColumns=["groupId"],childColumns=["groupId"])],indices=[Index("groupId"),Index(value=["groupId","inputFingerprint"],unique=true)])
data class ListingPlanRow(@PrimaryKey val id:String=newId(),val groupId:String,val itemId:String,val inventoryRevision:Long,
    val inputFingerprint:String,val payloadJson:String,val errorsJson:String,val state:String,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="siwc_proofs")
data class SiwcProofRow(@PrimaryKey val id:String=newId(),val stage:String,val detail:String,val physicalPixel:Boolean=false,val createdAt:Long=System.currentTimeMillis())

@Dao interface NextDao {
    @Query("SELECT * FROM pricing_evidence_native ORDER BY importedAt DESC") fun watchEvidence():Flow<List<PricingEvidenceRow>>
    @Query("SELECT * FROM pricing_evidence_native WHERE titleKey=:key ORDER BY collectedAt DESC,importedAt DESC") suspend fun evidenceForTitle(key:String):List<PricingEvidenceRow>
    @Query("SELECT * FROM pricing_evidence_native WHERE id=:id") suspend fun evidence(id:String):PricingEvidenceRow?
    @Insert suspend fun insert(row:PricingEvidenceRow)
    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC") fun watchChats():Flow<List<ChatConversationRow>>
    @Query("SELECT * FROM chat_conversations WHERE id=:id") suspend fun chat(id:String):ChatConversationRow?
    @Insert suspend fun insert(row:ChatConversationRow)
    @Update suspend fun update(row:ChatConversationRow)
    @Query("DELETE FROM chat_conversations WHERE id=:id") suspend fun deleteChat(id:String)
    @Query("SELECT * FROM chat_messages WHERE conversationId=:id ORDER BY createdAt,id") fun watchMessages(id:String):Flow<List<ChatMessageRow>>
    @Query("SELECT * FROM chat_messages WHERE conversationId=:id ORDER BY createdAt,id") suspend fun messages(id:String):List<ChatMessageRow>
    @Query("SELECT * FROM chat_messages WHERE id=:id") suspend fun message(id:String):ChatMessageRow?
    @Query("SELECT * FROM chat_messages WHERE status IN ('PENDING','STREAMING')") suspend fun interruptedMessages():List<ChatMessageRow>
    @Insert suspend fun insert(row:ChatMessageRow)
    @Update suspend fun update(row:ChatMessageRow)
    @Query("SELECT DISTINCT conversationId FROM chat_messages WHERE body LIKE :query ESCAPE '\\' LIMIT 200") suspend fun searchMessages(query:String):List<String>
    @Query("SELECT * FROM ai_capabilities WHERE provider=:provider ORDER BY displayName,model") suspend fun models(provider:String):List<AiCapabilityRow>
    @Query("SELECT * FROM ai_capabilities WHERE provider=:provider AND model=:model") suspend fun capability(provider:String,model:String):AiCapabilityRow?
    @Upsert suspend fun save(row:AiCapabilityRow)
    @Query("SELECT * FROM agent_approvals WHERE conversationId=:id ORDER BY createdAt DESC") fun watchApprovals(id:String):Flow<List<AgentApprovalRow>>
    @Query("SELECT * FROM agent_approvals WHERE id=:id") suspend fun approval(id:String):AgentApprovalRow?
    @Insert suspend fun insert(row:AgentApprovalRow)
    @Update suspend fun update(row:AgentApprovalRow)
    @Query("SELECT * FROM listing_plans ORDER BY createdAt DESC") fun watchPlans():Flow<List<ListingPlanRow>>
    @Query("SELECT * FROM listing_plans WHERE groupId=:id AND inputFingerprint=:fingerprint") suspend fun plan(id:String,fingerprint:String):ListingPlanRow?
    @Query("SELECT * FROM listing_plans WHERE id=:id") suspend fun planById(id:String):ListingPlanRow?
    @Insert suspend fun insert(row:ListingPlanRow)
    @Query("SELECT * FROM siwc_proofs ORDER BY createdAt DESC LIMIT 100") fun watchProofs():Flow<List<SiwcProofRow>>
    @Insert suspend fun insert(row:SiwcProofRow)
}
val MIGRATION_3_4=object:Migration(3,4) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pricing_evidence_native (id TEXT NOT NULL PRIMARY KEY,sourceId TEXT NOT NULL,collectionId TEXT NOT NULL,datasetVersion INTEGER NOT NULL,queriedTitle TEXT NOT NULL,titleKey TEXT NOT NULL,barcode TEXT NOT NULL,currency TEXT NOT NULL,status TEXT NOT NULL,searchUrl TEXT NOT NULL,collectedAt TEXT NOT NULL,expiresAt TEXT NOT NULL,listingsJson TEXT NOT NULL,evidenceHash TEXT NOT NULL,importedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pricing_evidence_native_titleKey ON pricing_evidence_native(titleKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pricing_evidence_native_barcode ON pricing_evidence_native(barcode)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pricing_evidence_native_sourceId_collectionId ON pricing_evidence_native(sourceId,collectionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_conversations (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,provider TEXT NOT NULL,model TEXT NOT NULL,authMode TEXT NOT NULL,instructions TEXT NOT NULL,toolsEnabled INTEGER NOT NULL,proposalsEnabled INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (id TEXT NOT NULL PRIMARY KEY,conversationId TEXT NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,attachmentsJson TEXT NOT NULL,toolCallsJson TEXT NOT NULL,toolCallId TEXT NOT NULL,status TEXT NOT NULL,provider TEXT NOT NULL,model TEXT NOT NULL,responseId TEXT NOT NULL,inputTokens INTEGER,outputTokens INTEGER,costUsd TEXT,error TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(conversationId) REFERENCES chat_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_conversationId ON chat_messages(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_createdAt ON chat_messages(createdAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_capabilities (id TEXT NOT NULL PRIMARY KEY,provider TEXT NOT NULL,model TEXT NOT NULL,displayName TEXT NOT NULL,supportsText INTEGER,supportsTools INTEGER,supportsImages INTEGER,supportsFiles INTEGER,inputUsdPerToken TEXT,outputUsdPerToken TEXT,contextWindow INTEGER,origin TEXT NOT NULL,checkedAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ai_capabilities_provider_model ON ai_capabilities(provider,model)")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_approvals (id TEXT NOT NULL PRIMARY KEY,conversationId TEXT NOT NULL,action TEXT NOT NULL,payloadJson TEXT NOT NULL,bindingHash TEXT NOT NULL,state TEXT NOT NULL,resultJson TEXT NOT NULL,createdAt INTEGER NOT NULL,completedAt INTEGER,FOREIGN KEY(conversationId) REFERENCES chat_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_approvals_conversationId ON agent_approvals(conversationId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS listing_plans (id TEXT NOT NULL PRIMARY KEY,groupId TEXT NOT NULL,itemId TEXT NOT NULL,inventoryRevision INTEGER NOT NULL,inputFingerprint TEXT NOT NULL,payloadJson TEXT NOT NULL,errorsJson TEXT NOT NULL,state TEXT NOT NULL,createdAt INTEGER NOT NULL,FOREIGN KEY(groupId) REFERENCES inventory(groupId) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listing_plans_groupId ON listing_plans(groupId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_listing_plans_groupId_inputFingerprint ON listing_plans(groupId,inputFingerprint)")
        db.execSQL("CREATE TABLE IF NOT EXISTS siwc_proofs (id TEXT NOT NULL PRIMARY KEY,stage TEXT NOT NULL,detail TEXT NOT NULL,physicalPixel INTEGER NOT NULL,createdAt INTEGER NOT NULL)")
    }
}
