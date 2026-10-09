package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * The long-press menu for a deck or folder row: [title] over one full-width button per action,
 * in the app's standard Wear M3 dialog. Presentational (actions passed in) so the picker wiring
 * stays in WelcomeScreen and the menu itself renders under a Robolectric test.
 */
@Composable
fun DeckRowMenu(title: String, actions: List<Pair<String, () -> Unit>>, onDismissRequest: () -> Unit) {
  AlertDialog(
    visible = true,
    onDismissRequest = onDismissRequest,
    confirmButton = {},
    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
  ) {
    actions.forEachIndexed { index, (label, action) ->
      item(index) {
        CompactButton(
          onClick = action,
          colors =
            if (label.startsWith("Remove")) {
              ButtonDefaults.filledVariantButtonColors()
            } else {
              ButtonDefaults.filledTonalButtonColors()
            },
          modifier = Modifier.fillMaxWidth(0.8f),
        ) {
          Text(label)
        }
      }
    }
  }
}

/**
 * The picker's open long-press menu, wired to the Activity's pool actions. Null [target] means
 * no menu is showing; every action dismisses first through [onDismiss].
 */
@Composable
internal fun MainActivity.DeckPickerRowMenu(
  target: RowMenuTarget?,
  onDismiss: () -> Unit,
  onRefreshDeck: (Long) -> Unit,
  onRefreshDecks: (List<Long>) -> Unit,
  onRemoveDecks: (List<Long>) -> Unit,
) {
  when (target) {
    null -> Unit
    is RowMenuTarget.Deck ->
      DeckRowMenu(
        title = target.name,
        actions =
          listOf(
            "Refresh from phone" to {
              onDismiss()
              onRefreshDeck(target.id)
            },
            "Remove from watch" to {
              onDismiss()
              onRemoveDecks(listOf(target.id))
            },
          ),
        onDismissRequest = onDismiss,
      )
    is RowMenuTarget.Folder ->
      DeckRowMenu(
        title = target.name,
        actions =
          listOf(
            "Refresh ${target.deckIds.size} decks" to {
              onDismiss()
              onRefreshDecks(target.deckIds)
            },
            "Remove ${target.deckIds.size} decks" to {
              onDismiss()
              onRemoveDecks(target.deckIds)
            },
          ),
        onDismissRequest = onDismiss,
      )
  }
}
