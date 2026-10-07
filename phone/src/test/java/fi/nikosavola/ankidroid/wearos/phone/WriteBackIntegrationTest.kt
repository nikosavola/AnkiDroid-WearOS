package fi.nikosavola.ankidroid.wearos.phone

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
 * away: the URI/authority, the exact cursor column + ContentValues keys, drain ordering, and the
 * deck-toggle handshake that drops rslib's study queues before a run of answers.
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

  private fun buildStudyQueues() {
    provider.query(scheduleUri, null, null, null, null).close()
  }

  // --- the deck-toggle handshake (the main path) ----------------------------------------------

  @Test
  fun `answers apply out of order once the queues are dropped`() {
    // AnkiDroid's UI has built its study queues (the schedule query), and the buffered answers are
    // not in queue order. One toggle drops the queues, after which every answer applies, whatever
    // order the watch sent them in.
    provider.seedDecks(mapOf(10L to listOf(111L to 0, 222L to 0, 333L to 0), 20L to emptyList()))
    buildStudyQueues()

    val outcome =
      writeBack(
        listOf(answer("a3", 333L, 0, 4), answer("a1", 111L, 0, 1), answer("a2", 222L, 0, 3))
      )

    assertEquals(listOf("a3", "a1", "a2"), outcome.settled)
    assertEquals(
      listOf(Applied(333L, 0, 4), Applied(111L, 0, 1), Applied(222L, 0, 3)),
      provider.applied,
    )
    assertTrue("the queue is fully drained", provider.isEmpty())
  }

  @Test
  fun `a learning chain on one card applies in order`() {
    // The same card graded Again, Good, Easy back to back. All three carry the same (note_id, ord),
    // so they apply in the order given as long as the queues are dropped first.
    provider.seedDecks(mapOf(10L to listOf(111L to 0), 20L to listOf(999L to 0)))

    val outcome =
      writeBack(
        listOf(
          answer("again", 111L, 0, 1),
          answer("good", 111L, 0, 3),
          answer("easy", 111L, 0, 4),
        )
      )

    assertEquals(listOf("again", "good", "easy"), outcome.settled)
    assertEquals(listOf(1, 3, 4), provider.applied.map { it.ease })
    assertEquals("all three grades landed", 3L, provider.repsOf(111L, 0))
  }

  @Test
  fun `a rebuilt queue mid-pass is dropped again and the answer retried once`() {
    // Mid-pass something rebuilds the queues (AnkiDroid's own UI opening a schedule screen), which
    // would reject the next answer. The bridge drops them again and retries that answer exactly once.
    provider.seedDecks(mapOf(10L to listOf(111L to 0, 222L to 0), 20L to listOf(333L to 0)))
    provider.rebuildQueuesBeforeUpdateIndex = 1

    val outcome = writeBack(listOf(answer("a1", 111L, 0, 3), answer("a2", 222L, 0, 4)))

    assertEquals(listOf("a1", "a2"), outcome.settled)
    assertEquals(listOf(Applied(111L, 0, 3), Applied(222L, 0, 4)), provider.applied)
    assertEquals("the rejected attempt was retried once", 1, provider.rejectedWhileBuilt)
  }

  @Test
  fun `an answer that still fails leaves later answers for the same card unplaced`() {
    // Card 111 keeps failing (its answer is swallowed every time). Once it is unplaceable, a later
    // grade for the same card must not be tried (that would apply the chain out of order), while a
    // different card still drains.
    provider.seedDecks(mapOf(10L to listOf(111L to 0, 222L to 0), 20L to listOf(333L to 0)))
    provider.failingCard = 111L to 0

    val outcome =
      writeBack(
        listOf(answer("a1", 111L, 0, 3), answer("a2", 111L, 0, 3), answer("b1", 222L, 0, 4))
      )

    assertEquals(listOf("b1"), outcome.settled)
    assertEquals(listOf("a1", "a2"), outcome.unplaced)
    assertEquals(listOf(Applied(222L, 0, 4)), provider.applied)
  }

  @Test
  fun `the original selected deck is restored after write-back`() {
    // The toggle moves the selection away and back; whatever happens (a normal pass or a thrown
    // write), the collection is left on the deck the user had selected.
    provider.seedDecks(mapOf(10L to listOf(111L to 0), 20L to listOf(999L to 0)))
    writeBack(listOf(answer("a1", 111L, 0, 3)))
    assertEquals(10L, provider.selectedDeckId())

    provider.seedDecks(mapOf(10L to listOf(111L to 0), 20L to listOf(999L to 0)))
    provider.throwOnAnswerUpdate = true
    // A fresh id: reusing "a1" would let the dedupe store settle it as a re-send before the throw.
    val outcome = writeBack(listOf(answer("a2", 111L, 0, 3)))

    assertTrue("a thrown write keeps the answer buffered", outcome.settled.isEmpty())
    assertEquals("the deck is restored even when the pass aborts", 10L, provider.selectedDeckId())
  }

  @Test
  fun `a single-deck collection falls back to head-gated write-back`() {
    // Nothing to toggle to, so write-back keeps the old head-gated path: only the card the scheduler
    // presents may be answered, and the other buffered answer stays unplaced.
    provider.seedDecks(mapOf(10L to listOf(111L to 0)))

    val outcome = writeBack(listOf(answer("a1", 222L, 0, 4), answer("a2", 111L, 0, 3)))

    assertEquals(listOf("a2"), outcome.settled)
    assertEquals(listOf("a1"), outcome.unplaced)
    assertEquals(listOf(Applied(111L, 0, 3)), provider.applied)
    assertEquals("the single deck was never toggled", 10L, provider.selectedDeckId())
  }

  // --- head-gated fallback (echoes of the old contract) ---------------------------------------

  @Test
  fun `a single-deck buffer drains in scheduler order`() {
    provider.seedDecks(mapOf(10L to listOf(111L to 0, 222L to 1)))

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
  fun `the fallback primes the scheduler before answering`() {
    provider.seedDecks(mapOf(10L to listOf(111L to 0)))
    val settled = writeBack(listOf(answer("a1", 111L, 0, 2)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 2)), provider.applied)
  }

  @Test
  fun `the fallback never answers a card the scheduler isnt presenting`() {
    // The scheduler head is 999, but we only have a buffered answer for 111. writeAnswersBack must
    // NOT answer 999 (that'd be a mis-grade); it reports 111 unplaced and leaves it buffered.
    provider.seedDecks(mapOf(10L to listOf(999L to 0)))
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
    provider.seedDecks(mapOf(10L to listOf(111L to 0)))
    val outcome = writeBack(listOf(answer("a1", 111L, 0, 3), answer("a2", 222L, 0, 4)))

    assertEquals(listOf("a1"), outcome.settled)
    assertEquals(listOf("a2"), outcome.unplaced)
  }

  @Test
  fun `a multi-deck buffer drains in one pass`() {
    // Reviewed on two decks offline, buffered, then synced together: one toggle frees both decks.
    provider.seedDecks(mapOf(1783L to listOf(111L to 0), 1784L to listOf(222L to 0)))

    val settled =
      writeBack(listOf(answer("a1", 111L, 0, 3, deckId = 1783L), answer("a2", 222L, 0, 4, deckId = 1784L)))

    assertEquals(listOf("a1", "a2"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 3), Applied(222L, 0, 4)), provider.applied)
    assertTrue("both decks fully drained", provider.isEmpty())
  }

  @Test
  fun unknownDeckId_fallsBackToTheSelectedDeck() {
    // deck_id 0 (legacy/cross-version answer) must still apply against whatever deck is selected.
    provider.seedDecks(mapOf(1783L to listOf(111L to 0)))
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3, deckId = 0L)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 3)), provider.applied)
  }

  @Test
  fun readOnly_drivesTheRealResolverPathButNeverAnswers() {
    provider.seedDecks(mapOf(10L to listOf(111L to 0), 20L to listOf(999L to 0)))
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3)), readOnly = true)

    assertEquals(listOf("a1"), settled.settled)
    assertTrue("read-only must not touch the collection", provider.applied.isEmpty())
    assertEquals("head card still due", 111L, provider.headNoteId())
  }

  @Test
  fun `an answer the provider swallowed stays buffered and is not marked applied`() {
    // Single deck: the fallback prime-then-answer path. AnkiDroid catches the scheduler's exception
    // and still reports a row, so the row count alone would settle a lost review. reps is the real
    // evidence: they didn't move, so the answer must stay buffered, and a later success must apply it
    // exactly once.
    provider.seedDecks(mapOf(10L to listOf(111L to 0)))
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
    provider.seedDecks(mapOf(10L to listOf(111L to 0)))
    provider.exposeRepsColumn = false
    val settled = writeBack(listOf(answer("a1", 111L, 0, 3)))

    assertEquals(listOf("a1"), settled.settled)
    assertEquals(listOf(Applied(111L, 0, 3)), provider.applied)
  }
}

