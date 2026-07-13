package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [HtmlText] renders sanitized-but-still-arbitrary card HTML through HtmlCompat inside an AndroidView
 * TextView. The rendered text lives in a View, not the Compose semantics tree, so we can't assert its
 * contents here - but composing it must never throw on the shapes real cards produce (empty, the
 * base(reading) furigana output of clean_html, malformed tags, every font option). This guards that
 * interop + HtmlCompat path against regressions. createComposeRule.setContent may only be called once
 * per test, so each test stacks its variants in a single Column.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HtmlTextTest {
  @get:Rule val composeRule = createComposeRule()

  private fun renderAll(vararg cards: Pair<String, WatchSettings.CardFont>) {
    composeRule.setContent {
      MaterialTheme {
        Column {
          for ((html, font) in cards) HtmlText(html, fontScale = 1.0f, cardFont = font)
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun html(vararg s: String) =
    s.map { it to WatchSettings.CardFont.DEFAULT }.toTypedArray()

  @Test
  fun rendersTypicalCardHtmlWithoutCrashing() {
    renderAll(*html("<b>答え</b><br>to answer", "日(に)本(ほん)語(ご)", "これは[...]です"))
  }

  @Test
  fun handlesEmptyAndMalformedHtml() {
    renderAll(*html("", "<b>unclosed and <i>nested", "&amp; &lt;3 &#39;quote&#39;"))
  }

  @Test
  fun rendersUnderEveryCardFont() {
    renderAll(
      "漢字テスト" to WatchSettings.CardFont.SERIF,
      "漢字テスト" to WatchSettings.CardFont.MONOSPACE,
      "漢字テスト" to WatchSettings.CardFont.DEFAULT,
    )
  }
}
