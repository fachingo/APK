package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageCapture
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import ca.canadavhs.snapmobile.core.LocalBarcodeDecoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = CaptureApplication::class)
class CameraUpdateTest {
    private lateinit var app: CaptureApplication
    private lateinit var journal: CaptureJournal
    private lateinit var repo: CaptureRepository
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as CaptureApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(java.util.concurrent.Executor { }).setTaskExecutor(SynchronousExecutor()).build())
        journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(app, journal) { directory -> java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        app.repository = repo
        repo.preferences.edit().clear().putBoolean("barcode", false).commit()
    }
    @After fun cleanup() { journal.close() }
    private fun jpeg(file: File) {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
    }
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalZeroShutterLag::class])
    @Test fun smallerSizesPersistAndOldPreferencesUpgradeToBalanced() {
        repo.preferences.edit().putBoolean("maximumResolution", true).commit()
        assertEquals(PhotoResolution.BALANCED, PhotoResolution.read(repo.preferences))
        PhotoResolution.entries.forEach { value ->
            repo.preferences.edit().putString("photoResolution", value.name).commit()
            assertEquals(value, PhotoResolution.read(repo.preferences))
            value.size?.let { assertTrue(it.width > 0 && it.height > 0) }
        }
        repo.preferences.edit().putString("photoResolution", "unknown").commit()
        assertEquals(PhotoResolution.BALANCED, PhotoResolution.read(repo.preferences))
        assertEquals(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG, shutterMode(true, false))
        assertEquals(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, shutterMode(true, true))
        assertEquals(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, shutterMode(false, false))
    }
    @Test fun threePendingCapturesKeepRolesAndBoundariesAndRecover(): Unit = runBlocking {
        repo.start("Rapid shots", "A1")
        val pending = List(3) { repo.reserve() }
        assertEquals(listOf("FRONT", "BACK", "ADDITIONAL"), pending.map { it.role })
        assertEquals(listOf(0, 1, 2), pending.map { it.position })
        try { repo.reserve(); fail("Bound the pending queue") } catch (_: IllegalStateException) { }
        pending.forEach { jpeg(repo.pendingFile(it.id)) }
        repo.complete(pending[0].id)
        try { repo.next(); fail("Cannot change group with outstanding photos") } catch (_: IllegalStateException) { }
        try { repo.finish(); fail("Cannot finish with outstanding photos") } catch (_: IllegalStateException) { }
        // Startup recovers every pending file, including the second/third rapid shot.
        repo.recover()
        pending.forEach { assertEquals("READY", repo.dao.photo(it.id).state); assertEquals(hashFile(repo.original(it.id)), repo.dao.photo(it.id).sha256) }
        repo.next()
    }
    @Test fun checksumWorkDoesNotHoldCameraReservationLock(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val slowRepo = CaptureRepository(app, journal) { entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        slowRepo.start("While saving", "Q1")
        val first = slowRepo.reserve(); jpeg(slowRepo.pendingFile(first.id))
        val finishing = async(Dispatchers.IO) { slowRepo.complete(first.id) }
        try {
            withTimeout(5000) { entered.await() }
            val second = withTimeout(2000) { slowRepo.reserve() }
            assertEquals("BACK", second.role)
            assertEquals(first.groupId, second.groupId)
        } finally { release.countDown(); finishing.await() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun shutterUnlocksBeforeVerificationAndAcceptsSecondShot(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val slowRepo = CaptureRepository(app, journal) { entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        app.repository = slowRepo
        slowRepo.start("Shutter pipeline", "A1")
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val vm = CaptureViewModel(app); store.put("camera", vm)
            withTimeout(5000) { while (!vm.initialized) delay(5) }
            val secondTaken = CompletableDeferred<Unit>()
            vm.shoot(true) { file, _ -> jpeg(file) }
            assertEquals(1, vm.shutterSignal)
            withTimeout(5000) { entered.await(); while (vm.busy) delay(5) }
            assertFalse("File verification must not block the shutter", vm.busy)
            assertEquals(1, vm.saving)
            assertTrue("Next/Finish remain gated", vm.pendingCapture)
            vm.shoot(true) { file, _ -> jpeg(file); secondTaken.complete(Unit) }
            withTimeout(5000) { secondTaken.await() }
            release.countDown()
            withTimeout(5000) { while (vm.pendingCapture) delay(5) }
            val photos = slowRepo.dao.photos(slowRepo.current().id)
            assertEquals(listOf("FRONT", "BACK"), photos.map { it.role })
            assertTrue(photos.all { it.state == "READY" && it.sha256 == hashFile(slowRepo.original(it.id)) })
        } finally { release.countDown(); store.clear(); Dispatchers.resetMain() }
    }
    @Test fun tileCoverageHasNoGapsAndIncludesAllEdges() {
        for (length in listOf(100, 1536, 1537, 3024, 4032, 8160)) {
            val starts = tileStarts(length)
            assertEquals(0, starts.first()); assertEquals(maxOf(0, length - 1536), starts.last())
            starts.zipWithNext().forEach { (a, b) -> assertTrue(b - a <= 1024) }
        }
    }
    @Test fun nativeTilesReadSmallCornerBarcodeThatOverviewLoses(): Unit = runBlocking {
        val width = 4032; val height = 3024
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); image.eraseColor(Color.WHITE)
        val matrix = MultiFormatWriter().encode("036000291452", BarcodeFormat.UPC_A, 230, 72, mapOf(EncodeHintType.MARGIN to 20))
        val pixels = IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) Color.BLACK else Color.WHITE }
        image.setPixels(pixels, 0, matrix.width, 60, 24, matrix.width, matrix.height)
        val file = File(app.cacheDir, "small-corner-barcode.jpg")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
        val overview = recognitionBitmap(file, 2200)
        val oldResults = try { LocalBarcodeDecoder.decode(overview.width, overview.height, overview.argbPixels()) } finally { overview.recycle() }
        assertTrue("Fixture must exercise the lost-detail failure", oldResults.none { it.valid })
        // Robolectric's BitmapRegionDecoder returns a blank bitmap even in native
        // mode. Exercise production tiling with native JPEG pixels and Bitmap crops.
        val results = scanOriginalBarcodes(file) { source -> object : BarcodeRegions {
            private val sourceBitmap = android.graphics.BitmapFactory.decodeFile(source.path)!!
            override val width = sourceBitmap.width
            override val height = sourceBitmap.height
            private val regionPixels = sourceBitmap.argbPixels().also { sourceBitmap.recycle() }
            override fun read(rect: android.graphics.Rect): Bitmap = Bitmap.createBitmap(regionPixels, rect.top * this.width + rect.left, this.width, rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            override fun close() = Unit
        } }
        assertTrue(results.any { it.valid && it.normalized == "00036000291452" })
    }
}
