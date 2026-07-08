package com.ichi2.anki.wearos.wear

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
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
  fun listCached_skipsCorruptFiles() {
    DeckCache.saveBatch(context, 1, serializeDueBatch(batch(1, "Good", 2)))
    DeckCache.deckFile(context, 999).writeBytes("not a valid batch".toByteArray())
    val cached = DeckCache.listCached(context)
    assertEquals(1, cached.size)
    assertTrue(cached.all { it.name == "Good" })
  }
}
