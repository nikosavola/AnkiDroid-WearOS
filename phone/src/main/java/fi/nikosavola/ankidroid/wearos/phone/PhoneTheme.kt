package fi.nikosavola.ankidroid.wearos.phone

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 theme for the phone's Compose UI: dynamic color on API 31+ (matching the wallpaper, the
 * M3 Expressive default), following the system light/dark setting. Expressive motion is delivered via
 * the spring animations in DeckSelectionTree. NOTE: the true MaterialExpressiveTheme API is `internal`
 * in stable material3 1.4.0 and only public in the 1.5.0-alpha line, which pulls compose 1.12.0-alpha
 * (too destabilizing); adopt it here once it ships in a stable release compatible with our compose.
 */
@Composable
fun AnkiPhoneTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val context = LocalContext.current
  val colorScheme =
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      dark -> darkColorScheme()
      else -> lightColorScheme()
    }
  MaterialTheme(colorScheme = colorScheme, content = content)
}
