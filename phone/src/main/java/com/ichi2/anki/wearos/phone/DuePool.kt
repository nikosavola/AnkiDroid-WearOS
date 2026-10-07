package com.ichi2.anki.wearos.phone

import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch

/** How many days past today the phone ships cards, so the watch can review offline for a week. */
internal const val POOL_HORIZON_DAYS = 7

/** Upper bound on the whole pool, so a dense collection can't overflow the batch Asset. */
internal const val MAX_POOL_CARDS = 400

/**
 * How many new cards the pool may carry: the deck's daily new limit across the whole horizon. Zero
 * when the deck exposed no config, since a missing limit must not become an unbounded allowance.
 */
internal fun newCardAllowance(newPerDay: Int, horizonDays: Int): Int =
  newPerDay.coerceAtLeast(0) * horizonDays

/**
 * Order the pool's groups the way the watch consumes them, then cap it at [limit]. Today's queue
 * first (its labels are AnkiDroid's own and the user studies it now), then intraday learning, then
 * upcoming days nearest first, then new cards (the watch rations those by its own daily limit). Card
 * ids are deduped defensively even though the fetch already skips ones it collected.
 */
internal fun orderedPool(
  today: List<WearosCard>,
  learning: List<WearosCard>,
  upcoming: List<WearosCard>,
  newCards: List<WearosCard>,
  limit: Int = MAX_POOL_CARDS,
): List<WearosCard> {
  val seen = HashSet<Long>(today.size + learning.size + upcoming.size + newCards.size)
  return buildList {
      addAll(today)
      addAll(learning.sortedBy { it.dueAtSecs ?: Long.MAX_VALUE })
      addAll(upcoming.sortedBy { it.dueDay ?: Long.MAX_VALUE })
      addAll(newCards)
    }
    .filter { seen.add(it.id) }
    .take(limit)
}

/**
 * A copy of [batch] holding only the cards worth rendering rich bitmaps for: today's queue, learning
 * cards, reviews due by tomorrow and one day's worth of pooled new cards. Rendering is the expensive
 * leg over Bluetooth, so a week of bitmaps up front would flood the Data Layer for cards the watch
 * won't reach for days.
 */
internal fun bitmapHorizonBatch(batch: WearosDueBatch, todayIndex: Long): WearosDueBatch {
  var newBudget = batch.scheduleConfig?.newPerDay ?: 0
  val cards =
    batch.cards.filter { card ->
      val dueDay = card.dueDay
      when {
        card.dueAtSecs != null -> true
        dueDay != null -> dueDay <= todayIndex + 1
        // Today's scheduler batch carries AnkiDroid's labels; pooled new cards have none.
        card.nextReviewTimes.isNotEmpty() -> true
        else -> newBudget-- > 0
      }
    }
  return if (cards.size == batch.cards.size) batch else batch.copy(cards = cards)
}
