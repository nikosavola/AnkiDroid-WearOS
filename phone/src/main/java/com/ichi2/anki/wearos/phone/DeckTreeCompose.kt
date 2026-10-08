package com.ichi2.anki.wearos.phone

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * The deck tree as lazy items, so it scrolls with the rest of the page instead of fighting a nested
 * scroller for height. Folder rows spring-rotate their chevron; rows animate in and out with
 * [LazyItemScope.animateItem].
 */
fun LazyListScope.deckTreeItems(
  rows: List<DeckRow>,
  isExpanded: (String) -> Boolean,
  onToggleFolder: (String) -> Unit,
  checkedOf: (DeckId) -> Boolean,
  onDeckChecked: (DeckId, Boolean) -> Unit,
  onFolderChecked: (DeckRow, Boolean) -> Unit,
  dueCountOf: (DeckRow) -> Int?,
  itemModifier: Modifier = Modifier,
) {
  items(rows, key = { "deck:${it.path}" }) { row ->
    val modifier = itemModifier.animateItem()
    if (row.isFolder) {
      FolderRow(
        row,
        isExpanded(row.path),
        { onToggleFolder(row.path) },
        checkedOf,
        onFolderChecked,
        dueCountOf(row),
        modifier,
      )
    } else {
      DeckLeafRow(row, checkedOf, onDeckChecked, dueCountOf(row), modifier)
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
  modifier: Modifier,
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
    shapes = ListItemDefaults.segmentedShapes(row.index, row.siblingCount),
    colors = segmentColors(),
    modifier = modifier.fillMaxWidth().padding(start = (row.depth * 16).dp),
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
  modifier: Modifier,
) {
  val id = row.node.deckId ?: return
  SegmentedListItem(
    checked = checkedOf(id),
    onCheckedChange = { onDeckChecked(id, it) },
    shapes = ListItemDefaults.segmentedShapes(row.index, row.siblingCount),
    colors = segmentColors(),
    modifier = modifier.fillMaxWidth().padding(start = (row.depth * 16).dp),
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
