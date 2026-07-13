package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.wear.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Proves Compose UI testing runs under Robolectric (createComposeRule + NATIVE graphics). The
 * top-level app composables are driven directly in GradeEdgeButtonsTest (tap-zone mapping) and
 * HtmlTextTest (card render); the remaining screen composables still live as MainActivity methods
 * and would need extracting to be driven here (tracked in TODO).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeSmokeTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun rendersAndFindsText() {
    composeRule.setContent { Text("AnkiDroid Wear") }
    composeRule.onNodeWithText("AnkiDroid Wear").assertIsDisplayed()
  }
}
