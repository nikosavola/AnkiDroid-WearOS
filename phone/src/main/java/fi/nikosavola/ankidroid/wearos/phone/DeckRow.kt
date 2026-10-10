package fi.nikosavola.ankidroid.wearos.phone

/** A node in the deck hierarchy parsed from Anki `::`-separated deck names. */
class DeckNode(val name: String) {
  var deckId: DeckId? = null
  val children = sortedMapOf<String, DeckNode>()
}

/** Build a folder tree from flat `parent::child` deck names. */
internal fun buildDeckTree(decks: List<Pair<DeckId, String>>): DeckNode {
  val root = DeckNode("")
  for ((id, fullName) in decks) {
    var node = root
    for (segment in fullName.split("::")) {
      node = node.children.getOrPut(segment) { DeckNode(segment) }
    }
    node.deckId = id
  }
  return root
}

/** Collect every real deck id at or below [node]. */
internal fun collectDeckIds(node: DeckNode, out: MutableList<DeckId>) {
  node.deckId?.let { out.add(it) }
  for (child in node.children.values) collectDeckIds(child, out)
}

/** Every real deck id at or below this node — an idiomatic `buildList` wrapper. */
internal fun DeckNode.deckIds(): List<DeckId> = buildList { collectDeckIds(this@deckIds, this) }

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
