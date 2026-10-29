package ca.canadavhs.snapmobile
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=CaptureApplication::class)
class MarketSmartMatchTest {
 private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
 private lateinit var repo:CaptureRepository
 @Before fun setup() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().commit()}
 @After fun close() {repo.journal.close()}
 private suspend fun sold() {val file=File(app.cacheDir,"smart-${newId()}.csv");file.writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url\n1,The Matrix 1999 VHS,EBAY_US,1001,2026-10-01,10,2,CAD,https://www.ebay.com/itm/1001\n2,The Matrix 1999 VHS,EBAY_US,1002,2026-10-02,12,2,CAD,https://www.ebay.com/itm/1002\n3,Matrix Reloaded 2003 VHS,EBAY_US,1003,2026-10-03,8,2,CAD,https://www.ebay.com/itm/1003\n4,Alien 1979 VHS,EBAY_US,1004,2026-10-04,15,2,CAD,https://www.ebay.com/itm/1004\n5,The Matrix 1999 VHS,EBAY_US,1005,2026-10-05,30,2,USD,https://www.ebay.com/itm/1005\n");val p=repo.previewMarket(file,file.name);repo.commitMarket(p);file.delete()}
 @Test fun transposedOcrWordsAndPartialQueriesUseIndexedLocalCandidates():Unit=runBlocking {sold();val typo=repo.searchMarket(MarketFilters(query="matirx 1999",currency="CAD"));assertTrue(typo.approximate);assertTrue(typo.groups.isNotEmpty());assertTrue(typo.correctedQuery.contains("matrix"));assertTrue(typo.groups.all {it.currency=="CAD"});assertEquals(2L,typo.groups.single().samples);val broad=repo.searchMarket(MarketFilters(query="matrix forgottenword",currency="CAD"));assertTrue(broad.groups.isNotEmpty());assertTrue(broad.groups.all {it.title.contains("Matrix")});assertTrue(broad.groups.all {it.matchScore in 0..100});assertTrue(marketMatchScore("Matrix 1999","Matrix 1999")>marketMatchScore("Matrix 1999","Matrix Reloaded 2003"));assertTrue(repo.searchMarket(MarketFilters(query="993399339933",currency="CAD")).groups.isEmpty())}
 @Test fun strictPriceRecommendationDoesNotAdoptApproximateIdentity():Unit=runBlocking {sold();repo.start("Match fixture","A1");val group=repo.current();val item=repo.ensureInventoryItem(group);val saved=repo.saveInventory(item.copy(title="Matirx",condition="Good"),item.revision);assertTrue(repo.searchMarket(MarketFilters(query=saved.title,currency="CAD")).groups.isNotEmpty());assertNull(repo.recommendMarket(saved.groupId,"CAD"));assertEquals("Matirx",repo.journal.inventoryDao().item(saved.groupId)!!.title);assertNull(repo.journal.inventoryDao().item(saved.groupId)!!.priceCents)}
 private fun candidate()=MarketGroup("matrix","The Matrix","CAD","",1999,2,1200,1400,"2026-10-01",true,85)
 @Test fun optionalSeparateModelRequiresConsentAndSendsOnlyBoundedCandidateText():Unit=runBlocking {val g=candidate();var calls=0;val request:suspend(ChatConversationRow,List<ChatMessageRow>,JSONArray,suspend(String)->Unit)->AiClient.Result={chat,messages,tools,_->calls++;assertEquals("independent-match-model",chat.model);assertFalse(chat.toolsEnabled);assertEquals(0,tools.length());assertEquals("[]",messages.single().attachmentsJson);assertTrue(messages.single().body.length<=12000);AiClient.Result(JSONObject().put("matches",JSONArray().put(JSONObject().put("id",marketMatchId(g)).put("confidence",.9).put("reason","Same film and release year"))).toString(),JSONArray(),"mock",100,20,"0.00001")}
  try {repo.rankMarketMatches("Matrix",null,listOf(g),"OPENAI","independent-match-model",false,request);fail()} catch(_:IllegalStateException) {};assertEquals(0,calls)
  val result=repo.rankMarketMatches("Matrix",null,listOf(g),"OPENAI","independent-match-model",true,request);assertEquals(1,calls);assertEquals(.9,result.first.getValue(marketMatchId(g)).confidence,0.0);assertTrue(result.second.contains("reported USD"));val chat=repo.journal.nextDao().watchChats().first().single();val rows=repo.journal.nextDao().messages(chat.id);assertEquals(2,rows.size);assertEquals(100L,rows.last().inputTokens);assertEquals("MARKET_MATCH_RANKING",JSONObject(rows.last().usageJson).getString("purpose"));assertEquals("COMPLETE",rows.last().status)
 }
 @Test fun inventedCandidatesDuplicatesAndPricesAreRejected() {val g=candidate();fun objectValue(id:String=marketMatchId(g))=JSONObject().put("id",id).put("confidence",.5).put("reason","fixture")
 for(array in listOf(JSONArray().put(objectValue(newId())),JSONArray().put(objectValue()).put(objectValue()),JSONArray().put(objectValue().put("price",999)))) {try {parseMarketAiMatches(JSONObject().put("matches",array).toString(),listOf(g));fail()} catch(_:IllegalArgumentException) {}}}
 @Test fun providerFailureDoesNotFallbackOrWriteEvidence():Unit=runBlocking {var calls=0;try {repo.rankMarketMatches("Matrix",null,listOf(candidate()),"OPENAI","selected-model",true) {_,_,_,_->calls++;throw java.io.IOException("Simulated transport failure")};fail()} catch(_:java.io.IOException) {};assertEquals(1,calls);val count=repo.journal.openHelper.writableDatabase.query("SELECT COUNT(*) FROM price_decisions").use {it.moveToFirst();it.getInt(0)};assertEquals(0,count)}
}
