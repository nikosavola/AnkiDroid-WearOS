package com.ichi2.anki.wearos.phone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// A folder's 24dp chevron plus its 4dp gap.
private val ChevronSlot = 28.dp

/**
 * What a long-press on a row offers. [allExpanded] is null while a search forces every folder open,
 * which hides the expand/collapse entry.
 */
class DeckRowMenu(
  val onStudy: (DeckId) -> Unit = {},
  val onBrowse: (String) -> Unit = {},
  val onSyncOnly: (DeckRow) -> Unit = {},
  val allExpanded: ((DeckRow) -> Boolean)? = null,
  val onExpandAll: (DeckRow, Boolean) -> Unit = { _, _ -> },
)

/**
 * The deck tree as lazy items, so it scrolls with the rest of the page instead of fighting a nested
 * scroller for height. Folder rows spring-rotate their chevron; rows animate in and out with
 * [LazyItemScope.animateItem]. A long-press opens the row's [DeckRowMenu].
 */
fun LazyListScope.deckTreeItems(
  rows: List<DeckRow>,
  isExpanded: (String) -> Boolean,
  onToggleFolder: (String) -> Unit,
  checkedOf: (DeckId) -> Boolean,
  onDeckChecked: (DeckId, Boolean) -> Unit,
  onFolderChecked: (DeckRow, Boolean) -> Unit,
  dueCountOf: (DeckRow) -> Int?,
  menu: DeckRowMenu = DeckRowMenu(),
  itemModifier: Modifier = Modifier,
) {
  items(rows, key = { "deck:${it.path}" }) { row ->
    var menuOpen by remember { mutableStateOf(false) }
    Box(itemModifier.animateItem().fillMaxWidth().padding(start = (row.depth * 16).dp)) {
      if (row.isFolder) {
        FolderRow(
          row,
          isExpanded(row.path),
          { onToggleFolder(row.path) },
          checkedOf,
          onFolderChecked,
          dueCountOf(row),
          onLongClick = { menuOpen = true },
        )
      } else {
        DeckLeafRow(row, checkedOf, onDeckChecked, dueCountOf(row), onLongClick = { menuOpen = true })
      }
      DeckRowMenuPopup(row, menu, menuOpen, onDismiss = { menuOpen = false })
    }
  }
}

@Composable
private fun DeckRowMenuPopup(row: DeckRow, menu: DeckRowMenu, open: Boolean, onDismiss: () -> Unit) {
  fun pick(action: () -> Unit) {
    onDismiss()
    action()
  }
  DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
    // A folder built from search matches alone has no deck of its own to study.
    row.node.deckId?.let { id ->
      DropdownMenuItem(text = { Text("Study in AnkiDroid") }, onClick = { pick { menu.onStudy(id) } })
    }
    DropdownMenuItem(text = { Text("Browse cards") }, onClick = { pick { menu.onBrowse(row.path) } })
    HorizontalDivider()
    DropdownMenuItem(
      text = { Text(if (row.isFolder) "Sync only this folder" else "Sync only this deck") },
      onClick = { pick { menu.onSyncOnly(row) } },
    )
    val allExpanded = menu.allExpanded
    if (row.isFolder && allExpanded != null) {
      val expanded = allExpanded(row)
      DropdownMenuItem(
        text = { Text(if (expanded) "Collapse all" else "Expand all") },
        onClick = { pick { menu.onExpandAll(row, !expanded) } },
      )
    }
  }
}

@Composable
private fun FolderRow(
  row: DeckRow,
  expanded: Boolean,
  onToggle: () -> Unit,
  checkedOf: (DeckId) -> Boolean,
  onFolderChecked: (DeckRow, Boolean) -> Unit,
  due: Int?,
  onLongClick: () -> Unit,
) {
  val chevron by
    animateFloatAsState(
      targetValue = if (expanded) 180f else 0f,
      animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
      label = "chevron",
    )
  val allChecked = row.deckIds.isNotEmpty() && row.deckIds.all { checkedOf(it) }
  val checkState =
    when {
      allChecked -> ToggleableState.On
      row.deckIds.any { checkedOf(it) } -> ToggleableState.Indeterminate
      else -> ToggleableState.Off
    }
  SegmentedListItem(
    onClick = onToggle,
    onLongClick = onLongClick,
    onLongClickLabel = "More options",
    shapes = ListItemDefaults.segmentedShapes(row.index, row.siblingCount),
    colors = segmentColors(),
    modifier = Modifier.fillMaxWidth(),
    leadingContent = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          painter = painterResource(id = R.drawable.ic_expand_more),
          contentDescription =
            if (expanded) "Collapse ${row.node.name}" else "Expand ${row.node.name}",
          modifier = Modifier.size(24.dp).rotate(chevron),
        )
        Spacer(Modifier.width(4.dp))
        // A folder that is only partly selected shows the indeterminate dash; a tap still
        // selects everything, or clears everything when it already is.
        TriStateCheckbox(state = checkState, onClick = { onFolderChecked(row, !allChecked) })
        Icon(
          painter = painterResource(id = R.drawable.ic_folder),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp).size(20.dp),
        )
      }
    },
    trailingContent = { if (due != null) DuePill(due) },
  ) {
    Text(
      text = row.node.name,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun DeckLeafRow(
  row: DeckRow,
  checkedOf: (DeckId) -> Boolean,
  onDeckChecked: (DeckId, Boolean) -> Unit,
  due: Int?,
  onLongClick: () -> Unit,
) {
  val id = row.node.deckId ?: return
  SegmentedListItem(
    checked = checkedOf(id),
    onCheckedChange = { onDeckChecked(id, it) },
    onLongClick = onLongClick,
    onLongClickLabel = "More options",
    shapes = ListItemDefaults.segmentedShapes(row.index, row.siblingCount),
    colors = segmentColors(),
    modifier = Modifier.fillMaxWidth(),
    leadingContent = {
      // Pad inside the row so it shares its left edge with sibling folders while the checkbox
      // still lines up with theirs.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(ChevronSlot))
        Checkbox(checked = checkedOf(id), onCheckedChange = null)
      }
    },
    trailingContent = { if (due != null) DuePill(due) },
  ) {
    Text(text = row.node.name, style = MaterialTheme.typography.bodyLarge)
  }
}

/** A rounded due-count pill shown at the trailing edge of a deck/folder row. */
@Composable
private fun DuePill(count: Int) {
  val zero = count == 0
  Surface(
    shape = RoundedCornerShape(percent = 50),
    color =
      if (zero) {
        MaterialTheme.colorScheme.surfaceVariant
      } else {
        MaterialTheme.colorScheme.secondaryContainer
      },
  ) {
    Text(
      text = "$count due",
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color =
        if (zero) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.onSecondaryContainer
        },
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

/** Unselected segments sit on a container too, so a group reads as one connected card. */
@Composable
private fun segmentColors() =
  ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
