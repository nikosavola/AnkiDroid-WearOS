package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [HtmlText] renders sanitized-but-still-arbitrary card HTML as Compose text. It must never throw on
 * the shapes real cards produce (empty, the base(reading) furigana output of clean_html, malformed
 * tags, every font option), and the markup must not leak into the visible text.
 * createComposeRule.setContent may only be called once per test, so each test stacks its variants
 * in a single Column.
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
    composeRule.onNodeWithText("答え", substring = true).assertExists()
    composeRule.onNodeWithText("<b>", substring = true).assertDoesNotExist()
  }

  @Test
  fun handlesEmptyAndMalformedHtml() {
    renderAll(*html("", "<b>unclosed and <i>nested", "&amp; &lt;3 &#39;quote&#39;"))
    composeRule.onNodeWithText("& <3 'quote'").assertExists()
  }

  @Test
  fun rendersUnderEveryCardFont() {
    renderAll(
      "漢字テスト" to WatchSettings.CardFont.SERIF,
      "漢字テスト" to WatchSettings.CardFont.MONOSPACE,
      "漢字テスト" to WatchSettings.CardFont.DEFAULT,
    )
  }

  @Test
  fun linksAreInertAndImagesLeaveNoPlaceholder() {
    val text = cardTextFromHtml("""see <a href="https://jisho.org">jisho</a> <img src="x.png"> here""")
    assertEquals("see jisho here", text.text)
    assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
  }

  @Test
  fun tapOnALinkReachesTheCardInsteadOfOpeningIt() {
    var clicks = 0
    composeRule.setContent {
      MaterialTheme {
        Box(Modifier.clickable { clicks++ }) {
          HtmlText("""<a href="https://jisho.org">jisho</a>""", 1.0f, WatchSettings.CardFont.DEFAULT)
        }
      }
    }
    composeRule.onNodeWithText("jisho").performClick()
    assertEquals(1, clicks)
  }
}
