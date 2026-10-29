package ca.canadavhs.snapmobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.work.Configuration
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CaptureApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognitionStorageTest {
    private val app: CaptureApplication get() = RuntimeEnvironment.getApplication() as CaptureApplication
    private fun repo(db: CaptureJournal) = CaptureRepository(app, db) { directory -> java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
    private suspend fun photo(repo: CaptureRepository): PhotoRow {
        val p = repo.reserve(); val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        repo.pendingFile(p.id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        repo.complete(p.id); return repo.dao.photo(p.id)
    }
    private fun workManager() { WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(java.util.concurrent.Executor { }).setTaskExecutor(SynchronousExecutor()).build()) }

    @Test fun encryptedKeyStorageNeverPersistsPlaintextAndDetectsCorruption() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = SecureApiKeyStore(app, wrappingKey = { key }, preferenceName = "cipher-unit-test")
        val input = "sk-unit-test-not-a-real-api-key"
        store.save(input); assertEquals(input, store.read())
        val prefs = app.getSharedPreferences("cipher-unit-test", Context.MODE_PRIVATE)
        val first = prefs.getString("ciphertext", "")!!
        assertFalse(prefs.all.values.any { it.toString().contains(input) })
        store.save(input); assertNotEquals(first, prefs.getString("ciphertext", ""))
        prefs.edit().putString("ciphertext", "corrupted").commit()
        try { store.read(); fail("Corrupt key must fail safely") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("cannot be unlocked")); assertFalse(e.message!!.contains(input)) }
        store.remove(); assertFalse(store.hasKey()); assertNull(store.read())
    }

    @Test fun upgradeFromV1RetainsAllCaptureTablesAndOriginalFiles(): Unit = runBlocking {
        val schema = JSONObject(File(System.getProperty("snapims.schemaDir"), "ca.canadavhs.snapmobile.CaptureJournal/1.json").readText()).getJSONObject("database")
        val name = "v1-upgrade-test.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        val old = helper.writableDatabase
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val table = entity.getString("tableName"); val values = ContentValues(); val fields = entity.getJSONArray("fields")
            for (j in 0 until fields.length()) {
                val field = fields.getJSONObject(j); val column = field.getString("columnName")
                if (!field.optBoolean("notNull", false)) values.putNull(column)
                else if (field.getString("affinity") == "INTEGER") values.put(column, 0L) else values.put(column, "")
            }
            values.put("id", when (table) { "sessions" -> "session-v1"; "groups" -> "group-v1"; "photos" -> "photo-v1"; "archives" -> "archive-v1"; else -> "event-v1" })
            if (table != "sessions") values.put("sessionId", "session-v1")
            if (table == "groups") { values.put("number", 1); values.put("location", "A1"); values.put("note", "Owner note") }
            if (table == "photos") { values.remove("sessionId"); values.put("groupId", "group-v1"); values.put("state", "READY"); values.put("role", "FRONT"); values.put("sha256", "original-hash"); values.put("bytes", 12345L) }
            if (table == "archives") { values.put("part", 1); values.put("state", "READY"); values.put("sha256", "archive-hash") }
            if (table == "events") values.put("groupId", "group-v1")
            if (table == "sessions") values.put("name", "Existing owner batch")
            old.insert(table, 0, values)
        }
        val original = File(app.filesDir, "originals/photo-v1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        helper.close()
        val upgraded = Room.databaseBuilder(app, CaptureJournal::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            upgraded.openHelper.writableDatabase
            val dao = upgraded.dao()
            assertEquals("Existing owner batch", dao.session("session-v1").name)
            assertEquals("Owner note", dao.group("group-v1").note)
            assertEquals(12345L, dao.photo("photo-v1").bytes); assertEquals("original-hash", dao.photo("photo-v1").sha256)
            assertEquals("archive-hash", dao.archive("archive-v1").sha256)
            assertEquals(1, dao.events("session-v1").size)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), original.readBytes())
            assertTrue(dao.recognitions("group-v1").isEmpty())
            dao.insert(RecognitionRow(groupId = "group-v1", provider = "CLASSICAL", model = "test", inputJson = "[]", inputFingerprint = "test"))
            assertEquals(1, dao.recognitions("group-v1").size)
        } finally { upgraded.close() }
    }

    @Test fun modelFreeWorkerPersistsSuggestionsWithoutChangingCaptureMetadata(): Unit = runBlocking {
        workManager()
        val journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
        try {
            val repository = repo(journal); app.repository = repository
            repository.preferences.edit().clear().putBoolean("barcode", false).putString("recognitionMode", "CLASSICAL").commit()
            repository.start("Local", "A1"); val group = repository.current(); val p = photo(repository)
            repository.flags(group.id, true, false, "Owner note")
            val attempt = repository.queueRecognition(group.id)
            assertEquals(attempt.id, repository.queueRecognition(group.id).id)
            TestListenableWorkerBuilder<RecognitionWorker>(app).setInputData(workDataOf("id" to attempt.id)).build().doWork()
            val complete = repository.dao.recognition(attempt.id)!!
            assertEquals("SUCCEEDED", complete.state)
            val result = JSONObject(complete.resultJson); assertFalse(result.getBoolean("uses_trained_ocr_data")); assertEquals("zxing-classical", result.getString("provider_name"))
            assertEquals("Owner note", repository.dao.group(group.id).note); assertTrue(repository.dao.group(group.id).rare)
            assertEquals(p.sha256, hashFile(repository.original(p.id)))
            repository.confirmRecognitionTitle(attempt.id, "Owner verified title")
            assertEquals("Owner verified title", repository.dao.recognition(attempt.id)!!.acceptedTitle)
            assertEquals(1, repository.recognitionManifest(group.id).length())
            repository.role(p.id, ca.canadavhs.snapmobile.core.Role.BACK)
            assertEquals(0, repository.recognitionManifest(group.id).length())
            try { repository.confirmRecognitionTitle(attempt.id, "Overwrite"); fail("Stale results cannot be accepted") } catch (_: IllegalStateException) { }
        } finally { journal.close() }
    }

    @Test fun currentRecognitionIsIncludedInRealArchiveManifest(): Unit = runBlocking {
        workManager()
        val journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
        try {
            val repository = repo(journal); app.repository = repository
            repository.preferences.edit().clear().putBoolean("barcode", false).putString("recognitionMode", "CLASSICAL").commit()
            val session = repository.start("Enriched ZIP", "B2"); val group = repository.current(); photo(repository)
            val attempt = repository.queueRecognition(group.id)
            TestListenableWorkerBuilder<RecognitionWorker>(app).setInputData(workDataOf("id" to attempt.id)).build().doWork()
            repository.confirmRecognitionTitle(attempt.id, "Owner verified printed title")
            repository.next(); repository.queueArchives(session.id)
            val archive = repository.dao.archives(session.id).single()
            TestListenableWorkerBuilder<ArchiveWorker>(app).setInputData(workDataOf("id" to archive.id)).build().doWork()
            val ready = repository.dao.archive(archive.id); assertEquals("READY", ready.state)
            val zip = File(repository.archiveDir, ready.filename)
            java.util.zip.ZipFile(zip).use { file ->
                val manifest = JSONObject(file.getInputStream(file.getEntry("manifest.json")).bufferedReader().readText())
                val recognition = manifest.getJSONArray("groups").getJSONObject(0).getJSONArray("recognition").getJSONObject(0)
                assertEquals("Owner verified printed title", recognition.getString("confirmed_title"))
                assertEquals("CLASSICAL", recognition.getString("provider")); assertFalse(recognition.toString().contains("api_key"))
                verifyArchive(zip, manifest)
            }
            val output = File(System.getProperty("snapims.testOutput"), "native-recognition-export.zip")
            output.parentFile!!.mkdirs(); zip.copyTo(output, overwrite = true)
        } finally { journal.close() }
    }

    @Test fun verifiedOcrDataRecoversFromAnInterruptedOrCorruptedCopy() {
        val root = ensureTesseractData(app)
        val data = File(root, "tessdata/eng.traineddata")
        val expected = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        assertEquals(expected, hashFile(data))
        data.writeText("interrupted model copy")
        ensureTesseractData(app)
        assertEquals(expected, hashFile(data))
    }

    @Test fun interruptedOpenAiAttemptRequiresManualRetryWithoutReadingAKey(): Unit = runBlocking {
        workManager()
        val journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
        try {
            val repository = repo(journal); app.repository = repository
            repository.preferences.edit().clear().putBoolean("barcode", false).putString("recognitionMode", "OPENAI").commit()
            repository.start("Interrupted API", "Q1"); val group = repository.current(); photo(repository)
            val input = repository.dao.photos(group.id)
            val attempt = RecognitionRow(groupId = group.id, provider = "OPENAI", model = DEFAULT_OPENAI_MODEL, inputJson = photoSnapshot(input), inputFingerprint = photoFingerprint(input), state = "RUNNING")
            repository.dao.insert(attempt)
            TestListenableWorkerBuilder<RecognitionWorker>(app).setInputData(workDataOf("id" to attempt.id)).build().doWork()
            val result = repository.dao.recognition(attempt.id)!!
            assertEquals("ERROR", result.state); assertTrue(result.error.contains("uncertain")); assertTrue(result.error.contains("manually"))
        } finally { journal.close() }
    }

    @Test fun modeOffCancelsQueuedRecognitionAndInputEditsMakeResultsStale(): Unit = runBlocking {
        workManager()
        val journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
        try {
            val repository = repo(journal); app.repository = repository
            repository.preferences.edit().clear().putBoolean("barcode", false).putString("recognitionMode", "CLASSICAL").commit()
            repository.start("Queue", "Q1"); val group = repository.current(); photo(repository)
            val first = repository.queueRecognition(group.id); repository.setRecognitionMode("OFF")
            assertEquals("CANCELLED", repository.dao.recognition(first.id)!!.state)
            repository.setRecognitionMode("CLASSICAL"); val second = repository.queueRecognition(group.id); photo(repository)
            TestListenableWorkerBuilder<RecognitionWorker>(app).setInputData(workDataOf("id" to second.id)).build().doWork()
            assertEquals("STALE", repository.dao.recognition(second.id)!!.state)
        } finally { journal.close() }
    }
}
