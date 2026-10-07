package com.ichi2.anki.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wearos_core.DeckInfo
import uniffi.wearos_core.ReviewSession
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.answerHtml
import uniffi.wearos_core.cleanHtml
import uniffi.wearos_core.deckChildren
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.formatQueueEta
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

  @Test
  fun serializeDueBatch_isZstdFramedAndRoundTripsCjkHtml() {
    val batch =
      WearosDueBatch(
        deckId = 9,
        deckName = "文法",
        cards =
          listOf(
            WearosCard(
              id = 1,
              noteId = 2,
              ord = 0,
              front = "<b>Q</b>",
              back = "A",
              buttonCount = 4,
              nextReviewTimes = listOf("1m", "10m"),
            )
          ),
      )
    val bytes = serializeDueBatch(batch)
    // The wire format must stay zstd(protobuf): if it silently reverts to raw JSON/gzip the phone
    // and watch would talk past each other. zstd magic number = 28 B5 2F FD.
    val magic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    assertEquals(magic.toList(), bytes.take(4))
    val restored = deserializeDueBatch(bytes)
    assertEquals(9L, restored.deckId)
    assertEquals("文法", restored.deckName)
    assertEquals("<b>Q</b>", restored.cards[0].front)
    assertEquals(listOf("1m", "10m"), restored.cards[0].nextReviewTimes)
  }

  @Test
  fun formatQueueEta_usesPaceWhenKnownElseFallback() {
    // An empty/clear queue reads as caught up regardless of pace.
    assertEquals("All caught up 🎉", formatQueueEta(0, null))
    // No observed pace -> flat 10s/card estimate (Long? maps Rust Option<i64>).
    assertEquals("~30s to finish", formatQueueEta(3, null))
    // Real average answer time (8s/card) drives the estimate: 20 cards -> 160s -> ~3 min.
    assertEquals("~3 min to finish", formatQueueEta(20, 8000L))
  }

  @Test
  fun `a graded card leaves the remaining batch`() {
    val batch =
      WearosDueBatch(
        deckId = 5,
        deckName = "Deck",
        cards =
          listOf(
            WearosCard(
              id = 1,
              noteId = 10,
              ord = 0,
              front = "Q1",
              back = "A1",
              buttonCount = 4,
              nextReviewTimes = listOf("1m", "10m", "4d", "7d"),
            ),
            WearosCard(
              id = 2,
              noteId = 20,
              ord = 0,
              front = "Q2",
              back = "A2",
              buttonCount = 4,
              nextReviewTimes = listOf("1m", "10m", "4d", "7d"),
            ),
          ),
      )
    val session = ReviewSession(batch)

    // No schedule config, so the "4d" Good label graduates card 1 out of the session.
    session.answerCard(3, 1000L)

    val remaining = session.remainingBatch()
    assertEquals(1, remaining.cards.size)
    assertEquals(2L, remaining.cards[0].id)
  }
}
