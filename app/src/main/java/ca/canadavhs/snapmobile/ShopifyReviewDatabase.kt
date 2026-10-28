package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ca.canadavhs.snapmobile.core.newId

/** A pending review never constitutes authorization for background execution. */
@Entity(tableName="shopify_reviews",indices=[Index("createdAt")])
data class ShopifyReviewRow(@PrimaryKey val id:String=newId(),val kind:String,val payloadJson:String,val bindingHash:String,
    val state:String="PENDING",val resultJson:String="{}",val createdAt:Long=System.currentTimeMillis(),val completedAt:Long?=null)
val MIGRATION_7_8=object:Migration(7,8) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS shopify_reviews (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,payloadJson TEXT NOT NULL,bindingHash TEXT NOT NULL,state TEXT NOT NULL,resultJson TEXT NOT NULL,createdAt INTEGER NOT NULL,completedAt INTEGER)")
        db.execSQL("UPDATE agent_approvals SET state='EXPIRED',resultJson='{\"error\":\"Upgrade requires a fresh review of barcode evidence and photographs\"}',completedAt=CAST(strftime('%s','now') AS INTEGER)*1000 WHERE state='PENDING'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shopify_reviews_createdAt ON shopify_reviews(createdAt)")
    }
}
