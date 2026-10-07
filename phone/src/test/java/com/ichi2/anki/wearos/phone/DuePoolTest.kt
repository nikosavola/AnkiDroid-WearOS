package com.ichi2.anki.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.parseDeckScheduleConfig

/** Pure ordering/capping rules of the multi-day pool, with no provider involved. */
class DuePoolTest {
  private fun card(
    id: Long,
    dueDay: Long? = null,
    dueAtSecs: Long? = null,
    labels: List<String> = emptyList(),
  ) =
    WearosCard(
      id = id,
      noteId = id,
      ord = 0,
      front = "front $id",
      back = "back $id",
      buttonCount = 4,
      nextReviewTimes = labels,
      dueDay = dueDay,
      dueAtSecs = dueAtSecs,
    )

  @Test
  fun `the pool keeps today, then learning, then nearest days, then new`() {
    val pool =
      orderedPool(
        today = listOf(card(1)),
        learning = listOf(card(2, dueAtSecs = 600)),
        upcoming = listOf(card(3, dueDay = 20), card(4, dueDay = 18)),
        newCards = listOf(card(5)),
        limit = 5,
      )
    assertEquals(listOf(1L, 2L, 4L, 3L, 5L), pool.map { it.id })
  }

  @Test
  fun `the cap drops the tail past the limit`() {
    val pool =
      orderedPool(
        today = listOf(card(1)),
        learning = listOf(card(2, dueAtSecs = 600)),
        upcoming = listOf(card(3, dueDay = 20)),
        newCards = listOf(card(4)),
        limit = 2,
      )
    assertEquals(listOf(1L, 2L), pool.map { it.id })
  }

  @Test
  fun `a repeated card id is carried once, under its earliest group`() {
    val pool =
      orderedPool(
        today = listOf(card(1)),
        learning = listOf(card(1), card(2, dueAtSecs = 600)),
        upcoming = emptyList(),
        newCards = emptyList(),
        limit = 10,
      )
    assertEquals(listOf(1L, 2L), pool.map { it.id })
  }

  @Test
  fun `new cards are limited to the per-day allowance across the horizon`() {
    assertEquals(140, newCardAllowance(newPerDay = 20, horizonDays = 7))
    assertEquals(0, newCardAllowance(newPerDay = 0, horizonDays = 7))
    assertEquals(0, newCardAllowance(newPerDay = -5, horizonDays = 7))
  }

  @Test
  fun `bitmap rendering keeps today and the next day only`() {
    val batch =
      WearosDueBatch(
        deckId = 1,
        deckName = "deck",
        cards =
          listOf(
            card(1, labels = listOf("1m", "10m", "1d", "4d")),
            card(2, dueAtSecs = 600),
            card(3, dueDay = 100),
            card(4, dueDay = 101),
            card(5, dueDay = 102),
          ),
      )
    val kept = bitmapHorizonBatch(batch, todayIndex = 100)
    assertEquals(listOf(1L, 2L, 3L, 4L), kept.cards.map { it.id })
  }

  @Test
  fun `bitmap rendering takes only one day of pooled new cards`() {
    val config = parseDeckScheduleConfig("""{"new":{"perDay":2}}""")
    val batch =
      WearosDueBatch(
        deckId = 1,
        deckName = "deck",
        cards = (1L..5L).map { card(it) },
        scheduleConfig = config,
      )
    val kept = bitmapHorizonBatch(batch, todayIndex = 100)
    assertEquals(listOf(1L, 2L), kept.cards.map { it.id })
  }
}
