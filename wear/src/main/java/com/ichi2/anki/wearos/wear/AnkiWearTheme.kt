package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Wraps content in a Wear Material 3 theme honoring the user's [WatchSettings.ThemeMode].
 *
 * Wear devices are dark by default (OLED, always-on power), so DARK prefers the watch face's
 * dynamic colors (Wear OS 6+) and falls back to the library's default scheme elsewhere. LIGHT swaps
 * in a high-contrast light scheme. SYSTEM defers to [isSystemInDarkTheme]; note many round Wear OS
 * devices always report dark, so SYSTEM may look identical to DARK on them; that's expected, not a
 * bug. Motion is always the expressive scheme.
 */
@Composable
fun AnkiWearTheme(mode: WatchSettings.ThemeMode, content: @Composable () -> Unit) {
  val dark =
    when (mode) {
      WatchSettings.ThemeMode.DARK -> true
      WatchSettings.ThemeMode.LIGHT -> false
      WatchSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
  val context = LocalContext.current
  MaterialTheme(
    colorScheme = if (dark) dynamicColorScheme(context) ?: ColorScheme() else LIGHT_SCHEME,
    motionScheme = MotionScheme.expressive(),
    content = content,
  )
}

// A minimal light scheme: light surfaces with dark on-content. Accent roles keep their defaults.
private val LIGHT_SCHEME =
  ColorScheme(
    background = Color(0xFFF6F6F6),
    onBackground = Color(0xFF1B1B1B),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFECECEC),
    surfaceContainerHigh = Color(0xFFDFDFDF),
    onSurface = Color(0xFF1B1B1B),
    onSurfaceVariant = Color(0xFF4A4A4A),
  )