/** An answer the fake scheduler actually applied: which card, which ease. */
data class Applied(val noteId: Long, val ord: Int, val ease: Int)

/** A transient ContentProvider failure, thrown by the fake to exercise the retry/abort paths. */
private class TransientProviderException : RuntimeException("transient provider failure")

/**
 * In-memory stand-in for AnkiDroid's schedule ContentProvider, faithful to the rslib behaviour the
 * write-back leans on: rslib rejects an answer that isn't at the head of its study queues ("not at
 * top of queue") only while those queues are built, and any schedule query builds them. Changing the
 * selected deck to a *different* deck drops them. So a toggle (select another deck, select ours back)
 * with no schedule query in between lets every answer apply, in any order.
 *
 * The provider swallows rslib's rejection (reports a row, changes nothing, drops the queues) exactly
 * as AnkiDroid does, which is why write-back confirms an answer by re-reading reps rather than the
 * row count. Enough to drive [AnkiDroidBridge.writeAnswersBack] without a device.
 */
class FakeAnkiSchedulerProvider : ContentProvider() {
  private val decks = linkedMapOf<Long, ArrayDeque<Pair<Long, Int>>>()
  private val reps = mutableMapOf<Pair<Long, Int>, Long>()
  private var currentDeck: Long = 0
  // Set by any schedule query (rslib building its study queues), dropped when the selected deck
  // actually changes. While built, only the head answers; while dropped, any card does.
  private var queuesBuilt = false
  private var updateCount = 0
  val applied = mutableListOf<Applied>()

