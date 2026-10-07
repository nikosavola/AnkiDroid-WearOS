package com.ichi2.anki.wearos.wear

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.serializeDueBatch

/**
 * DeckCache needs a real Context (files dir) so it runs under Robolectric. It also round-trips
 * through the Rust serializer, exercising the Context + FFI layers together.
 */
@RunWith(RobolectricTestRunner::class)
class DeckCacheTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun batch(id: Long, name: String, cards: Int) =
    WearosDueBatch(
      deckId = id,
      deckName = name,
      cards =
        (0 until cards).map {
          WearosCard(
            id = it.toLong(),
            noteId = it.toLong(),
            ord = 0,
            front = "Q$it",
            back = "A$it",
            buttonCount = 4,
            nextReviewTimes = listOf("1m"),
          )
        },
    )

  @Test
  fun saveThenListCached_returnsSavedDecks() {
    DeckCache.saveBatch(context, 100, serializeDueBatch(batch(100, "Alpha", 3)))
    DeckCache.saveBatch(context, 200, serializeDueBatch(batch(200, "Beta", 5)))

    val cached = DeckCache.listCached(context)
    assertEquals(2, cached.size)
    // Sorted by name (lowercased): Alpha before Beta.
    assertEquals("Alpha", cached[0].name)
    assertEquals(3, cached[0].cardCount)
    assertEquals("Beta", cached[1].name)
    assertEquals(5, cached[1].cardCount)
  }

  @Test
  fun totalDue_sumsCardCounts() {
    DeckCache.saveBatch(context, 1, serializeDueBatch(batch(1, "A", 4)))
    DeckCache.saveBatch(context, 2, serializeDueBatch(batch(2, "B", 6)))
    assertEquals(10, DeckCache.totalDue(context))
  }

  @Test
  fun pruneOrphans_dropsDeselectedDecksOnly() {
    DeckCache.saveBatch(context, 1, serializeDueBatch(batch(1, "Keep", 2)))
    DeckCache.saveBatch(context, 2, serializeDueBatch(batch(2, "Drop", 3)))
    DeckCache.saveBatch(context, 3, serializeDueBatch(batch(3, "Keep2", 1)))

    // Only 1 and 3 remain selected on the phone; 2 was deselected.
    DeckCache.pruneOrphans(context, setOf(1L, 3L))

    assertEquals(setOf("Keep", "Keep2"), DeckCache.listCached(context).map { it.name }.toSet())
    assertFalse("orphaned deck file should be deleted", DeckCache.deckFile(context, 2).exists())
  }

  @Test
  fun listCached_skipsCorruptFiles() {
    DeckCache.saveBatch(context, 1, serializeDueBatch(batch(1, "Good", 2)))
    DeckCache.deckFile(context, 999).writeBytes("not a valid batch".toByteArray())
    val cached = DeckCache.listCached(context)
    assertEquals(1, cached.size)
    assertTrue(cached.all { it.name == "Good" })
  }

  @Test
  fun `session progress overwrites the cache when the expected bytes still match`() {
    val original = serializeDueBatch(batch(300, "Alpha", 3))
    DeckCache.saveBatch(context, 300, original)
    val updated = serializeDueBatch(batch(300, "Alpha", 1))

    assertTrue(DeckCache.saveSessionProgress(context, 300, original, updated))
    assertEquals(
      1,
      deserializeDueBatch(DeckCache.deckFile(context, 300).readBytes()).cards.size,
    )
  }

  @Test
  fun `a fresh phone batch is not overwritten by session progress`() {
    val original = serializeDueBatch(batch(300, "Alpha", 3))
    DeckCache.saveBatch(context, 300, original)
    // The phone delivers a new batch while the session still holds the old bytes.
    DeckCache.saveBatch(context, 300, serializeDueBatch(batch(300, "Alpha", 5)))
    val stale = serializeDueBatch(batch(300, "Alpha", 1))

    assertFalse(DeckCache.saveSessionProgress(context, 300, original, stale))
    assertEquals(
      5,
      deserializeDueBatch(DeckCache.deckFile(context, 300).readBytes()).cards.size,
    )
  }

  @Test
  fun `an atomic save leaves no temp file behind`() {
    DeckCache.saveBatch(context, 300, serializeDueBatch(batch(300, "Alpha", 2)))
    val leftovers = context.filesDir.listFiles { f -> f.name.endsWith(".tmp") }
    assertTrue(
      "no temp files should remain: ${leftovers?.map { it.name }}",
      leftovers.isNullOrEmpty(),
    )
  }
}
