package com.ichi2.anki.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.wearos_core.WearosCard

/** Pure checks of the Anki 3-vs-4-button ease mapping — no Compose or Android needed. */
class GradeSpecTest {
  private fun card(buttonCount: Int) =
    WearosCard(
      id = 1,
      noteId = 1,
      ord = 0,
      front = "Q",
      back = "A",
      buttonCount = buttonCount,
      nextReviewTimes = emptyList(),
    )

  @Test
  fun threeButtonCard_dropsHard_usesEases1_3_4() {
    val specs = gradeSpecs(card(3))
    assertEquals(listOf("Again", "Good", "Easy"), specs.map { it.text })
    assertEquals(listOf(1, 3, 4), specs.map { it.ease })
  }

  @Test
  fun fourButtonCard_includesHard_usesEases1_2_3_4() {
    val specs = gradeSpecs(card(4))
    assertEquals(listOf("Again", "Hard", "Good", "Easy"), specs.map { it.text })
    assertEquals(listOf(1, 2, 3, 4), specs.map { it.ease })
  }

  @Test
  fun onlyThreeIsSpecial_otherCountsFallToFourButtonLayout() {
    // The mapping keys off "== 3"; any other count (incl. an unexpected value) yields the 4-button
    // set, so ease 2 (Hard) is reachable exactly when the card is not a 3-button card.
    assertEquals(4, gradeSpecs(card(2)).size)
    assertEquals(4, gradeSpecs(card(4)).size)
    assertEquals(3, gradeSpecs(card(3)).size)
  }
}
