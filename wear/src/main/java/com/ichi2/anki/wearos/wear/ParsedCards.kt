package com.ichi2.anki.wearos.wear

import android.util.LruCache
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.answerHtml
import uniffi.wearos_core.cleanHtml
import uniffi.wearos_core.htmlForDisplay
import uniffi.wearos_core.typeAnswerExpected

/**
 * The per-card strings the review screen renders: the Rust HTML passes (answer split, display HTML,
 * plain text) for one card, parsed once. Upcoming cards are parsed in the background so the frame
 * that shows a card only swaps already-built strings; a cache miss parses inline, the way the
 * screen did before. The Rust functions are pure, so a background thread is safe.
 */
internal object ParsedCards {
  // The current card plus the next couple, mirroring the bitmap cache.
  private const val MAX_ENTRIES = 6

  private val cache = LruCache<Long, ParsedCard>(MAX_ENTRIES)

  internal data class ParsedCard(
    val answerBack: String,
    val typeExpected: String?,
    val displayFront: String,
    val displayBack: String,
    val cleanFront: String,
    val cleanBack: String,
  )

  /** The card's parsed strings, parsing them now on a miss (the first card of a session). */
  fun parsed(card: WearosCard): ParsedCard {
    val hit = cache.get(card.id)
    if (hit != null) return hit
    val fresh = parse(card)
    cache.put(card.id, fresh)
    return fresh
  }

  /** Parse the cards the queue will show next. */
  fun prefetch(cards: List<WearosCard>) {
    for (card in cards) {
      if (cache.get(card.id) == null) cache.put(card.id, parse(card))
    }
  }

  private fun parse(card: WearosCard): ParsedCard {
    val answerBack = answerHtml(card.back)
    return ParsedCard(
      answerBack = answerBack,
      typeExpected = typeAnswerExpected(card.back),
      displayFront = htmlForDisplay(card.front),
      displayBack = htmlForDisplay(answerBack),
      cleanFront = cleanHtml(card.front),
      cleanBack = cleanHtml(answerBack),
    )
  }
}
