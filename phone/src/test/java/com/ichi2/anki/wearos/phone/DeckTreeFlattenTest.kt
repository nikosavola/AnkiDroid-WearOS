package com.ichi2.anki.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DeckTreeFlattenTest {
  private val root =
    buildDeckTree(
      listOf(
        DeckId(1L) to "Japani",
        DeckId(2L) to "Japani::Kaishi",
        DeckId(3L) to "Japani::文法::N2",
        DeckId(4L) to "Fysiikka",
      )
    )

  @Test
  fun collapsedFolders_showOnlyTopLevel() {
    val rows = flattenDeckTree(root, expanded = emptySet())
    assertEquals(listOf("Fysiikka", "Japani"), rows.map { it.path })
    assertEquals(listOf(0, 1), rows.map { it.index })
    assertEquals(listOf(2, 2), rows.map { it.siblingCount })
  }

  @Test
  fun expandedFolder_insertsChildrenAfterIt_withDepthAndSiblingPlacement() {
    val rows = flattenDeckTree(root, expanded = setOf("Japani"))
    assertEquals(listOf("Fysiikka", "Japani", "Japani::Kaishi", "Japani::文法"), rows.map { it.path })
    val kaishi = rows[2]
    assertEquals(1, kaishi.depth)
    assertEquals(0, kaishi.index)
    assertEquals(2, kaishi.siblingCount)
    // A folder row carries every deck id below it, for the tri-state checkbox.
    assertEquals(setOf(DeckId(1L), DeckId(2L), DeckId(3L)), rows[1].deckIds.toSet())
  }

  @Test
  fun expandAll_opensNestedFolders_soSearchMatchesCantHide() {
    val rows = flattenDeckTree(root, expanded = emptySet(), expandAll = true)
    assertEquals("Japani::文法::N2", rows.last().path)
    assertEquals(2, rows.last().depth)
  }

  @Test
  fun folderPaths_coverTheWholeBranch() {
    val japani = flattenDeckTree(root, expanded = emptySet()).single { it.path == "Japani" }
    assertEquals(listOf("Japani", "Japani::文法"), japani.folderPaths())
  }

  @Test
  fun deckSearch_quotesAndEscapesLikeAnki() {
    assertEquals("\"deck:Japani::Kaishi 1.5k\"", deckSearch("Japani::Kaishi 1.5k"))
    assertEquals(""""deck:a\*b\_c\"d\\e"""", deckSearch("""a*b_c"d\e"""))
  }
}
