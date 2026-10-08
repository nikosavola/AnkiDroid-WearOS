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

  @Test
  fun longText_addsEtaOnlyWhenSomethingIsDue() {
    assertEquals("12 due, ~6 min to finish", dueLongText(12, "~6 min to finish"))
    assertEquals("No cards due", dueLongText(0, ""))
  }

  @Test
  fun progressRange_isDoneOverDonePlusDue() {
    assertEquals(28f to 40f, todayProgressRange(reviewedToday = 28, due = 12))
    assertEquals(0f to 5f, todayProgressRange(reviewedToday = 0, due = 5))
  }

  @Test
  fun progressRange_nothingAtAllReadsAsCompleteAndKeepsMaxAboveMin() {
    assertEquals(1f to 1f, todayProgressRange(reviewedToday = 0, due = 0))
  }
}
