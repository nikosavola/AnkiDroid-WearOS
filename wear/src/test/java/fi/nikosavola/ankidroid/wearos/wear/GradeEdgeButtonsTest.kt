package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import uniffi.wearos_core.WearosCard

/**
 * Drives the real [GradeEdgeButtons] composable under Robolectric to lock the tap-to-ease mapping -
 * the interaction that broke on device before (an overlay layer that never received the synthetic
 * taps). The buttons have no per-pill click targets; a single pointerInput strip over the bottom of
 * the screen maps the tap's x-position to a grade left->right, so these tests click at known
 * horizontal fractions and assert the ease that fires. [GradeSpecTest] covers the pure ease mapping;
 * this covers the touch wiring on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradeEdgeButtonsTest {
  @get:Rule val composeRule = createComposeRule()

  private fun card(buttonCount: Int) =
    WearosCard(
      id = 1,
      noteId = 1,
      ord = 0,
      front = "Q",
      back = "A",
      buttonCount = buttonCount,
      nextReviewTimes = emptyList(),
    )

  /** Renders the buttons in a fixed 200dp square and returns the last ease tapped (via the holder). */
  private fun setButtons(buttonCount: Int, enabled: Boolean = true, onGrade: (Int) -> Unit) {
    composeRule.setContent {
      MaterialTheme {
        Box(Modifier.size(200.dp).testTag("host")) {
          GradeEdgeButtons(card(buttonCount), enabled = enabled, colored = true, onGrade = onGrade)
        }
      }
    }
  }

  /** Tap the bottom strip (where the arc lives) at horizontal fraction [fx] of the width. */
  private fun tapAtFraction(fx: Float) {
    composeRule.onNodeWithTag("host").performTouchInput {
      click(Offset(width * fx, height * 0.85f))
    }
    composeRule.waitForIdle()
  }

  @Test
  fun fourButtonCard_mapsLeftToRightToAgainHardGoodEasy() {
    var graded: Int? = null
    setButtons(4) { graded = it }

    tapAtFraction(0.1f)
    assertEquals("far left -> Again", 1, graded)
    tapAtFraction(0.4f)
    assertEquals("second quarter -> Hard", 2, graded)
    tapAtFraction(0.6f)
    assertEquals("third quarter -> Good", 3, graded)
    tapAtFraction(0.9f)
    assertEquals("far right -> Easy", 4, graded)
  }

  @Test
  fun threeButtonCard_skipsHard_mapsToAgainGoodEasy() {
    var graded: Int? = null
    setButtons(3) { graded = it }

    tapAtFraction(0.1f)
    assertEquals("far left -> Again", 1, graded)
    tapAtFraction(0.5f)
    assertEquals("middle -> Good (no Hard on a 3-button card)", 3, graded)
    tapAtFraction(0.9f)
    assertEquals("far right -> Easy", 4, graded)
  }

  @Test
  fun edgesClampIntoRange_neverThrows() {
    var graded: Int? = null
    setButtons(4) { graded = it }
    tapAtFraction(0.001f)
    assertEquals(1, graded)
    tapAtFraction(0.999f)
    assertEquals(4, graded)
  }

  @Test
  fun accessibility_eachGradeIsAFocusableActivatableElement() {
    var graded: Int? = null
    setButtons(4) { graded = it }
    // TalkBack sees one labelled element per grade (the pointerInput strip alone is invisible to it).
    listOf("Again", "Hard", "Good", "Easy").forEach {
      composeRule.onNodeWithContentDescription(it).assertExists()
    }
    // Activating one via its semantics onClick (what TalkBack invokes) grades that ease.
    composeRule.onNodeWithContentDescription("Good").performSemanticsAction(SemanticsActions.OnClick)
    assertEquals(3, graded)
  }

  @Test
  fun disabled_tapsAreIgnored() {
    var graded: Int? = null
    setButtons(4, enabled = false) { graded = it }
    tapAtFraction(0.1f)
    tapAtFraction(0.9f)
    assertNull("no grade should fire while answering is disabled", graded)
  }
}
