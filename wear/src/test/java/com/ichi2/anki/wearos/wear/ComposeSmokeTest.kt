package com.ichi2.anki.wearos.wear

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
 * and would need extracting to be driven here (tracked in issue #22).
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

  @Test
  fun gradeIntervalLabels_showOneLabelPerButtonInGradeOrder() {
    composeRule.setContent {
      GradeIntervalLabels(labels = listOf("1m", "6m", "10m", "4d"))
    }
    // The strip is positional: reading left to right it matches the pills (Again/Hard/Good/Easy).
    composeRule.onNodeWithText("1m · 6m · 10m · 4d").assertIsDisplayed()
  }

  @Test
  fun gradeIntervalLabels_areHiddenWhenACardCarriesNone() {
    // Mock cards and phones that sent no scheduling config ship no labels; the strip must not leave
    // an empty gap above the grade buttons.
    composeRule.setContent { GradeIntervalLabels(labels = emptyList()) }
    composeRule.onNodeWithText("1m").assertDoesNotExist()
  }
}
