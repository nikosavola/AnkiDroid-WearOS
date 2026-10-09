package com.ichi2.anki.wearos.wear

/** What the long-press menu is open for: one deck row, or a folder (with its pooled deck ids). */
internal sealed interface RowMenuTarget {
  data class Deck(val id: Long, val name: String) : RowMenuTarget

  data class Folder(val name: String, val deckIds: List<Long>) : RowMenuTarget
}
