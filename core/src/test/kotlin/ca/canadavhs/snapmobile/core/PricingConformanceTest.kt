package ca.canadavhs.snapmobile.core

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal

class PricingConformanceTest {
    private val data=JSONObject(javaClass.getResource("/desktop_pricing.json")!!.readText())
    @Test fun matchesUnmodifiedDesktopTitleFilters() {
        val cases=data.getJSONArray("matches")
        for(i in 0 until cases.length()) {val c=cases.getJSONObject(i);assertEquals(c.toString(),c.getBoolean("matches"),PricingRules.listingMatches(c.getString("requested"),c.getString("listing"),2026))}
    }
    @Test fun matchesUnmodifiedDesktopDecimalStatistics() {
        val cases=data.getJSONArray("statistics")
        for(i in 0 until cases.length()) {val c=cases.getJSONObject(i);val values=c.getJSONArray("values")
            val result=PricingRules.statistics((0 until values.length()).map {j->val v=values.getJSONArray(j);SoldComparable(BigDecimal(v.getString(0)),BigDecimal(v.getString(1)),"Fixture","https://example.invalid","")})
            if(c.isNull("result")) assertNull(result) else {val e=c.getJSONObject("result");assertNotNull(result);assertEquals(e.getLong("averageCents"),result!!.averageCents);assertEquals(e.getLong("medianCents"),result.medianCents);assertEquals(e.getLong("lowestCents"),result.lowestCents);assertEquals(e.getLong("highestCents"),result.highestCents);assertEquals(e.getInt("count"),result.count)}
        }
    }
    @Test(expected=IllegalArgumentException::class) fun negativeSalesNeverProducePrices() {PricingRules.statistics(listOf(SoldComparable(BigDecimal("-1"),BigDecimal.ZERO,"","","")))}
}
