package fi.nikosavola.ankidroid.wearos.wear

import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.wear.compose.material3.MaterialTheme

/**
 * Renders sanitized card HTML (bold, colors, line breaks, etc.) via HtmlCompat in a TextView. Wear
 * OS has no WebView, so this is the richest safe renderer available. Text color follows the active
 * theme (so it stays legible in light mode); [fontScale] and [cardFont] come from the user's
 * settings. Top-level (state passed in) so it's reusable and testable outside the Activity.
 */
@Composable
fun HtmlText(
  html: String,
  fontScale: Float,
  cardFont: WatchSettings.CardFont,
  modifier: Modifier = Modifier,
) {
  val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
  AndroidView(
    modifier = modifier,
    factory = { ctx -> TextView(ctx).apply { gravity = Gravity.CENTER } },
    update = { tv ->
      tv.setTextColor(textColor)
      tv.textSize = 16f * fontScale
      tv.typeface =
        when (cardFont) {
          WatchSettings.CardFont.SERIF -> Typeface.SERIF
          WatchSettings.CardFont.MONOSPACE -> Typeface.MONOSPACE
          WatchSettings.CardFont.DEFAULT -> Typeface.DEFAULT
        }
      tv.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    },
  )
}
