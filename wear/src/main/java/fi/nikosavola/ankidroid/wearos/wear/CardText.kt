package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * Renders sanitized card HTML (bold, colors, sizes, line breaks, etc.) as Compose text. Wear OS has
 * no WebView, and a Compose [Text] keeps the card in the same render pipeline as the rest of the
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

/** The attribute the core's font-size pass marks text runs with, carrying the scale to apply. */
private const val FONT_SIZE_ANNOTATION = "fs"

/**
 * The card's styled text without its links: fromHtml makes `<a href>` clickable, which on a watch
 * with no browser would throw on tap and swallow the tap that reveals the answer.
 *
 * Android's HTML import drops font-size styles, so the core wraps sized runs in
 * `<annotation fs="scale">` tags instead; Compose turns those into string annotations, which are
 * folded back into font-size spans here (the one thing Compose's importer left out).
 */
internal fun cardTextFromHtml(html: String): AnnotatedString {
  val parsed = AnnotatedString.fromHtml(html.replace(IMG_TAG, ""))
  val spans =
    parsed.spanStyles +
    parsed.getStringAnnotations(FONT_SIZE_ANNOTATION, 0, parsed.length).mapNotNull { range ->
      range.item.toFloatOrNull()?.let { AnnotatedString.Range(SpanStyle(fontSize = it.em), range.start, range.end) }
    }
  return AnnotatedString(parsed.text, spans, parsed.paragraphStyles)
}
