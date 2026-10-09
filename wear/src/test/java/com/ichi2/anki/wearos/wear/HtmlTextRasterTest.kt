package com.ichi2.anki.wearos.wear

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import uniffi.wearos_core.answerHtml
import uniffi.wearos_core.compareTypedAnswer
import uniffi.wearos_core.htmlForDisplay

/**
 * The card renderer's drawn pixels, not just its AnnotatedString: the typed diff's wrong/missing
 * runs and the font-size annotation both live entirely in span styling, so a pipeline that dropped
 * spans would still pass every string-level assertion while rendering a plain white card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HtmlTextRasterTest {
  @get:Rule val composeRule = createComposeRule()

  private fun inkOf(html: String): Pair<Int, Map<Char, Int>> {
    composeRule.setContent { MaterialTheme { HtmlText(html, 1f, WatchSettings.CardFont.DEFAULT) } }
    composeRule.waitForIdle()
    val text = cardTextFromHtml(html)
    val bmp = composeRule.onAllNodes(hasText(text.text))[0].captureToImage()
    var ink = 0
    val byColor = mutableMapOf<Char, Int>('r' to 0, 'g' to 0, 'w' to 0)
    val map = bmp.toPixelMap()
    for (y in 0 until bmp.height) {
      for (x in 0 until bmp.width) {
        val p = map[x, y]
        if (p.alpha < 0.2f) continue
        val channel =
          when {
            // The diff's fixed colours: red ff5252 for typed-wrong, grey 9e9e9e for missed.
            p.red > 0.85f && p.green < 0.55f && p.blue < 0.55f -> 'r'
            p.red in 0.5f..0.75f && p.green in 0.5f..0.75f && p.blue in 0.5f..0.75f -> 'g'
            else -> 'w'
          }
        byColor[channel] = (byColor[channel] ?: 0) + 1
        ink++
      }
    }
    return ink to byColor
  }

  @Test
  fun typedDiffDrawsItsRedAndGreyRuns() {
    val back = "Capital of France?<hr id=answer><code id=typeans>Paris</code>"
    val diff = compareTypedAnswer(answerHtml(back), "paros")!!
    val (ink, byColor) = inkOf(htmlForDisplay(diff!!))
    assertTrue("diff drew nothing", ink > 100)
    val red = byColor['r'] ?: 0
    val grey = byColor['g'] ?: 0
    assertTrue("no red pixels for the wrong typed char: $red", red > 0)
    assertTrue("no grey pixels for the missed char: $grey", grey > 0)
  }

  @Test
  fun fontSizeAnnotationDrawsInk() {
    val (ink, _) = inkOf(htmlForDisplay("<font size=7>犬</font> body"))
    assertTrue("font-size card drew nothing", ink > 200)
  }
}
