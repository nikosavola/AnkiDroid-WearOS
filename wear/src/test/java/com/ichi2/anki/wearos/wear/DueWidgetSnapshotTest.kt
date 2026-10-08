package com.ichi2.anki.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.wearos_core.PoolDeckSummary

class DueWidgetSnapshotTest {
  private fun deck(
    id: Long,
    name: String,
    due: Int,
    later: Int = 0,
    nextSecs: Long? = null,
  ) = PoolDeckSummary(id, name, due, later, nextSecs, totalCards = 50)

  @Test
  fun `sums due and keeps the busiest decks first`() {
    val snapshot =
      dueWidgetSnapshot(
        listOf(
          deck(1, "Spanish", 4),
          deck(2, "Japanese::Kanji", 9),
          deck(3, "Empty", 0, later = 2, nextSecs = 2_000),
          deck(4, "biology", 4),
          deck(5, "Chemistry", 1, later = 1, nextSecs = 1_500),
        )
      )

    assertEquals(18, snapshot.dueNow)
    assertEquals(3, snapshot.learningLater)
    assertEquals(1_500_000L, snapshot.nextLearningDueMs)
    // Ties broken by name, case-insensitive; capped at three; leaf names only.
    assertEquals(listOf("Kanji", "biology", "Spanish"), snapshot.topDecks.map { it.name })
    assertEquals(listOf(2L, 4L, 1L), snapshot.topDecks.map { it.id })
  }

  @Test
  fun `decks with nothing due are not listed`() {
    val snapshot = dueWidgetSnapshot(listOf(deck(1, "A", 0)))
    assertEquals(0, snapshot.dueNow)
    assertEquals(emptyList<WidgetDeck>(), snapshot.topDecks)
    assertEquals("All caught up", emptyTitle(snapshot))
    assertNull(laterLine(snapshot) { "x" })
  }

  @Test
  fun `no decks asks for a sync`() {
    val snapshot = dueWidgetSnapshot(emptyList())
    assertFalse(snapshot.hasDecks)
    assertEquals(DueWidgetSnapshot.NoDecks, snapshot)
    assertEquals("Sync from phone", laterLine(snapshot) { "x" })
  }

  @Test
  fun `later line uses the formatted next due time`() {
    val snapshot = dueWidgetSnapshot(listOf(deck(1, "A", 0, later = 3, nextSecs = 60)))
    assertEquals("3 more at t60000", laterLine(snapshot) { "t$it" })
    val noTime = snapshot.copy(nextLearningDueMs = null)
    assertEquals("3 more later today", laterLine(noTime) { "x" })
  }

  @Test
  fun `due label pluralises`() {
    assertEquals("card due", dueLabel(1))
    assertEquals("cards due", dueLabel(2))
  }

  @Test
  fun `leaf name falls back to the full name`() {
    assertEquals("Kanji", deckLeafName("Japanese::Kanji"))
    assertEquals("Japanese::", deckLeafName("Japanese::"))
    assertEquals("Plain", deckLeafName("Plain"))
  }
}
