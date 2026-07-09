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
 * Material 3 theme for the phone's Compose surfaces. Uses dynamic color on API 31+ (matching the
 * user's wallpaper, the M3 Expressive default) and follows the system light/dark setting, so the
 * migrated UI feels native and consistent with the watch's M3 direction.
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
