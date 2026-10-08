package com.ichi2.anki.wearos.wear

import uniffi.wearos_core.PoolDeckSummary

/** One deck row on the large widget. */
internal data class WidgetDeck(val id: Long, val name: String, val dueNow: Int)

/**
 * What the due-cards widget shows, reduced from the pool summaries so the layout and its previews
 * never touch the Rust core.
 */
internal data class DueWidgetSnapshot(
  val hasDecks: Boolean,
  val dueNow: Int,
  val learningLater: Int,
  /** Earliest learning card due later today, epoch ms, if any. */
  val nextLearningDueMs: Long?,
  /** Decks with cards due now, most due first. */
  val topDecks: List<WidgetDeck>,
) {
  companion object {
    val NoDecks = DueWidgetSnapshot(false, 0, 0, null, emptyList())
  }
}

internal const val WIDGET_MAX_DECKS = 3

internal fun dueWidgetSnapshot(summaries: List<PoolDeckSummary>): DueWidgetSnapshot =
  DueWidgetSnapshot(
    hasDecks = summaries.isNotEmpty(),
    dueNow = summaries.sumOf { it.dueNow },
    learningLater = summaries.sumOf { it.learningLater },
    nextLearningDueMs = summaries.mapNotNull { it.nextLearningDueSecs }.minOrNull()?.times(1000),
    topDecks =
      summaries
        .filter { it.dueNow > 0 }
        .sortedWith(compareByDescending<PoolDeckSummary> { it.dueNow }.thenBy { it.name.lowercase() })
        .take(WIDGET_MAX_DECKS)
        .map { WidgetDeck(it.deckId, deckLeafName(it.name), it.dueNow) },
  )

// "Japanese::Core 2k" is too long for a widget row; the leaf is what people recognise.
internal fun deckLeafName(name: String): String = name.substringAfterLast("::").ifBlank { name }

internal fun dueLabel(count: Int): String = if (count == 1) "card due" else "cards due"

/** Headline for the no-cards-due state. */
internal fun emptyTitle(snapshot: DueWidgetSnapshot): String =
  if (snapshot.hasDecks) "All caught up" else "No decks yet"

/**
 * Secondary line: learning cards still to come today, or a nudge to sync when the watch has nothing.
 * Uses an absolute time since the widget has no freshness hook to keep a relative one current.
 */
internal fun laterLine(snapshot: DueWidgetSnapshot, formatTime: (Long) -> String): String? =
  when {
    !snapshot.hasDecks -> "Sync from phone"
    snapshot.learningLater > 0 && snapshot.nextLearningDueMs != null ->
      "${snapshot.learningLater} more at ${formatTime(snapshot.nextLearningDueMs)}"
    snapshot.learningLater > 0 -> "${snapshot.learningLater} more later today"
    else -> null
  }