  /** When true, an answer reports a row but neither answers the card nor bumps reps. */
  var swallowAnswerCardFailure = false

  /** When set, only this card's answers are swallowed (the others behave normally). */
  var failingCard: Pair<Long, Int>? = null

  /** When false, the card URI query omits the reps column (older AnkiDroid). */
  var exposeRepsColumn = true

  /** When set, the fake rebuilds its queues onto the other deck before this 0-based update. */
  var rebuildQueuesBeforeUpdateIndex = -1

  /** When true, a schedule answer update throws, modelling a transient provider failure. */
  var throwOnAnswerUpdate = false

  /** How many answers were rejected because the queues were built and the card wasn't the head. */
  var rejectedWhileBuilt = 0
    private set

  /** Single-deck seed (deck id 0), for the tests that don't exercise deck selection. */
  fun seed(cards: List<Pair<Long, Int>>) = seedDecks(mapOf(0L to cards))

  fun seedDecks(deckCards: Map<Long, List<Pair<Long, Int>>>) {
    decks.clear()
    deckCards.forEach { (deckId, cards) -> decks[deckId] = ArrayDeque(cards) }
    reps.clear()
    currentDeck = deckCards.keys.firstOrNull() ?: 0
    applied.clear()
    queuesBuilt = false
    updateCount = 0
    rejectedWhileBuilt = 0
  }

