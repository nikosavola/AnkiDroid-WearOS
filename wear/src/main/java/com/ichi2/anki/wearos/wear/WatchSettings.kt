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
  private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
  private const val KEY_TTS_ENABLED = "tts_enabled"
  private const val KEY_SPEECH_RATE = "speech_rate"
  private const val KEY_HIDE_CLOCK = "hide_clock"
  private const val KEY_HIDE_PROGRESS = "hide_progress"
  private const val KEY_COLORED_GRADE_BUTTONS = "colored_grade_buttons"

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

  // TTS speech-rate clamp (1.0 == the engine's normal rate).
  const val SPEECH_RATE_MIN = 0.5f
  const val SPEECH_RATE_MAX = 2.0f

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
    prefs(context)
      .edit()
      .putFloat(KEY_FONT_SCALE, scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX))
      .apply()
  }

  fun cardFont(context: Context): CardFont =
    runCatching { CardFont.valueOf(prefs(context).getString(KEY_CARD_FONT, "") ?: "") }
      .getOrDefault(CardFont.DEFAULT)

  fun setCardFont(context: Context, font: CardFont) {
    prefs(context).edit().putString(KEY_CARD_FONT, font.name).apply()
  }

  /** Keep the screen awake during a review (handy for hands-free TTS study; costs battery). */
  fun keepScreenOn(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)

  fun setKeepScreenOn(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
  }

  /** Whether card text is read aloud during review. Off by default (no surprise audio). */
  fun ttsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TTS_ENABLED, false)

  fun setTtsEnabled(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean(KEY_TTS_ENABLED, on).apply()
  }

  fun speechRate(context: Context): Float =
    prefs(context).getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX)

  fun setSpeechRate(context: Context, rate: Float) {
    prefs(context)
      .edit()
      .putFloat(KEY_SPEECH_RATE, rate.coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX))
      .apply()
  }

  /** Hide the clock (TimeText) on the review card, for a minimal look. Off by default. */
  fun hideClock(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_CLOCK, false)

  fun setHideClock(context: Context, hide: Boolean) {
    prefs(context).edit().putBoolean(KEY_HIDE_CLOCK, hide).apply()
  }

  /** Hide the N/M progress text on the review card, for a minimal look. Off by default. */
  fun hideProgress(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_PROGRESS, false)

  fun setHideProgress(context: Context, hide: Boolean) {
    prefs(context).edit().putBoolean(KEY_HIDE_PROGRESS, hide).apply()
  }

  /** Color the grade buttons per Anki's ease scheme (on by default); off renders them neutral. */
  fun coloredGradeButtons(context: Context): Boolean =
    prefs(context).getBoolean(KEY_COLORED_GRADE_BUTTONS, true)

  fun setColoredGradeButtons(context: Context, colored: Boolean) {
    prefs(context).edit().putBoolean(KEY_COLORED_GRADE_BUTTONS, colored).apply()
  }
}
