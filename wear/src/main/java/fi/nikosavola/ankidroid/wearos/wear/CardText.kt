package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.LocalTextStyle
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
  val baseSp = 16.sp * fontScale
  val screenWidthDp = LocalConfiguration.current.screenWidthDp
  val maxScale =
    with(LocalDensity.current) { (screenWidthDp * MAX_RUN_WIDTH_FRACTION).dp.toSp().value } /
      baseSp.value
  val text = remember(html, maxScale) { cardTextFromHtml(html, maxScale) }
  // The theme's fixed line height is sized for body text, so a larger run would overprint the
  // lines around it. Without sized runs the theme's spacing stays.
  val style =
    if (text.spanStyles.any { it.item.fontSize.value > 1f }) {
      LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)
    } else {
      LocalTextStyle.current
    }
  Text(
    text = text,
    modifier = modifier,
    style = style,
    color = MaterialTheme.colorScheme.onSurface,
    fontSize = baseSp,
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
 * Widest a sized run may get, as a fraction of the screen width. Card CSS sizes display text for a
 * phone, where AnkiDroid shows a 125px glyph at about 30% of the width; scaled against the watch's
 * boosted body text the same glyph would take over half of it.
 */
private const val MAX_RUN_WIDTH_FRACTION = 0.3f

/**
 * How much of a size's step above the body text is kept. The watch's body text is already boosted
 * against a phone's, so a run's scale relative to it overshoots: 30px beside 18px body would be
 * 1.7x, which is far heavier on a watch than on a phone.
 */
private const val EMPHASIS_SLOPE = 0.5f

/** The attribute the core's font-size pass marks text runs with, carrying the scale to apply. */
private const val FONT_SIZE_ANNOTATION = "fs"

/**
 * The card's styled text without its links: fromHtml makes `<a href>` clickable, which on a watch
 * with no browser would throw on tap and swallow the tap that reveals the answer.
 *
 * Android's HTML import drops font-size styles, so the core wraps sized runs in
 * `<annotation fs="scale">` tags instead; Compose turns those into string annotations, which are
 * folded back into font-size spans here (the one thing Compose's importer left out). Sizes above
 * the base grow at [EMPHASIS_SLOPE] of their step and no span exceeds [maxScale] times the base.
 */
internal fun cardTextFromHtml(html: String, maxScale: Float = Float.MAX_VALUE): AnnotatedString {
  val parsed = AnnotatedString.fromHtml(html.replace(IMG_TAG, ""))
  val spans =
    parsed.spanStyles +
    parsed.getStringAnnotations(FONT_SIZE_ANNOTATION, 0, parsed.length).mapNotNull { range ->
      range.item.toFloatOrNull()?.let {
        val scale = if (it > 1f) 1f + (it - 1f) * EMPHASIS_SLOPE else it
        val size = SpanStyle(fontSize = minOf(scale, maxScale).em)
        AnnotatedString.Range(size, range.start, range.end)
      }
    }
  return AnnotatedString(parsed.text, spans, parsed.paragraphStyles)
}
