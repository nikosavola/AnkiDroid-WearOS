package fi.nikosavola.ankidroid.provider

/** Restricts Anki browser searches to [deckIds] (exact decks; list subdecks explicitly). */
class DeckSelector(deckIds: Collection<Long>) {
  init {
    require(deckIds.isNotEmpty()) { "DeckSelector needs at least one deck id" }
  }

  // Kept last and parenthesized: on AnkiDroid 2.25.1 a search that starts with did: ignores every
  // term after it (verified on-device).
  private val term = "(did:${deckIds.joinToString(",")})"

  /** [terms] limited to the selected decks. */
  fun scope(terms: String): String = if (terms.isBlank()) term else "$terms $term"

  override fun toString(): String = term
}
