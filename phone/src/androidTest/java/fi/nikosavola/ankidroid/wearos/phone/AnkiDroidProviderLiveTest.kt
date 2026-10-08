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
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.formatIntervalLabel
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
      assertEquals("the fuzz seed is the card id plus its reps", true, state.fuzzSeed?.let { it >= card.id })
    }
  }

  /**
   * Logs the day spread of the multi-day pool for the first deck. No assertion beyond "no exception,
   * no duplicate ids": a real collection's schedule is whatever the user's is, so there is no
   * expected per-day count to pin here.
   */
  @Test
  fun poolCarriesDueDaysForUpcomingCards() {
    val deckId = assumeAnkiDroid()
    val pool = bridge.fetchDuePool(deckId = DeckId(deckId), todayLimit = 20)

    val ids = pool.cards.map { it.id }
    assertEquals("pool must not repeat card ids", ids.size, ids.toSet().size)
    val countsByDay = pool.cards.groupingBy { it.dueDay }.eachCount()
    val queued = pool.cards.count { it.queuedDay != null }
    Log.i(
      TAG,
      "pool deck $deckId: ${pool.cards.size} cards, $queued queued, queueDay=${pool.queueDay}, " +
        "dueDay counts=$countsByDay",
    )
    assertEquals("AnkiDroid's deck counts vouch for its whole queue", bridge.todayDayIndex(), pool.queueDay)
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
        assertLabelMatches("fresh card", index, shipped, recomputed[index])
      }
      // With no cards due on later days the load balancer has nothing to weigh and Anki falls back
      // to plain fuzz, so the labels should then agree exactly (seed = id + reps).
      Log.i(
        TAG,
        "fresh card ${card.id} seed=${state.fuzzSeed}: AnkiDroid ${card.nextReviewTimes}, " +
          "ours ${recomputed.map { formatIntervalLabel(it) }}",
      )
      // The labels themselves are the evidence: [1m, 6m, 10m, 3d] on a stock deck is Anki's
      // first-step average for Hard and the FSRS/SM-2 graduation for Easy.
      Log.i(TAG, "shadow-agreed on ${card.nextReviewTimes} for a fresh card")
    } finally {
      context.contentResolver.delete(noteUri, null, null)
    }
  }

  /**
   * Out-of-order write-back against the real provider: answers for cards that are not the queue head,
   * plus a learning chain on one card, must all apply. Uses its own deck so the deck toggle always has
   * a second deck to flip to. The notes are deleted afterwards; the provider can't delete decks, so
   * the empty deck is reused on the next run.
   */
  @Test
  fun writeBackAppliesAnswersOutOfQueueOrder() {
    assumeAnkiDroid()
    val resolver = context.contentResolver
    val deckId = liveDeckId(WRITE_BACK_DECK)
    assumeTrue("AnkiDroid refused the test deck", deckId != null)
    val notes = List(3) { createTestNote(deckId ?: return) }
    assumeTrue("AnkiDroid refused a test note", notes.all { it != null })
    val (a, b, c) = notes.map { it?.second ?: error("checked above") }

    fun reps(noteId: Long): Long =
      resolver
        .query(
          android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes/$noteId/cards/0"),
          arrayOf("reps"),
          null,
          null,
          null,
        )
        ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L

    try {
      val before = listOf(a, b, c).associateWith { reps(it) }
      val stamp = System.currentTimeMillis()
      // c is graded first and twice (a learning chain), a and b in reverse of the queue order.
      val answers =
        listOf(c, b, c, a).mapIndexed { i, noteId ->
          WearosAnswer(
            id = "live-$stamp-$i",
            noteId = noteId,
            ord = 0,
            ease = 3,
            timeTakenMs = 1_000L,
            deckId = deckId ?: 0L,
          )
        }

      val outcome = bridge.writeAnswersBack(answers)

      assertEquals("every answer settles", answers.map { it.id }.toSet(), outcome.settled.toSet())
      assertTrue("nothing is left unplaced", outcome.unplaced.isEmpty())
      assertEquals("the chain applied twice", (before[c] ?: 0) + 2, reps(c))
      assertEquals((before[b] ?: 0) + 1, reps(b))
      assertEquals((before[a] ?: 0) + 1, reps(a))
    } finally {
      notes.forEach { note -> note?.let { resolver.delete(it.first, null, null) } }
    }
  }

  /** The id of the deck named [name], created on first use (the provider can't delete decks). */
  private fun liveDeckId(name: String): Long? =
    bridge.fetchAllDecks().firstOrNull { it.second == name }?.first?.value
      ?: context.contentResolver
        .insert(
          android.net.Uri.parse("content://com.ichi2.anki.flashcards/decks"),
          android.content.ContentValues().apply { put("deck_name", name) },
        )
        ?.lastPathSegment
        ?.toLongOrNull()

  /**
   * A card graduated into the review queue must reach the pool from the cards search with its
   * due day, text and scheduler state, not as a stateless placeholder, and a subdeck's cards come
   * with the parent deck as they do in today's scheduler batch.
   */
  @Test
  fun poolShipsGraduatedCardsWithDueDayAndState() {
    assumeAnkiDroid()
    val deckId = liveDeckId(WRITE_BACK_DECK)
    val subDeckId = liveDeckId("$WRITE_BACK_DECK::sub")
    assumeTrue("AnkiDroid refused the test decks", deckId != null && subDeckId != null)
    val note = createTestNote(deckId ?: return)
    val subNote = createTestNote(subDeckId ?: return)
    assumeTrue("AnkiDroid refused a test note", note != null && subNote != null)
    val noteId = note?.second ?: return
    val subNoteId = subNote?.second ?: return
    // The notes insert ignores its deckId parameter, so place the cards explicitly.
    moveCardToDeck(noteId, deckId)
    moveCardToDeck(subNoteId, subDeckId)
    try {
      val stamp = System.currentTimeMillis()
      // Easy on a new card graduates it straight to the review queue, days out.
      val outcome =
        bridge.writeAnswersBack(
          listOf(noteId to deckId, subNoteId to subDeckId).mapIndexed { i, (nid, did) ->
            WearosAnswer(
              id = "live-pool-$stamp-$i",
              noteId = nid,
              ord = 0,
              ease = 4,
              timeTakenMs = 1_000L,
              deckId = did,
            )
          }
        )
      assertTrue("both test cards graduate", outcome.unplaced.isEmpty())

      val today = bridge.todayDayIndex()
      val pool = bridge.fetchDuePool(DeckId(deckId), todayLimit = 50)
      assertEquals("no duplicate ids", pool.cards.size, pool.cards.map { it.id }.toSet().size)
      val card = pool.cards.firstOrNull { it.noteId == noteId }
      assertNotNull("the graduated card is in the pool", card)
      card!!
      val dueDay = card.dueDay
      assertNotNull("a future card carries its due day", dueDay)
      // Answered today, so AnkiDroid's due day is today plus the interval it just set.
      assertEquals(
        "due day matches the card's interval",
        card.state?.intervalDays?.toLong(),
        dueDay!! - today,
      )
      assertEquals(0, card.ord)
      assertTrue("real card text, not a placeholder", card.front.contains("ANKIDROID-WEAROS TEST"))
      assertEquals("scheduler state rides along", 2, card.state?.queue)
      assertTrue(
        "a subdeck's cards ride with the parent, like today's batch",
        pool.cards.any { it.noteId == subNoteId },
      )
    } finally {
      listOfNotNull(note, subNote).forEach { context.contentResolver.delete(it.first, null, null) }
    }
  }

  private fun moveCardToDeck(noteId: Long, deckId: Long) {
    context.contentResolver.update(
      android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes/$noteId/cards/0"),
      android.content.ContentValues().apply { put("deck_id", deckId) },
      null,
      null,
    )
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
        put("tags", TEST_TAG)
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

  /** Assert AnkiDroid's displayed label is consistent with our computed interval. */
  private fun assertLabelMatches(stage: String, button: Int, shipped: String, oursSecs: Double) {
    val shippedSecs = parseLabelToSeconds(shipped) ?: return
    val allowed = allowedRange(oursSecs, shipped)
    assertTrue(
      "$stage button $button: AnkiDroid says '$shipped' (${shippedSecs.toLong()}s) but we computed " +
        "${oursSecs.toLong()}s; allowed ${allowed.start.toLong()}..${allowed.endInclusive.toLong()}s",
      shippedSecs in allowed,
    )
  }

  companion object {
    private const val TAG = "AnkiDroidLiveTest"

    /** Tag on the fixture notes this suite creates, so cleanup never touches a real card. */
    private const val TEST_TAG = "wearos-live-test"
    private const val WRITE_BACK_DECK = "zz WearOS live write-back"
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
   * (`test_relearning_graduation_returns_to_the_interval_the_lapse_left`,
   * `test_sm2_late_review_grows_faster_and_stays_ordered`).
   *
   * The tolerance is for Anki's load balancer, which can move a fuzzed interval to a less busy day
   * the watch can't know about. Everything else - the learning ladder, graduation, Hard's average,
   * and the fuzz itself - matches exactly.
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
          Log.i(
            TAG,
            "$stage button $button: AnkiDroid '$shipped' vs ours ${recomputed[button].toLong()}s",
          )
          assertLabelMatches(stage, button, shipped, recomputed[button])
        }
      }
    } finally {
      context.contentResolver.delete(noteUri, null, null)
    }
  }

  /**
   * Compare previews for a card in the REVIEW queue, and then for the same card after a lapse.
   *
   * A graduated card is scheduled for tomorrow, so it only becomes due - and only then exposes labels
   * at all - on a later day. This test therefore works across two runs, a day apart:
   *
   *   1. First run (no due fixture): it creates a card tagged [TEST_TAG], graduates it, and leaves it.
   *   2. Advance the emulator's clock a day so that card is due:
   *        adb root
   *        adb shell date $(date -v+1d "+%m%d%H%M%Y.%S")
   *        adb shell am force-stop com.ichi2.anki
   *   3. Second run: it finds the due fixture, compares every button in the review state, fails it,
   *      compares the relearning state, and deletes the note.
   *
   * It skips when AnkiDroid is absent, and in a normal run leaves nothing behind beyond the fixture
   * it seeded for the next one.
   */
  @Test
  fun previewsFollowTheReviewStateAfterADayBoundary() {
    val deckId = assumeAnkiDroid()
    val due = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 50)
    val config = due.scheduleConfig
    val fixture = due.cards.firstOrNull { it.state?.queue == 2 && isTestNote(it.noteId) }

    if (fixture == null) {
      // Seed the fixture: a card that will be due once the clock moves on.
      val note = createTestNote(deckId)
      assumeTrue("AnkiDroid provider refused the test note", note != null)
      val (_, noteId) = note!!
      // Skip rather than fail if the deck has other due cards in front of the fixture.
      answerFirstCard(noteId, ease = 3, assumeSettle = true)
      answerFirstCard(noteId, ease = 3, assumeSettle = true)
      Log.i(TAG, "no due review fixture; graduated one for the next run (advance the clock a day)")
      return
    }

    val reviewState = fixture.state ?: error("review fixture carries no state")
    assertEquals("fixture must be a review card", 2, reviewState.cardType)
    Log.i(
      TAG,
      "review state: queue=${reviewState.queue} interval=${reviewState.intervalDays} " +
        "ease=${reviewState.easeFactor} elapsed=${reviewState.elapsedDays} " +
        "stability=${reviewState.stability} difficulty=${reviewState.difficulty}",
    )
    val reviewed = previewIntervalSecs(config!!, reviewState, System.currentTimeMillis())
    // Report every button before failing, so one mismatch doesn't hide the rest.
    fixture.nextReviewTimes.forEachIndexed { button, shipped ->
      Log.i(
        TAG,
        "review button $button: AnkiDroid '$shipped' vs ours " +
          "${examined(reviewed[button], shipped)}",
      )
    }
    fixture.nextReviewTimes.forEachIndexed { button, shipped ->
      assertLabelMatches("review", button, shipped, reviewed[button])
    }

    // Everything from here on is the fixture's last act, so remove it however this ends.
    try {
      // Failing it should show the relearning steps and put the card back into today's queue. Anki
      // keeps a lapsed card in the *learning* queue with type 3 (relearning), not queue 3 - queue 3
      // is day-learning - so the type is what tells the two ladders apart.
      answerFirstCard(fixture.noteId, ease = 1)
      val lapsed = bridge.fetchDueBatch(deckId = DeckId(deckId), limit = 50)
      val lapsedCard = lapsed.cards.firstOrNull { it.noteId == fixture.noteId }
      assumeTrue("lapsed card left today's queue", lapsedCard != null)
      val lapsedState = lapsedCard!!.state ?: error("lapsed card carries no state")
      assertEquals("a lapsed card is relearning", 3, lapsedState.cardType)
      Log.i(
        TAG,
        "lapsed state: queue=${lapsedState.queue} type=${lapsedState.cardType} " +
          "interval=${lapsedState.intervalDays} ease=${lapsedState.easeFactor}",
      )
      val relearned = previewIntervalSecs(config, lapsedState, System.currentTimeMillis())
      lapsedCard.nextReviewTimes.forEachIndexed { button, shipped ->
        Log.i(
          TAG,
          "lapsed button $button: AnkiDroid '$shipped' vs ours ${examined(relearned[button], shipped)}",
        )
      }
      lapsedCard.nextReviewTimes.forEachIndexed { button, shipped ->
        assertLabelMatches("lapsed", button, shipped, relearned[button])
      }
    } finally {
      deleteTestNote(fixture.noteId)
    }
  }

  /** A comparison rendered for logging, with the allowed window, so a mismatch is diagnosable. */
  private fun examined(oursSecs: Double, shipped: String): String {
    val shippedSecs = parseLabelToSeconds(shipped) ?: return "unparseable label"
    val allowed = allowedRange(oursSecs, shipped)
    return "${oursSecs.toLong()}s (theirs ${shippedSecs.toLong()}s, allowed " +
      "${allowed.start.toLong()}..${allowed.endInclusive.toLong()}s)"
  }

  /** Whether this note is one of ours, so a real collection is never graded by the fixture path. */
  private fun isTestNote(noteId: Long): Boolean {
    val uri = android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes/$noteId")
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return false
      val tagsIdx = cursor.getColumnIndex("tags")
      tagsIdx != -1 && cursor.getString(tagsIdx)?.contains(TEST_TAG) == true
    } ?: false
  }

  /** Remove one of our fixture notes by id. */
  private fun deleteTestNote(noteId: Long) {
    context.contentResolver.delete(
      android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes/$noteId"),
      null,
      null,
    )
  }

  /**
   * Answer the test note's card through the app's own write-back path (prime-then-answer).
   * [AnkiDroidBridge.writeAnswersBack] returns the ids it settled in `settled`, so a missing id means
   * the grade never reached the collection.
   */
  private fun answerFirstCard(noteId: Long, ease: Int, assumeSettle: Boolean = false) {
    val answer =
      uniffi.wearos_core.WearosAnswer(
        id = "live-test-$noteId-$ease-${System.currentTimeMillis()}",
        noteId = noteId,
        ord = 0,
        ease = ease,
        timeTakenMs = 2_000,
        deckId = 1,
      )
    val settled = bridge.writeAnswersBack(listOf(answer)).settled
    if (assumeSettle) {
      // Anki's write-back only answers the card the scheduler currently presents, so another due card
      // in the deck can legitimately block ours. Callers that can wait for a quiet collection ask for
      // this instead of failing.
      assumeTrue(
        "another card is ahead of the fixture in this deck; run against a quiet collection",
        settled.contains(answer.id),
      )
      return
    }
    assertTrue(
      "write-back did not apply the test answer (settled: $settled)",
      settled.contains(answer.id),
    )
  }
}
