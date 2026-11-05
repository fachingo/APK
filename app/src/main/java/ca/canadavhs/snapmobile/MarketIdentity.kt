package ca.canadavhs.snapmobile

import ca.canadavhs.snapmobile.core.*
import org.json.*
import java.time.LocalDate

internal fun barcodeForms(value:String):List<String> = if(value.length !in 8..14 || !value.all(Char::isDigit)) listOf(value) else listOf(value,value.padStart(14,'0'),value.trimStart('0').padStart(12,'0'),value.trimStart('0').padStart(13,'0')).distinct()
internal fun sameBarcode(a:String,b:String)=a.isNotBlank() && b.isNotBlank() && a.trimStart('0')==b.trimStart('0')
private val listingNoise=setOf("vhs","tape","tapes","movie","video","cassette","ntsc","pal","tested","working","rare","classic","preowned","pre","owned","used","clamshell","slipcover","slip","case","fast","shipping","free","ship","sale","authentic","canadian","canada","mint")
private val articles=setOf("a","an","the")
private val romanNumbers=mapOf("ii" to "2","iii" to "3","iv" to "4","v" to "5","vi" to "6","vii" to "7","viii" to "8","ix" to "9","x" to "10")
internal fun filmWords(value:String,preserve:Set<String> = emptySet()):List<String> {
    val text=marketRawKey(value.replace("&"," and ").replace(Regex("^\\s*new\\s+listing\\s*[:–—-]?",RegexOption.IGNORE_CASE),""))
    return text.split(' ').filter {it.isNotBlank() && (it !in listingNoise || it in preserve) && it !in articles && !it.matches(Regex("(?:18|19|20|21)[0-9]{2}"))}.map {romanNumbers[it] ?: it}
}
internal fun strictFilmWords(value:String)=filmWords(value,marketRawKey(value).split(' ').toSet()-setOf("vhs","tape","movie","video","cassette"))
internal fun compatibleFilmTitle(item:InventoryRow,title:String):Boolean {
 val edition=marketRawKey(item.edition).split(' ').filter {it.isNotBlank()}.toSet()
 val a=strictFilmWords(item.title).filterNot {it in edition};val b=filmWords(title,a.toSet()).filterNot {it in edition}
 return PricingRules.listingMatches(item.title,title) || (a.isNotEmpty() && a.sorted()==b.sorted())
}
internal fun filmTitle(value:String)=filmWords(value).joinToString(" ")
internal fun filmYears(value:String)=Regex("\\b(?:18|19|20|21)[0-9]{2}\\b").findAll(value).map {it.value.toInt()}.toSet()
/** Broad retrieval never weakens these hard evidence checks. Explanations are also shown in the inspector. */
internal fun comparableExclusion(item:InventoryRow,row:MarketSaleRow):String? {
    if(!row.current) return "SUPERSEDED: a newer version of this sold observation exists"
    if(!row.eligible) return "INELIGIBLE_SALE: source is not verified usable sold evidence"
    val raw=" "+marketRawKey(row.title+" "+row.edition)+" "
    val target=" "+marketRawKey(item.title+" "+item.edition)+" "
    if(listOf("lot of","set of","bundle of").any {raw.contains(" $it ")} || listOf("lot","lots","bundle","bundles","collection","trilogy","boxset","box set","pack").any {raw.contains(" $it ") && !target.contains(" $it ")}) return "MULTI_ITEM: lot or collection cannot price one physical tape"
    if(listOf("dvd","blu ray","bluray","laserdisc","betamax","cd","soundtrack","poster","book").any {raw.contains(" $it ")}) return "FORMAT_CONFLICT: source is a different product format"
    if(!raw.contains(" vhs ") && !runCatching {JSONObject(row.rawJson).optString("format").equals("VHS",true)}.getOrDefault(false)) return "FORMAT_UNKNOWN: source does not establish VHS format"
    if(item.edition.isNotBlank()) {
        val expected=marketRawKey(item.edition);val recorded=marketRawKey(row.edition)
        if((recorded.isNotBlank() && recorded!=expected) || (recorded.isBlank() && !(" "+marketRawKey(row.title)+" ").contains(" $expected "))) return "EDITION_CONFLICT: source does not corroborate the confirmed edition"
    }
    if(item.edition.isBlank() && row.edition.isNotBlank()) return "EDITION_UNKNOWN: confirm the physical tape edition"
    for(marker in listOf("widescreen","fullscreen","special edition","limited edition","collectors edition","collector s edition","anniversary edition","directors cut","director s cut","black diamond","masterpiece","screener"))
        if(raw.contains(" $marker ")!=target.contains(" $marker ")) return "EDITION_CONFLICT: $marker"
    val wantedYears=filmYears(item.title)+listOfNotNull(item.releaseYear)
    val sourceYears=filmYears(row.title)+listOfNotNull(row.releaseYear)
    if(item.releaseYear!=null && row.releaseYear!=null && item.releaseYear!=row.releaseYear) return "YEAR_CONFLICT: explicit release-year metadata differs"
    if(wantedYears.isNotEmpty() && sourceYears.isNotEmpty() && wantedYears.intersect(sourceYears).isEmpty()) return "YEAR_CONFLICT: printed/release year differs"
    if(wantedYears.isNotEmpty() && sourceYears.isEmpty() && !sameBarcode(item.barcode,row.barcode)) return "YEAR_UNVERIFIED: source does not corroborate this release year"
    if(item.barcode.isNotBlank() && row.barcode.isNotBlank() && item.barcode.trimStart('0')!=row.barcode.trimStart('0')) return "BARCODE_CONFLICT: different UPC/EAN"
    if(!compatibleAutoCondition(item,row)) return "CONDITION_CONFLICT: sealed, graded or known condition differs"
    val a=strictFilmWords(item.title);val b=filmWords(row.title,a.toSet())
    val numsA=a.filter {it.all(Char::isDigit)}.toSet();val numsB=b.filter {it.all(Char::isDigit)}.toSet()
    if(numsA!=numsB) return "SEQUEL_CONFLICT: sequel or volume number differs"
    if(compatibleFilmTitle(item,row.title)) return null
    if(sameBarcode(item.barcode,row.barcode) && item.releaseYear!=null && item.releaseYear==row.releaseYear && a.size==b.size && editDistance(a.joinToString(" "),b.joinToString(" "),2)<=2) return null
    return "IDENTITY_REVIEW: similar spelling or partial title is a candidate, not confirmed sold evidence"
}
internal data class EvidenceQuality(val score:Int,val factors:JSONObject)
internal fun evidenceQuality(item:InventoryRow,rows:List<MarketSaleRow>,values:List<java.math.BigDecimal>):EvidenceQuality {
    require(rows.isNotEmpty());val factors=JSONObject()
    fun add(key:String,points:Int,why:String):Int {factors.put(key,JSONObject().put("points",points).put("reason",why));return points}
    var score=add("identity",35,"Strict film identity passed; incompatible sequels and formats were excluded")
    score+=add("formatEdition",15,"VHS format and edition checks passed")
    score+=add("year",if(item.releaseYear!=null && rows.all {it.releaseYear==item.releaseYear || item.releaseYear in filmYears(it.title)}) 5 else 0,"Year corroboration requires explicit agreeing source metadata")
    score+=add("barcode",if(rows.any {sameBarcode(it.barcode,item.barcode)}) 5 else 0,"Exact UPC/GTIN corroboration where recorded")
    score+=add("condition",if(item.condition!="Not Graded" && rows.all {JSONObject(it.rawJson).optString("condition").equals(item.condition,true)}) 5 else 0,"Unknown source condition adds no condition confidence")
    score+=add("independentSales",when(rows.size) {1->0;2->6;3->12;else->15},"${rows.size} independent sold observations after deduplication and outliers")
    val age=rows.minOf {marketEvidenceOldestAge(it.soldAt)}
    score+=add("recency",when {age<=180->10;age<=730->6;else->2},"Newest compatible sale is $age days old")
    score+=add("sourceMetadata",if(rows.all {it.sourceItemId.isNotBlank() && it.url.startsWith("https://") && runCatching {LocalDate.parse(it.soldAt.take(10))}.isSuccess}) 5 else 0,"Traceable sold-listing IDs, dates and source URLs")
    val sorted=values.sorted();val median=sorted[sorted.size/2];val spread=if(median.signum()>0) (sorted.last()-sorted.first()).divide(median,4,java.math.RoundingMode.HALF_UP).toDouble() else 10.0
    score+=add("dispersion",when {spread<=.6->5;spread<=1.5->0;else->-12},"Converted retained price range / median = $spread; single-sale variance is unknown")
    if(rows.size==1) {score=minOf(score,79);factors.put("singleSaleCap","One comparable can support review, never bulk approval")}
    return EvidenceQuality(score.coerceIn(0,100),factors)
}
