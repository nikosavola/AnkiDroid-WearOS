package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.Text
import uniffi.wearos_core.*

/** The deck ids pooled under [path] (itself included when it is a deck), for folder menu actions. */
private fun pooledDecksUnder(entries: List<DeckEntry>, path: String): List<Long> =
  entries.filter { it.dueNow != null && (path.isEmpty() || it.name.startsWith("$path::")) }.map { it.id }

/**
 * A deck row's long-press, or null when its pool isn't on the watch: an unpooled deck's tap
 * already downloads it, so a long-press would have nothing else to offer.
 */
private fun deckLongPress(
  id: Long,
  dueNow: Int?,
  name: String,
  open: (RowMenuTarget) -> Unit,
): (() -> Unit)? = dueNow?.let { { open(RowMenuTarget.Deck(id, name)) } }

/**
 * The own-deck row ("Study ...", since Anki parent decks are real decks) and this level's folder
 * and deck children. Every pooled row long-presses into the menu; a folder's menu covers the
 * pooled decks nested under it.
 */
private fun TransformingLazyColumnScope.deckLevelRows(
  level: DeckLevel,
  entries: List<DeckEntry>,
  countById: Map<Long, Int?>,
  transformSpec: TransformationSpec,
  selectDeck: (Long) -> Unit,
  openFolder: (String) -> Unit,
  openMenu: (RowMenuTarget) -> Unit,
) {
  level.ownDeck?.let { own ->
    item {
      DeckCard(
        label = "Study ${own.name}",
        dueNow = countById[own.id],
        transformation = SurfaceTransformation(transformSpec),
        modifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec),
        onClick = { selectDeck(own.id) },
        onLongClick = deckLongPress(own.id, countById[own.id], own.name, openMenu),
      )
    }
  }

  items(level.children) { node ->
    val transformation = SurfaceTransformation(transformSpec)
    val cardModifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec)
    if (node.isFolder) {
      val pooledUnder = remember(entries, node.path) { pooledDecksUnder(entries, node.path) }
      FolderCard(
        name = node.name,
        transformation = transformation,
        modifier = cardModifier,
        onClick = { openFolder(node.path) },
        // An unpooled folder has nothing to refresh or remove, so no menu.
        onLongClick =
          pooledUnder.takeIf { it.isNotEmpty() }?.let { decks ->
            { openMenu(RowMenuTarget.Folder(node.name, decks)) }
          },
      )
    } else {
      DeckCard(
        label = node.name,
        dueNow = countById[node.id],
        transformation = transformation,
        modifier = cardModifier,
        onClick = { selectDeck(node.id) },
        onLongClick = deckLongPress(node.id, countById[node.id], node.name, openMenu),
      )
    }
  }
}

/** The deck picker for one level of the `::` hierarchy; [folderPath] is "" at the root. */
@Composable
internal fun MainActivity.WelcomeScreen(folderPath: String) {
  val listState = rememberTransformingLazyColumnState()
  val transformSpec = rememberEdgeMorphSpec()
  // Memoize on the underlying deck state, not on a freshly-built list: vm.deckEntries() returns a new
  // List each call, so keying the remembers on it would defeat them and re-run the deckChildren FFI
  // + the maps on every recomposition. Now they recompute only when the decks/folder actually change.
  val entries = remember(vm.cachedDecks, vm.knownDecks) { vm.deckEntries() }
  val countById = remember(entries) { entries.associate { it.id to it.dueNow } }
  // Resolve one folder level from the flat deck list in Rust; card counts stay a UI concern here.
  val level =
    remember(entries, folderPath) {
      deckChildren(entries.map { DeckInfo(it.id, it.name) }, folderPath)
    }
  // Reset scroll to the top whenever we descend into or pop out of a folder.
  LaunchedEffect(folderPath) { listState.scrollToItem(0) }

  var rowMenu by remember { mutableStateOf<RowMenuTarget?>(null) }

  val title =
    if (folderPath.isEmpty()) "AnkiDroid WearOS" else folderPath.substringAfterLast("::")

  ScreenScaffold(
    scrollState = listState,
    edgeButton = {
      EdgeButton(
        onClick = {
          requestDecksFromPhone()
          vm.loadDecks()
        },
        buttonSize = EdgeButtonSize.Medium,
      ) {
        Icon(
          painter = painterResource(id = R.drawable.ic_refresh),
          contentDescription = "Refresh decks",
        )
      }
    },
  ) { contentPadding ->
    // M3 Expressive edge-hugging list: TransformingLazyColumn morphs each item container toward the
    // round bezel, so deck cards visibly widen and round as they approach the vertical center and
    // shrink at the top/bottom edges. SurfaceTransformation + transformedHeight drive the morph.
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
      }

      if (entries.isEmpty()) {
        item {
          Text(
            text = "Tap Refresh to load your decks, or open the phone app and press Sync.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            textAlign = TextAlign.Center,
          )
        }
      } else {
        // Up one folder. Only shown when we've descended below the root.
        if (folderPath.isNotEmpty()) {
          item {
            CompactButton(
              onClick = { popBackStackAndRunSideEffects() },
              colors = ButtonDefaults.filledTonalButtonColors(),
              icon = {
                Icon(
                  painter = painterResource(id = R.drawable.ic_arrow_up),
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.fillMaxWidth(0.7f),
            ) {
              Text("Up")
            }
          }
        }

        // Studying the folder itself, then its children; pooled rows long-press into the menu.
        deckLevelRows(
          level = level,
          entries = entries,
          countById = countById,
          transformSpec = transformSpec,
          selectDeck = ::selectDeck,
          openFolder = { pushScreen(WatchScreen.Decks(it)) },
          openMenu = { rowMenu = it },
        )
      }

      item {
        CompactButton(
          onClick = { pushScreen(WatchScreen.Settings) },
          colors = ButtonDefaults.filledTonalButtonColors(),
          icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
          modifier = Modifier.fillMaxWidth(0.7f),
        ) {
          Text("Settings")
        }
      }
    }
    DeckPickerRowMenu(
      target = rowMenu,
      onDismiss = { rowMenu = null },
      onRefreshDeck = { requestDeckFromPhone(it) },
      onRefreshDecks = { refreshDecksSilently(it) },
      onRemoveDecks = { removeDecksFromWatch(it) },
    )
  }
}
