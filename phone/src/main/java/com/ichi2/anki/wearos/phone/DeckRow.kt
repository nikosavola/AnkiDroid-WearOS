package com.ichi2.anki.wearos.phone

/**
 * One visible row of the deck tree. [index]/[siblingCount] place it within its sibling group so the
 * segmented shapes still read as one connected card per level.
 */
data class DeckRow(
  val node: DeckNode,
  val path: String,
  val depth: Int,
  val index: Int,
  val siblingCount: Int,
  val deckIds: List<DeckId>,
) {
  val isFolder: Boolean
    get() = node.children.isNotEmpty()
}

/**
 * Flatten [root] into the rows a lazy list shows: every top-level node, plus the children of each
 * folder whose path is in [expanded]. With [expandAll] (an active search) every folder is open, so a
 * match nested under a collapsed folder can't hide.
 */
fun flattenDeckTree(root: DeckNode, expanded: Set<String>, expandAll: Boolean = false): List<DeckRow> =
  buildList {
    fun walk(node: DeckNode, parentPath: String?, depth: Int) {
      val children = node.children.values.toList()
      children.forEachIndexed { index, child ->
        val path = if (parentPath == null) child.name else "$parentPath::${child.name}"
        add(DeckRow(child, path, depth, index, children.size, child.deckIds()))
        if (child.children.isNotEmpty() && (expandAll || path in expanded)) {
          walk(child, path, depth + 1)
        }
      }
    }
    walk(root, null, 0)
  }

/** Paths of this folder and every folder below it, for expanding or collapsing a whole branch. */
fun DeckRow.folderPaths(): List<String> = buildList {
  fun walk(node: DeckNode, path: String) {
    if (node.children.isEmpty()) return
    add(path)
    for (child in node.children.values) walk(child, "$path::${child.name}")
  }
  walk(node, path)
}

/**
 * Card browser search for a deck and its subdecks, quoted and escaped like Anki's own search writer:
 * `*` and `_` are wildcards in deck names and `"` would end the term.
 */
fun deckSearch(fullName: String): String {
  val escaped = fullName.replace(Regex("""[\\*_"]""")) { "\\" + it.value }
  return "\"deck:$escaped\""
}
