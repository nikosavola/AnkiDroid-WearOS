package fi.nikosavola.ankidroid.wearos.phone

import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch

/** How many days past today the phone ships cards, so the watch can review offline for a week. */
internal const val POOL_HORIZON_DAYS = 7

/** Upper bound on the whole pool, so a dense collection can't overflow the batch Asset. */
internal const val MAX_POOL_CARDS = 400

/**
 * Most cards fetched from AnkiDroid's own queue for today. Each one costs a rendered card query
 * while the watch waits on its download timeout, so a huge backlog is shipped in part.
 */
internal const val MAX_TODAY_QUEUE = 200

/** A deck's `deck_count` value (`[learn, review, new]`), or null if it isn't three integers. */
internal fun parseDueCounts(raw: String?): List<Int>? {
  val nums = Regex("-?\\d+").findAll(raw ?: return null).map { it.value.toInt() }.toList()
  return nums.takeIf { it.size == 3 && it.all { n -> n >= 0 } }
}

/**
 * Whether a schedule fetch of [requested] cards that returned [fetched] holds AnkiDroid's whole
 * queue for today. Needs the deck's [dueToday] counts: an empty result is otherwise
 * indistinguishable from a failed query, and only a complete queue may stand in for the day.
 */
internal fun isCompleteQueue(fetched: Int, requested: Int, dueToday: List<Int>?): Boolean {
  if (dueToday == null) return false
  return fetched >= dueToday.sum() || fetched in 1 until requested
}

/**
 * The deck's real daily new-card limit. The provider only exposes the preset's `new.perDay`, not a
 * deck-level override, but AnkiDroid's count of new cards queued today already reflects it: when
 * more new cards are available than were queued, the limit is what was queued plus what was
 * introduced earlier today. Otherwise the limit isn't binding and can't be read off; keep the
 * preset unless today shows more.
 */
internal fun effectiveNewPerDay(
  presetNewPerDay: Int,
  newQueuedToday: Int?,
  introducedToday: Int?,
  newAvailable: Int,
): Int {
  if (newQueuedToday == null || introducedToday == null) return presetNewPerDay
  val today = newQueuedToday + introducedToday
  return if (newAvailable > newQueuedToday) today else maxOf(presetNewPerDay, today)
}

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
