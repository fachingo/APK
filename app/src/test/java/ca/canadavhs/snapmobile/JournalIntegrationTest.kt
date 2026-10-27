package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
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
import org.json.JSONObject
import ca.canadavhs.snapmobile.core.Role

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = CaptureApplication::class)
class JournalIntegrationTest {
    private lateinit var app: CaptureApplication
    private lateinit var journal: CaptureJournal
    private lateinit var repo: CaptureRepository
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as CaptureApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(java.util.concurrent.Executor { }).setTaskExecutor(SynchronousExecutor()).build())
        journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
        repo = hostRepository(); app.repository = repo
        repo.preferences.edit().clear().putBoolean("barcode", false).putInt("archiveMb", 500).commit()
    }
    // Robolectric's ShadowLinux opens directories via RandomAccessFile, which
    // cannot represent a directory fd. Still perform a real Linux fsync on host.
    private fun hostRepository() = CaptureRepository(app, journal) { directory ->
        java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
    @After fun cleanup() { journal.close() }
    private suspend fun capture(): PhotoRow {
        val p = repo.reserve()
        val b = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.BLUE)
        repo.pendingFile(p.id).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 100, it) }; b.recycle()
        repo.complete(p.id); return repo.dao.photo(p.id)
    }
    @Test fun nextUndoGroupingAndRestore(): Unit = runBlocking {
        val s = repo.start("Tape session", "A1"); val group = repo.current()
        repeat(3) { capture() }
        repo.barcode(group.id, "04252614")
        assertEquals("04252614", repo.dao.group(group.id).barcodeOverride)
        assertEquals(listOf("FRONT", "BACK", "ADDITIONAL"), repo.dao.photos(group.id).map { it.role })
        repo.next(); assertNotEquals(group.id, repo.current().id)
        repo.undoNext(); assertEquals(group.id, repo.current().id)
        assertEquals(3, repo.dao.photos(group.id).size)
        // A new repository instance reads the exact same unfinished session/group.
        val restored = hostRepository(); restored.recover()
        assertEquals(s.id, restored.dao.active()?.id); assertEquals(group.id, restored.current().id)
    }
    @Test fun interruptedCaptureRecoversOriginalAndJournal(): Unit = runBlocking {
        repo.start("Crash", "Q1"); val p = repo.reserve()
        val b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        repo.pendingFile(p.id).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 100, it) }; b.recycle()
        hostRepository().recover()
        val recovered = repo.dao.photo(p.id)
        assertEquals("READY", recovered.state); assertTrue(repo.original(p.id).exists()); assertEquals(hashFile(repo.original(p.id)), recovered.sha256)
    }
    @Test fun incompleteWriteIsRetainedAndFlagged(): Unit = runBlocking {
        repo.start("Interrupted", "A1"); val p = repo.reserve(); repo.pendingFile(p.id).writeBytes(byteArrayOf(1, 2, 3))
        repo.recover(); assertEquals("ERROR", repo.dao.photo(p.id).state); assertTrue(repo.pendingFile(p.id).exists())
        try { repo.next(); fail("Must review damaged capture") } catch (_: IllegalStateException) { }
    }
    @Test fun reorderMoveAndRemoveNeverDeleteFiles(): Unit = runBlocking {
        repo.start("Edit", "A1"); val first = capture(); val second = capture(); val g = repo.current()
        repo.reorder(second.id, -1); assertEquals(second.id, repo.dao.photos(g.id).first().id)
        repo.role(second.id, Role.BACK); repo.next(); repo.move(first.id, 1)
        assertEquals(repo.current().id, repo.dao.photo(first.id).groupId)
        repo.deletePhoto(first.id); assertTrue(repo.original(first.id).exists()); repo.restorePhoto(first.id)
        assertFalse(repo.dao.photo(first.id).deleted)
    }
    @Test fun pendingShutterPreventsBoundaryChange(): Unit = runBlocking {
        repo.start("Boundary", "A1"); capture(); repo.reserve()
        try { repo.next(); fail("Pending write cannot cross Next") } catch (_: IllegalStateException) { }
        try { repo.finish(); fail("Pending write cannot cross Finish") } catch (_: IllegalStateException) { }
    }
    @Test fun archiveRetryVerifiesBytesManifestAndLocksGroups(): Unit = runBlocking {
        val session = repo.start("ZIP test", "B2"); val group = repo.current(); val first = capture(); val second = capture()
        repo.next(); repo.queueArchives(session.id)
        val archive = repo.dao.archives(session.id).single()
        // Simulate a crash in ZIP creation: the pending output is replaced on retry.
        File(repo.archiveDir, "${archive.id}.pending").writeText("interrupted ZIP")
        val worker = TestListenableWorkerBuilder<ArchiveWorker>(app).setInputData(workDataOf("id" to archive.id)).build()
        worker.doWork()
        val ready = repo.dao.archive(archive.id); assertEquals("READY", ready.state)
        val target = File(repo.archiveDir, ready.filename)
        ZipFile(target).use { zip ->
            val m = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            val entries = m.getJSONArray("groups").getJSONObject(0).getJSONArray("photos")
            assertEquals(2, entries.length())
            assertEquals("FRONT", entries.getJSONObject(0).getString("role")); assertEquals("BACK", entries.getJSONObject(1).getString("role"))
            val original = repo.original(first.id).readBytes(); assertArrayEquals(original, zip.getInputStream(zip.getEntry(entries.getJSONObject(0).getString("filename"))).readBytes())
            verifyArchive(target, m)
        }
        assertTrue(repo.original(first.id).exists() && repo.original(second.id).exists())
        try { repo.deletePhoto(first.id); fail("Archived group must be immutable") } catch (_: IllegalStateException) { }
        // Simulate crash after atomic rename but before READY; same archive is verified/reused.
        repo.dao.update(ready.copy(state = "BUILDING")); worker.doWork()
        assertEquals(ready.sha256, repo.dao.archive(archive.id).sha256)
        // Hand this real Android-generated ZIP to the desktop interoperability check.
        val fixtureOutput = File(System.getProperty("snapims.testOutput"), "native-export.zip")
        fixtureOutput.parentFile!!.mkdirs(); target.copyTo(fixtureOutput, overwrite = true)
    }
}
