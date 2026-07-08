package fi.nikosavola.ankidroid.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DeckTreeTest {
  @Test
  fun buildDeckTree_nestsBySeparator() {
    val root =
      buildDeckTree(
        listOf(1L to "Japani", 2L to "Japani::Kaishi", 3L to "Japani::文法::N2", 4L to "Fysiikka")
      )
    // Top level: Fysiikka, Japani (sortedMap => alphabetical).
    assertEquals(setOf("Fysiikka", "Japani"), root.children.keys)
    val japani = root.children["Japani"]!!
    assertEquals(1L, japani.deckId) // parent deck keeps its own id
    assertEquals(setOf("Kaishi", "文法"), japani.children.keys)
    // 文法 is an intermediate folder with no own id, but a nested N2 leaf.
    val bunpou = japani.children["文法"]!!
    assertEquals(null, bunpou.deckId)
    assertEquals(3L, bunpou.children["N2"]!!.deckId)
  }

  @Test
  fun deckIds_gathersWholeSubtree() {
    val root = buildDeckTree(listOf(1L to "A", 2L to "A::B", 3L to "A::B::C", 4L to "Other"))
    assertEquals(setOf(1L, 2L, 3L), root.children["A"]!!.deckIds().toSet())
  }
}
