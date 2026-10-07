package com.ichi2.anki.wearos.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.deserializeAnswers
import uniffi.wearos_core.serializeAnswers

/**
 * Integration test of the phone write-back path against a REAL ContentResolver routed to a fake
 * AnkiDroid scheduler ContentProvider - the heaviest slice of the sync that can run with no emulator
 * at all (pure JVM via Robolectric, so it runs in any `./gradlew test` container). It exercises the
 * real Rust deserialization + the real [AnkiDroidBridge.writeAnswersBack] loop against genuine
 * ContentResolver.query/update dispatch, so it catches integration wiring the MockK unit tests stub
 * away: the URI/authority, the exact cursor column + ContentValues keys, drain ordering, and - most
 * importantly - the prime-then-answer contract (the fake models the v3 backend quirk where a bare
 * update no-ops until a schedule query has primed the scheduler; the original write-back bug).
 *
 * The watch<->phone Data Layer leg is deliberately out of scope: it needs Google Play Services + two
 * paired devices and cannot run on a headless/Docker emulator, so it can't be covered in CI at all.
 */
@RunWith(RobolectricTestRunner::class)
class WriteBackIntegrationTest {
  private lateinit var context: Context
  private lateinit var provider: FakeAnkiSchedulerProvider

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // Reset the dedupe store so applied-id history from another test can't pre-filter our answers.
    context.getSharedPreferences("writeback_applied", Context.MODE_PRIVATE).edit().clear().commit()
    provider =
      Robolectric.setupContentProvider(
        FakeAnkiSchedulerProvider::class.java,
        "com.ichi2.anki.flashcards",
      )
  }

  private fun answer(id: String, noteId: Long, ord: Int, ease: Int, deckId: Long = 0L) =
    WearosAnswer(id = id, noteId = noteId, ord = ord, ease = ease, timeTakenMs = 3000L, deckId = deckId)

  /** Serialize on the watch side, deserialize on the phone side - the real Bluetooth payload. */
  private fun overTheWire(answers: List<WearosAnswer>): List<WearosAnswer> =
    deserializeAnswers(serializeAnswers(answers))

  /** Run [answers] through the wire and the real write-back against the fake provider. */
  private fun writeBack(answers: List<WearosAnswer>, readOnly: Boolean = false): WriteBackOutcome =
    AnkiDroidBridge(context).writeAnswersBack(overTheWire(answers), readOnly)

  private val scheduleUri: Uri = Uri.parse("content://com.ichi2.anki.flashcards/schedule")

  @Test
  fun drainsWholeBufferInSchedulerOrder_throughRealResolverAndProvider() {
    provider.seed(listOf(111L to 0, 222L to 1))

    val settled = writeBack(listOf(answer("a1", 111L, 0, 3), answer("a2", 222L, 1, 4)))

    assertEquals(listOf("a1", "a2"), settled.settled)
    assertEquals(
      "both cards answered, in scheduler order, with the right ease",
      listOf(Applied(111L, 0, 3), Applied(222L, 1, 4)),
      provider.applied,
    )
    assertTrue("scheduler queue fully drained", provider.isEmpty())
  }

  @Test
  fun primesSchedulerBeforeAnswering_soTheAnswerIsntA_noOp() {
    // The fake mimics the v3 backend: update() before any query() returns 0 rows (see below). So this
    // test only passes if writeAnswersBack primes (queries) before it updates - the exact regression
    // that once left every grade silently unapplied.
    provider.seed(listOf(111L to 0))
    val settled = writeBack(listOf(answer("a1", 111L, 0, 2)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 2)), provider.applied)
  }

  @Test
  fun bareUpdateWithoutPriming_isANoOp_documentingTheContractTheFakeModels() {
    provider.seed(listOf(111L to 0))
    val values =
      ContentValues().apply {
        put("note_id", 111L)
        put("ord", 0)
        put("answer_ease", 3)
        put("time_taken", 3000L)
      }
    val rows = context.contentResolver.update(scheduleUri, values, null, null)
    assertEquals("a bare update with no prior schedule query must no-op", 0, rows)
    assertTrue(provider.applied.isEmpty())
  }

  @Test
  fun stopsWhenSchedulerPresentsAnUnbufferedCard_noMisgrade() {
    // Scheduler head is 999, but we only have a buffered answer for 111. writeAnswersBack must NOT
    // answer 999 (that'd be a mis-grade); it reports 111 unplaced and leaves it buffered.
    provider.seed(listOf(999L to 0))
    val outcome = writeBack(listOf(answer("a1", 111L, 0, 3)))

    assertTrue("unplaceable answer stays buffered", outcome.settled.isEmpty())
    assertEquals("the buffered answer comes back unplaced", listOf("a1"), outcome.unplaced)
    assertTrue("nothing answered", provider.applied.isEmpty())
    assertEquals("head card untouched", 999L, provider.headNoteId())
  }

  @Test
  fun `unplaced answers are reported separately from the settled ones`() {
    // The scheduler presents 111 only: 111 settles and is cleared, while 222 has no presented card
    // and comes back unplaced so the watch counts it against the orphan cap.
    provider.seed(listOf(111L to 0))
    val outcome = writeBack(listOf(answer("a1", 111L, 0, 3), answer("a2", 222L, 0, 4)))

    assertEquals(listOf("a1"), outcome.settled)
    assertEquals(listOf("a2"), outcome.unplaced)
  }

  @Test
  fun readOnly_drivesTheRealResolverPathButNeverAnswers() {
    provider.seed(listOf(111L to 0))
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3)), readOnly = true)

    assertEquals(listOf("a1"), settled.settled)
    assertTrue("read-only must not touch the collection", provider.applied.isEmpty())
    assertEquals("head card still due", 111L, provider.headNoteId())
  }

  @Test
  fun drainsAcrossMultipleDecks_primingEachDeckDirectly() {
    // The offline-durability case: two decks reviewed offline, buffered, then synced together. Each
    // answer carries its deck_id, so write-back primes deck A with deckID=, drains it, then primes
    // deck B with deckID= and drains it - both apply in one pass. This only holds because primeScheduler
    // queries with deckID= directly: the provider models the real rslib/v3 quirk that a null-selection
    // prime reverts to the current deck, so the old "select once, then null-prime" drained only deck A
    // (1783, seeded first = current) and left 1784's answer buffered. Regression guard for that bug.
    provider.seedDecks(mapOf(1783L to listOf(111L to 0), 1784L to listOf(222L to 0)))

    val settled =
      writeBack(listOf(answer("a1", 111L, 0, 3, deckId = 1783L), answer("a2", 222L, 0, 4, deckId = 1784L)))

    assertEquals(listOf("a1", "a2"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 3), Applied(222L, 0, 4)), provider.applied)
    assertTrue("both decks fully drained", provider.isEmpty())
  }

  @Test
  fun unknownDeckId_fallsBackToTheSelectedDeck() {
    // deck_id 0 (legacy/cross-version answer) must still apply against whatever deck is selected,
    // preserving the pre-deck_id behaviour.
    provider.seedDecks(mapOf(1783L to listOf(111L to 0)))
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3, deckId = 0L)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 3)), provider.applied)
  }

  @Test
  fun `an answer the provider swallowed stays buffered and is not marked applied`() {
    // AnkiDroid catches the scheduler's exception and still reports a row, so the row count alone
    // would settle a lost review. reps is the real evidence: they didn't move, so the answer must
    // stay buffered and un-recorded, and a later success must apply it exactly once.
    provider.seed(listOf(111L to 0))
    provider.swallowAnswerCardFailure = true
    val swallowed = writeBack(listOf(answer("a1", 111L, 0, 3)))

    assertTrue("a swallowed answer must stay buffered", swallowed.settled.isEmpty())

    provider.swallowAnswerCardFailure = false
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals("applied exactly once", listOf(Applied(111L, 0, 3)), provider.applied)
  }

  @Test
  fun `a provider without a reps column still settles on the row count`() {
    // Older AnkiDroid exposes no reps column; write-back falls back to the row-count rule so those
    // builds keep working.
    provider.seed(listOf(111L to 0))
    provider.exposeRepsColumn = false
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 3)), provider.applied)
  }
}

