package fi.nikosavola.ankidroid.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DeckTreeTest {
  @Test
  fun buildDeckTree_nestsBySeparator() {
    val root =
      buildDeckTree(
        listOf(
          DeckId(1L) to "Japani",
          DeckId(2L) to "Japani::Kaishi",
          DeckId(3L) to "Japani::文法::N2",
          DeckId(4L) to "Fysiikka",
        )
      )
    // Top level: Fysiikka, Japani (sortedMap => alphabetical).
    assertEquals(setOf("Fysiikka", "Japani"), root.children.keys)
    val japani = root.children["Japani"]!!
    assertEquals(DeckId(1L), japani.deckId) // parent deck keeps its own id
    assertEquals(setOf("Kaishi", "文法"), japani.children.keys)
    // 文法 is an intermediate folder with no own id, but a nested N2 leaf.
    val bunpou = japani.children["文法"]!!
    assertEquals(null, bunpou.deckId)
    assertEquals(DeckId(3L), bunpou.children["N2"]!!.deckId)
  }

  @Test
  fun deckIds_gathersWholeSubtree() {
    val root =
      buildDeckTree(
        listOf(DeckId(1L) to "A", DeckId(2L) to "A::B", DeckId(3L) to "A::B::C", DeckId(4L) to "Other")
      )
    assertEquals(setOf(DeckId(1L), DeckId(2L), DeckId(3L)), root.children["A"]!!.deckIds().toSet())
  }
}
