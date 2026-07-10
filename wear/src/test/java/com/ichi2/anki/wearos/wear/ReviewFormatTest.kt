package com.ichi2.anki.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure formatting checks — no Activity, Compose, or Robolectric needed. */
class ReviewFormatTest {
  @Test
  fun humanAge_picksUnitByThreshold() {
    assertEquals("0s", humanAge(0))
    assertEquals("59s", humanAge(59))
    // Crossing each boundary switches unit and truncates toward zero.
    assertEquals("1m", humanAge(60))
    assertEquals("59m", humanAge(3599))
    assertEquals("1h", humanAge(3600))
    assertEquals("23h", humanAge(86_399))
    assertEquals("1d", humanAge(86_400))
    assertEquals("2d", humanAge(200_000))
  }

  @Test
  fun formatDuration_secondsThenMinutesAndSeconds() {
    assertEquals("0s", formatDuration(0))
    assertEquals("45s", formatDuration(45_000))
    // Sub-second remainder is truncated, not rounded.
    assertEquals("59s", formatDuration(59_999))
    assertEquals("1m 0s", formatDuration(60_000))
    assertEquals("3m 20s", formatDuration(200_000))
  }
}
