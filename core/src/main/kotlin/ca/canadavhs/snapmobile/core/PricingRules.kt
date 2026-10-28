package ca.canadavhs.snapmobile.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class SoldComparable(val sold: BigDecimal,val shipping: BigDecimal,val title: String,val url: String,val soldDate: String)
data class SoldStatistics(val averageCents: Long,val medianCents: Long,val lowestCents: Long,val highestCents: Long,val count: Int)
object PricingRules {
    private val seller=setOf("authentic","canada","canadian","case","classic","clamshell","edition","factory","former","horror","mint","new","ntsc","pal","previewed","rare","sealed","slip","slipcover","tested","widescreen","black","white","color","colour","preowned")
    private val bundles=setOf("bundle","bundles","collection","lot","lots","trilogy","set","pack","dvd","bluray","blu","ray","poster","shirt","book","soundtrack","laserdisc","betamax","game","figurine","figure")
    private val sequel=setOf("returns","return","resurrection","revenge","reloaded","revolutions","awakens","rises","forever","begins","legacy","chapter","part","episode","versus","vs")
    private val roman=setOf("ii","iii","iv","v","vi","vii","viii","ix","x")
    fun listingMatches(request: String,listing: String,year: Int=LocalDate.now().year): Boolean {
        val wanted=InventoryRules.normalizedTitle(request).split(' ').filter {it.isNotEmpty()}
        if(wanted.isEmpty()) return false
        val text=listing.replace(Regex("^\\s*new\\s+listing\\s*[:\\-–—]?\\s*",RegexOption.IGNORE_CASE),"")
        val words=InventoryRules.normalizedTitle(text).split(' ').filter {it.isNotEmpty()}
        if(words.size<wanted.size) return false
        val requestedYear=wanted.any {it.matches(Regex("(?:18|19|20|21)\\d{2}"))}
        fun optionalYear(word:String)=!requestedYear && word.matches(Regex("(?:18|19|20|21)\\d{2}")) && (word.toIntOrNull() ?: 0) in 1888..year+2
        for(start in 0..words.size-wanted.size) {
            if(words.subList(start,start+wanted.size)!=wanted) continue
            val extras=words.take(start)+words.drop(start+wanted.size)
            if(extras.any {it in bundles || it in sequel || it in roman || (it.all(Char::isDigit) && !optionalYear(it))}) continue
            if(extras.all {it in seller || optionalYear(it)}) return true
        };return false
    }
    fun statistics(values:List<SoldComparable>):SoldStatistics? {
        if(values.isEmpty()) return null
        require(values.size<=100_000)
        val totals=values.map {require(it.sold.signum()>=0 && it.shipping.signum()>=0 && it.sold<=BigDecimal("1000000") && it.shipping<=BigDecimal("1000000"));it.sold.add(it.shipping,MathContext(28))}.sorted()
        val total=totals.fold(BigDecimal.ZERO) {sum,value->sum.add(value,MathContext(28))}
        val average=total.divide(BigDecimal(totals.size),MathContext(28))
        val middle=if(totals.size%2==1) totals[totals.size/2] else totals[totals.size/2-1].add(totals[totals.size/2],MathContext(28)).divide(BigDecimal(2),MathContext(28))
        fun cents(value:BigDecimal)=value.setScale(2,RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        return SoldStatistics(cents(average),cents(middle),cents(totals.first()),cents(totals.last()),totals.size)
    }
}