/** An answer the fake scheduler actually applied: which card, which ease. */
data class Applied(val noteId: Long, val ord: Int, val ease: Int)

/**
 * Minimal in-memory stand-in for AnkiDroid's schedule ContentProvider, faithful to the rslib/v3
 * quirk that bit multi-deck write-back on real hardware: a `deckID=` query presents THAT deck's head
 * (as fetchDueBatch relies on), but a null-selection query reverts to the collection's *current*
 * deck - a prior `deckID=` query does NOT persist the selection. So the deck a bare-null prime (and
 * the update after it) acts on is [currentDeck], not whatever `deckID=` was last queried. The update
 * grades the head of whichever deck the *last* query targeted, and only if primed since the last
 * answer (a bare update no-ops). This is why write-back must prime each deck with deckID= directly
 * rather than select-then-null-prime. Enough to drive [AnkiDroidBridge.writeAnswersBack] - including
 * its multi-deck drain - without a device.
 */
class FakeAnkiSchedulerProvider : ContentProvider() {
  private val decks = linkedMapOf<Long, ArrayDeque<Pair<Long, Int>>>()
  // Review count per card, bumped on a real answer. write-back re-reads it to confirm the answer
  // landed (the provider swallows a scheduler error and still counts a row).
  private val reps = mutableMapOf<Pair<Long, Int>, Long>()
  // The collection's current deck (col.decks.current). A null-selection query reverts to it; a
  // deckID= query does not change it. Seeded to the first deck, mirroring "some deck is always current".
  private var currentDeck: Long = 0
  // The deck the most recent query targeted = the one the next update grades.
  private var queriedDeck: Long = 0
  private var primedSinceAnswer = false
  val applied = mutableListOf<Applied>()

