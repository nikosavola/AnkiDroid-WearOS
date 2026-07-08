package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Wraps content in a Wear Material 3 theme honoring the user's [WatchSettings.ThemeMode].
 *
 * Wear devices are dark by default (OLED, always-on power), so DARK uses the library's default
 * scheme untouched. LIGHT swaps in a high-contrast light scheme. SYSTEM defers to
 * [isSystemInDarkTheme]; note many round Wear OS devices always report dark, so SYSTEM may look
 * identical to DARK on them — that's expected, not a bug.
 *
 * NOTE FOR VERIFICATION: the light [ColorScheme] below is constructed with named color roles that
 * exist in wear.compose.material3 1.6.2. If a future catalog bump renames a role, only this file
 * needs updating.
 */
@Composable
fun AnkiWearTheme(mode: WatchSettings.ThemeMode, content: @Composable () -> Unit) {
  val dark =
    when (mode) {
      WatchSettings.ThemeMode.DARK -> true
      WatchSettings.ThemeMode.LIGHT -> false
      WatchSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
  MaterialTheme(colorScheme = if (dark) ColorScheme() else LIGHT_SCHEME, content = content)
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
