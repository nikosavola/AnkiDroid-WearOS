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
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.Text
import uniffi.wearos_core.*

@Composable
internal fun MainActivity.WelcomeScreen() {
  val listState = rememberTransformingLazyColumnState()
  val transformSpec = rememberEdgeMorphSpec()
  // Memoize on the underlying deck state, not on a freshly-built list: vm.deckEntries() returns a new
  // List each call, so keying the remembers on it would defeat them and re-run the deckChildren FFI
  // + the maps on every recomposition. Now they recompute only when the decks/folder actually change.
  val entries = remember(vm.cachedDecks, vm.knownDecks) { vm.deckEntries() }
  val countById = remember(entries) { entries.associate { it.id to it.cardCount } }
  // Resolve one folder level from the flat deck list in Rust; card counts stay a UI concern here.
  val level =
    remember(entries, deckFolderPath) {
      deckChildren(entries.map { DeckInfo(it.id, it.name) }, deckFolderPath)
    }
  // Reset scroll to the top whenever we descend into or pop out of a folder.
  LaunchedEffect(deckFolderPath) { listState.scrollToItem(0) }

  val title =
    if (deckFolderPath.isEmpty()) "AnkiDroid WearOS" else deckFolderPath.substringAfterLast("::")

  ScreenScaffold(scrollState = listState) { contentPadding ->
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
        if (deckFolderPath.isNotEmpty()) {
          item {
            CompactButton(
              onClick = { deckFolderPath = parentFolder(deckFolderPath) },
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

        // Studying the folder itself (Anki parent decks are real decks).
        level.ownDeck?.let { own ->
          item {
            DeckCard(
              label = "Study ${own.name}",
              cardCount = countById[own.id],
              transformation = SurfaceTransformation(transformSpec),
              modifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec),
              onClick = { selectDeck(own.id) },
            )
          }
        }

        items(level.children) { node ->
          val transformation = SurfaceTransformation(transformSpec)
          val cardModifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec)
          if (node.isFolder) {
            FolderCard(
              name = node.name,
              transformation = transformation,
              modifier = cardModifier,
              onClick = { deckFolderPath = node.path },
            )
          } else {
            DeckCard(
              label = node.name,
              cardCount = countById[node.id],
              transformation = transformation,
              modifier = cardModifier,
              onClick = { selectDeck(node.id) },
            )
          }
        }
      }

      item {
        CompactButton(
          onClick = { showSettings = true },
          colors = ButtonDefaults.filledTonalButtonColors(),
          modifier = Modifier.fillMaxWidth(0.7f),
        ) {
          Text("Settings")
        }
      }

      item { Spacer(modifier = Modifier.height(8.dp)) }
      item {
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
      }
    }
  }
}
