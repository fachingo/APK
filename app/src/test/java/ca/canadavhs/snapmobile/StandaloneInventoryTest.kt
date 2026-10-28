package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import ca.canadavhs.snapmobile.core.InventoryRules

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=CaptureApplication::class)
class StandaloneInventoryTest {
    private lateinit var app: CaptureApplication
    private val databases=mutableListOf<CaptureJournal>()
    private lateinit var repo: CaptureRepository
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication() as CaptureApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(java.util.concurrent.Executor {}).setTaskExecutor(SynchronousExecutor()).build())
        repo=createRepo();app.repository=repo
        repo.preferences.edit().clear().putBoolean("barcode",false).commit()
    }
    private fun createRepo(lowSpace: Boolean=false):CaptureRepository {
        val journal=Room.inMemoryDatabaseBuilder(app,CaptureJournal::class.java).allowMainThreadQueries().build();databases+=journal
        val root=File(app.filesDir,"inventory-test-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        return CaptureRepository(app,journal,root,storageFreeBytes={if(lowSpace) 0L else it.usableSpace}) {directory->java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}
    }
    @After fun close() {databases.forEach {it.close()}}
    private fun jpeg(file:File) {val image=Bitmap.createBitmap(60,80,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.BLUE);file.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)};image.recycle()}
    private suspend fun shot(target:CaptureRepository=repo):PhotoRow {val photo=target.reserve();jpeg(target.pendingFile(photo.id));target.complete(photo.id);return target.dao.photo(photo.id)}
    private suspend fun finishArchives(target:CaptureRepository=repo) {
        app.repository=target
        for(archive in target.dao.unfinishedArchives()) {
            TestListenableWorkerBuilder<ArchiveWorker>(app).setInputData(workDataOf("id" to archive.id)).build().doWork()
            assertEquals("READY",target.dao.archive(archive.id).state)
        }
    }
    private suspend fun ready(group:String,title:String="A real title"):InventoryRow {
        val old=repo.journal.inventoryDao().item(group)!!
        return repo.saveInventory(old.copy(title=title,condition="Good",priceCents=1299,ready=true),old.revision)
    }
    @Test fun copiesKeepImmutableIdentityAndRevisionGuard():Unit=runBlocking {
        repo.start("Two copies","A1");val first=shot();shot();val a=ready(first.groupId,"Same movie")
        repo.next();val second=shot();shot();val b=ready(second.groupId,"Same movie")
        assertNotEquals(a.itemId,b.itemId);assertEquals("MANUAL",a.priceSource)
        val changed=repo.saveInventory(a.copy(location="J10",condition="Damaged",barcode="036000291452"),a.revision)
        assertEquals(a.itemId,changed.itemId);assertEquals("Good",repo.journal.inventoryDao().item(b.groupId)!!.condition)
        try {repo.saveInventory(a.copy(title="Stale overwrite"),a.revision);fail("Must reject stale revision")} catch(_:IllegalStateException){}
        assertTrue(repo.journal.inventoryDao().history(a.groupId).isNotEmpty())
    }
    @Test fun completedInventoryPhotosCanBeCorrectedWithoutChangingOldZip():Unit=runBlocking {
        repo.start("Corrections","A1");val front=shot();val back=shot();ready(front.groupId);repo.finish();finishArchives()
        val archive=repo.dao.unfinishedArchives();assertTrue(archive.isEmpty())
        val zip=repo.archiveDir.listFiles()!!.single {it.extension=="zip"};val checksum=hashFile(zip)
        repo.reviseInventoryPhoto(back.id,"UP");assertFalse(repo.journal.inventoryDao().item(front.groupId)!!.ready)
        repo.reviseInventoryPhoto(back.id,"DOWN")
        val replacement=repo.reserveInventoryPhoto(front.groupId,front.id);jpeg(repo.pendingFile(replacement.id));repo.complete(replacement.id);repo.replaceInventoryPhoto(front.id,replacement.id)
        assertTrue(repo.dao.photo(front.id).deleted);assertEquals("FRONT",repo.dao.photo(replacement.id).role)
        assertTrue(repo.original(front.id).exists());assertEquals(checksum,hashFile(zip))
        repo.reviseInventoryPhoto(front.id,"RESTORE");assertFalse(repo.dao.photo(front.id).deleted)
    }
    @Test fun encryptedBackupRestoresOriginalsAndRejectsWrongPasswordAndTamper():Unit=runBlocking {
        repo.start("Private collection","Q1");val p=shot();shot();ready(p.groupId);repo.finish();finishArchives()
        val backup=repo.createInventoryBackup("a long backup passphrase".toCharArray())
        assertFalse(String(backup.readBytes(),Charsets.ISO_8859_1).contains("Private collection"))
        val empty=createRepo()
        try {empty.restoreInventoryBackup(backup,"a different passphrase".toCharArray());fail("Wrong passphrase must fail")} catch(_:Exception){}
        assertTrue(empty.journal.inventoryDao().items().isEmpty());assertTrue(empty.originals.listFiles()!!.isEmpty())
        assertEquals(1,empty.restoreInventoryBackup(backup,"a long backup passphrase".toCharArray()))
        assertArrayEquals(repo.original(p.id).readBytes(),empty.original(p.id).readBytes())
        assertEquals(0,empty.restoreInventoryBackup(backup,"a long backup passphrase".toCharArray()))
        val damaged=File(app.cacheDir,"tampered.snapbak");val bytes=backup.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();damaged.writeBytes(bytes)
        val other=createRepo()
        try {other.restoreInventoryBackup(damaged,"a long backup passphrase".toCharArray());fail("GCM tamper must fail")} catch(_:Exception){}
        assertTrue(other.journal.inventoryDao().items().isEmpty())
    }
    @Test fun conflictingRestoreDoesNotOverwriteAnyInventory():Unit=runBlocking {
        repo.start("Conflict","A1");val p=shot();shot();ready(p.groupId);repo.finish();finishArchives()
        val backup=repo.createInventoryBackup("a long backup passphrase".toCharArray());val other=createRepo()
        other.restoreInventoryBackup(backup,"a long backup passphrase".toCharArray())
        val old=other.journal.inventoryDao().item(p.groupId)!!;other.saveInventory(old.copy(title="Locally edited"),old.revision)
        val original=other.original(p.id).readBytes()
        try {other.restoreInventoryBackup(backup,"a long backup passphrase".toCharArray());fail("Changed records must conflict")} catch(_:IllegalArgumentException){}
        assertEquals("Locally edited",other.journal.inventoryDao().item(p.groupId)!!.title);assertArrayEquals(original,other.original(p.id).readBytes())
    }
    @Test fun lowStorageDoesNotReserveAFalseItemOrDeleteSources():Unit=runBlocking {
        val full=createRepo(lowSpace=true);full.start("No storage","A1")
        try {full.reserve();fail("Must fail before reservation")} catch(_:IllegalStateException){}
        assertTrue(full.journal.inventoryDao().items().isEmpty());assertTrue(full.dao.pending().isEmpty())
    }
    @Test fun nativeMobileZipImportsAndRepeatsWithoutDuplicateCopies():Unit=runBlocking {
        repo.start("Native handoff","B2");val p=shot();shot();ready(p.groupId);repo.finish();finishArchives()
        val zip=repo.archiveDir.listFiles()!!.single {it.extension=="zip"};val receiver=createRepo()
        assertEquals(1,receiver.importInventoryPackage(zip));assertEquals(0,receiver.importInventoryPackage(zip))
        assertEquals(repo.journal.inventoryDao().item(p.groupId)!!.itemId,receiver.journal.inventoryDao().item(p.groupId)!!.itemId)
        assertArrayEquals(repo.original(p.id).readBytes(),receiver.original(p.id).readBytes())
    }
    @Test fun oneHundredItemsThreeHundredPhotosSurvivePortableRestore():Unit=runBlocking {
        val session=repo.start("100-copy offline stress","A1")
        repeat(100) {index->val photo=shot();shot();shot();val old=repo.journal.inventoryDao().item(photo.groupId)!!
            repo.saveInventory(old.copy(title="Tape ${index+1}",condition=if(index%2==0) "Good" else "Sealed",priceCents=1000L+index,ready=true),old.revision);repo.next()}
        repo.finish();finishArchives()
        assertEquals(100,repo.journal.inventoryDao().items().size)
        assertEquals(300,repo.dao.groups(session.id).sumOf {repo.dao.photos(it.id).size})
        assertEquals(100,repo.journal.inventoryDao().items().map {it.itemId}.distinct().size)
        val portable=repo.createPortableInventoryExport();val restored=createRepo()
        assertEquals(100,restored.importInventoryPackage(portable));assertEquals(0,restored.importInventoryPackage(portable))
        assertEquals(300,restored.originals.listFiles()!!.count {it.extension=="jpg"})
        restored.journal.inventoryDao().items().forEach {item->assertEquals("Tape",item.title.substringBefore(' '));assertTrue(item.ready)}
        val fixture=File(System.getProperty("snapims.testOutput"),"standalone-100x300.zip").apply {parentFile!!.mkdirs()};portable.copyTo(fixture,overwrite=true)
    }
    @Test fun actualDesktopExportRetainsItemIdsAndOriginalPhotos():Unit=runBlocking {
        val source=File(app.cacheDir,"desktop-export-fixture.zip")
        javaClass.getResourceAsStream("/desktop-to-phone.zip")!!.use {input->source.outputStream().use {input.copyTo(it)}}
        val expected=org.json.JSONObject(javaClass.getResourceAsStream("/desktop-to-phone-expected.json")!!.bufferedReader().use {it.readText()})
        assertEquals(2,repo.importInventoryPackage(source));assertEquals(0,repo.importInventoryPackage(source))
        val ids=repo.journal.inventoryDao().items().map {it.itemId}.sorted()
        val original=expected.getJSONArray("item_ids");assertEquals((0 until original.length()).map {original.getString(it)}.sorted(),ids)
        assertEquals(4,repo.originals.listFiles()!!.count {it.extension=="jpg"})
        assertEquals(2,repo.journal.inventoryDao().items().count {it.barcode=="036000291452"})
    }
    @Test fun transferRecoveryDoesNotClaimStoppedWorkIsRunning():Unit=runBlocking {
        val job=TransferJobRow(kind="BACKUP");repo.journal.inventoryDao().insert(job)
        repo.recoverTransferJobs();assertEquals("INTERRUPTED",repo.journal.inventoryDao().job(job.id)!!.state)
    }
}
