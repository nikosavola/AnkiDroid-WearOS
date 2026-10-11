package com.ichi2.anki.wearos.phone

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.wearos_core.formatIntervalLabel
import uniffi.wearos_core.previewIntervalSecs

/**
 * Compares AnkiDroid's own interval labels with our offline previews for every due card in every
 * deck of the installed collection. It only reads, so unlike [AnkiDroidProviderLiveTest] it is safe
 * against a real collection, which is where FSRS decks with real weights and odd card states live.
 * It also fetches each top-level deck's seven-day pool, whose searches real deck names stress.
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
    var exact = 0
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
          if (formatIntervalLabel(ours[button]) == shipped) exact++
          if (shippedSecs !in allowed) {
            mismatches +=
              "deck '$name' card ${card.id} (queue ${state.queue}, type ${state.cardType}, " +
                "fsrs ${config.fsrsEnabled()}, ivl ${state.intervalDays}, elapsed " +
                "${state.elapsedDays}, S ${state.stability}, D ${state.difficulty}, step " +
                "${state.learningStep}, retention ${state.fsrsRetention}, decay " +
                "${state.fsrsDecay}) button $button: AnkiDroid '$shipped', ours " +
                "${ours[button].toLong()}s, allowed ${allowed.start.toLong()}.." +
                "${allowed.endInclusive.toLong()}s"
          }
        }
      }
    }

    mismatches.forEach { Log.w(TAG, it) }
    Log.i(
      TAG,
      "compared $compared labels: $exact exact, ${mismatches.size} outside Anki's fuzz window",
    )
    assertTrue(
      "${mismatches.size} of $compared labels disagree; first: ${mismatches.firstOrNull()}",
      mismatches.isEmpty(),
    )
  }

  @Test
  fun poolFetchCoversEveryTopLevelDeckWithoutDuplicates() {
    assumeTrue("AnkiDroid not installed/reachable", bridge.isAnkiDroidAvailable())
    val today = bridge.todayDayIndex()
    val problems = mutableListOf<String>()

    for ((deckId, name) in bridge.fetchAllDecks().filter { !it.second.contains("::") }) {
      val started = System.currentTimeMillis()
      val batch = bridge.fetchDuePool(deckId = deckId, todayLimit = POOL_TODAY_LIMIT)
      val tookMs = System.currentTimeMillis() - started
      val pool = batch.cards
      val dupes = pool.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys
      if (dupes.isNotEmpty()) problems += "deck '$name' ships duplicate cards $dupes"
      pool
        .filter { card -> card.dueDay?.let { it < today || it > today + POOL_HORIZON_DAYS } == true }
        .forEach { problems += "deck '$name' card ${it.id} due day ${it.dueDay} outside the horizon" }
      pool
        .filter { it.state?.queue == 1 && it.nextReviewTimes.isEmpty() && it.dueAtSecs == null }
        .forEach { problems += "deck '$name' learning card ${it.id} has no due time" }
      val byDay = pool.mapNotNull { it.dueDay }.groupingBy { it - today }.eachCount().toSortedMap()
      Log.i(
        TAG,
        "pool '$name' in ${tookMs}ms: ${pool.size} cards, queued ${pool.count { it.queuedDay == today }}" +
          " (day complete: ${batch.queueDay == today}), new/day ${batch.scheduleConfig?.newPerDay}, " +
          "today ${pool.count { it.nextReviewTimes.isNotEmpty() }}, " +
          "learning ${pool.count { it.dueAtSecs != null }}, new " +
          "${pool.count { it.state?.queue == 0 && it.nextReviewTimes.isEmpty() }}, by day $byDay",
      )
    }

    problems.forEach { Log.w(TAG, it) }
    assertTrue("${problems.size} pool problems; first: ${problems.firstOrNull()}", problems.isEmpty())
  }

  private companion object {
    const val TAG = "PreviewAudit"
    const val CARDS_PER_DECK = 200
    // What DeckSync asks for, so the pool is the one the watch would get.
    const val POOL_TODAY_LIMIT = 20
  }
}
