package ca.canadavhs.snapmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = CaptureApplication::class, qualifiers = "w900dp-h420dp-land")
class CameraLayoutTest {
    private lateinit var journal: CaptureJournal
    private val seed = object : ExternalResource() {
        override fun before() {
            val app = org.robolectric.RuntimeEnvironment.getApplication() as CaptureApplication
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(java.util.concurrent.Executor { }).setTaskExecutor(SynchronousExecutor()).build())
            journal = Room.inMemoryDatabaseBuilder(app, CaptureJournal::class.java).allowMainThreadQueries().build()
            app.repository = CaptureRepository(app, journal)
            app.repository.preferences.edit().clear().commit()
            runBlocking { app.repository.start("Landscape batch", "A1") }
        }
        override fun after() {
            // Drain Activity teardown before closing flows observed by Compose.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            journal.close()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)
    @Test fun landscapeKeepsShutterNextAndResolutionVisible() {
        compose.onNodeWithText("Capture").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Landscape batch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shutter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next product").assertIsDisplayed()
        compose.onNodeWithText("3 MP · balanced ▾").assertIsDisplayed()
        val shutter = compose.onNodeWithTag("shutter").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription("Next product").fetchSemanticsNode().boundsInRoot
        assertTrue(shutter.width > 0 && shutter.height > 0 && next.width > 0)
        compose.onNodeWithText("3 MP · balanced ▾").performClick()
        compose.onNodeWithText("1.2 MP · smaller files").performClick()
        compose.onNodeWithText("1.2 MP · smaller files ▾").assertIsDisplayed()
        screenshot("landscape-controls.png")
        checkSettingsAndPortraitRotation()
    }
    private fun checkSettingsAndPortraitRotation() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Camera & capture").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Photo resolution"))
        compose.onNodeWithText("Photo resolution").assertIsDisplayed()
        compose.onNodeWithText("1.2 MP · smaller files ▾").performScrollTo().performClick()
        compose.onNodeWithText("6 MP · more detail").performScrollTo().performClick()
        val activity = compose.activity
        val before = System.identityHashCode(activity)
        compose.runOnUiThread { org.robolectric.RuntimeEnvironment.setQualifiers("w420dp-h900dp-port") }
        compose.runOnUiThread { activity.onConfigurationChanged(activity.resources.configuration) }
        compose.waitForIdle()
        val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
        assertTrue(info.configChanges and android.content.pm.ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue(info.configChanges and android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        assertEquals(before, System.identityHashCode(compose.activity))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Photo resolution"))
        compose.onNodeWithText("Photo resolution").assertIsDisplayed()
        compose.onNodeWithText("6 MP · more detail ▾").assertIsDisplayed()
        compose.onNodeWithText("Capture").performClick()
        compose.onNodeWithText("6 MP · more detail ▾").assertIsDisplayed()
        compose.onNodeWithTag("shutter").assertIsDisplayed()
        screenshot("portrait-controls.png")
    }
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(System.getProperty("snapims.testOutput"), name).apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
