package com.ichi2.anki.wearos.phone

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.wearos_core.previewIntervalSecs

/**
 * Instrumented checks against a real AnkiDroid install (emulator or device), covering the parts of
 * the provider contract the host-JVM tests can only fake: the deck `OPTIONS` payload our offline
 * previews depend on, and the per-card scheduler state read alongside the due batch.
 *
 * Requires the AnkiDroid app installed and `com.ichi2.anki.permission.READ_WRITE_DATABASE` granted
 * to this app; every test assumes that and skips otherwise, so the suite is safe to run anywhere.
 * These are the checks that cannot run in CI (no GMS/AnkiDroid there) — see issue #23.
 *
 * Emulator recipe (both steps are needed, and both are easy to get wrong):
 *  1. Installing the test APK reinstalls the app under test, which drops the runtime grant. Grant it
 *     after every install, then run the tests without another install:
 *     `adb shell pm grant com.ichi2.anki.wearos com.ichi2.anki.permission.READ_WRITE_DATABASE`
 *     `adb shell am instrument -w -e class <this class> com.ichi2.anki.wearos.test/androidx.test.runner.AndroidJUnitRunner`
 *  2. AnkiDroid must be able to create its collection directory. On a fresh emulator it fails with
 *     `StorageAccessException: Failed to create AnkiDroid directory /storage/emulated/0/AnkiDroid`
 *     and then answers EVERY provider query with a null cursor (not an exception), so grant storage
 *     and relaunch it first:
 *     `adb shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow`
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidProviderLiveTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val bridge = AnkiDroidBridge(context)

  private fun assumeAnkiDroid(): Long {
    assumeTrue("AnkiDroid not installed/reachable", bridge.isAnkiDroidAvailable())
    val deck = bridge.fetchAllDecks().firstOrNull()
    assumeTrue("AnkiDroid has no decks", deck != null)
    return deck!!.first.value
  }

  @Test
  fun deckScheduleConfigParsesFromRealAnkiDroid() {
    val deckId = assumeAnkiDroid()
    val batch = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 20)
    val config = batch.scheduleConfig

    assertNotNull("deck OPTIONS must parse into a schedule config", config)
    config!!
    assertTrue("learning steps must be non-empty", config.learnSteps.isNotEmpty())
    assertTrue("learning steps must be positive", config.learnSteps.all { it > 0.0 })
    assertTrue("relearning steps must be positive", config.relearnSteps.all { it > 0.0 })
    assertTrue("graduating interval must be at least a day", config.graduatingIntervalDays >= 1)
    assertTrue("max interval must be positive", config.maxIntervalDays >= 1)
    if (config.fsrsEnabled()) {
      assertTrue(
        "desired retention must be a fraction, not a percentage",
        config.desiredRetention in 0.5..1.0,
      )
      assertTrue("FSRS weights look truncated: ${config.fsrsWeights.size}", config.fsrsWeights.size >= 17)
    }

    // Values are logged rather than asserted exactly: a collection may legitimately use custom
    // steps, but AnkiDroid's stock config is 1m/10m learning steps and 1d/4d graduations.
    Log.i(
      TAG,
      "AnkiDroid deck $deckId: steps=${config.learnSteps} relearn=${config.relearnSteps} " +
        "ints=${config.graduatingIntervalDays}/${config.easyIntervalDays} fsrs=${config.fsrsEnabled()}",
    )
  }

  @Test
  fun cardSchedulerStateIsReadAlongsideTheBatch() {
    val deckId = assumeAnkiDroid()
    val batch = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 20)
    assumeTrue("deck has no due cards to inspect", batch.cards.isNotEmpty())

    batch.cards.forEach { card ->
      val state = card.state
      assertNotNull("card ${card.id} must carry scheduler state", state)
      state!!
      assertTrue("queue must be a known Anki queue value: ${state.queue}", state.queue in -3..4)
      assertTrue("interval must not be negative", state.intervalDays >= 0.0)
      assertTrue("stability must not be negative", state.stability >= 0.0)
      assertTrue("elapsed days must not be negative", state.elapsedDays >= 0.0)
    }
  }

  /**
   * Shadow validation (issue #24): for a card that has not been graded yet, the labels AnkiDroid
   * shipped and the labels we recompute from the deck config describe the same state, so they should
   * agree. A mismatch here means our offline previews disagree with AnkiDroid's own scheduler.
   *
   * Tolerance: AnkiDroid's labels carry scheduling fuzz and round to whole units in their own
   * formatting, while ours are unfuzzed, so agreement is checked within a small relative margin
   * rather than exactly.
   */
  @Test
  fun shippedLabelsAgreeWithRecomputedPreviews() {
    val deckId = assumeAnkiDroid()
    val batch = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 20)
    val config = batch.scheduleConfig
    assumeTrue("no cards to compare", batch.cards.isNotEmpty())
    assumeTrue("no schedule config to recompute from", config != null)
    config!!

    var compared = 0
    batch.cards.forEach { card ->
      val state = card.state
      if (state == null || card.nextReviewTimes.isEmpty()) return@forEach
      val recomputed = previewIntervalSecs(config, state, System.currentTimeMillis())
      card.nextReviewTimes.forEachIndexed { index, shipped ->
        if (index >= recomputed.size) return@forEachIndexed
        val shippedSecs = parseLabelToSeconds(shipped) ?: return@forEachIndexed
        val ours = recomputed[index]
        compared++
        val ratio = if (shippedSecs > 0) ours / shippedSecs else 1.0
        assertTrue(
          "card ${card.id} button $index: AnkiDroid says '$shipped' (${shippedSecs}s) " +
            "but we computed ${ours}s",
          ratio in 0.5..2.0,
        )
      }
    }
    Log.i(TAG, "shadow-compared $compared button labels against AnkiDroid")
  }

  private fun parseLabelToSeconds(label: String): Double? {
    val unit = label.takeLastWhile { it.isLetter() }
    val value = label.dropLast(unit.length).toDoubleOrNull() ?: return null
    return when (unit) {
      "s" -> value
      "m" -> value * 60
      "h" -> value * 3600
      "d" -> value * 86_400
      "mo" -> value * 30 * 86_400
      "y" -> value * 365 * 86_400
      else -> null
    }
  }

  companion object {
    private const val TAG = "AnkiDroidLiveTest"
  }
}
