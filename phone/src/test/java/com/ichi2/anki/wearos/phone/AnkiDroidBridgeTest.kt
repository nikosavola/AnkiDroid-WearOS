package com.ichi2.anki.wearos.phone

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
