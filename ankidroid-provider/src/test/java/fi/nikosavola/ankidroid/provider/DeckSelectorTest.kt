package fi.nikosavola.ankidroid.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeckSelectorTest {
  @Test
  fun didTermGoesLastAndParenthesized() {
    val decks = DeckSelector(listOf(42L, 43L))

    assertEquals(
      "is:learn -is:suspended (did:42,43)",
      decks.scope("is:learn -is:suspended"),
    )
  }

  @Test
  fun blankTermsGiveTheBareSelector() {
    assertEquals("(did:7)", DeckSelector(listOf(7L)).scope(" "))
  }

  @Test
  fun emptyDeckListIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { DeckSelector(emptyList()) }
  }

  @Test
  fun dueCountsParseOnlyThreeNonNegatives() {
    assertEquals(DueCounts(1, 2, 3), DueCounts.parse("[1,2,3]"))
    assertEquals(null, DueCounts.parse("[1,2]"))
    assertEquals(null, DueCounts.parse("[1,-2,3]"))
    assertEquals(null, DueCounts.parse(null))
  }
}
