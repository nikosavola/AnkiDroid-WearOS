package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wearos_core.WearosCard

/** The grade pill text (13sp names, 11sp intervals) must clear WCAG 4.5:1 on its own fill. */
class GradeContrastTest {
  private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
  }

  @Test
  fun everyGradeColorIsLegibleOnItsPill() {
    val card =
      WearosCard(
        id = 1,
        noteId = 1,
        ord = 0,
        front = "Q",
        back = "A",
        buttonCount = 4,
        nextReviewTimes = emptyList(),
      )
    for (spec in gradeSpecs(card)) {
      val fill = spec.color.copy(alpha = PILL_FILL_ALPHA).compositeOver(Color.Black)
      val ratio = contrast(spec.color, fill)
      assertTrue("${spec.text} is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
    }
  }
}
