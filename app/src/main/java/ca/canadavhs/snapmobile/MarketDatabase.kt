package ca.canadavhs.snapmobile

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ca.canadavhs.snapmobile.core.newId

/** Immutable evidence versions. A new payload supersedes, but never deletes, an older observation. */
@Entity(tableName="market_sales",indices=[Index("sourceKey"),Index("titleKey"),Index("barcode"),Index(value=["current","currency","soldAt"]),Index(value=["current","titleKey","currency"]),Index("payloadHash"),Index("totalCents")])
data class MarketSaleRow(@PrimaryKey val id:String,val sourceKey:String,val payloadHash:String,val recordType:String="IMPORTED_SOLD_COMP",val title:String,val titleKey:String,
    val barcode:String="",val edition:String="",val releaseYear:Int?=null,val soldDecimal:String,val shippingDecimal:String,val totalCents:Long,val currency:String,
    val soldAt:String,val url:String,val site:String,val sourceItemId:String,val collectedAt:String="",val observedAt:String,val importId:String,val rawJson:String,
    val current:Boolean=true,val eligible:Boolean=true)
@Entity(tableName="market_imports")
data class MarketImportRow(@PrimaryKey val id:String=newId(),val fileHash:String,val filename:String,val format:String,val observedAt:String,val total:Long,val added:Long,val duplicates:Long,val versions:Long,val warningsJson:String="[]",val databaseVersion:Int=1)
@Entity(tableName="price_decisions",indices=[Index("titleKey"),Index("groupId"),Index("itemId"),Index("createdAt")])
data class PriceDecisionRow(@PrimaryKey val id:String=newId(),val groupId:String,val itemId:String,val title:String,val titleKey:String,val edition:String="",val releaseYear:Int?=null,val barcode:String="",
    val recordType:String,val priceCents:Long,val currency:String="CAD",val method:String,val evidenceJson:String="[]",val provenanceJson:String="{}",val createdAt:String)
@Entity(tableName="market_searches",indices=[Index("query")])
data class MarketSearchRow(@PrimaryKey val id:String,val query:String,val favorite:Boolean=false,val usedAt:Long=System.currentTimeMillis())
@Dao interface MarketDao {
    @Query("SELECT COUNT(*) FROM market_sales WHERE current=1") fun watchCount():Flow<Long>
    @Query("SELECT COUNT(*) FROM price_decisions") fun watchDecisionCount():Flow<Long>
    @Query("SELECT * FROM market_imports ORDER BY observedAt DESC LIMIT 30") fun watchImports():Flow<List<MarketImportRow>>
    @Query("SELECT * FROM market_searches ORDER BY favorite DESC,usedAt DESC LIMIT 30") fun watchSearches():Flow<List<MarketSearchRow>>
    @Query("SELECT * FROM market_sales WHERE id=:id") suspend fun sale(id:String):MarketSaleRow?
    @Query("SELECT * FROM price_decisions WHERE titleKey LIKE :key ESCAPE '\\' ORDER BY createdAt DESC LIMIT :limit OFFSET :offset") suspend fun decisions(key:String,limit:Int=50,offset:Int=0):List<PriceDecisionRow>
    @Query("SELECT * FROM price_decisions WHERE groupId=:groupId ORDER BY createdAt DESC LIMIT 50") suspend fun decisionsForItem(groupId:String):List<PriceDecisionRow>
    @Query("SELECT * FROM market_imports WHERE fileHash=:hash LIMIT 1") suspend fun imported(hash:String):MarketImportRow?
    @Query("SELECT * FROM market_searches WHERE id=:id") suspend fun search(id:String):MarketSearchRow?
    @Insert suspend fun insert(row:MarketImportRow)
    @Insert suspend fun insert(row:PriceDecisionRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(row:MarketSearchRow)
}
val MIGRATION_5_6=object:Migration(5,6) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS market_sales (id TEXT NOT NULL PRIMARY KEY,sourceKey TEXT NOT NULL,payloadHash TEXT NOT NULL,recordType TEXT NOT NULL,title TEXT NOT NULL,titleKey TEXT NOT NULL,barcode TEXT NOT NULL,edition TEXT NOT NULL,releaseYear INTEGER,soldDecimal TEXT NOT NULL,shippingDecimal TEXT NOT NULL,totalCents INTEGER NOT NULL,currency TEXT NOT NULL,soldAt TEXT NOT NULL,url TEXT NOT NULL,site TEXT NOT NULL,sourceItemId TEXT NOT NULL,collectedAt TEXT NOT NULL,observedAt TEXT NOT NULL,importId TEXT NOT NULL,rawJson TEXT NOT NULL,current INTEGER NOT NULL,eligible INTEGER NOT NULL)")
        for(field in listOf("sourceKey","titleKey","barcode","payloadHash","totalCents")) db.execSQL("CREATE INDEX IF NOT EXISTS index_market_sales_$field ON market_sales($field)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_market_sales_current_currency_soldAt ON market_sales(current,currency,soldAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_market_sales_current_titleKey_currency ON market_sales(current,titleKey,currency)")
        db.execSQL("CREATE TABLE IF NOT EXISTS market_imports (id TEXT NOT NULL PRIMARY KEY,fileHash TEXT NOT NULL,filename TEXT NOT NULL,format TEXT NOT NULL,observedAt TEXT NOT NULL,total INTEGER NOT NULL,added INTEGER NOT NULL,duplicates INTEGER NOT NULL,versions INTEGER NOT NULL,warningsJson TEXT NOT NULL,databaseVersion INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS price_decisions (id TEXT NOT NULL PRIMARY KEY,groupId TEXT NOT NULL,itemId TEXT NOT NULL,title TEXT NOT NULL,titleKey TEXT NOT NULL,edition TEXT NOT NULL,releaseYear INTEGER,barcode TEXT NOT NULL,recordType TEXT NOT NULL,priceCents INTEGER NOT NULL,currency TEXT NOT NULL,method TEXT NOT NULL,evidenceJson TEXT NOT NULL,provenanceJson TEXT NOT NULL,createdAt TEXT NOT NULL)")
        for(field in listOf("titleKey","groupId","itemId","createdAt")) db.execSQL("CREATE INDEX IF NOT EXISTS index_price_decisions_$field ON price_decisions($field)")
        db.execSQL("CREATE TABLE IF NOT EXISTS market_searches (id TEXT NOT NULL PRIMARY KEY,query TEXT NOT NULL,favorite INTEGER NOT NULL,usedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_market_searches_query ON market_searches(query)")
    }
}
