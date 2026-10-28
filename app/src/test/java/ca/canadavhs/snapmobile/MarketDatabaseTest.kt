package ca.canadavhs.snapmobile

import android.graphics.Bitmap
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class MarketDatabaseTest {
    private val app get()=RuntimeEnvironment.getApplication() as CaptureApplication
    private val databases=mutableListOf<CaptureJournal>();private lateinit var repo:CaptureRepository
    private fun create():CaptureRepository {val db=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();databases+=db;return CaptureRepository(app,db,File(app.filesDir,"market-${newId()}").apply {mkdirs()}) {directory->java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}}
    @Before fun before() {WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build());repo=create();app.repository=repo;repo.preferences.edit().clear().putBoolean("barcode",false).commit()}
    @After fun after() {databases.forEach {it.close()}}
    private fun csv(rows:List<String>):File=File(app.cacheDir,"fixture-${newId()}.csv").apply {writeText("id,title,site,item_id,date_sold,sale_price,shipping_price,currency,url,barcode,edition,release_year\n"+rows.joinToString("\n")+"\n")}
    private fun row(id:String,title:String="Alien",sold:String="10",shipping:String="2",currency:String="USD",date:String="2020-01-01",barcode:String="",edition:String="",year:String="")="$id,$title,EBAY_US,$id,$date,$sold,$shipping,$currency,https://www.ebay.com/itm/$id,$barcode,$edition,$year"
    private suspend fun import(vararg values:String):MarketPreview {val input=csv(values.toList());val p=repo.previewMarket(input,input.name);repo.commitMarket(p);input.delete();return p}
    private fun count(table:String,where:String="1")=repo.journal.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use {it.moveToFirst();it.getLong(0)}
    private suspend fun item():InventoryRow {repo.start("Fixture","A1");val p=repo.reserve();val b=Bitmap.createBitmap(30,40,Bitmap.Config.ARGB_8888);repo.pendingFile(p.id).outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle();repo.complete(p.id);val old=repo.journal.inventoryDao().item(p.groupId)!!;return repo.saveInventory(old.copy(title="Alien",priceCents=777),old.revision)}
    @Test fun importsDeduplicateAcrossDifferentFilesAndPreserveChangedHistoricalPayloads():Unit=runBlocking {
        val first=import(row("1"),row("2",currency="CAD"));assertEquals(2L,count("market_sales"));val again=repo.commitMarket(first);assertEquals(0L,again.added)
        val other=import(row("1"),row("3",title="Aliens"));assertEquals(1L,count("market_sales","sourceItemId='1'"));assertEquals(3L,count("market_sales"))
        import(row("1",sold="15"));assertEquals(4L,count("market_sales"));assertEquals(1L,count("market_sales","sourceItemId='1' AND current=1"));assertEquals(1L,count("market_sales","sourceItemId='1' AND current=0"));assertEquals(3L,count("market_sales","current=1"))
        first.file.delete();other.file.delete()
    }
    @Test fun sourceIntegrityAndAnyMalformedRowRollBackWithoutDeletingPriorEvidence():Unit=runBlocking {
        import(row("1"));val invalid=csv(listOf(row("2"),row("3",sold="NaN")))
        try {repo.previewMarket(invalid,invalid.name);fail()} catch(_:IllegalArgumentException) {};assertEquals(1L,count("market_sales"))
        val p=repo.previewMarket(csv(listOf(row("2"),row("3"))),"fixture.csv");android.database.sqlite.SQLiteDatabase.openDatabase(p.file.path,null,0).use {it.execSQL("UPDATE market_records SET soldDecimal='99' WHERE sourceItemId='3'")}
        try {repo.commitMarket(p);fail()} catch(_:IllegalArgumentException) {};assertEquals(1L,count("market_sales"))
    }
    @Test fun indexedSearchHandlesPartialTitleBarcodeFiltersTypoAndPagination():Unit=runBlocking {
        import(row("1",title="Alien VHS",currency="CAD",barcode="036000291452",year="1979"),row("2",title="Aliens",sold="20",currency="CAD",year="1986"),row("3",title="Alien 3",currency="USD"))
        val exact=repo.searchMarket(MarketFilters(query="alien",currency="CAD"));assertEquals(2,exact.groups.size);assertTrue(exact.index in listOf("FTS5","FTS4","INDEXED"))
        assertEquals(1,repo.searchMarket(MarketFilters(query="036000291452")).groups.size)
        assertEquals(1,repo.searchMarket(MarketFilters(query="alien 3",currency="USD")).groups.size)
        assertEquals(1,repo.searchMarket(MarketFilters(query="alien",year=1979,currency="CAD",maxCents=1500)).groups.size)
        val typo=repo.searchMarket(MarketFilters(query="alienn",currency="CAD"));assertTrue(typo.approximate);assertTrue(typo.groups.isNotEmpty())
        assertEquals(1,repo.searchMarket(MarketFilters(query="alien"),0,1).groups.size);assertEquals(1,repo.searchMarket(MarketFilters(query="alien"),1,1).groups.size)
    }
    @Test fun usdShippingDecimalRoundingManualProtectionAndHistoricalReuseRequireExplicitApproval():Unit=runBlocking {
        val item=item();import(row("1",sold="10",shipping="2"),row("2",sold="20",shipping="2"),row("3",title="Aliens",sold="900",shipping="0"))
        val r=repo.recommendMarket(item.groupId,"USD")!!;assertEquals(2,r.stats.count);assertEquals(1700L,r.stats.medianCents);assertNull(repo.recommendMarket(item.groupId,"CAD"))
        try {repo.applyMarketPrice(r,"MEDIAN",null,false,true,"1.25","2026-10-28");fail()} catch(_:IllegalStateException) {}
        try {repo.applyMarketPrice(r,"MEDIAN",null,true,false,"1.25","2026-10-28");fail()} catch(_:IllegalStateException) {}
        try {repo.applyMarketPrice(r,"MEDIAN",null,true,true,"","");fail()} catch(_:IllegalStateException) {}
        val saved=repo.applyMarketPrice(r,"MEDIAN",null,true,true,"1.25","2026-10-28");assertEquals(2125L,saved.priceCents);assertEquals("MARKET_MEDIAN",saved.priceSource);assertEquals(3L,count("market_sales"));assertEquals(2L,count("price_decisions"));assertEquals(1L,count("price_decisions","recordType='MANUAL_ESTIMATE'"));assertEquals(1L,count("price_decisions","recordType='OPERATOR_APPROVED_PRICE'"))
        val changed=repo.saveInventory(saved.copy(title="Aliens"),saved.revision);assertEquals("STALE_COMPARABLES",changed.priceSource)
    }
    @Test fun exactEditionYearAndBundlesNeverAutomaticallyBecomeAcceptedEvidence():Unit=runBlocking {
        val item=item();import(row("1",title="Alien 1979",year="1979",edition="Widescreen"),row("2",title="Alien bundle",year="1979",edition="Widescreen"),row("3",title="Alien 2003",year="2003",edition="Fullscreen"),row("4",title="Aliens",year="1986",edition="Widescreen"))
        val edited=repo.saveInventory(item.copy(releaseYear=1979,edition="Widescreen"),item.revision);val r=repo.recommendMarket(item.groupId,"USD")!!;assertEquals(1,r.stats.count);assertEquals(edited.revision,r.revision)
        repo.saveInventory(edited.copy(edition="Fullscreen"),edited.revision);assertNull(repo.recommendMarket(item.groupId,"USD"))
    }
    @Test fun normalizedEvidenceAndDecisionsAreIncludedInPhotoBackupAndAtomicRestore():Unit=runBlocking {
        val item=item();import(row("1",currency="CAD"));val r=repo.recommendMarket(item.groupId,"CAD")!!;repo.applyMarketPrice(r,"MEDIAN",1300L,true,true)
        val snapshot=repo.hashSnapshotFiles(repo.buildSnapshot());assertEquals(8,snapshot.getInt("schema"));val files=snapshot.getJSONArray("files");assertEquals(1,(0 until files.length()).count {files.getJSONObject(it).getString("path").startsWith("market/")})
        val stage=File(app.cacheDir,newId()).apply {mkdirs()};for(i in 0 until files.length()) {val path=files.getJSONObject(i).getString("path");File(stage,path).apply {parentFile!!.mkdirs();repo.snapshotFile(path).copyTo(this)}}
        val dest=create();dest.mergeSnapshot(snapshot,stage);assertEquals(1,dest.searchMarket(MarketFilters(query="alien")).groups.size);assertEquals(2,dest.journal.marketDao().decisionsForItem(item.groupId).size);assertEquals(1300L,dest.journal.inventoryDao().item(item.groupId)!!.priceCents)
        val archive=repo.exportMarketDatabase();val p=dest.previewMarket(archive,"export.sqlite");assertEquals(0L,dest.commitMarket(p).added)
    }
    @Test fun contradictorySourceVersionsArePreservedButNotEligibleForStatistics():Unit=runBlocking {
        val item=item();val p=import(row("1",sold="10"),row("1",sold="999"));assertTrue(p.warnings.any {it.contains("conflicting")});assertEquals(2L,count("market_sales"));assertEquals(0L,count("market_sales","eligible=1"));assertNull(repo.recommendMarket(item.groupId,"USD"))
    }
    @Test fun indexedFallbackFindsMiddleWordsWithoutFtsAndRebuildRetainsEvidence():Unit=runBlocking {
        repo.mutate {repo.ensureMarketSearch();val db=repo.journal.openHelper.writableDatabase;db.execSQL("DROP TABLE market_fts");db.execSQL("UPDATE market_index_config SET kind='INDEXED'")}
        import(row("1",title="The Alien 1979"),row("2",title="Another Movie"))
        assertEquals("INDEXED",repo.marketIndexKind());assertEquals(1,repo.searchMarket(MarketFilters(query="alien 1979")).groups.size)
        repo.rebuildMarketSearch();assertEquals(2L,count("market_sales"));assertEquals(1,repo.searchMarket(MarketFilters(query="alien 1979")).groups.size)
    }
    @Test fun askingPriceHintsRequirePolicyAndApprovalAndNeverCreateSoldRecords():Unit=runBlocking {
        val item=item();import(row("1",currency="CAD"));val r=repo.recommendMarket(item.groupId,"CAD")!!;val approved=repo.applyMarketPrice(r,"MEDIAN",1500L,true,true)
        assertNull(repo.operatorPriceHint("Alien"));repo.preferences.edit().putBoolean("reuseOperatorPrices",true).commit();val hint=repo.operatorPriceHint("Alien")!!
        val manual=repo.saveInventory(approved.copy(priceCents=9999),approved.revision)
        try {repo.applyOperatorPriceHint(manual.groupId,hint.id,manual.revision,false);fail()} catch(_:IllegalStateException) {}
        val reused=repo.applyOperatorPriceHint(manual.groupId,hint.id,manual.revision,true);assertEquals(1500L,reused.priceCents);assertEquals("OPERATOR_APPROVED",reused.priceSource);assertEquals(1L,count("market_sales"))
    }
    @Test fun genericFormatOnlyTitlesArePreservedSearchableAndNotAutomaticFilmEvidence():Unit=runBlocking {
        val item=item();val p=import(row("1",title="VHS Tape"),row("2",title="Movie"));assertEquals(2L,count("market_sales"));assertEquals(0L,count("market_sales","eligible=1"));assertTrue(p.warnings.any {it.contains("generic/format-only")})
        assertEquals(1,repo.searchMarket(MarketFilters(query="vhs")).groups.size);assertTrue(repo.searchMarket(MarketFilters(year=2020)).groups.isEmpty());assertNull(repo.recommendMarket(item.groupId,"USD"))
    }
    @Test fun unspecifiedRemakesAndExplicitPremiumEditionsDoNotMixIntoOrdinaryPrices():Unit=runBlocking {
        val item=item();import(row("1",title="Alien 1979"),row("2",title="Alien 2003"),row("3",title="Alien sealed",sold="1000"),row("4",title="Alien widescreen",sold="100"))
        try {repo.recommendMarket(item.groupId,"USD");fail()} catch(e:IllegalStateException) {assertTrue(e.message!!.contains("different release years"))}
        val saved=repo.saveInventory(item.copy(releaseYear=1979),item.revision);val r=repo.recommendMarket(saved.groupId,"USD")!!;assertEquals(1,r.stats.count);assertEquals(1200L,r.stats.medianCents)
    }
    @Test fun typedAskingPricesAndAiSuggestionsCannotBecomeHistoricalSales():Unit=runBlocking {
        import(row("1"));for(kind in listOf("MANUAL_ESTIMATE","OPERATOR_APPROVED_PRICE","AI_SUGGESTION")) {
            val file=File(app.cacheDir,"typed-${newId()}.csv").apply {writeText("title,site,item_id,date_sold,sale_price,shipping_price,currency,url,record_type\nAlien,EBAY_US,2,2020-01-01,10,0,USD,https://www.ebay.com/itm/2,$kind\n")}
            try {repo.previewMarket(file,file.name);fail()} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("cannot be imported as sold-market evidence"))}
        };assertEquals(1L,count("market_sales"))
    }
    @Test fun incrementallyAddedMatchingSalesInvalidateAnOlderPriceReview():Unit=runBlocking {
        val item=item();import(row("1",currency="CAD"));val reviewed=repo.recommendMarket(item.groupId,"CAD")!!;import(row("2",currency="CAD",sold="20"))
        try {repo.applyMarketPrice(reviewed,"MEDIAN",null,true,true);fail()} catch(e:IllegalStateException) {assertTrue(e.message!!.contains("changed after review"))}
        assertEquals(777L,repo.journal.inventoryDao().item(item.groupId)!!.priceCents)
    }
    @Test fun realWorkbenchSchemaAdapterKeepsSourceBytesAndOldEvidence():Unit=runBlocking {
        val file=File(app.cacheDir,"source-workbench.sqlite");javaClass.getResourceAsStream("/desktop-pricing-fixture.sqlite")!!.use {input->file.outputStream().use {input.copyTo(it)}};val hash=hashFile(file)
        val p=repo.previewMarket(file,file.name);assertTrue(p.rows>0);repo.commitMarket(p);assertEquals(hash,hashFile(file));assertTrue(repo.searchMarket(MarketFilters(query="alien",currency="CAD")).groups.isNotEmpty())
    }
}
