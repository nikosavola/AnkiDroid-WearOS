package com.ichi2.anki.wearos.phone

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.wearos_core.previewIntervalSecs

/**
 * Compares AnkiDroid's own interval labels with our offline previews for every due card in every
 * deck of the installed collection. It only reads, so unlike [AnkiDroidProviderLiveTest] it is safe
 * against a real collection, which is where FSRS decks with real weights and odd card states live.
 * Fetching a deck's schedule makes AnkiDroid select it briefly and then reselect the previous deck.
 *
 * `adb shell am instrument -w -e class com.ichi2.anki.wearos.phone.RealDeckPreviewAuditTest \
 *   com.ichi2.anki.wearos.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class RealDeckPreviewAuditTest {
  private val bridge = AnkiDroidBridge(ApplicationProvider.getApplicationContext<Context>())

  @Test
  fun previewsAgreeWithAnkiDroidForEveryDueCard() {
    assumeTrue("AnkiDroid not installed/reachable", bridge.isAnkiDroidAvailable())
    val now = System.currentTimeMillis()
    var compared = 0
    val mismatches = mutableListOf<String>()

    for ((deckId, name) in bridge.fetchAllDecks()) {
      val batch = bridge.fetchDueBatch(deckId = deckId, limit = CARDS_PER_DECK)
      val config = batch.scheduleConfig ?: continue
      for (card in batch.cards) {
        val state = card.state ?: continue
        val ours = previewIntervalSecs(config, state, now)
        card.nextReviewTimes.forEachIndexed { button, shipped ->
          val shippedSecs = parseLabelToSeconds(shipped) ?: return@forEachIndexed
          val allowed = allowedRange(ours[button], shipped)
          compared++
          if (shippedSecs !in allowed) {
            mismatches +=
              "deck '$name' card ${card.id} (queue ${state.queue}, type ${state.cardType}, " +
                "fsrs ${config.fsrsEnabled()}, ivl ${state.intervalDays}, elapsed " +
                "${state.elapsedDays}, S ${state.stability}, D ${state.difficulty}, step " +
                "${state.learningStep}) button $button: AnkiDroid '$shipped', ours " +
                "${ours[button].toLong()}s, allowed ${allowed.start.toLong()}.." +
                "${allowed.endInclusive.toLong()}s"
          }
        }
      }
    }

    mismatches.forEach { Log.w(TAG, it) }
    Log.i(TAG, "compared $compared labels, ${mismatches.size} outside Anki's fuzz window")
    assertTrue(
      "${mismatches.size} of $compared labels disagree; first: ${mismatches.firstOrNull()}",
      mismatches.isEmpty(),
    )
  }

  private companion object {
    const val TAG = "PreviewAudit"
    const val CARDS_PER_DECK = 200
  }
}
