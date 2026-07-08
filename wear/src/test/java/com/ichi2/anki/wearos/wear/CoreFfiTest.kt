package com.ichi2.anki.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wearos_core.DeckInfo
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.answerHtml
import uniffi.wearos_core.cleanHtml
import uniffi.wearos_core.deckChildren
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.htmlForDisplay
import uniffi.wearos_core.parseReviewTimes
import uniffi.wearos_core.serializeDueBatch

/**
 * Exercises the Rust core through the generated Kotlin bindings (camelCase names, record mapping,
 * ByteArray marshalling). Complements the Rust-side unit tests by proving the FFI layer itself.
 */
class CoreFfiTest {
  @Test
  fun cleanHtml_furiganaAndEntities() {
    assertEquals("答(こた)える", cleanHtml("<ruby><rb>答</rb><rt>こた</rt></ruby>える"))
    assertEquals("a & b", cleanHtml("a &amp; b"))
  }

  @Test
  fun htmlForDisplay_keepsFormattingDropsScript() {
    val out = htmlForDisplay("<b>hi</b><script>alert(1)</script>")
    assertTrue(out.contains("<b>hi</b>"))
    assertFalse(out.contains("alert"))
  }

  @Test
  fun answerHtml_splitsAtMarker() {
    assertEquals("A", answerHtml("Q<hr id=answer>A"))
    assertEquals("whole", answerHtml("whole"))
  }

  @Test
  fun parseReviewTimes_stripsBidiIsolates() {
    val raw = "[\"⁨10⁩m\", \"⁨2⁩d\"]"
    assertEquals(listOf("10m", "2d"), parseReviewTimes(raw))
  }

  @Test
  fun deckChildren_folderAndDeck() {
    val decks =
      listOf(
        DeckInfo(1, "Japani"),
        DeckInfo(2, "Japani::Kaishi"),
        DeckInfo(3, "Japani::文法"),
        DeckInfo(4, "Japani::文法::N2"),
      )
    val level = deckChildren(decks, "Japani")
    assertEquals(1L, level.ownDeck?.id)
    val bunpou = level.children.first { it.name == "文法" }
    assertTrue(bunpou.isFolder)
    assertEquals(3L, bunpou.id) // folder that is also a studyable deck
    assertEquals("Japani::文法", bunpou.path)
    // Root has no own deck.
    assertNull(deckChildren(decks, "").ownDeck)
  }

  @Test
  fun serializeDueBatch_roundTrips() {
    val batch =
      WearosDueBatch(
        deckId = 42,
        deckName = "Deck",
        cards =
          listOf(
            WearosCard(
              id = 1,
              noteId = 2,
              ord = 0,
              front = "Q",
              back = "A",
              buttonCount = 4,
              nextReviewTimes = listOf("1m", "10m"),
            )
          ),
      )
    val restored = deserializeDueBatch(serializeDueBatch(batch))
    assertEquals(42L, restored.deckId)
    assertEquals("Deck", restored.deckName)
    assertEquals(1, restored.cards.size)
    assertEquals("Q", restored.cards[0].front)
    assertEquals(listOf("1m", "10m"), restored.cards[0].nextReviewTimes)
  }
}
