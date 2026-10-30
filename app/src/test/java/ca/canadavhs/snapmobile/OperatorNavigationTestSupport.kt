package ca.canadavhs.snapmobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.junit.rules.TestRule

internal fun <R:TestRule,A:ComponentActivity> AndroidComposeTestRule<R,A>.openUtility(label:String) {
 onNodeWithContentDescription("More navigation").performClick()
 onAllNodesWithText(label).onLast().performClick()
}
