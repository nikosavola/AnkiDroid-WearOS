package com.ichi2.anki.wearos.phone

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch

/**
 * AnkiDroidBridge talks to AnkiDroid's ContentProvider. We drive it with MockK fakes for the
 * ContentResolver/Cursor so cursor parsing and the mock-fallback path are testable without a real
 * provider. Runs under Robolectric because the companion initializes android.net.Uri fields.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiDroidBridgeTest {
  @Test
  fun stripBase64Images_replacesInlineDataUris() {
    val html = "<img src=\"data:image/png;base64,AAAABBBBCCCCDDDD==\"> caption"
    val out = AnkiDroidBridge.stripBase64Images(html)
    assertFalse("base64 payload should be gone: $out", out.contains("AAAABBBB"))
    assertTrue(out.contains("data:image/placeholder"))
    assertTrue(out.contains("caption"))
  }

  @Test
  fun stripBase64Batch_stripsEveryCardFrontAndBackButKeepsMetadata() {
    val batch =
      WearosDueBatch(
        deckId = 7,
        deckName = "Japani",
        cards =
          listOf(
            WearosCard(
              id = 1,
              noteId = 1,
              ord = 0,
              front = "<img src=\"data:image/png;base64,AAAABBBBCCCC==\"> q1",
              back = "a1 <img src=\"data:image/jpeg;base64,DDDDEEEE==\">",
              buttonCount = 4,
              nextReviewTimes = listOf("1m"),
            ),
            WearosCard(
              id = 2,
              noteId = 2,
              ord = 1,
              front = "plain q2",
              back = "plain a2",
              buttonCount = 3,
              nextReviewTimes = emptyList(),
            ),
          ),
      )

    val cleaned = AnkiDroidBridge.stripBase64Batch(batch)

    // Deck metadata + per-card fields (ids, counts, review times) are untouched.
    assertEquals(7, cleaned.deckId)
    assertEquals("Japani", cleaned.deckName)
    assertEquals(listOf(1L, 2L), cleaned.cards.map { it.id })
    assertEquals(listOf(4, 3), cleaned.cards.map { it.buttonCount })
    // Base64 payloads are gone from both sides of every card; plain text is preserved.
    assertFalse(cleaned.cards[0].front.contains("AAAABBBB"))
    assertFalse(cleaned.cards[0].back.contains("DDDDEEEE"))
    assertTrue(cleaned.cards[0].front.contains("q1"))
    assertTrue(cleaned.cards[0].back.contains("a1"))
    assertEquals("plain q2", cleaned.cards[1].front)
  }

  @Test
  fun fetchAllDecks_parsesCursorRows() {
    val cursor =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("deck_id") } returns 0
        every { getColumnIndex("deck_name") } returns 1
        every { moveToNext() } returnsMany listOf(true, true, false)
        every { getLong(0) } returnsMany listOf(10L, 20L)
        every { getString(1) } returnsMany listOf("Deck A", "Deck B")
      }
    val providerClient = mockk<ContentProviderClient>(relaxed = true)
    val resolver =
      mockk<ContentResolver>(relaxed = true) {
        every { acquireContentProviderClient(any<Uri>()) } returns providerClient
        every { query(any(), any(), any(), any(), any()) } returns cursor
      }
    val context = mockk<Context>(relaxed = true) { every { contentResolver } returns resolver }

    val decks = AnkiDroidBridge(context).fetchAllDecks()
    assertEquals(listOf(DeckId(10L) to "Deck A", DeckId(20L) to "Deck B"), decks)
  }

  @Test
  fun fetchAllDecks_returnsEmptyWhenProviderUnavailable() {
    val resolver =
      mockk<ContentResolver>(relaxed = true) {
        every { acquireContentProviderClient(any<Uri>()) } returns null
      }
    val context = mockk<Context>(relaxed = true) { every { contentResolver } returns resolver }

    // Unavailable provider must NOT synthesize phantom decks: mock decks would overwrite the
    // user's real cached decks and offer non-existent decks to sync.
    val decks = AnkiDroidBridge(context).fetchAllDecks()
    assertTrue("expected no decks when provider unavailable", decks.isEmpty())
  }

  // --- write-back (watch grades -> AnkiDroid) contract ---------------------------------------
  // These pin AnkiDroidBridge.writeAnswersBack's behavior toward the ContentProvider. They do NOT
  // (and cannot) prove AnkiDroid actually persists the review - see WRITEBACK_DEBUGGING.md - they
  // guard our side of the contract: the right ContentValues, read-only skipping, and which answers
  // are reported "settled" (safe to clear from the watch buffer) vs kept for retry.

  private fun answer(id: String, ease: Int, noteId: Long = 111L, ord: Int = 0) =
    WearosAnswer(id = id, noteId = noteId, ord = ord, ease = ease, timeTakenMs = 4200L, deckId = 0L)

  /** A schedule-query cursor presenting [noteId]/[ord] as the current card (what primeScheduler reads). */
  private fun scheduleCursor(noteId: Long, ord: Int) =
    mockk<Cursor>(relaxed = true) {
      every { moveToFirst() } returns true
      every { getColumnIndex("note_id") } returns 0
      every { getColumnIndex("ord") } returns 1
      // No reps column: these tests pin the row-count fallback, not the reps confirmation.
      every { getColumnIndex("reps") } returns -1
      every { getLong(0) } returns noteId
      every { getInt(1) } returns ord
    }

  /** A schedule-query cursor with no due card (moveToFirst == false). */
  private fun noCardCursor() = mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns false }

  /** ContentResolver whose provider is reachable (isAnkiDroidAvailable() == true). */
  private fun availableResolver(block: ContentResolver.() -> Unit): ContentResolver {
    val providerClient = mockk<ContentProviderClient>(relaxed = true)
    return mockk<ContentResolver>(relaxed = true) {
      every { acquireContentProviderClient(any<Uri>()) } returns providerClient
      block()
    }
  }

  private fun bridge(resolver: ContentResolver): AnkiDroidBridge {
    val context = mockk<Context>(relaxed = true) { every { contentResolver } returns resolver }
    return AnkiDroidBridge(context)
  }

  /**
   * Resolver for the multi-step write-back tests: schedule queries come from [scheduleCursors] in
   * order (a no-card cursor once exhausted), and the card-reps probes return a cursor without a reps
   * column so the loop uses the row-count fallback. Updates are captured into [capture] when given.
   */
  private fun writeBackResolver(
    scheduleCursors: List<Cursor>,
    capture: MutableList<ContentValues>? = null,
  ): ContentResolver {
    val schedule = ArrayDeque(scheduleCursors)
    val noRepsCursor = mockk<Cursor>(relaxed = true) { every { getColumnIndex("reps") } returns -1 }
    return availableResolver {
      every { query(any(), any(), any(), any(), any()) } answers
        {
          if (firstArg<Uri>().toString().endsWith("/schedule")) {
            schedule.removeFirstOrNull() ?: noCardCursor()
          } else {
            noRepsCursor
          }
        }
      if (capture != null) {
        every { update(any(), capture(capture), any(), any()) } returns 1
      } else {
        every { update(any(), any(), any(), any()) } returns 1
      }
    }
  }

  @Test
  fun writeAnswersBack_readOnly_neverTouchesProviderButSettlesAll() {
    val resolver = availableResolver {}
    val outcome =
      bridge(resolver).writeAnswersBack(listOf(answer("a1", 3), answer("a2", 1)), readOnly = true)

    verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    assertEquals(listOf("a1", "a2"), outcome.settled)
  }

  @Test
  fun writeAnswersBack_appliesAnswer_withCorrectContentValues() {
    val values = slot<ContentValues>()
    val resolver = availableResolver {
      every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 2)
      every { update(any(), capture(values), any(), any()) } returns 1
    }

    val outcome =
      bridge(resolver)
        .writeAnswersBack(
          listOf(WearosAnswer(id = "a1", noteId = 111L, ord = 2, ease = 3, timeTakenMs = 4200L, deckId = 0L)),
          readOnly = false,
        )

    assertEquals(listOf("a1"), outcome.settled)
    val v = values.captured
    assertEquals(111L, v.getAsLong("note_id"))
    assertEquals(2, v.getAsInteger("ord"))
    assertEquals(3, v.getAsInteger("answer_ease"))
    assertEquals(4200L, v.getAsLong("time_taken"))
  }

  @Test
  fun writeAnswersBack_settlesNoMatchAsTerminal_notRetried() {
    // The scheduler presents the card, but the update writes 0 rows (already answered / removed):
    // terminal, so it's reported settled and cleared from the buffer rather than piling up.
    val resolver = availableResolver {
      every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 0)
      every { update(any(), any(), any(), any()) } returns 0
    }
    val outcome = bridge(resolver).writeAnswersBack(listOf(answer("a1", 3)), readOnly = false)
    assertEquals(listOf("a1"), outcome.settled)
  }

  @Test
  fun writeAnswersBack_keepsForRetry_onTransientException() {
    val resolver = availableResolver {
      every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 0)
      every { update(any(), any(), any(), any()) } throws RuntimeException("boom")
    }
    val outcome = bridge(resolver).writeAnswersBack(listOf(answer("a1", 3)), readOnly = false)
    assertTrue("a thrown write must NOT be settled (kept for retry)", outcome.settled.isEmpty())
    assertTrue("a transient failure says nothing about placement", outcome.unplaced.isEmpty())
  }

  @Test
  fun writeAnswersBack_neverAnswersACardTheSchedulerIsntPresenting() {
    // The scheduler is on note 999 (nothing buffered for it). Answering the current card with a
    // buffered answer for a DIFFERENT card would mis-grade note 999, so update must never be called
    // and the buffered answer is reported unplaced for the watch to count.
    val resolver = availableResolver {
      every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(999L, 0)
      every { update(any(), any(), any(), any()) } returns 1
    }
    val outcome =
      bridge(resolver).writeAnswersBack(listOf(answer("a1", 3, noteId = 111L)), readOnly = false)

    verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    assertTrue("unmatched answer is kept for retry, not mis-applied", outcome.settled.isEmpty())
    assertEquals(listOf("a1"), outcome.unplaced)
  }

  @Test
  fun writeAnswersBack_reportsUnplacedSeparatelyFromSettled() {
    // The scheduler presents 111 (buffered) and applies it, then has nothing more this pass; the
    // buffered 222 has no presented card, so it comes back unplaced rather than silently settled.
    val resolver =
      writeBackResolver(scheduleCursors = listOf(scheduleCursor(111L, 0), noCardCursor()))

    val outcome =
      bridge(resolver)
        .writeAnswersBack(
          listOf(answer("a1", 3, noteId = 111L), answer("a2", 3, noteId = 222L)),
          readOnly = false,
        )

    assertEquals(listOf("a1"), outcome.settled)
    assertEquals(listOf("a2"), outcome.unplaced)
  }

  @Test
  fun writeAnswersBack_drainsInSchedulerOrder_notBufferOrder() {
    // Buffer order is [222, 111] but the scheduler presents 111 then 222; answers must follow the
    // scheduler, so 111 is answered first.
    val applied = mutableListOf<ContentValues>()
    val resolver =
      writeBackResolver(
        scheduleCursors = listOf(scheduleCursor(111L, 0), scheduleCursor(222L, 0), noCardCursor()),
        capture = applied,
      )

    val outcome =
      bridge(resolver)
        .writeAnswersBack(
          listOf(answer("a2", 1, noteId = 222L), answer("a1", 3, noteId = 111L)),
          readOnly = false,
        )

    assertEquals(listOf(111L, 222L), applied.map { it.getAsLong("note_id") })
    assertEquals(listOf("a1", "a2"), outcome.settled)
  }

  @Test
  fun writeAnswersBack_sameCardRelearn_appliesBothWhenRepresented() {
    // A card graded Again-then-Good in one session: two buffered answers for note 111. The scheduler
    // presents 111, then re-presents it (relearn step), so both land - no grade is dropped.
    val eases = mutableListOf<ContentValues>()
    val resolver =
      writeBackResolver(
        scheduleCursors = listOf(scheduleCursor(111L, 0), scheduleCursor(111L, 0), noCardCursor()),
        capture = eases,
      )

    val outcome =
      bridge(resolver)
        .writeAnswersBack(listOf(answer("again", 1), answer("good", 3)), readOnly = false)

    assertEquals(listOf(1, 3), eases.map { it.getAsInteger("answer_ease") })
    assertEquals(listOf("again", "good"), outcome.settled)
  }

  @Test
  fun writeAnswersBack_primesSchedulerBeforeAnswering() {
    // The rslib/v3 backend silently no-ops a bare ReviewInfo update; writeAnswersBack must first
    // query the schedule to reset the scheduler so the answer persists (verified on the emulator:
    // bare update -> reps 0/no revlog; query-then-update -> reps++/revlog row). Pin that ordering.
    val resolver =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 0)
        every { update(any(), any(), any(), any()) } returns 1
      }
    bridge(resolver).writeAnswersBack(listOf(answer("a1", 3)), readOnly = false)
    verifyOrder {
      resolver.query(any(), any(), any(), any(), any())
      resolver.update(any(), any(), any(), any())
    }
  }

  // --- fetchDueBatch (AnkiDroid schedule/card cursors -> WearosCard) --------------------------
  // The primary data path the watch studies from. These drive it with fake cursors for the three
  // query shapes it issues (decks list, schedule rows, per-card front/back).

  private fun deckCursor(id: Long, name: String) =
    mockk<Cursor>(relaxed = true) {
      every { getColumnIndex("deck_id") } returns 0
      every { getColumnIndex("deck_name") } returns 1
      every { moveToNext() } returnsMany listOf(true, false)
      every { getLong(0) } returns id
      every { getString(1) } returns name
    }

  private fun cardCursor(id: Long, front: String, back: String) =
    mockk<Cursor>(relaxed = true) {
      every { getColumnIndex("_id") } returns 0
      every { getColumnIndex("question") } returns 1
      every { getColumnIndex("answer") } returns 2
      every { moveToFirst() } returns true
      every { getLong(0) } returns id
      every { getString(1) } returns front
      every { getString(2) } returns back
    }

  /** Routes fetchDueBatch's queries (decks / schedule / per-card) to the given fake cursors. */
  private fun dueBatchResolver(
    deck: Cursor,
    schedule: Cursor,
    card: Cursor,
  ): ContentResolver = availableResolver {
    every { query(any(), any(), any(), any(), any()) } answers
      {
        when {
          firstArg<Uri>().toString().endsWith("/decks") -> deck
          firstArg<Uri>().toString().endsWith("/schedule") -> schedule
          firstArg<Uri>().toString().contains("/cards/") -> card
          else -> null
        }
      }
  }

  @Test
  fun fetchDueBatch_forceMock_neverTouchesProvider() {
    // Guards the safety path the user relies on to never grade real decks from a test build.
    val resolver = availableResolver {}
    val batch = bridge(resolver).fetchDueBatch(deckId = DeckId(7L), limit = 10, forceMock = true)

    verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
    assertTrue("mock batch should carry cards", batch.cards.isNotEmpty())
  }

  @Test
  fun fetchDueBatch_parsesScheduleAndCardCursors() {
    val schedule =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("note_id") } returns 0
        every { getColumnIndex("ord") } returns 1
        every { getColumnIndex("button_count") } returns 2
        every { getColumnIndex("next_review_times") } returns 3
        every { moveToNext() } returnsMany listOf(true, false)
        every { getLong(0) } returns 1001L
        every { getInt(1) } returns 0
        every { getInt(2) } returns 3
        every { getString(3) } returns "1m, 8m, 3d"
      }
    val resolver =
      dueBatchResolver(
        deck = deckCursor(42L, "Japani::文法"),
        schedule = schedule,
        card = cardCursor(555L, "<b>Front</b>", "Back"),
      )

    val batch = bridge(resolver).fetchDueBatch(deckId = DeckId(42L), limit = 10)

    assertEquals(42L, batch.deckId)
    assertEquals("Japani::文法", batch.deckName)
    assertEquals(1, batch.cards.size)
    val card = batch.cards.single()
    assertEquals(555L, card.id)
    assertEquals(1001L, card.noteId)
    assertEquals(0, card.ord)
    assertEquals(3, card.buttonCount)
    assertEquals("<b>Front</b>", card.front)
    assertEquals("Back", card.back)
    assertEquals(listOf("1m", "8m", "3d"), card.nextReviewTimes)
  }

  @Test
  fun fetchDueBatch_defaultsWhenScheduleColumnsMissing() {
    // Older/leaner providers may omit button_count and next_review_times; fall back rather than crash.
    val schedule =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("note_id") } returns 0
        every { getColumnIndex("ord") } returns 1
        every { getColumnIndex("button_count") } returns -1
        every { getColumnIndex("next_review_times") } returns -1
        every { moveToNext() } returnsMany listOf(true, false)
        every { getLong(0) } returns 1001L
        every { getInt(1) } returns 0
      }
    val resolver =
      dueBatchResolver(
        deck = deckCursor(42L, "Japani"),
        schedule = schedule,
        card = cardCursor(555L, "Q", "A"),
      )

    val card = bridge(resolver).fetchDueBatch(deckId = DeckId(42L), limit = 10).cards.single()

    assertEquals(4, card.buttonCount)
    assertEquals(listOf("1m", "10m", "4d", "7d"), card.nextReviewTimes)
  }

  @Test
  fun learningStepFor_mapsAnkisLeftPackingOntoTheStepLadder() {
    // `left % 1000` is the reps left before graduation, so a card at the start of a two-step
    // ladder still has both steps ahead of it.
    assertEquals(0, AnkiDroidBridge.learningStepFor(left = 2, stepCount = 2, queue = 1))
    assertEquals(1, AnkiDroidBridge.learningStepFor(left = 1, stepCount = 2, queue = 1))
    // `left / 1000` (reps completable today) must not affect the step.
    assertEquals(1, AnkiDroidBridge.learningStepFor(left = 2001, stepCount = 2, queue = 1))
    // A new card has left 0 because it has never been answered: it is on the first step, not out of
    // steps. (Reading it as "no reps left" made Good graduate instead of advancing a step.)
    assertEquals(0, AnkiDroidBridge.learningStepFor(left = 0, stepCount = 2, queue = 0))
    // Defensive: no step list means no position to report.
    assertEquals(-1, AnkiDroidBridge.learningStepFor(left = 1, stepCount = 0, queue = 1))
    // A stale/oversized counter clamps into the ladder rather than going negative.
    assertEquals(0, AnkiDroidBridge.learningStepFor(left = 99, stepCount = 3, queue = 1))
  }

  @Test
  fun fetchDueBatch_readsDeckOptionsFromTheDeckListingItAlreadyDoes() {
    // The scheduling config must not cost an extra provider query: the deck listing carries every
    // column, OPTIONS included, so one /decks query serves both.
    val decks =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("deck_id") } returns 0
        every { getColumnIndex("deck_name") } returns 1
        every { getColumnIndex("options") } returns 2
        every { moveToNext() } returnsMany listOf(true, false)
        every { getLong(0) } returns 42L
        every { getString(1) } returns "Japani"
        every { isNull(2) } returns false
        every { getString(2) } returns
          """{"new":{"delays":[1.0,10.0],"ints":[1,4]},"rev":{},"lapse":{}}"""
      }
    val schedule =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("note_id") } returns 0
        every { getColumnIndex("ord") } returns 1
        every { getColumnIndex("button_count") } returns -1
        every { getColumnIndex("next_review_times") } returns -1
        every { moveToNext() } returnsMany listOf(true, false)
        every { getLong(0) } returns 1001L
        every { getInt(1) } returns 0
      }
    val resolver =
      dueBatchResolver(
        deck = decks,
        schedule = schedule,
        card = cardCursor(555L, "Q", "A"),
      )

    val batch = bridge(resolver).fetchDueBatch(deckId = DeckId(42L), limit = 10)

    assertEquals(1, batch.cards.size)
    val config = batch.scheduleConfig
    assertNotNull("the deck listing supplies the config", config)
    assertEquals(listOf(60.0, 600.0), config!!.learnSteps)
    verify(exactly = 1) { resolver.query(match { it.toString().endsWith("/decks") }, any(), any(), any(), any()) }
  }

  @Test
  fun fetchDueBatch_survivesADeckRowWithoutOptions() {
    // A deck row with a null OPTIONS value must not break the sync, and must not be re-queried.
    val decks =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("deck_id") } returns 0
        every { getColumnIndex("deck_name") } returns 1
        every { getColumnIndex("options") } returns 2
        every { moveToNext() } returnsMany listOf(true, false)
        every { getLong(0) } returns 42L
        every { getString(1) } returns "Japani"
        every { isNull(2) } returns true
      }
    val schedule =
      mockk<Cursor>(relaxed = true) {
        every { getColumnIndex("note_id") } returns 0
        every { getColumnIndex("ord") } returns 1
        every { getColumnIndex("button_count") } returns -1
        every { getColumnIndex("next_review_times") } returns -1
        every { moveToNext() } returnsMany listOf(true, false)
        every { getLong(0) } returns 1001L
        every { getInt(1) } returns 0
      }
    val resolver = dueBatchResolver(deck = decks, schedule = schedule, card = cardCursor(555L, "Q", "A"))

    val batch = bridge(resolver).fetchDueBatch(deckId = DeckId(42L), limit = 10)

    assertEquals(1, batch.cards.size)
    assertNull("no options means no config, not a failed sync", batch.scheduleConfig)
    verify(exactly = 1) { resolver.query(match { it.toString().endsWith("/decks") }, any(), any(), any(), any()) }
  }
}
