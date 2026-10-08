package com.ichi2.anki.wearos.wear

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Accessibility of the review controls: the undo touch target and image-rendered card sides. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewControlsTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun undo_hasAFullSizeButtonTarget() {
    var undone = 0
    composeRule.setContent { MaterialTheme { UndoButton(onClick = { undone++ }) } }
    composeRule
      .onNodeWithContentDescription("Undo")
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
      .assertHasClickAction()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      .performClick()
    assertEquals(1, undone)
  }

  @Test
  fun imageSide_isReadAsTheCardText() {
    composeRule.setContent {
      MaterialTheme {
        CardSide(
          image = SideImage.Ready(ImageBitmap(8, 8)),
          fallbackHtml = "<b>犬</b>",
          spokenText = "犬(いぬ) dog",
          fontScale = 1f,
          cardFont = WatchSettings.CardFont.DEFAULT,
        )
      }
    }
    composeRule.onNodeWithContentDescription("犬(いぬ) dog").assertExists()
  }
}
