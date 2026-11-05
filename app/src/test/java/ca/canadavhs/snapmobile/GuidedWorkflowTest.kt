package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import ca.canadavhs.snapmobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class GuidedWorkflowTest {
 private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
 private lateinit var repo:CaptureRepository
 private lateinit var gateway:DirectShopifyGateway
 private val location="gid://shopify/Location/1"
 @Before fun setup() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=CaptureRepository(app,Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}};app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).putLong("fxLastAttempt",System.currentTimeMillis()).putBoolean("shopifyDirect",true).putBoolean("shopifyPaired",true).putBoolean("shopifyOwnerWrites",true).commit();val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();gateway=DirectShopifyGateway(app,SecureCredentialStore(app,"guided-fixture-${newId()}") {key},OkHttpClient(),ShopifyUploadVault(app) {key});gateway.save("fixture.myshopify.com",newId(),"fixture-secret");repo.directShopifyFactory={gateway}}
 @After fun close() {repo.journal.close()}
 private fun health()=JSONObject().put("shopId","gid://shopify/Shop/1").put("shop","fixture.myshopify.com").put("currency","CAD").put("developmentStore",false).put("direct",true).put("allowProduction",true).put("publications",JSONArray()).put("locations",JSONArray().put(JSONObject().put("id",location).put("isActive",true)))
 private suspend fun inventory(titles:List<String>):List<InventoryRow> {val session=repo.start("Guided six copies","A1");val first=repo.current();val b=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888);val bytes=java.io.ByteArrayOutputStream().use {b.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};b.recycle();return titles.mapIndexed {i,title->val g=if(i==0) first else GroupRow(sessionId=session.id,number=i+1,closed=true,location="A1").also {repo.dao.insert(it)};val item=repo.ensureInventoryItem(g);val p=PhotoRow(groupId=g.id,position=0,role="FRONT",state="READY",scanState="OFF",bytes=bytes.size.toLong(),sha256=sha256(bytes));repo.original(p.id).writeBytes(bytes);repo.dao.insert(p);repo.saveInventory(item.copy(title=title,releaseYear=1979,condition="Good"),item.revision)}}
 private suspend fun sales(count:Int=5,currency:String="USD",title:String="Alien 1979 VHS",price:String="10",barcode:String="",year:String="1979",edition:String="",condition:String="Good",itemPrefix:String="1000") {
  val file=File(app.cacheDir,"guided-${newId()}.csv");file.writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url,barcode,edition,release_year,condition\n"+(1..count).joinToString("\n") {i->val id=itemPrefix+i;"$i,$title,EBAY_US,$id,${LocalDate.now().minusDays(2)},$price,2,$currency,https://www.ebay.com/itm/$id,$barcode,$edition,$year,$condition"});val p=repo.previewMarket(file,file.name);repo.commitMarket(p);p.file.delete();file.delete()
 }
 private fun cached(days:Long=0) {repo.preferences.edit().putString("verifiedUsdCad",VerifiedFx("1.35",LocalDate.now().minusDays(days).toString(),System.currentTimeMillis()).json().toString()).commit()}
 private fun valet(date:String=LocalDate.now().toString(),rate:String="1.35")=JSONObject().put("seriesDetail",JSONObject().put("FXUSDCAD",JSONObject().put("label","USD/CAD"))).put("observations",JSONArray().put(JSONObject().put("d",date).put("FXUSDCAD",JSONObject().put("v",rate)))).toString()
 @Test fun sixBlankIdentitiesCreateDraftsButPublicationExplainsMissingVerifiedDraft():Unit=runBlocking {
  val rows=inventory(List(6) {"Alien"}).map {repo.saveInventory(it.copy(priceCents=1200,description="Actual VHS copy"),it.revision)}
  val failure=runCatching {validatePublicationIdentities(rows)}.exceptionOrNull()!!;assertTrue(failure.message!!.contains("verified"));assertFalse(failure.message!!.contains("multiple"))
  val response=repo.submitSelectedDrafts(rows.map {it.groupId}.toSet(),health(),gateway.endpoint(),location,emptySet());assertEquals(0,response.skipped.size);val queued=repo.journal.shopifyDao().items(response.jobs.single());assertEquals(6,queued.size);assertEquals(6,queued.map {it.itemId}.distinct().size);assertEquals(6,queued.map {it.creationKey}.distinct().size);assertTrue(queued.none {it.state=="SUCCESS"})
  val linked=rows.take(2).map {it.copy(shopifyProductId="gid://shopify/Product/1",shopifyState="UPLOADED_AS_DRAFT")};assertTrue(runCatching {validatePublicationIdentities(linked)}.exceptionOrNull()!!.message!!.contains("multiple"))
  assertTrue(runCatching {validatePublicationIdentities(linked.mapIndexed {i,r->r.copy(shopifyProductId="gid://shopify/Product/${i+1}")})}.isSuccess)
 }
 @Test fun missingFxIsAnExplicitBlockAndVerifiedCachedRateConvertsSoldAndShippingOffline():Unit=runBlocking {
  sales();val item=inventory(listOf("Alien")).single();val missing=repo.buildAutoPrice(item,AutoPriceRules());assertEquals("FX_MISSING",missing.getString("reasonCode"));assertFalse(missing.has("priceCents"));cached(3)
  val priced=repo.buildAutoPrice(item,AutoPriceRules());assertEquals(1620L,priced.getLong("priceCents"));assertEquals("HIGH_CONFIDENCE",priced.getString("category"));assertEquals(VALET_USD_CAD,priced.getJSONObject("fx").getString("source"));assertTrue(priced.getJSONArray("warnings").toString().contains("3 days old"))
  val reopened=CaptureRepository(app,repo.journal);assertEquals("1.35",reopened.cachedFx()!!.rate);cached(31);val stale=repo.buildAutoPrice(item,AutoPriceRules());assertEquals("FX_STALE",stale.getString("reasonCode"));assertFalse(stale.has("priceCents"))
 }
 @Test fun valetNetworkParsingPersistenceWeekendsAndInvalidRates():Unit=runBlocking {
  repo.preferences.edit().remove("fxLastAttempt").commit();var calls=0
  val date=LocalDate.now().minusDays(3);val client=OkHttpClient.Builder().addInterceptor {chain->calls++;assertEquals(VALET_USD_CAD,chain.request().url.toString());Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(valet(date.toString()).toResponseBody("application/json".toMediaType())).build()}.build()
  val rules=repo.resolvePriceFx(AutoPriceRules(),client=client);assertEquals("1.35",rules.usdCad);assertEquals(date.toString(),rules.rateDate);assertEquals(1,calls);repo.resolvePriceFx(AutoPriceRules(),client=client);assertEquals(1,calls)
  assertEquals("1.35",repo.cachedFx()!!.rate);assertTrue(runCatching {parseValetRate(valet(rate="0"))}.isFailure);assertTrue(runCatching {parseValetRate(valet(rate="NaN"))}.isFailure);assertTrue(runCatching {parseValetRate(valet(LocalDate.now().plusDays(1).toString()))}.isFailure)
  repo.preferences.edit().remove("fxLastAttempt").commit();val offline=OkHttpClient.Builder().addInterceptor {throw java.io.IOException("offline fixture")}.build();assertEquals("1.35",repo.resolvePriceFx(AutoPriceRules(),client=offline).usdCad)
 }
 @Test fun oneReliableSaleSupportsIndividualApprovalButCannotBeBulkApproved():Unit=runBlocking {
  sales(1,"CAD",barcode="036000291452");val item=inventory(listOf("Alien")).single();val j=repo.queueProduction(setOf(item.groupId),"MATCH_PRICE",AutoPriceRules(minimum=100).json());repo.runProduction(j);val r=repo.journal.productionDao().items(j).single();val data=JSONObject(r.resultJson);assertEquals("NEEDS_REVIEW",r.state);assertTrue(data.getInt("score") in 65..84);assertEquals(1200L,data.getLong("priceCents"));assertEquals(0,repo.applyProductionPrices(setOf(r.id)).first);assertEquals(1,repo.applyProductionPrices(setOf(r.id),reviewedIndividual=true).first)
 }
 @Test fun hardIdentityConflictsAlwaysDefeatManySalesAndFuzzyRetrievalIsOnlyACandidate():Unit=runBlocking {
  sales(6,"CAD",title="Aliens 1986 VHS",year="1986");sales(6,"CAD",title="Alien 1979 DVD",itemPrefix="2000");sales(6,"CAD",title="Alien Special Edition 1979 VHS",edition="Special Edition",itemPrefix="3000");sales(6,"CAD",title="Alien 1979 VHS lot of 2",itemPrefix="4000")
  val item=inventory(listOf("Alien")).single();val data=repo.buildAutoPrice(item,AutoPriceRules());assertFalse(data.has("priceCents"));assertEquals("INCOMPATIBLE_EVIDENCE",data.getString("reasonCode"));assertTrue(data.toString(),data.getJSONObject("excludedCounts").has("FORMAT_CONFLICT"));assertTrue(data.toString(),data.getJSONObject("excludedCounts").has("MULTI_ITEM"));assertTrue(data.toString(),data.getJSONObject("excludedCounts").has("EDITION_UNKNOWN"))
  sales(3,"CAD",title="Terminator 2 Judgment Day 1991 VHS",year="1991",itemPrefix="5000");assertTrue(repo.searchMarket(MarketFilters(query="Termlnator 2 Judgement Day")).groups.any {it.title.contains("Terminator 2")});assertTrue(repo.searchMarket(MarketFilters(query="Judgment Day Terminator 2")).groups.any {it.title.contains("Terminator 2")});assertTrue(repo.searchMarket(MarketFilters(query="Terminator")).groups.any {it.title.contains("Terminator 2")})
  assertTrue(comparableExclusion(item,repo.journal.marketDao().sale(JSONObject(data.getJSONArray("exclusions").getJSONObject(0).toString()).getString("id"))!!) !=null)
 }
 @Test fun duplicateListingIdsDoNotInflateEvidenceAndWordOrderCanBeCompatible():Unit=runBlocking {
  sales(1,"CAD",title="Back to the Future 1985 VHS",year="1985");val item=inventory(listOf("Future Back to the")).single().copy(releaseYear=1985)
  val data=repo.buildAutoPrice(item,AutoPriceRules());assertEquals(1,data.getInt("count"));assertEquals("NEEDS_REVIEW",data.getString("category"))
  val id=data.getJSONArray("evidence").getJSONObject(0).getString("id");val raw=repo.journal.openHelper.writableDatabase.query("SELECT * FROM market_sales WHERE id=?",arrayOf(id)).use {c->c.moveToFirst();cursorObject(c)};raw.put("id",newId()).put("sourceKey","duplicate-cross-date").put("soldAt",LocalDate.now().minusDays(1).toString());repo.journal.openHelper.writableDatabase.insert("market_sales",android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,marketValues(raw));val again=repo.buildAutoPrice(item,AutoPriceRules());assertEquals(1,again.getInt("count"));assertEquals(1,again.getJSONObject("excludedCounts").getInt("DUPLICATE_LISTING"))
 }
 @Test fun orchestrationIsDurableIndependentIdempotentAndWaitsForApproval():Unit=runBlocking {
  cached();sales();val rows=inventory(listOf("Alien","Alien","Unknown Film","Alien","Alien","Alien"));val manual=repo.saveInventory(rows.last().copy(priceCents=777),rows.last().revision);val ids=rows.map {it.groupId}.toSet();val session=repo.dao.group(rows[0].groupId).sessionId
  val id=repo.startGuidedBatch(session,ids);assertEquals(id,repo.startGuidedBatch(session,ids));assertFalse(repo.runGuidedBatch(id));val items=repo.journal.productionDao().items(id);assertEquals(6,items.size);assertEquals(4,items.count {guidedPhase(it)=="PRICE_RECOMMENDED"});assertEquals(1,items.count {guidedPhase(it)=="PRICE_REVIEW"});assertEquals(777L,repo.journal.inventoryDao().item(manual.groupId)!!.priceCents)
  val prices=rows.dropLast(1).mapNotNull {repo.journal.productionDao().latestPrice(it.groupId)}.filter {it.state=="HIGH_CONFIDENCE"};assertEquals(4,repo.applyProductionPrices(prices.map {it.id}.toSet()).first)
  val reopened=CaptureRepository(app,repo.journal);reopened.directShopifyFactory={gateway};assertEquals(id,reopened.startGuidedBatch(session,ids));reopened.runGuidedBatch(id);val valid=rows.filter {it.groupId!=rows[2].groupId}.map {it.groupId}.toSet();assertEquals(5,reopened.approveGuidedListings(valid).first)
  val response=reopened.submitSelectedDrafts(valid,health(),gateway.endpoint(),location,emptySet());assertEquals(5,reopened.journal.shopifyDao().items(response.jobs.single()).size);assertTrue(reopened.submitSelectedDrafts(valid,health(),gateway.endpoint(),location,emptySet()).jobs.isEmpty());assertNull(reopened.journal.inventoryDao().item(rows[2].groupId)!!.priceCents)
  assertEquals(0,reopened.journal.nextDao().watchChats().first().size);assertEquals("ok",reopened.journal.openHelper.writableDatabase.query("PRAGMA integrity_check").use {it.moveToFirst();it.getString(0)})
 }
 @Test fun completedOfflineOcrIsCorroboratedAndUncertainTextWaitsWithoutPaidRequests():Unit=runBlocking {
  cached();sales();val rows=inventory(listOf("",""));val session=repo.dao.group(rows.first().groupId).sessionId
  for((i,item) in rows.withIndex()) {val photos=repo.dao.photos(item.groupId);repo.dao.insert(RecognitionRow(groupId=item.groupId,provider="OCR",model="fixture",inputFingerprint=photoFingerprint(photos),inputJson=photoSnapshot(photos),state="SUCCEEDED",resultJson=JSONObject().put("title","").put("text_lines",JSONArray().put(if(i==0) "Alien" else "Aliern" )).put("ocr_character_confidence",.99).toString()))}
  val job=repo.startGuidedBatch(session,rows.map {it.groupId}.toSet());repo.runGuidedBatch(job);assertEquals("Alien",repo.journal.inventoryDao().item(rows[0].groupId)!!.title);assertEquals("",repo.journal.inventoryDao().item(rows[1].groupId)!!.title);assertEquals("IDENTIFY_REVIEW",guidedPhase(repo.journal.productionDao().items(job).first {it.groupId==rows[1].groupId}));assertEquals(0,repo.dao.unsettledRecognition().size)
  repo.deferGuided(rows[1].groupId);repo.startGuidedBatch(session,rows.map {it.groupId}.toSet());repo.runGuidedBatch(job);assertEquals("DEFERRED",guidedPhase(repo.journal.productionDao().items(job).first {it.groupId==rows[1].groupId}));assertEquals(0,repo.journal.nextDao().watchChats().first().size)
 }
 @Test fun changedInventoryEvidenceAndFxCannotApplyStaleApprovals():Unit=runBlocking {
  cached();sales();val item=inventory(listOf("Alien")).single();val job=repo.queueProduction(setOf(item.groupId),"MATCH_PRICE",AutoPriceRules().json());repo.runProduction(job);val result=repo.journal.productionDao().items(job).single();repo.saveInventory(item.copy(title="Aliens"),item.revision);assertEquals(0,repo.applyProductionPrices(setOf(result.id)).first)
  val data=JSONObject(result.resultJson);data.getJSONObject("rules").put("rateDate",LocalDate.now().minusDays(31).toString());assertTrue(data.toString().contains("fxSource"));assertEquals(0,repo.journal.shopifyDao().active().size)
 }
 @Test fun targetedRetryKeepsOtherCopiesPausedAndRetainsCreationIdentity():Unit=runBlocking {
  val items=inventory(List(3) {"Alien"}).map {repo.saveInventory(it.copy(priceCents=1200,description="Actual copy"),it.revision)}
  val submitted=repo.submitSelectedDrafts(items.map {it.groupId}.toSet(),health(),gateway.endpoint(),location,emptySet());val d=repo.journal.shopifyDao();val job=d.job(submitted.jobs.single())!!;val rows=d.items(job.id)
  d.update(job.copy(state="PARTIAL_REVIEW"));rows.forEach {d.update(it.copy(state="RETRYABLE",step="CREATE",checkpointJson="{\"attemptRecorded\":true}"))}
  repo.resumeShopify(job.id,gateway.endpoint(),health(),setOf(rows.first().groupId));val after=d.items(job.id)
  assertEquals(1,after.count {it.state=="QUEUED"});assertEquals(2,after.count {it.state=="PAUSED"});assertEquals(rows.map {it.creationKey},after.map {it.creationKey});assertTrue(after.all {JSONObject(it.checkpointJson).getBoolean("attemptRecorded")});assertTrue(after.none {it.state=="SUCCESS"})
 }
 @Test fun sameAmountManualConfirmationProtectsThePriceAndBarcodeNormalizationIsIndexed():Unit=runBlocking {
  sales(1,"CAD",barcode="036000291452");val item=inventory(listOf("Alien")).single();assertTrue(repo.searchMarket(MarketFilters(query="00036000291452")).groups.any {it.matchTier=="EXACT"})
  val saved=repo.saveInventory(item.copy(priceCents=1200),item.revision);val simulatedMarket=saved.copy(priceSource="MARKET_AUTO_MEDIAN");repo.journal.inventoryDao().update(simulatedMarket)
  val manual=repo.saveGuidedManualPrice(simulatedMarket,1200);assertTrue(protectedManualPrice(manual));assertEquals("MANUAL",manual.priceSource);assertTrue(repo.journal.marketDao().decisionsForItem(item.groupId).any {it.method=="MANUAL"})
 }
 @Test fun actualFileDatabaseReopensAndResumesWithoutDuplicatingCompletedPriceJobs():Unit=runBlocking {
  repo.journal.close();val name="guided-restart-${newId()}.sqlite"
  fun open()=CaptureRepository(app,Room.databaseBuilder(app,CaptureJournal::class.java,name).allowMainThreadQueries().build()) {d->java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}
  repo=open();app.repository=repo;cached();sales();val copies=inventory(listOf("Alien","Unknown Film"));val session=repo.dao.group(copies.first().groupId).sessionId;val job=repo.startGuidedBatch(session,copies.map {it.groupId}.toSet());repo.runGuidedBatch(job);repo.deferGuided(copies.last().groupId);val before=repo.journal.productionDao().watchJobs().first().map {it.id}.toSet();repo.journal.productionDao().update(repo.journal.productionDao().job(job)!!.copy(state="RUNNING"));repo.journal.close()
  repo=open();app.repository=repo;assertEquals(job,repo.journal.productionDao().guided(session)!!.id);repo.recoverProduction();repo.runGuidedBatch(job)
  assertEquals(before,repo.journal.productionDao().watchJobs().first().map {it.id}.toSet());assertEquals("DEFERRED",guidedPhase(repo.journal.productionDao().items(job).first {it.groupId==copies.last().groupId}));assertEquals("PRICE_RECOMMENDED",guidedPhase(repo.journal.productionDao().items(job).first {it.groupId==copies.first().groupId}));assertEquals("ok",repo.journal.openHelper.writableDatabase.query("PRAGMA integrity_check").use {it.moveToFirst();it.getString(0)})
 }
 @Test fun sellerNoiseNeverRemovesLegitimateFilmWordsAndLotInTitleIsNotABundle():Unit=runBlocking {
  val item=inventory(listOf("Case 39")).single().copy(releaseYear=2009)
  val sale=MarketSaleRow(newId(),"fixture","a".repeat(64),title="39 2009 VHS",titleKey="39 2009",releaseYear=2009,soldDecimal="10",shippingDecimal="2",totalCents=1200,currency="CAD",soldAt=LocalDate.now().toString(),url="https://example.com/fixture",site="FIXTURE",sourceItemId="1",observedAt=LocalDate.now().toString(),importId=newId(),rawJson="{}")
  assertNotNull(comparableExclusion(item,sale));assertNull(comparableExclusion(item.copy(title="Salem's Lot",releaseYear=1979),sale.copy(title="Salem's Lot 1979 VHS",releaseYear=1979)));assertNotNull(comparableExclusion(item.copy(title="Salem's Lot",releaseYear=1979),sale.copy(title="Salem's Lot 1979 VHS lot of 2",releaseYear=1979)))
 }
 @Test fun importedBroadUsedConditionSupportsUsedInventoryWithoutInventingAGrade():Unit=runBlocking {
  cached();sales(condition="used");val item=inventory(listOf("Alien")).single();val result=repo.buildAutoPrice(item,AutoPriceRules());assertEquals("HIGH_CONFIDENCE",result.getString("category"));assertEquals(0,result.getJSONObject("scoreFactors").getJSONObject("condition").getInt("points"));assertEquals(5,result.getInt("count"));assertFalse(repo.buildAutoPrice(item.copy(condition="Sealed"),AutoPriceRules()).has("priceCents"));assertFalse(repo.buildAutoPrice(item.copy(condition="Damaged"),AutoPriceRules()).has("priceCents"))
 }
 @Test fun rateAgingIsRecheckedAtApprovalAndNewSalesCannotPriceUnknownCondition():Unit=runBlocking {
  cached();sales();val items=inventory(listOf("Alien","Alien"));val id=repo.queueProduction(items.map {it.groupId}.toSet(),"MATCH_PRICE",AutoPriceRules().json());repo.runProduction(id);val d=repo.journal.productionDao();val rows=d.items(id)
  for((index,row) in rows.withIndex()) {val data=JSONObject(row.resultJson);data.getJSONObject("rules").put("rateDate",LocalDate.now().minusDays(if(index==0) 8 else 31).toString());d.update(row.copy(resultJson=data.toString()))}
  assertEquals(0,repo.applyProductionPrices(setOf(rows[0].id)).first);assertEquals(1,repo.applyProductionPrices(setOf(rows[0].id),reviewedIndividual=true).first);assertEquals(0,repo.applyProductionPrices(setOf(rows[1].id),reviewedIndividual=true).first)
  val sold=repo.journal.marketDao().sale(JSONObject(rows[0].resultJson).getJSONArray("evidence").getJSONObject(0).getString("id"))!!
  assertFalse(compatibleAutoCondition(items[0].copy(condition="Not Graded"),sold.copy(rawJson="{\"condition\":\"new\"}")))
 }
 @Test fun closingACopyQueuesOneOfflineWorkflowEvenWhenOptionalAiRecognitionIsSelected():Unit=runBlocking {
  cached();sales();val item=inventory(listOf("")).single();val session=repo.dao.group(item.groupId).sessionId
  repo.preferences.edit().putString("recognitionMode","OPENAI").commit()
  repo.next();val guide=repo.journal.productionDao().guided(session)!!;assertEquals(setOf(item.groupId),repo.journal.productionDao().items(guide.id).map {it.groupId}.toSet())
  assertTrue(repo.runGuidedBatch(guide.id));val attempt=repo.dao.unsettledRecognition().single();assertEquals("OCR",attempt.provider);assertEquals("OFFLINE",attempt.authBinding);assertEquals("GUIDED:${guide.id}",attempt.approvalId)
  repo.advanceCapturedBatch(session);assertEquals(guide.id,repo.journal.productionDao().guided(session)!!.id);repo.runGuidedBatch(guide.id);assertEquals(1,repo.dao.unsettledRecognition().size)
  assertEquals(0,repo.journal.nextDao().watchChats().first().size);assertEquals(0,repo.journal.shopifyDao().active().size)
 }
 @Test fun printedEditionCannotBeSilentlyDiscardedDuringAutomaticIdentification():Unit=runBlocking {
  sales();val item=inventory(listOf("")).single()
  val identified=repo.identifyGuided(item,JSONObject().put("text_lines",JSONArray().put("Alien").put("Special Edition")).put("ocr_character_confidence",.99))
  assertNull(identified.title);assertTrue(identified.reason.contains("special edition"));assertTrue(identified.candidates.length()>0)
 }

 @Test fun batchProgressRemainsVisibleAfterMoreThanOneHundredChildJobs():Unit=runBlocking {
  val item=inventory(listOf("Alien")).single();val session=repo.dao.group(item.groupId).sessionId;val id=repo.startGuidedBatch(session,setOf(item.groupId));val d=repo.journal.productionDao()
  repeat(150) {val child=ProductionJobRow(kind="MATCH_PRICE",state="COMPLETE",createdAt=System.currentTimeMillis()+it+1000);d.insert(child);d.insert(ProductionItemRow(jobId=child.id,groupId=item.groupId,revision=item.revision,state="NEEDS_REVIEW"))}
  assertEquals(id,d.watchGuided(session).first()!!.id);assertEquals(id,d.guided(session)!!.id)
 }

 @Test fun confirmedEditionCanBeCorroboratedByPrintedSoldTitleWithoutInventingMissingFields():Unit=runBlocking {
  sales(currency="CAD",title="Alien Special Edition 1979 VHS");val item=inventory(listOf("Alien")).single();assertFalse(repo.buildAutoPrice(item,AutoPriceRules()).has("priceCents"))
  val confirmed=repo.saveInventory(item.copy(edition="Special Edition"),item.revision);val result=repo.buildAutoPrice(confirmed,AutoPriceRules());assertEquals(5,result.getInt("count"));assertEquals(1200L,result.getLong("priceCents"))
  val sale=repo.journal.marketDao().sale(result.getJSONArray("evidence").getJSONObject(0).getString("id"))!!;assertNotNull(comparableExclusion(confirmed,sale.copy(edition="Limited Edition")))
 }

 @Test fun correctedIdentityRefreshesOnlyWorkflowGeneratedDescriptions():Unit=runBlocking {
  cached();val copies=inventory(listOf("Alien","Alien")).map {repo.saveInventory(it.copy(priceCents=777),it.revision)};val session=repo.dao.group(copies.first().groupId).sessionId;val job=repo.startGuidedBatch(session,copies.map {it.groupId}.toSet());repo.runGuidedBatch(job)
  for((index,copy) in copies.withIndex()) {val current=repo.journal.inventoryDao().item(copy.groupId)!!;assertTrue(current.description.contains("Alien"));repo.saveInventory(current.copy(title="Aliens",description=if(index==1) "Operator description; retain exactly." else current.description),current.revision)}
  repo.startGuidedBatch(session,copies.map {it.groupId}.toSet());repo.runGuidedBatch(job)
  assertTrue(repo.journal.inventoryDao().item(copies[0].groupId)!!.description.contains("Aliens"));assertEquals("Operator description; retain exactly.",repo.journal.inventoryDao().item(copies[1].groupId)!!.description);assertTrue(copies.all {repo.journal.inventoryDao().item(it.groupId)!!.priceCents==777L})
 }

}