  fun isEmpty() = decks.values.all { it.isEmpty() }

  fun headNoteId(): Long? = decks[currentDeck]?.firstOrNull()?.first

  fun selectedDeckId(): Long = currentDeck

  fun repsOf(noteId: Long, ord: Int): Long = reps.getOrDefault(noteId to ord, 0L)

  override fun onCreate() = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    val segments = uri.pathSegments
    // Card URI (notes/<noteId>/cards/<ord>): serve the card's reps so write-back can confirm an
    // answer landed. Without the column, callers fall back to the update's row count.
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
    return when (segments.firstOrNull()) {
      "decks" -> {
        val cursor = MatrixCursor(arrayOf("deck_id", "deck_name"))
        for (id in decks.keys) cursor.addRow(arrayOf<Any>(id, "Deck $id"))
        cursor
      }
      "selected_deck" -> {
        val cursor = MatrixCursor(arrayOf("deck_id", "deck_name"))
        cursor.addRow(arrayOf<Any>(currentDeck, "Deck $currentDeck"))
        cursor
      }
      // A schedule query rebuilds the queues and presents the queried deck's head (or the current
      // deck's, when no deckID= is given), matching AnkiDroid.
      "schedule" -> {
        queuesBuilt = true
        val deck =
          if (selection?.contains("deckID") == true) {
            selectionArgs?.lastOrNull()?.toLongOrNull() ?: currentDeck
          } else {
            currentDeck
          }
        val cursor = MatrixCursor(arrayOf("note_id", "ord"))
        decks[deck]?.firstOrNull()?.let { (noteId, ord) -> cursor.addRow(arrayOf<Any>(noteId, ord)) }
        cursor
      }
      else -> {
        MatrixCursor(arrayOf<String>())
      }
    }
  }

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int =
    when (uri.pathSegments.firstOrNull()) {
      "selected_deck" -> selectDeck(values)
      "schedule" -> answerCard(values)
      else -> 0
    }

  /** Drop the queues when the selected deck actually changes; re-selecting the current deck keeps them. */
  private fun selectDeck(values: ContentValues?): Int {
    val deckId = values?.getAsLong("deck_id") ?: return 0
    if (!decks.containsKey(deckId)) return 0
    if (deckId != currentDeck) {
      currentDeck = deckId
      queuesBuilt = false
    }
    return 1
  }

  private fun answerCard(values: ContentValues?): Int {
    if (throwOnAnswerUpdate) throw TransientProviderException()
    val noteId = values?.getAsLong("note_id") ?: return 0
    val ord = values?.getAsInteger("ord") ?: return 0
    val ease = values?.getAsInteger("answer_ease") ?: return 0
    val card = noteId to ord

    // Simulate AnkiDroid's UI rebuilding the queues mid-pass, onto the other deck.
    if (updateCount++ == rebuildQueuesBeforeUpdateIndex) {
      queuesBuilt = true
      currentDeck = decks.keys.firstOrNull { it != currentDeck } ?: currentDeck
    }

    if (queuesBuilt) {
      val head = decks[currentDeck]?.firstOrNull()
      if (head != card) {
        // rslib rejects a non-head answer while built; the provider still reports a row, changes
        // nothing, and drops the queues.
        queuesBuilt = false
        rejectedWhileBuilt++
        return 1
      }
    }
    if (swallowAnswerCardFailure || card == failingCard) {
      queuesBuilt = false
      return 1
    }

    applied.add(Applied(card.first, card.second, ease))
    reps[card] = reps.getOrDefault(card, 0L) + 1
    for (queue in decks.values) if (queue.remove(card)) break
    return 1
  }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
