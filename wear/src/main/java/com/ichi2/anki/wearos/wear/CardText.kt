package com.ichi2.anki.wearos.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * Renders sanitized card HTML (bold, colors, line breaks, etc.) as Compose text. Wear OS has no
 * WebView, and a Compose [Text] keeps the card in the same render pipeline as the rest of the
 * screen instead of an interop View. Text color follows the theme's onSurface; [fontScale] and
 * [cardFont] come from the user's settings. Top-level (state passed in) so it's reusable and
 * testable outside the Activity.
 */
@Composable
fun HtmlText(
  html: String,
  fontScale: Float,
  cardFont: WatchSettings.CardFont,
  modifier: Modifier = Modifier,
) {
  val text = remember(html) { cardTextFromHtml(html) }
  Text(
    text = text,
    modifier = modifier,
    color = MaterialTheme.colorScheme.onSurface,
    fontSize = 16.sp * fontScale,
    fontFamily =
      when (cardFont) {
        WatchSettings.CardFont.SERIF -> FontFamily.Serif
        WatchSettings.CardFont.MONOSPACE -> FontFamily.Monospace
        WatchSettings.CardFont.DEFAULT -> FontFamily.Default
      },
    textAlign = TextAlign.Center,
  )
}

// Html.fromHtml shows a placeholder glyph for every image, and the watch has no media to show.
private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

/**
 * The card's styled text without its links: fromHtml makes `<a href>` clickable, which on a watch
 * with no browser would throw on tap and swallow the tap that reveals the answer.
 */
internal fun cardTextFromHtml(html: String): AnnotatedString {
  val parsed = AnnotatedString.fromHtml(html.replace(IMG_TAG, ""))
  return AnnotatedString(parsed.text, parsed.spanStyles, parsed.paragraphStyles)
}
