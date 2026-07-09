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
    assertEquals(listOf(10L to "Deck A", 20L to "Deck B"), decks)
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
}
