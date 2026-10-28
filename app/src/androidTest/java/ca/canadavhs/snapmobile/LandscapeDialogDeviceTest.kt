package ca.canadavhs.snapmobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Rule
import org.junit.Test

/** Read-only UI fixture: no batches/photos/notes are written; cancel only. */
class LandscapeDialogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun landscapeNotesCanScrollAndCancel() {
        val previous = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread {
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                compose.activity.setContent {
                    MaterialTheme {
                        var open by remember { mutableStateOf(true) }
                        if (open) NotesDialog(viewModel(), GroupRow(sessionId = "read-only-device-fixture", number = 1)) { open = false }
                        else Text("Dialog closed")
                    }
                }
            }
            compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("Flags & notes").assertIsDisplayed()
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Rare")) and hasAnyDescendant(hasText("Capture notes"))).performScrollToNode(hasText("Capture notes"))
            compose.onNodeWithText("Capture notes").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
            compose.onNodeWithText("Dialog closed").assertIsDisplayed()
        } finally { compose.runOnUiThread { compose.activity.requestedOrientation = previous } }
    }
}
