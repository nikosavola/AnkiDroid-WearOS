package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
 *     `adb shell pm grant fi.nikosavola.ankidroid.wearos com.ichi2.anki.permission.READ_WRITE_DATABASE`
 *     `adb shell am instrument -w -e class <this class> fi.nikosavola.ankidroid.wearos.test/androidx.test.runner.AndroidJUnitRunner`
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
   * Shadow validation (issue #24) on a card this test creates itself.
   *
   * Answering needs a card, and a fresh collection has none, so this inserts a note into the deck,
   * waits for it to appear in the due batch, compares every button label, and deletes the note again
   * (in a `finally`). It temporarily adds a card to the collection under test — on a device holding
   * a personal collection, expect a short-lived test note.
   */
  @Test
  fun previewsAgreeWithAnkiDroidForAFreshlyAddedCard() {
    val deckId = assumeAnkiDroid()
    val note = createTestNote(deckId)
    assumeTrue("AnkiDroid provider refused the test note", note != null)
    val (noteUri, noteId) = note!!

    try {
      val batch = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 50)
      val card =
        batch.cards.firstOrNull { it.noteId == noteId }
          ?: error("the new note's card is missing from the due batch")
      val config = batch.scheduleConfig ?: error("no schedule config for deck $deckId")
      val state = card.state ?: error("card ${card.id} carries no scheduler state")

      val recomputed = previewIntervalSecs(config, state, System.currentTimeMillis())
      assertEquals(
        "AnkiDroid ships one label per button",
        recomputed.size,
        card.nextReviewTimes.size,
        )
      card.nextReviewTimes.forEachIndexed { index, shipped ->
        val shippedSecs = parseLabelToSeconds(shipped) ?: return@forEachIndexed
        val ours = recomputed[index]
        val ratio = if (shippedSecs > 0) ours / shippedSecs else 1.0
        assertTrue(
          "button $index: AnkiDroid says '$shipped' (${shippedSecs}s) but we computed ${ours}s",
          ratio in 0.5..2.0,
        )
      }
      // The labels themselves are the evidence: [1m, 6m, 10m, 3d] on a stock deck is Anki's
      // first-step average for Hard and the FSRS/SM-2 graduation for Easy.
      Log.i(TAG, "shadow-agreed on ${card.nextReviewTimes} for a fresh card")
    } finally {
      context.contentResolver.delete(noteUri, null, null)
    }
  }

  /** Insert a two-field note into [deckId]; returns its URI and id, or null if unsupported. */
  private fun createTestNote(deckId: Long): Pair<android.net.Uri, Long>? {
    val models = android.net.Uri.parse("content://com.ichi2.anki.flashcards/models")
    val modelId =
      context.contentResolver.query(models, null, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndex("_id")
        val fieldsIdx = cursor.getColumnIndex("field_names")
        var found: Long? = null
        while (cursor.moveToNext() && found == null) {
          val fields = if (fieldsIdx != -1) cursor.getString(fieldsIdx) else null
          // AnkiDroid concatenates field names with Anki's unit-separator (\u001f), not commas;
          // two fields keeps the fixture to Front/Back, like a stock Basic note type.
          if (fields != null && fields.split('\u001f', ',').size == 2) {
            found = cursor.getLong(idIdx)
          }
        }
        found
      } ?: return null

    val notes = android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes?deckId=$deckId")
    val values =
      android.content.ContentValues().apply {
        put("mid", modelId)
        put("flds", "ANKIDROID-WEAROS TEST\u001fanswer")
        put("tags", "wearos-live-test")
        put("allow_empty", true)
      }
    val uri =
      try {
        context.contentResolver.insert(notes, values)
      } catch (e: Exception) {
        Log.w(TAG, "test note insert failed", e)
        null
      } ?: return null
    val noteId = uri.lastPathSegment?.toLongOrNull() ?: return null
    return Pair(uri, noteId)
  }

  /**
   * The same comparison for whatever cards the deck already has. Skips on a fresh collection; the
   * test above creates its own card when there is none.
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

  /**
   * Walk one card through a real review session and compare our previews against AnkiDroid's own
   * labels at each state the watch can actually see: new, then learning after a Good. Answering goes
   * through the app's real write-back path (prime-then-answer), so this exercises the same code the
   * watch uses.
   *
   * Only due cards expose `NEXT_REVIEW_TIMES`, so a card that graduates or lapses out of today's
   * queue disappears from the comparison (that is Anki's scheduling working, not a gap in this
   * test). Those states are covered by the unit tests in `core/src/lib.rs` instead
   * (`test_relearning_graduation_uses_the_lapse_reduced_interval`,
   * `test_sm2_late_review_grows_faster_and_stays_ordered`).
   *
   * The tolerance is for Anki's fuzz, which our previews deliberately do not reproduce: AnkiDroid's
   * Easy label on a 4-day interval varies between runs (seen: 3d, 4d, 5d) while we always compute
   * 4d. Everything outside fuzz - the learning ladder, graduation, Hard's average - matches exactly.
   */
  @Test
  fun previewsTrackAnkiDroidThroughAReviewSession() {
    val deckId = assumeAnkiDroid()
    val note = createTestNote(deckId)
    assumeTrue("AnkiDroid provider refused the test note", note != null)
    val (noteUri, noteId) = note!!

    try {
      // A graduated card is scheduled for tomorrow and a lapse needs a due review card to fail, so
      // only these two states are reachable through the due queue.
      val stages = listOf("new", "learning")
      stages.forEachIndexed { index, stage ->
        if (index > 0) answerFirstCard(noteId, ease = 3)

        val batch = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 50)
        val card =
          batch.cards.firstOrNull { it.noteId == noteId }
            ?: error("card for the test note is missing at stage '$stage'")
        val config = batch.scheduleConfig ?: error("no schedule config for deck $deckId")
        val state = card.state ?: error("card ${card.id} carries no scheduler state")
        val recomputed = previewIntervalSecs(config, state, System.currentTimeMillis())

        card.nextReviewTimes.forEachIndexed { button, shipped ->
          if (button >= recomputed.size) return@forEachIndexed
          val shippedSecs = parseLabelToSeconds(shipped) ?: return@forEachIndexed
          val ours = recomputed[button]
          val ratio = if (shippedSecs > 0) ours / shippedSecs else 1.0
          Log.i(
            TAG,
            "$stage button $button: AnkiDroid '$shipped' (${shippedSecs.toLong()}s) vs ours ${ours.toLong()}s",
          )
          assertTrue(
            "$stage button $button: AnkiDroid says '$shipped' (${shippedSecs}s) but we computed ${ours}s",
            ratio in 0.5..2.0,
          )
        }
      }
    } finally {
      context.contentResolver.delete(noteUri, null, null)
    }
  }

  /**
   * Answer the test note's card through the app's own write-back path (prime-then-answer).
   * [AnkiDroidBridge.writeAnswersBack] returns the ids it settled, so a missing id means the grade
   * never reached the collection.
   */
  private fun answerFirstCard(noteId: Long, ease: Int) {
    val answer =
      uniffi.wearos_core.WearosAnswer(
        id = "live-test-$noteId-$ease-${System.currentTimeMillis()}",
        noteId = noteId,
        ord = 0,
        ease = ease,
        timeTakenMs = 2_000,
        deckId = 1,
      )
    val settled = bridge.writeAnswersBack(listOf(answer))
    assertTrue(
      "write-back did not apply the test answer (settled: $settled)",
      settled.contains(answer.id),
    )
  }

}