  /** When true, [update] reports a row but neither answers the card nor bumps reps. */
  var swallowAnswerCardFailure = false

  /** When false, the card URI query omits the reps column (older AnkiDroid). */
  var exposeRepsColumn = true

  /** Single-deck seed (deck id 0), for the tests that don't exercise deck selection. */
  fun seed(cards: List<Pair<Long, Int>>) = seedDecks(mapOf(0L to cards))

  fun seedDecks(deckCards: Map<Long, List<Pair<Long, Int>>>) {
    decks.clear()
    deckCards.forEach { (deckId, cards) -> decks[deckId] = ArrayDeque(cards) }
    reps.clear()
    currentDeck = deckCards.keys.firstOrNull() ?: 0
    queriedDeck = currentDeck
    applied.clear()
    primedSinceAnswer = false
  }

  fun isEmpty() = decks.values.all { it.isEmpty() }

  fun headNoteId(): Long? = decks[currentDeck]?.firstOrNull()?.first

  override fun onCreate() = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    // Card URI (notes/<noteId>/cards/<ord>): serve the card's reps so write-back can confirm an
    // answer landed. Without the column, callers fall back to the update's row count.
    val segments = uri.pathSegments
    if (segments.firstOrNull() == "notes" && segments.getOrNull(2) == "cards") {
      val noteId = segments.getOrNull(1)?.toLongOrNull()
      val ord = segments.getOrNull(3)?.toIntOrNull()
      if (!exposeRepsColumn || noteId == null || ord == null) {
        return MatrixCursor(arrayOf("question"))
      }
      val cursor = MatrixCursor(arrayOf("reps"))
      cursor.addRow(arrayOf<Any>(reps.getOrDefault(noteId to ord, 0L)))
      return cursor
    }
    // A `deckID=` selection scopes this query to that deck (its id is the last selection arg, per
    // fetchDueBatch's "limit=?,deckID=?" format) WITHOUT persisting it as the current deck. A query
    // with no deckID= reverts to the current deck - the exact behaviour that made select-then-null-
    // prime drain only the current deck.
    queriedDeck =
      if (selection?.contains("deckID") == true) {
        selectionArgs?.lastOrNull()?.toLongOrNull() ?: currentDeck
      } else {
        currentDeck
      }
    primedSinceAnswer = true
    val cursor = MatrixCursor(arrayOf("note_id", "ord"))
    decks[queriedDeck]?.firstOrNull()?.let { (noteId, ord) ->
      cursor.addRow(arrayOf<Any>(noteId, ord))
    }
    return cursor
  }

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int {
    // v3 backend quirk: answering no-ops until a schedule query has primed the scheduler.
    if (!primedSinceAnswer) return 0
    val queue = decks[queriedDeck] ?: return 0
    val head = queue.firstOrNull() ?: return 0
    val ease = values?.getAsInteger("answer_ease") ?: return 0
    // AnkiDroid catches the scheduler's exception and still counts the row, so report a row without
    // touching the queue or reps. write-back must notice the reps didn't move.
    if (swallowAnswerCardFailure) {
      primedSinceAnswer = false
      return 1
    }
    applied.add(Applied(head.first, head.second, ease))
    reps[head.first to head.second] = reps.getOrDefault(head.first to head.second, 0L) + 1
    queue.removeFirst()
    primedSinceAnswer = false
    return 1
  }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
