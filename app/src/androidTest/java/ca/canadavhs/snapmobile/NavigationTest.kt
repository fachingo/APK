package ca.canadavhs.snapmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Non-destructive device UI checks: never clear data, capture photos or export. */
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun cameraCompanionNavigationAndOfflineSettings() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Recognition & AI").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Offline barcode scanning"))
        compose.onNodeWithText("Offline barcode scanning").assertIsDisplayed()
        compose.onNodeWithContentDescription("More navigation").performClick()
        compose.onNodeWithText("Batches").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("production-batch").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Your batches").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("More navigation").performClick()
        compose.onNodeWithText("Transfers").performClick()
        compose.onNodeWithText("Portable archives · originals stay on this device").assertIsDisplayed()
        compose.onNodeWithText("Capture").performClick()
        compose.onNodeWithText("SNAPIMS").assertIsDisplayed()
    }
}
