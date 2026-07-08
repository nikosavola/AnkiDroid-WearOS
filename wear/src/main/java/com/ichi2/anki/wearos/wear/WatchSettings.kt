package com.ichi2.anki.wearos.wear

import android.content.Context

/**
 * User-adjustable watch settings, persisted in SharedPreferences. Deliberately tiny and
 * synchronous: [MainActivity] mirrors these into Compose state on start and re-reads on each change
 * rather than pulling in a reactive store.
 */
object WatchSettings {
  private const val PREFS = "watch_settings"
  private const val KEY_THEME = "theme_mode"
  private const val KEY_FONT_SCALE = "font_scale"
  private const val KEY_CARD_FONT = "card_font"

  /** Theme selection. SYSTEM follows the OS light/dark setting where the platform reports one. */
  enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
  }

  /** Typeface family for card text; Serif/Monospace render larger, CJK-friendly glyphs. */
  enum class CardFont {
    DEFAULT,
    SERIF,
    MONOSPACE,
  }

  // Legible clamp for the card text multiplier (1.0 == the 16sp baseline).
  const val FONT_SCALE_MIN = 0.8f
  const val FONT_SCALE_MAX = 1.8f

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun themeMode(context: Context): ThemeMode =
    runCatching { ThemeMode.valueOf(prefs(context).getString(KEY_THEME, "") ?: "") }
      .getOrDefault(ThemeMode.DARK)

  fun setThemeMode(context: Context, mode: ThemeMode) {
    prefs(context).edit().putString(KEY_THEME, mode.name).apply()
  }

  fun fontScale(context: Context): Float =
    prefs(context).getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

  fun setFontScale(context: Context, scale: Float) {
    prefs(context).edit().putFloat(KEY_FONT_SCALE, scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)).apply()
  }

  fun cardFont(context: Context): CardFont =
    runCatching { CardFont.valueOf(prefs(context).getString(KEY_CARD_FONT, "") ?: "") }
      .getOrDefault(CardFont.DEFAULT)

  fun setCardFont(context: Context, font: CardFont) {
    prefs(context).edit().putString(KEY_CARD_FONT, font.name).apply()
  }
}
