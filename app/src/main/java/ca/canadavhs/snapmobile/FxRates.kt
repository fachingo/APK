package ca.canadavhs.snapmobile

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

internal const val VALET_USD_CAD = "https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?recent=10"
internal const val FX_MAX_AGE_DAYS = 30L
internal data class VerifiedFx(val rate:String,val date:String,val fetchedAt:Long,val source:String=VALET_USD_CAD) {
    fun age(today:LocalDate=LocalDate.now())=ChronoUnit.DAYS.between(LocalDate.parse(date),today)
    fun json()=JSONObject().put("rate",rate).put("date",date).put("fetchedAt",fetchedAt).put("source",source)
    fun validate(today:LocalDate=LocalDate.now()):VerifiedFx {
        require(rate.toBigDecimal() in BigDecimal("0.1")..BigDecimal("10") && rate.toBigDecimal().scale()<=8) {"Invalid USD/CAD rate"}
        require(LocalDate.parse(date)<=today && fetchedAt>0 && source==VALET_USD_CAD) {"Invalid exchange-rate provenance"};return this
    }
    companion object {fun parse(o:JSONObject)=VerifiedFx(o.getString("rate"),o.getString("date"),o.getLong("fetchedAt"),o.getString("source")).validate()}
}
internal fun parseValetRate(body:String,today:LocalDate=LocalDate.now(),now:Long=System.currentTimeMillis()):VerifiedFx {
    val root=JSONObject(body);check(root.getJSONObject("seriesDetail").has("FXUSDCAD")) {"Bank of Canada response did not identify FXUSDCAD"}
    val rows=root.getJSONArray("observations");require(rows.length() in 1..100)
    return (0 until rows.length()).mapNotNull {i->val row=rows.getJSONObject(i);val date=LocalDate.parse(row.getString("d"));val value=row.optJSONObject("FXUSDCAD")?.optString("v").orEmpty()
        if(value.isBlank() || date>today) null else VerifiedFx(value,date.toString(),now).validate(today)
    }.maxByOrNull {it.date} ?: error("No published USD/CAD observation in the Bank of Canada response")
}
private val fxLock=Mutex()
internal fun CaptureRepository.cachedFx():VerifiedFx?=runCatching {VerifiedFx.parse(JSONObject(preferences.getString("verifiedUsdCad",null) ?: return null))}.getOrNull()
/** Called only by an explicitly started pricing workflow. No periodic network job or AI request. */
internal suspend fun CaptureRepository.resolvePriceFx(rules:AutoPriceRules,refresh:Boolean=true,client:OkHttpClient=OkHttpClient.Builder().callTimeout(12,TimeUnit.SECONDS).followRedirects(false).build()):AutoPriceRules=withContext(Dispatchers.IO) {
    rules.validate();if(rules.usdCad.isNotBlank()) return@withContext rules.copy(fxSource=rules.fxSource.ifBlank {"MANUAL_OVERRIDE"})
    fxLock.withLock {
        var cached=cachedFx();var failure=""
        val now=System.currentTimeMillis();val lastAttempt=preferences.getLong("fxLastAttempt",0)
        if(refresh && (cached==null || cached!!.age()>0) && now-lastAttempt>15*60*1000) {
            preferences.edit().putLong("fxLastAttempt",now).commit()
            try {val fetched=client.newCall(Request.Builder().url(VALET_USD_CAD).header("Accept","application/json").build()).execute().use {response->
                check(response.isSuccessful) {"Bank of Canada rate service returned HTTP ${response.code}"}
                val source=response.body?.source() ?: error("Empty rate response");source.request(512_000);val bytes=source.buffer.readByteArray()
                check(bytes.size<512_000) {"Unexpectedly large rate response"};parseValetRate(bytes.toString(Charsets.UTF_8))
            };check(fetched.age()<=FX_MAX_AGE_DAYS) {"Latest published rate is too old"};check(preferences.edit().putString("verifiedUsdCad",fetched.json().toString()).remove("fxLastError").commit());cached=fetched
            } catch(e:CancellationException) {throw e} catch(e:Exception) {failure=e.message.orEmpty().take(250);preferences.edit().putString("fxLastError",failure).commit()}
        }
        val rate=cached
        if(rate==null) rules.copy(fxWarning="FX_MISSING: Connect to retrieve a Bank of Canada USD/CAD rate. ${failure.ifBlank {preferences.getString("fxLastError","").orEmpty()}}")
        else if(rate.age() !in 0..FX_MAX_AGE_DAYS) rules.copy(fxWarning="FX_STALE: Cached Bank of Canada rate dated ${rate.date} is ${rate.age()} days old; refresh before using USD evidence.")
        else rules.copy(usdCad=rate.rate,rateDate=rate.date,fxSource=rate.source,fxFetchedAt=rate.fetchedAt,fxWarning=if(rate.age()>0 || failure.isNotBlank()) "Using last verified Bank of Canada rate dated ${rate.date} (${rate.age()} days old). ${if(rate.age()>7) "Rate is older than seven days; review prices. " else ""}$failure" else "")
    }
}
