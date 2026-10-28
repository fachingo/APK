package ca.canadavhs.snapmobile

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

internal val marketColumns=listOf("id","sourceKey","payloadHash","recordType","title","titleKey","barcode","edition","releaseYear","soldDecimal","shippingDecimal","totalCents","currency","soldAt","url","site","sourceItemId","collectedAt","observedAt","importId","rawJson","current","eligible")
internal const val marketArchiveSchema="CREATE TABLE market_records (id TEXT PRIMARY KEY NOT NULL,sourceKey TEXT NOT NULL,payloadHash TEXT NOT NULL,recordType TEXT NOT NULL,title TEXT NOT NULL,titleKey TEXT NOT NULL,barcode TEXT NOT NULL,edition TEXT NOT NULL,releaseYear INTEGER,soldDecimal TEXT NOT NULL,shippingDecimal TEXT NOT NULL,totalCents INTEGER NOT NULL,currency TEXT NOT NULL,soldAt TEXT NOT NULL,url TEXT NOT NULL,site TEXT NOT NULL,sourceItemId TEXT NOT NULL,collectedAt TEXT NOT NULL,observedAt TEXT NOT NULL,importId TEXT NOT NULL,rawJson TEXT NOT NULL,current INTEGER NOT NULL,eligible INTEGER NOT NULL)"
internal data class MarketPreview(val file:File,val fileHash:String,val filename:String,val format:String,val importId:String,val observedAt:String,val rows:Long,val distinctRows:Long,val currencies:Map<String,Long>,val warnings:List<String>)
internal fun marketMoney(value:String):BigDecimal=try {BigDecimal(value).also {require(it.scale() in -12..12 && it.signum()>=0 && it<=BigDecimal("1000000"))}} catch(_:Exception) {throw IllegalArgumentException("Invalid market price/shipping amount; import stopped.")}
internal fun marketDate(value:String):String = try {if(value.length==10) LocalDate.parse(value).toString() else OffsetDateTime.parse(value).toInstant().toString()} catch(_:Exception) {throw IllegalArgumentException("Invalid sale/collection date; import stopped.")}
internal fun marketRow(raw:JSONObject,importId:String,observed:String):JSONObject {
    fun text(name:String,max:Int=4000,required:Boolean=true):String=raw.optString(name,"").also {require(it.length<=max && '\u0000' !in it && (!required || it.isNotBlank())) {"Missing or oversized $name"}}
    require(raw.optString("record_type","IMPORTED_SOLD_COMP") in listOf("IMPORTED_SOLD_COMP","VERIFIED_SOLD_COMP")) {"Asking prices and AI suggestions cannot be imported as sold-market evidence."}
    require(raw.toString().length<=200_000) {"Market source row exceeds metadata limit"}
    val title=text("title",2000);val normalizedTitle=InventoryRules.normalizedTitle(title)
    val item=text("item_id",200);val site=text("site",100);val soldAt=marketDate(text("date_sold",100));val currency=text("currency",3).uppercase();require(currency.matches(Regex("[A-Z]{3}")))
    val sold=marketMoney(text("sale_price",100));val shipping=marketMoney(text("shipping_price",100));val total=sold.add(shipping).setScale(2,RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    val barcode=text("barcode",14,false);require(barcode.isBlank() || (barcode.length in listOf(8,12,13,14) && barcode.all {it in '0'..'9'}))
    val year=text("release_year",4,false).let {if(it.isBlank()) null else it.toInt().also {y->require(y in 1800..2200)}}
    val collected=text("collected_at",100,false).let {if(it.isBlank()) "" else marketDate(it)}
    val url=text("url",4000);val uri=java.net.URI(url);require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null) {"Invalid listing URL"}
    val source=if(site.startsWith("EBAY",true)) "ebay:$item:$soldAt" else "${site.lowercase()}:$item:$soldAt"
    val titleKey=normalizedTitle.ifBlank {"~unidentified:$source"}
    val payload=JSONObject(raw.toString()).apply {remove("id")};val hash=sha256(canonicalJson(payload).toByteArray())
    return JSONObject().put("id",stableRowId("market:$source:$hash")).put("sourceKey",source).put("payloadHash",hash).put("recordType","IMPORTED_SOLD_COMP")
        .put("title",title).put("titleKey",titleKey).put("barcode",barcode).put("edition",text("edition",1000,false)).put("releaseYear",year ?: JSONObject.NULL)
        .put("soldDecimal",sold.toPlainString()).put("shippingDecimal",shipping.toPlainString()).put("totalCents",total).put("currency",currency).put("soldAt",soldAt).put("url",url).put("site",site).put("sourceItemId",item)
        .put("collectedAt",collected).put("observedAt",observed).put("importId",importId).put("rawJson",raw.toString()).put("current",1).put("eligible",if(normalizedTitle.isBlank()) 0 else 1)
}
internal fun marketValues(row:JSONObject)=ContentValues().apply {marketColumns.forEach {key->val v=row.get(key);when(v) {JSONObject.NULL->putNull(key);is Number->put(key,v.toLong());else->put(key,v.toString())}}}
/** Stage on disk, validate completely, and preview before touching permanent evidence. */
internal suspend fun CaptureRepository.previewMarket(input:File,displayName:String,progress:(Long)->Unit={}):MarketPreview=withContext(Dispatchers.IO) {
    require(input.length() in 1..2_000_000_000L);val hash=hashFile(input);val imported=journal.marketDao().imported(hash)
    val id=imported?.id ?: newId();val observed=OffsetDateTime.now().toString();val file=File(context.cacheDir,"market-preview-${newId()}.sqlite")
    var rows=0L;val currencies=mutableMapOf<String,Long>();var format="HARVESTER_CSV_V1";var missingCollection=0L
    try {
        SQLiteDatabase.openOrCreateDatabase(file,null).use {target->
            target.execSQL(marketArchiveSchema);target.execSQL("CREATE TABLE market_format(version INTEGER NOT NULL,format TEXT NOT NULL)");target.execSQL("INSERT INTO market_format VALUES(1,'snapims.market.archive')")
            target.beginTransaction()
            try {
                suspend fun add(raw:JSONObject,normalized:Boolean=false) {
                    currentCoroutineContext().ensureActive();check(++rows<=2_000_000) {"Import at most two million observations per package."}
                    val row=if(normalized) validateMarketArchiveRow(raw).put("recordType","IMPORTED_SOLD_COMP") else marketRow(raw,id,observed)
                    target.insertWithOnConflict("market_records",null,marketValues(row),SQLiteDatabase.CONFLICT_IGNORE)
                    val currency=row.getString("currency");currencies[currency]=(currencies[currency] ?: 0L)+1
                    if(row.getString("collectedAt").isBlank()) missingCollection++
                    if(rows%500==0L) progress(rows)
                }
                val header=input.inputStream().use {ByteArray(16).also {bytes->it.read(bytes)}}
                if(String(header,Charsets.US_ASCII).startsWith("SQLite format 3")) {
                    SQLiteDatabase.openDatabase(input.path,null,SQLiteDatabase.OPEN_READONLY).use {source->
                        source.rawQuery("PRAGMA quick_check",null).use {check(it.moveToFirst() && it.getString(0)=="ok") {"Source SQLite integrity check failed."}}
                        val tables=source.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use {c->buildSet {while(c.moveToNext()) add(c.getString(0))}}
                        when {
                            "market_format" in tables->{format="SNAPIMS_MARKET_ARCHIVE_V1";source.rawQuery("SELECT version,format FROM market_format",null).use {check(it.moveToFirst() && it.getInt(0)==1 && it.getString(1)=="snapims.market.archive")};source.rawQuery("SELECT * FROM market_records",null).use {c->while(c.moveToNext()) add(cursorObject(c),true)}}
                            "pricing_evidence" in tables && "pricing_evidence_listings" in tables->{format="SNAPIMS_PRICING_SQLITE";source.rawQuery("SELECT l.*,e.currency,e.collected_at,e.queried_title,e.status FROM pricing_evidence_listings l JOIN pricing_evidence e ON e.evidence_id=l.evidence_id ORDER BY l.evidence_id,l.ordinal",null).use {c->while(c.moveToNext()) {
                                val raw=cursorObject(c);check(raw.getString("status").equals("Complete",true)) {"Only completed sold-evidence collections can be imported."}
                                val url=raw.getString("listing_url");val ebay=Regex("/itm/(?:[^/]+/)?([0-9]+)").find(url)?.groupValues?.get(1)
                                add(JSONObject().put("id",raw.get("evidence_id").toString()+":"+raw.get("ordinal")).put("title",raw.getString("listing_title")).put("site",if(ebay!=null) "EBAY" else "SNAPIMS_WORKBENCH").put("item_id",ebay ?: sha256(url.toByteArray())).put("date_sold",raw.getString("sold_date")).put("sale_price",raw.get("sold_price_cad").toString()).put("shipping_price",raw.get("shipping_price_cad").toString()).put("currency",raw.getString("currency")).put("url",url).put("collected_at",raw.getString("collected_at")).put("workbench_source",raw))
                            }}}
                            else->{val table=listOf("trawl_sales","sales").firstOrNull {it in tables} ?: error("Unsupported Harvester SQLite schema. Export the documented CSV or a SnapIMS market archive.");format="HARVESTER_SQLITE_V1";source.rawQuery("SELECT * FROM $table",null).use {c->check(harvesterRequired.all {it in c.columnNames}) {"Harvester columns do not match the documented adapter."};while(c.moveToNext()) {val raw=cursorObject(c);raw.keys().forEach {k->if(!raw.isNull(k)) raw.put(k,raw.get(k).toString())};add(raw)}}}
                        }
                    }
                } else MarketCsv(java.io.InputStreamReader(input.inputStream(),Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))).use {csv->val columns=csv.next() ?: error("CSV is empty");check(columns.distinct().size==columns.size && harvesterRequired.all {it in columns}) {"Harvester CSV columns do not match the documented adapter."};while(true) {val values=csv.next() ?: break;if(values.size==1 && values[0].isBlank()) continue;check(values.size==columns.size) {"CSV column mismatch near row ${rows+2}; no evidence imported."};val raw=JSONObject();columns.zip(values).forEach {(k,v)->raw.put(k,v)};try {add(raw)} catch(e:Exception) {if(e is CancellationException) throw e;throw IllegalArgumentException("Market row ${rows+2}: ${e.message}",e)}}}
                target.setTransactionSuccessful()
            } finally {target.endTransaction()}
            val distinct=target.rawQuery("SELECT COUNT(*) FROM market_records",null).use {it.moveToFirst();it.getLong(0)}
            val conflicts=target.rawQuery("SELECT COUNT(*) FROM (SELECT sourceKey FROM market_records GROUP BY sourceKey HAVING COUNT(*)>1)",null).use {it.moveToFirst();it.getLong(0)}
            if(conflicts>0) target.execSQL("UPDATE market_records SET eligible=0 WHERE sourceKey IN (SELECT sourceKey FROM market_records GROUP BY sourceKey HAVING COUNT(*)>1)")
            val unidentified=target.rawQuery("SELECT COUNT(*) FROM market_records WHERE titleKey LIKE '~unidentified:%'",null).use {it.moveToFirst();it.getLong(0)}
            val warnings=buildList {if(unidentified>0) add("$unidentified generic/format-only titles are preserved for browsing and excluded from automatic pricing.");if(missingCollection>0) add("$missingCollection rows have no collection timestamp; import observation time is stored separately.");if(conflicts>0) add("$conflicts conflicting source-sale identities are preserved and excluded from sold statistics until reviewed.");if(currencies.keys.any {it!="CAD"}) add("Original currencies are retained. CAD application requires an explicitly entered conversion rate.")}
            progress(rows);MarketPreview(file,hash,displayName.take(240),format,id,observed,rows,distinct,currencies,warnings)
        }
    } catch(e:Exception) {file.delete();throw e}
}
internal val harvesterRequired=setOf("title","site","item_id","date_sold","sale_price","shipping_price","currency","url")
internal fun validateMarketArchiveRow(row:JSONObject):JSONObject {
    require(row.keys().asSequence().toSet()==marketColumns.toSet());requireUuid(row.getString("id"));require(row.getString("sourceKey").length in 1..500);require(row.getString("payloadHash").matches(Regex("[a-f0-9]{64}")))
    require(row.getString("recordType") in listOf("IMPORTED_SOLD_COMP","VERIFIED_SOLD_COMP"));require(row.getString("titleKey")==InventoryRules.normalizedTitle(row.getString("title")).ifBlank {"~unidentified:${row.getString("sourceKey")}"})
    require(row.getString("currency").matches(Regex("[A-Z]{3}")));marketDate(row.getString("soldAt"));marketDate(row.getString("observedAt"));requireUuid(row.getString("importId"))
    val total=marketMoney(row.getString("soldDecimal")).add(marketMoney(row.getString("shippingDecimal"))).setScale(2,RoundingMode.HALF_UP).movePointRight(2).longValueExact();require(total==row.getLong("totalCents"));require(row.getInt("current") in 0..1 && row.getInt("eligible") in 0..1)
    require(row.getString("rawJson").length<=200_000)
    val original=marketRow(JSONObject(row.getString("rawJson")),row.getString("importId"),row.getString("observedAt"))
    if(original.getInt("eligible")==0) require(row.getInt("eligible")==0)
    for(key in marketColumns.filter {it !in listOf("current","eligible","recordType","rawJson")}) require(canonicalJson(row.get(key))==canonicalJson(original.get(key))) {"Market archive content/hash mismatch in $key"}
    return row
}
internal suspend fun CaptureRepository.commitMarket(preview:MarketPreview,progress:(Long,Long)->Unit={_,_->}):MarketImportRow {
    marketBusy.value=true
    try {return mutate {
    ensureMarketSearch();val count=mergeMarketArchiveLocked(preview.file,progress)
    val record=MarketImportRow(preview.importId,preview.fileHash,preview.filename,preview.format,preview.observedAt,preview.rows,count.first,preview.rows-count.first,count.second,JSONArray(preview.warnings).toString())
    val prior=journal.marketDao().imported(preview.fileHash)
    if(prior==null) journal.marketDao().insert(record)
    record
    }} finally {marketBusy.value=false}
}
/** Called within the shared inventory transaction: any invalid row rolls back the whole import. */
internal suspend fun CaptureRepository.mergeMarketArchiveLocked(file:File,progress:(Long,Long)->Unit={_,_->}):Pair<Long,Long> {
    val db=journal.openHelper.writableDatabase;var added=0L;var versions=0L;var done=0L
    SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {source->
        source.rawQuery("PRAGMA quick_check",null).use {check(it.moveToFirst() && it.getString(0)=="ok") {"Market archive failed integrity verification."}}
        source.rawQuery("SELECT version,format FROM market_format",null).use {check(it.moveToFirst() && it.getInt(0)==1 && it.getString(1)=="snapims.market.archive")}
        val total=source.rawQuery("SELECT COUNT(*) FROM market_records",null).use {it.moveToFirst();it.getLong(0)}
        val insert=db.compileStatement("INSERT INTO market_sales (${marketColumns.joinToString(",")}) VALUES (${marketColumns.joinToString(",") {"?"}})")
        source.rawQuery("SELECT * FROM market_records ORDER BY sourceKey,observedAt,id",null).use {c->while(c.moveToNext()) {
            currentCoroutineContext().ensureActive();val row=validateMarketArchiveRow(cursorObject(c));val id=row.getString("id")
            val exists=db.query("SELECT payloadHash FROM market_sales WHERE id=?",arrayOf(id)).use {existing->if(existing.moveToFirst()) existing.getString(0) else null}
            if(exists!=null) check(exists==row.getString("payloadHash")) {"Conflicting sold record identity; import rolled back."}
            else {
                val previous=db.query("SELECT COUNT(*) FROM market_sales WHERE sourceKey=?",arrayOf(row.getString("sourceKey"))).use {it.moveToFirst();it.getLong(0)}
                if(previous>0 && row.getInt("current")==1) {db.execSQL("UPDATE market_sales SET current=0 WHERE sourceKey=?",arrayOf(row.getString("sourceKey")));versions++}
                marketColumns.forEachIndexed {i,key->when(val value=row.get(key)) {JSONObject.NULL->insert.bindNull(i+1);is Number->insert.bindLong(i+1,value.toLong());else->insert.bindString(i+1,value.toString())}}
                insert.executeInsert();insert.clearBindings();added++
                indexMarketRow(row)
            }
            done++;if(done%500==0L) progress(done,total)
        }};insert.close();progress(done,total)
    };return added to versions
}
