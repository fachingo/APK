package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import java.time.OffsetDateTime

internal data class MarketFilters(val query:String="",val currency:String="",val edition:String="",val year:Int?=null,val minCents:Long?=null,val maxCents:Long?=null,val from:String="",val until:String="",val sort:String="RELEVANCE",val history:Boolean=false)
internal data class MarketGroup(val titleKey:String,val title:String,val currency:String,val edition:String,val year:Int?,val samples:Long,val low:Long,val high:Long,val newest:String,val approximate:Boolean=false,val matchScore:Int=0)
internal data class MarketPage(val groups:List<MarketGroup>,val index:String,val approximate:Boolean=false,val correctedQuery:String="")
internal fun CaptureRepository.marketIndexKind():String=journal.openHelper.writableDatabase.query("SELECT kind FROM market_index_config LIMIT 1").use {check(it.moveToFirst());it.getString(0)}
internal fun CaptureRepository.ensureMarketSearch():String {
    val db=journal.openHelper.writableDatabase
    val exists=db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='market_index_config'").use {it.moveToFirst()}
    if(exists) return marketIndexKind()
    db.execSQL("CREATE TABLE market_index_config(kind TEXT NOT NULL)")
    var kind="INDEXED"
    try {db.execSQL("CREATE VIRTUAL TABLE market_fts USING fts5(id UNINDEXED,titleKey,barcode UNINDEXED,tokenize='unicode61')");kind="FTS5"} catch(_:android.database.SQLException) {
        try {db.execSQL("CREATE VIRTUAL TABLE market_fts USING fts4(id,titleKey,barcode,notindexed=id,notindexed=barcode,tokenize=unicode61)");kind="FTS4"} catch(_:android.database.SQLException) { /* Main title/barcode B-tree indexes are always available. */ }
    }
    db.execSQL("INSERT INTO market_index_config VALUES(?)",arrayOf(kind))
    db.execSQL("CREATE TABLE market_token_rows(term TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(term,id))")
    db.execSQL("CREATE TABLE market_lexicon(term TEXT NOT NULL PRIMARY KEY,length INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX market_lexicon_length ON market_lexicon(length)")
    db.execSQL("CREATE TABLE market_grams(gram TEXT NOT NULL,term TEXT NOT NULL,PRIMARY KEY(gram,term))")
    db.query("SELECT * FROM market_sales").use {c->while(c.moveToNext()) indexMarketRow(cursorObject(c),kind)}
    return kind
}
internal fun marketRawKey(value:String):String=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"),"").lowercase(java.util.Locale.ROOT).replace("ß","ss").replace("ς","σ").replace(Regex("[^\\p{L}\\p{N}_]+")," ").trim()
internal fun wordGrams(word:String):Set<String> = if(word.length<3) setOf(word) else ("^$word$").windowed(3).toSet()
internal fun CaptureRepository.indexMarketRow(row:JSONObject,kind:String=marketIndexKind()) {
    val db=journal.openHelper.writableDatabase
    val searchText=marketRawKey(row.getString("title"))
    if(kind!="INDEXED") db.execSQL("INSERT INTO market_fts(id,titleKey,barcode) VALUES(?,?,?)",arrayOf(row.getString("id"),searchText,row.getString("barcode")))
    if(kind=="INDEXED") searchText.split(' ').filter {it.isNotBlank()}.distinct().forEach {word->db.execSQL("INSERT OR IGNORE INTO market_token_rows VALUES(?,?)",arrayOf(word,row.getString("id")))}
    searchText.split(' ').filter {it.length in 2..80}.distinct().forEach {word->
        val inserted=db.compileStatement("INSERT OR IGNORE INTO market_lexicon(term,length) VALUES(?,?)").use {s->s.bindString(1,word);s.bindLong(2,word.length.toLong());s.executeInsert()!=-1L}
        if(inserted) wordGrams(word).forEach {gram->db.execSQL("INSERT OR IGNORE INTO market_grams VALUES(?,?)",arrayOf(gram,word))}
    }
}
internal suspend fun CaptureRepository.rebuildMarketSearch(progress:(Long)->Unit={}) {marketBusy.value=true;try {mutate {
    val db=journal.openHelper.writableDatabase
    for(table in listOf("market_fts","market_index_config","market_lexicon","market_grams","market_token_rows")) db.execSQL("DROP TABLE IF EXISTS $table")
    ensureMarketSearch();db.query("SELECT COUNT(*) FROM market_sales").use {it.moveToFirst();progress(it.getLong(0))}
    }} finally {marketBusy.value=false}
}
private fun likeMarket(text:String)=text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
internal fun editDistance(a:String,b:String,maximum:Int=2):Int=org.apache.commons.text.similarity.LevenshteinDistance(maximum).apply(a,b).let {if(it<0) maximum+1 else it}
internal fun marketMatchScore(query:String,title:String):Int {
    val q=InventoryRules.normalizedTitle(query);val t=InventoryRules.normalizedTitle(title);if(q.isBlank() || t.isBlank()) return 0;if(q==t) return 100
    val stops=setOf("the","a","an","and","of","in","on");val qs=q.split(' ').filter {it.isNotBlank() && it !in stops}.toSet().ifEmpty {q.split(' ').toSet()};val ts=t.split(' ').filter {it.isNotBlank() && it !in stops}.toSet().ifEmpty {t.split(' ').toSet()}
    val overlap=2.0*qs.intersect(ts).size/(qs.size+ts.size);val jw=org.apache.commons.text.similarity.JaroWinklerSimilarity().apply(q,t)
    val years=Regex("(?:18|19|20|21)[0-9]{2}");val qy=years.findAll(q).map {it.value}.toSet();val ty=years.findAll(t).map {it.value}.toSet();val penalty=if(qy.isNotEmpty() && ty.isNotEmpty() && qy!=ty) .25 else 0.0
    return ((.6*overlap+.4*jw-penalty)*100).toInt().coerceIn(0,99)
}
private fun CaptureRepository.correctMarketQuery(query:String):String {
    val db=journal.openHelper.writableDatabase
    return query.split(' ').take(10).joinToString(" ") {word->
        if(word.length<4 || word.all {it.isDigit()}) word else {
            val grams=wordGrams(word).take(20);val options=mutableListOf<String>()
            db.query("SELECT g.term,COUNT(*) score FROM market_grams g JOIN market_lexicon l ON l.term=g.term WHERE g.gram IN (${grams.joinToString(",") {"?"}}) AND l.length BETWEEN ? AND ? GROUP BY g.term ORDER BY score DESC,g.term LIMIT 100",(grams+listOf((word.length-2).toString(),(word.length+2).toString())).toTypedArray()).use {c->while(c.moveToNext()) options+=c.getString(0)}
            options.map {it to editDistance(word,it)}.filter {it.second<=if(word.length>=5) 2 else 1}.minWithOrNull(compareBy<Pair<String,Int>> {it.second}.thenBy {it.first})?.first ?: word
        }
    }
}
internal suspend fun CaptureRepository.searchMarket(filters:MarketFilters,offset:Int=0,pageSize:Int=40,allowApproximate:Boolean=true):MarketPage=withContext(Dispatchers.IO) {
    require(offset in 0..1_000_000 && pageSize in 1..100);val kind=mutate {ensureMarketSearch()};val normalized=InventoryRules.normalizedTitle(filters.query).ifBlank {marketRawKey(filters.query)}.take(500)
    suspend fun search(key:String,approximate:Boolean,broad:Boolean=false):List<MarketGroup> {
        val clauses=mutableListOf<String>();val args=mutableListOf<String>();var source="market_sales"
        if(!filters.history) clauses+="current=1"
        if(key.isNotBlank()) {
            if(key.matches(Regex("[0-9]{8,14}"))) {clauses+="barcode=?";args+=key}
            else if(kind!="INDEXED") {val tokens=key.split(' ').filter {it.isNotBlank() && (!broad || it !in setOf("the","a","an","and","of","in","on"))}.take(15).ifEmpty {listOf(key)};source="(SELECT id FROM market_fts WHERE market_fts MATCH ?) AS hit CROSS JOIN market_sales AS s ON s.id=hit.id";args+=tokens.joinToString(if(broad) " OR " else " ") {it+"*"}}
            else {val parts=key.split(' ').filter {it.isNotBlank()}.take(15).map {word->args+=word;args+=word+"\uffff";"id IN (SELECT id FROM market_token_rows WHERE term>=? AND term<?)"};clauses+="("+parts.joinToString(if(broad) " OR " else " AND ")+")"}
        }
        if(filters.currency.isNotBlank()) {clauses+="currency=?";args+=filters.currency}
        if(filters.edition.isNotBlank()) {clauses+="edition LIKE ? ESCAPE '\\'";args+="%${likeMarket(filters.edition)}%"}
        filters.year?.let {clauses+="(releaseYear=? OR title LIKE ?)";args+=it.toString();args+="%$it%"}
        filters.minCents?.let {clauses+="totalCents>=?";args+=it.toString()};filters.maxCents?.let {clauses+="totalCents<=?";args+=it.toString()}
        if(filters.from.isNotBlank()) {clauses+="soldAt>=?";args+=filters.from};if(filters.until.isNotBlank()) {clauses+="soldAt<=?";args+=filters.until+"T23:59:59.999Z"}
        val where=if(clauses.isEmpty()) "1" else clauses.joinToString(" AND ")
        val order=when(filters.sort) {"PRICE_LOW"->if(filters.currency.isBlank()) "currency,low ASC" else "low ASC";"PRICE_HIGH"->if(filters.currency.isBlank()) "currency,high DESC" else "high DESC";"NEWEST"->"newest DESC";"SAMPLES"->"samples DESC";else->"CASE WHEN titleKey=? THEN 0 ELSE 1 END,samples DESC,titleKey"}
        if(filters.sort=="RELEVANCE") args+=normalized
        args+=(if(broad) 500 else pageSize).toString();args+=(if(broad) 0 else offset).toString()
        val sql="SELECT titleKey,MIN(title) title,currency,edition,releaseYear,COUNT(*) samples,MIN(totalCents) low,MAX(totalCents) high,MAX(soldAt) newest FROM $source WHERE $where GROUP BY titleKey,currency,edition,releaseYear ORDER BY $order LIMIT ? OFFSET ?"
        journal.openHelper.writableDatabase.query(sql,args.toTypedArray()).use {c->return buildList {while(c.moveToNext()) add(MarketGroup(c.getString(0),c.getString(1),c.getString(2),c.getString(3),if(c.isNull(4)) null else c.getInt(4),c.getLong(5),c.getLong(6),c.getLong(7),c.getString(8),approximate,marketMatchScore(filters.query,c.getString(1))))}}
    }
    val exact=search(normalized,false)
    if(exact.isNotEmpty() || normalized.isBlank() || !allowApproximate || offset>0 || normalized.matches(Regex("[0-9]+"))) MarketPage(exact,kind)
    else {val corrected=correctMarketQuery(normalized);val correctedHits=if(corrected==normalized) emptyList() else search(corrected,true)
        if(correctedHits.isNotEmpty()) MarketPage(correctedHits.sortedByDescending {it.matchScore},kind,true,corrected)
        else {val hits=search(corrected,true,true).filter {it.matchScore>=30}.sortedWith(compareByDescending<MarketGroup> {it.matchScore}.thenByDescending {it.samples}.thenBy {it.titleKey}).take(pageSize);MarketPage(hits,kind,hits.isNotEmpty(),if(corrected!=normalized) corrected else "")}
    }
}
internal suspend fun CaptureRepository.marketListings(group:MarketGroup,offset:Int=0):List<JSONObject> = withContext(Dispatchers.IO) {
    journal.openHelper.writableDatabase.query("SELECT * FROM market_sales WHERE titleKey=? AND currency=? AND edition=? AND releaseYear IS ? ORDER BY current DESC,soldAt DESC,id LIMIT 50 OFFSET ?",arrayOf<Any?>(group.titleKey,group.currency,group.edition,group.year,offset)).use {c->buildList {while(c.moveToNext()) add(cursorObject(c))}}
}
internal suspend fun CaptureRepository.rememberMarketSearch(query:String,favorite:Boolean?=null) = mutate {
    val value=query.trim().take(500);if(value.isNotBlank()) {val dao=journal.marketDao();val id=stableRowId("search:$value");val prior=dao.search(id);dao.save(MarketSearchRow(id,value,favorite ?: prior?.favorite ?: false))}
}
internal data class MarketRecommendation(val groupId:String,val revision:Long,val titleKey:String,val edition:String,val year:Int?,val currency:String,val stats:SoldStatistics,val ids:List<String>,val oldest:String,val newest:String,val hash:String)
internal suspend fun CaptureRepository.recommendMarket(groupId:String,currency:String):MarketRecommendation?=withContext(Dispatchers.IO) {
    val item=journal.inventoryDao().item(groupId) ?: error("Item missing");mutate {ensureMarketSearch()};recommendMarketLocked(item,currency)
}
internal fun CaptureRepository.recommendMarketLocked(item:InventoryRow,currency:String):MarketRecommendation? {
    if(item.title.isBlank()) return null
    val key=InventoryRules.normalizedTitle(item.title);if(key.isBlank()) return null;val kind=marketIndexKind();val fts=key.split(' ').joinToString(" ") {it+"*"}
    val args=mutableListOf<String>();var source="market_sales";var search="1"
    if(kind!="INDEXED") {source="(SELECT id FROM market_fts WHERE market_fts MATCH ?) AS hit CROSS JOIN market_sales AS s ON s.id=hit.id";args+=fts}
    args+=currency
    if(kind=="INDEXED") search=key.split(' ').filter {it.isNotBlank()}.joinToString(" AND ") {word->args+=word;args+=word+"\uffff";"id IN (SELECT id FROM market_token_rows WHERE term>=? AND term<?)"}
    val values=mutableListOf<SoldComparable>();val ids=mutableListOf<String>();val dates=mutableListOf<String>();val hashes=mutableListOf<String>();val releaseYears=mutableSetOf<Int>();var candidates=0
    journal.openHelper.writableDatabase.query("SELECT ${if(kind=="INDEXED") "*" else "s.*"} FROM $source WHERE current=1 AND eligible=1 AND currency=? AND ($search) ORDER BY soldAt DESC LIMIT 5001",args.toTypedArray()).use {c->while(c.moveToNext()) {
        check(++candidates<=5000) {"Narrow the title, edition or year: more than 5,000 candidate sales. No partial sample recommendation was made."}
        val row=cursorObject(c)
        if(!PricingRules.listingMatches(item.title,row.getString("title"))) continue
        val edition=row.getString("edition");val year=if(row.isNull("releaseYear")) null else row.getInt("releaseYear")
        // Explicit edition/year evidence must agree. Unknown edition is not assumed compatible.
        if(item.edition.isNotBlank() && !edition.equals(item.edition,true)) continue
        if(item.edition.isBlank() && edition.isNotBlank()) continue
        val variantMarkers=listOf("widescreen","fullscreen","special edition","limited edition","collectors edition","collector s edition","anniversary edition","directors cut","director s cut","cgc","vga","graded","sealed")
        val sourceWords=" "+marketRawKey(row.getString("title"))+" ";val itemWords=" "+marketRawKey(item.title+" "+item.edition)+" "
        if(variantMarkers.any {marker->sourceWords.contains(" $marker ") && !itemWords.contains(" $marker ")}) continue
        if(item.releaseYear!=null && year!=item.releaseYear && !Regex("(?:^|\\W)${item.releaseYear}(?:$|\\W)").containsMatchIn(row.getString("title"))) continue
        if(item.barcode.isNotBlank() && row.getString("barcode").isNotBlank() && item.barcode!=row.getString("barcode")) continue
        if(item.releaseYear==null && !Regex("(?:18|19|20|21)[0-9]{2}").containsMatchIn(item.title)) {
            val titleYears=Regex("\\b(?:18|19|20|21)[0-9]{2}\\b").findAll(row.getString("title")).map {it.value.toInt()}.filter {it in 1888..java.time.LocalDate.now().year+2}.toSet()
            if(year!=null) releaseYears+=year else if(titleYears.size==1) releaseYears+=titleYears.single()
        }
        values+=SoldComparable(marketMoney(row.getString("soldDecimal")),marketMoney(row.getString("shippingDecimal")),row.getString("title"),row.getString("url"),row.getString("soldAt"));ids+=row.getString("id");dates+=row.getString("soldAt");hashes+=row.getString("payloadHash")
    }}
    check(releaseYears.size<=1) {"Compatible titles contain different release years. Set the inventory release year before using sold-price evidence."}
    val stats=PricingRules.statistics(values) ?: return null
    return MarketRecommendation(item.groupId,item.revision,key,item.edition,item.releaseYear,currency,stats,ids,dates.min(),dates.max(),sha256(hashes.sorted().joinToString("\n").toByteArray()))
}

internal data class MarketBin(val startCents:Long,val endCents:Long,val count:Long)
internal suspend fun CaptureRepository.marketDistribution(group:MarketGroup):List<MarketBin> = withContext(Dispatchers.IO) {
    val step=((group.high-group.low)/10+1).coerceAtLeast(1)
    journal.openHelper.writableDatabase.query("SELECT CAST((totalCents-?)/? AS INTEGER) bucket,COUNT(*) FROM market_sales WHERE current=1 AND eligible=1 AND titleKey=? AND currency=? AND edition=? AND releaseYear IS ? GROUP BY bucket ORDER BY bucket",arrayOf<Any?>(group.low,step,group.titleKey,group.currency,group.edition,group.year)).use {c->buildList {while(c.moveToNext()) {val start=group.low+c.getLong(0)*step;add(MarketBin(start,start+step-1,c.getLong(1)))}}}
}
