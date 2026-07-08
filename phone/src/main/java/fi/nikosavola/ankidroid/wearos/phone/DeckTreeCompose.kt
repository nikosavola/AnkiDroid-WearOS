package fi.nikosavola.ankidroid.wearos.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Material 3 **Expressive** deck tree for the phone — phase-1 of migrating the deck selector off
 * imperative Views. This is the piece the backlog specifically flagged as missing: real motion.
 *
 * Folder rows sit on a tonal surface and carry a chevron that *spring-rotates* on toggle; a folder's
 * children expand/collapse with an animated height + fade instead of the current full-list redraw.
 * State is hoisted (checked lookups + callbacks) so it's host-agnostic and testable; only the
 * per-folder expansion state is kept locally so a toggle animates just that subtree.
 *
 * NOT yet hosted by [MainActivity] (which is still the Views implementation). To adopt: give
 * MainActivity a `ComposeView`/`setContent { AnkiPhoneTheme { DeckSelectionTree(...) } }` for the
 * deck section and delete the imperative `renderDeckTree`/`addDeckNodeViews` path. Reuses the pure
 * `buildDeckTree`/`collectDeckIds` helpers.
 */
@Composable
fun DeckSelectionTree(
  root: DeckNode,
  checkedOf: (Long) -> Boolean,
  onDeckChecked: (Long, Boolean) -> Unit,
  onFolderChecked: (DeckNode, Boolean) -> Unit,
  modifier: Modifier = Modifier,
  dueCountOf: (DeckNode) -> Int? = { null },
) {
  val expanded = remember { mutableStateMapOf<String, Boolean>() }
  Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    for (child in root.children.values) {
      DeckNodeRow(child, child.name, 0, expanded, checkedOf, onDeckChecked, onFolderChecked, dueCountOf)
    }
  }
}

@Composable
private fun DeckNodeRow(
  node: DeckNode,
  path: String,
  depth: Int,
  expanded: MutableMap<String, Boolean>,
  checkedOf: (Long) -> Boolean,
  onDeckChecked: (Long, Boolean) -> Unit,
  onFolderChecked: (DeckNode, Boolean) -> Unit,
  dueCountOf: (DeckNode) -> Int?,
) {
  val indent = (depth * 16).dp
  val due = dueCountOf(node)
  val dueLabel = if (due != null) "  ·  $due due" else ""

  if (node.children.isNotEmpty()) {
    val isExpanded = expanded[path] ?: false
    // Springy chevron rotation — the Expressive motion the Views tree couldn't do.
    val chevron by
      animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chevron",
      )
    val deckIds = remember(node) { node.deckIds() }
    val allChecked = deckIds.isNotEmpty() && deckIds.all { checkedOf(it) }

    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth().clickable { expanded[path] = !isExpanded }.padding(8.dp),
      ) {
        Icon(
          painter = painterResource(id = R.drawable.ic_expand_more),
          contentDescription = if (isExpanded) "Collapse ${node.name}" else "Expand ${node.name}",
          modifier = Modifier.size(24.dp).rotate(chevron),
        )
        Spacer(Modifier.width(4.dp))
        Checkbox(checked = allChecked, onCheckedChange = { onFolderChecked(node, it) })
        Text(
          text = "📁 ${node.name}$dueLabel",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(start = 4.dp),
        )
      }
    }
    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
      exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
    ) {
      Column {
        for (child in node.children.values) {
          DeckNodeRow(
            child,
            "$path::${child.name}",
            depth + 1,
            expanded,
            checkedOf,
            onDeckChecked,
            onFolderChecked,
            dueCountOf,
          )
        }
      }
    }
  } else {
    val id = node.deckId ?: return
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(start = indent + 28.dp, top = 2.dp, bottom = 2.dp),
    ) {
      Checkbox(checked = checkedOf(id), onCheckedChange = { onDeckChecked(id, it) })
      Text(
        text = "${node.name}$dueLabel",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
  }
}

@Preview
@Composable
private fun DeckSelectionTreePreview() {
  val root =
    buildDeckTree(
      listOf(
        1L to "Japani",
        2L to "Japani::Kaishi 1.5k",
        3L to "Japani::文法",
        4L to "Japani::文法::N2",
        5L to "Fysiikka::Electricity",
      )
    )
  val checked = remember { mutableStateMapOf(1L to true, 2L to true) }
  MaterialTheme {
    DeckSelectionTree(
      root = root,
      checkedOf = { checked[it] ?: false },
      onDeckChecked = { id, c -> checked[id] = c },
      onFolderChecked = { node, c -> node.deckIds().forEach { checked[it] = c } },
      dueCountOf = { null },
    )
  }
}
