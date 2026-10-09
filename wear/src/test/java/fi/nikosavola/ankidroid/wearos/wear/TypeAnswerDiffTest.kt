package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.isUnspecified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import uniffi.wearos_core.answerHtml
import uniffi.wearos_core.compareTypedAnswer
import uniffi.wearos_core.htmlForDisplay
import uniffi.wearos_core.typeAnswerExpected

/**
 * The typed-answer pipeline end to end against the real core .so: the filled back's expected
 * answer, the rslib-style diff, and its trip through htmlForDisplay and the same HTML import the
 * card renderer uses. Anki colours the diff runs as backgrounds; ours must arrive as foreground
 * colours the importer can carry, with no font-size side effects.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeAnswerDiffTest {
  private val back = "Capital of France?<hr id=answer><code id=typeans>Paris</code>"

  @Test
  fun expectedAnswerReadsBackOutOfTheFilledBack() {
    assertEquals("<code id=typeans>Paris</code>", answerHtml(back))
    assertEquals("Paris", typeAnswerExpected(back))
  }

  @Test
  fun nothingTypedKeepsThePlainExpectedAnswer() {
    assertNull(compareTypedAnswer(answerHtml(back), ""))
    assertNull(compareTypedAnswer("Q<hr id=answer>no typeans here", "paris"))
  }

  @Test
  fun typedDiffSurvivesTheDisplayPipelineWithItsColours() {
    val diff = compareTypedAnswer(answerHtml(back), "paros")
    assertNotNull(diff)
    val text = cardTextFromHtml(htmlForDisplay(diff!!))
    // The typed line, the rslib arrow, the expected line (its own casing kept).
    assertEquals("paros\n↓\nParis", text.text)
    val colors = text.spanStyles.map { it.item.color }
    assertTrue("no red for the wrong typed char: ${text.spanStyles}", colors.contains(Color(0xFFFF5252)))
    assertTrue("no grey for the missed expected char: ${text.spanStyles}", colors.contains(Color(0xFF9E9E9E)))
    // The diff must not pick up font-size scaling on the way through.
    assertTrue(text.spanStyles.all { it.item.fontSize.isUnspecified })
  }
}
