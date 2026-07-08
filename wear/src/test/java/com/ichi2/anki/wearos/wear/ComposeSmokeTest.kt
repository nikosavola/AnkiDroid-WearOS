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
 * Proves Compose UI testing runs under Robolectric (createComposeRule + NATIVE graphics). This is
 * the scaffold; app composables currently live as MainActivity methods and would need extracting to
 * top-level @Composables to be driven here directly (tracked in TODO).
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
