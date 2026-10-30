package ca.canadavhs.snapmobile

import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

/** 100k generated fixtures are synthetic. Optional owner CSV stays outside repository/APK. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class MarketDatasetValidationTest {
    @Test fun largeOfflineImportIncrementalSearchAndRestartWithOptionalActualHarvesterFile():Unit=runBlocking {
        val app=RuntimeEnvironment.getApplication() as CaptureApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build())
        val name="market-benchmark-${newId()}.db";val root=File(app.filesDir,"large-${newId()}").apply {mkdirs()}
        var journal=Room.databaseBuilder(app,CaptureJournal::class.java,name).allowMainThreadQueries().build()
        fun repository()=CaptureRepository(app,journal,root) {directory->java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}
        var repo=repository();app.repository=repo
        val report=JSONObject();val output=File(System.getProperty("snapims.testOutput"),"market-benchmark.json").apply {parentFile!!.mkdirs()}
        fun log(text:String) {println(text);File(output.parentFile,"market-import-progress.txt").appendText(text+"\n")}
        fun count()=journal.openHelper.writableDatabase.query("SELECT COUNT(*) FROM market_sales WHERE current=1").use {it.moveToFirst();it.getLong(0)}
        try {
            val fixture=File(app.cacheDir,"synthetic-100k-${newId()}.csv")
            fixture.bufferedWriter().use {out->out.appendLine("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url,barcode,edition,release_year")
                for(i in 0 until 100_000) {val title="Fixture Movie "+(i%1000).toString().padStart(4,'0');val id=(900000000000L+i).toString();out.appendLine("$i,$title,EBAY_US,$id,2026-09-${(i%28+1).toString().padStart(2,'0')},${(i%100+100)/10.0},2.50,USD,https://www.ebay.com/itm/$id,036000291452,,")}}
            var started=System.nanoTime();val preview=repo.previewMarket(fixture,fixture.name) {if(it%10000==0L) log("Synthetic preview $it / 100000")};assertEquals(100_000L,preview.rows)
            report.put("syntheticPreviewMs",(System.nanoTime()-started)/1_000_000)
            started=System.nanoTime();assertEquals(100_000L,repo.commitMarket(preview) {done,total->if(done%10000==0L) log("Synthetic import $done / $total")}.added);assertEquals(100_000L,count());report.put("syntheticImportMs",(System.nanoTime()-started)/1_000_000)
            started=System.nanoTime();assertEquals(0L,repo.commitMarket(preview).added);report.put("repeatImportMs",(System.nanoTime()-started)/1_000_000)
            val times=mutableListOf<Long>();repeat(12) {iteration->val query="Fixture Movie "+((iteration*71)%1000).toString().padStart(4,'0');val time=System.nanoTime();val result=repo.searchMarket(MarketFilters(query=query,currency="USD"));assertEquals(100L,result.groups.first {it.titleKey==InventoryRules.normalizedTitle(query)}.samples);assertTrue(result.groups.size>1);times+=(System.nanoTime()-time)/1_000_000}
            val barcode=repo.searchMarket(MarketFilters(query="036000291452"));assertEquals(40,barcode.groups.size)
            report.put("syntheticRecords",100000).put("queryTimesMs",JSONArray(times)).put("medianQueryMs",times.sorted()[times.size/2]).put("index",repo.marketIndexKind()).put("physicalPixelBenchmark","UNRUN")
            preview.file.delete();fixture.delete()
            val actual=System.getProperty("snapims.marketCsv")?.let(::File)
            var expected=100_000L
            if(actual!=null) {
                assertTrue(actual.isFile);val before=hashFile(actual);started=System.nanoTime();val p=repo.previewMarket(actual,actual.name) {if(it%10000==0L) log("Owner CSV preview $it")}
                assertEquals(75054L,p.rows);assertEquals(mapOf("USD" to 75054L),p.currencies);assertTrue(p.warnings.any {it.contains("collection timestamp")})
                val imported=repo.commitMarket(p) {done,total->if(done%10000==0L) log("Owner CSV import $done / $total")};assertEquals(75054L,imported.added);expected+=75054L;assertEquals(expected,count());assertEquals(before,hashFile(actual))
                assertEquals(0L,repo.commitMarket(p).added)
                report.put("ownerCsvRows",p.rows).put("ownerCsvSha256",before).put("ownerCsvImportMs",(System.nanoTime()-started)/1_000_000).put("ownerCsvSourceUnchanged",true).put("ownerCsvRepeatedAdded",0)
                assertTrue(repo.searchMarket(MarketFilters(query="Disney",currency="USD")).groups.isNotEmpty());p.file.delete()
            } else report.put("ownerCsvValidation","Not requested in this run; no owner data bundled")
            // One hundred selected physical-copy records, against the same 175k library, with zero model requests.
            val session=repo.start("Offline bulk pricing benchmark","A1");val first=repo.current();val selected=linkedSetOf<String>()
            for(i in 0 until 100) {val group=if(i==0) first else GroupRow(sessionId=session.id,number=i+1,location="A1").also {repo.dao.insert(it)};val item=repo.ensureInventoryItem(group);val title=when {i<50->"Fixture Movie "+i.toString().padStart(4,'0');i<75->"Fxiture Movie "+(i-50).toString().padStart(4,'0');else->"Unfindable Qxzzy title "+i};repo.saveInventory(item.copy(title=title,condition="Good"),item.revision);selected+=item.groupId}
            val pricingStart=System.nanoTime();val pricingJob=repo.queueProduction(selected,"MATCH_PRICE",AutoPriceRules(minimum=10,ageDays=36500,usdCad="1.35",rateDate=java.time.LocalDate.now().toString()).json());var providerCalls=0
            repo.runProduction(pricingJob) {_,_,_,_,_->providerCalls++;error("Deterministic bulk pricing must never dispatch a model request")}
            val outcomes=journal.productionDao().items(pricingJob);assertEquals(100,outcomes.size);assertEquals(50,outcomes.count {it.state=="HIGH_CONFIDENCE"});assertTrue(outcomes.filter {it.groupId in selected.drop(50)}.none {it.state=="HIGH_CONFIDENCE"});assertEquals(0,providerCalls)
            val applied=repo.applyProductionPrices(outcomes.filter {it.state=="HIGH_CONFIDENCE"}.map {it.id}.toSet());assertEquals(50,applied.first);assertTrue(applied.second.isEmpty());assertEquals(50,journal.inventoryDao().items().count {it.priceSource.startsWith("MARKET_AUTO_")});assertEquals(expected,count())
            report.put("offlineBulkSelected",100).put("offlineBulkHighConfidence",50).put("offlineBulkApplied",50).put("offlineBulkProviderRequests",providerCalls).put("offlineBulkMs",(System.nanoTime()-pricingStart)/1_000_000).put("bulkFxRate","1.35 synthetic test configuration, not a fetched quote")
            // Exercise the exact agent gateway against the imported native SQLite library.
            val agentChat=ChatConversationRow(title="Private dataset agent validation",provider="LOCAL",model="fixture",authMode="API_KEY",toolsEnabled=true)
            journal.nextDao().insert(agentChat);val agent=AgentTools(repo)
            suspend fun agentRead(resource:String,args:JSONObject=JSONObject())=agent.execute(agentChat,"app_read",JSONObject().put("resource",resource).put("arguments_json",args.toString()))
            val syntheticRead=agentRead("market.search",JSONObject().put("query","Fixture Movie 0042").put("currency","USD"));assertEquals(100,syntheticRead.getJSONArray("items").getJSONObject(0).getInt("count"));assertTrue(syntheticRead.toString().length<=10000)
            if(actual!=null) {val realRead=agentRead("market.search",JSONObject().put("query","Disney").put("currency","USD").put("limit",50));assertTrue(realRead.getJSONArray("items").length()>0);assertTrue(realRead.toString().length<=10000);report.put("agentActualCsvSearch","PASS").put("agentResultCharacters",realRead.toString().length)}
            assertEquals(expected,agent.execute(agentChat,"app_overview",JSONObject()).getLong("soldObservations"));report.put("agentNativeLibraryRead","PASS").put("agentNoProviderRequests",true)
            journal.openHelper.writableDatabase.query("PRAGMA quick_check").use {assertTrue(it.moveToFirst());assertEquals("ok",it.getString(0))}
            journal.close();journal=Room.databaseBuilder(app,CaptureJournal::class.java,name).allowMainThreadQueries().build();repo=repository();app.repository=repo
            assertEquals(expected,count());assertTrue(repo.searchMarket(MarketFilters(query="Fixture Movie 0042",currency="USD")).groups.any {it.titleKey=="fixture 0042"})
            report.put("totalCurrentRowsAfterRestart",expected).put("restartPreserved",true);output.writeText(report.toString(2));log("Market validation complete: $expected current observations; host median ${times.sorted()[times.size/2]}ms. Physical Pixel performance UNRUN.")
        } finally {journal.close()}
    }
}
