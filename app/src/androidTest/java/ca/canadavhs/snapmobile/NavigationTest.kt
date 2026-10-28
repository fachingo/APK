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
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Offline barcode scanning"))
        compose.onNodeWithText("Offline barcode scanning").assertIsDisplayed()
        compose.onNodeWithText("Batches").performClick()
        compose.onNodeWithText("Your batches").assertIsDisplayed()
        compose.onNodeWithText("Transfers").performClick()
        compose.onNodeWithText("Portable archives · originals stay on this device").assertIsDisplayed()
        compose.onNodeWithText("Capture").performClick()
        compose.onNodeWithText("SNAPIMS").assertIsDisplayed()
    }
}
