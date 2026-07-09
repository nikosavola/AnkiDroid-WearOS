package fi.nikosavola.ankidroid.wearos.phone

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosAnswer

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
  fun fetchAllDecks_fallsBackToMockWhenProviderUnavailable() {
    val resolver =
      mockk<ContentResolver>(relaxed = true) {
        every { acquireContentProviderClient(any<Uri>()) } returns null
      }
    val context = mockk<Context>(relaxed = true) { every { contentResolver } returns resolver }

    val decks = AnkiDroidBridge(context).fetchAllDecks()
    assertTrue("expected mock fallback decks", decks.any { it.second.contains("Mock") })
  }

  // --- write-back (watch grades -> AnkiDroid) contract ---------------------------------------
  // These pin AnkiDroidBridge.writeAnswersBack's behavior toward the ContentProvider. They do NOT
  // (and cannot) prove AnkiDroid actually persists the review - see WRITEBACK_DEBUGGING.md - they
  // guard our side of the contract: the right ContentValues, read-only skipping, and which answers
  // are reported "settled" (safe to clear from the watch buffer) vs kept for retry.

  private fun answer(id: String, ease: Int) =
    WearosAnswer(id = id, noteId = 111L, ord = 0, ease = ease, timeTakenMs = 4200L)

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

  @Test
  fun writeAnswersBack_readOnly_neverTouchesProviderButSettlesAll() {
    val resolver = availableResolver {}
    val settled =
      bridge(resolver).writeAnswersBack(listOf(answer("a1", 3), answer("a2", 1)), readOnly = true)

    verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    assertEquals(listOf("a1", "a2"), settled)
  }

  @Test
  fun writeAnswersBack_appliesAnswer_withCorrectContentValues() {
    val values = slot<ContentValues>()
    val resolver = availableResolver {
      every { update(any(), capture(values), any(), any()) } returns 1
    }

    val settled =
      bridge(resolver)
        .writeAnswersBack(
          listOf(WearosAnswer(id = "a1", noteId = 111L, ord = 2, ease = 3, timeTakenMs = 4200L)),
          readOnly = false,
        )

    assertEquals(listOf("a1"), settled)
    val v = values.captured
    assertEquals(111L, v.getAsLong("note_id"))
    assertEquals(2, v.getAsInteger("ord"))
    assertEquals(3, v.getAsInteger("answer_ease"))
    assertEquals(4200L, v.getAsLong("time_taken"))
  }

  @Test
  fun writeAnswersBack_settlesNoMatchAsTerminal_notRetried() {
    // rows==0 means the card no longer matches (already answered / removed): terminal, so it is
    // reported settled and cleared from the buffer rather than piling up.
    val resolver = availableResolver { every { update(any(), any(), any(), any()) } returns 0 }
    val settled = bridge(resolver).writeAnswersBack(listOf(answer("a1", 3)), readOnly = false)
    assertEquals(listOf("a1"), settled)
  }

  @Test
  fun writeAnswersBack_keepsForRetry_onTransientException() {
    val resolver = availableResolver {
      every { update(any(), any(), any(), any()) } throws RuntimeException("boom")
    }
    val settled = bridge(resolver).writeAnswersBack(listOf(answer("a1", 3)), readOnly = false)
    assertTrue("a thrown write must NOT be settled (kept for retry)", settled.isEmpty())
  }

  @Test
  fun writeAnswersBack_perAnswerOutcomes_areIndependent() {
    // a1 applies, a2 throws (retry), a3 is no-match (settled). One failure must not drop the
    // others.
    val resolver = availableResolver {
      every { update(any(), any(), any(), any()) } returns
        1 andThenThrows
        RuntimeException("boom") andThen
        0
    }
    val settled =
      bridge(resolver)
        .writeAnswersBack(
          listOf(answer("a1", 3), answer("a2", 1), answer("a3", 2)),
          readOnly = false,
        )
    assertEquals("a2 kept for retry; a1+a3 settled", listOf("a1", "a3"), settled)
  }

  @Test
  fun writeAnswersBack_primesSchedulerBeforeAnswering() {
    // The rslib/v3 backend silently no-ops a bare ReviewInfo update; writeAnswersBack must first
    // query the schedule to reset the scheduler so the answer persists (verified on the emulator:
    // bare update -> reps 0/no revlog; query-then-update -> reps++/revlog row). Pin that ordering.
    val resolver =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns null
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
}
