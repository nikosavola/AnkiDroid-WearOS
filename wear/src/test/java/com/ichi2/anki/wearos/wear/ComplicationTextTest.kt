package com.ichi2.anki.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure checks of the due-count -> complication text mapping — no complications framework needed. */
class ComplicationTextTest {
  @Test
  fun zeroDue_showsCheckmarkAndCaughtUpDescription() {
    val (text, description) = dueCountComplicationText(0)
    assertEquals("✓", text)
    assertEquals("No cards due", description)
  }

  @Test
  fun oneDue_usesSingularDescription() {
    val (text, description) = dueCountComplicationText(1)
    assertEquals("1", text)
    assertEquals("1 card due", description)
  }

  @Test
  fun multipleDue_usesPluralDescription() {
    val (text, description) = dueCountComplicationText(42)
    assertEquals("42", text)
    assertEquals("42 cards due", description)
  }
}
