package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.Text
import uniffi.wearos_core.*

@Composable
internal fun MainActivity.DeckScreen(session: ReviewSession) {
  val activity = this
  var pickingNewCount by remember { mutableStateOf(false) }
  val listState = rememberTransformingLazyColumnState()
  val transformSpec = rememberEdgeMorphSpec()
  ScreenScaffold(
    scrollState = listState,
    edgeButton = {
      EdgeButton(onClick = { clearSession() }, buttonSize = EdgeButtonSize.Medium) {
        Text("Change Deck")
      }
    },
  ) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item {
        Text(
          text = "Deck stats",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 2.dp),
        )
      }
      item {
        // Leaf name only, capped: a long path at a large font size pushed the stats off screen.
        Text(
          text = deckLeafName(session.getDeckName()),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )
      }
      item {
        DeckStatsCard(
          session,
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
        )
      }
      item {
        Button(
          onClick = {
            requestReviewNotificationsIfNeeded()
            pushScreen(WatchScreen.Review)
          },
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
        ) {
          Text("Start Review")
        }
      }
      // Study new cards ahead of the daily queue limit. Only available when we know the deck id.
      if (activeDeckId != null) {
        newCardControlItems(
          picking = pickingNewCount,
          transformSpec = transformSpec,
          onRequestNewCards = { n -> activeDeckId?.let { requestNewCardsFromPhone(it, n) } },
          setPicking = { pickingNewCount = it },
        )
      }
      item {
        SwitchButton(
          checked = ttsEnabled,
          onCheckedChange = {
            ttsEnabled = it
            WatchSettings.setTtsEnabled(activity, it)
          },
          label = { Text("Voice Reviews") },
          secondaryLabel = { Text(if (ttsEnabled) "Speaker on" else "Speaker off") },
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
        )
      }
      if (outstandingAnswersCount > 0) {
        item {
          Button(
            onClick = { triggerAnswersSync() },
            colors = ButtonDefaults.filledTonalButtonColors(),
            transformation = SurfaceTransformation(transformSpec),
            modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
          ) {
            Text("Sync $outstandingAnswersCount Reviews")
          }
        }
        if (outstandingOldestSecs >= 86_400) {
          item {
            Text(
              text = "⚠ Oldest unsynced ${humanAge(outstandingOldestSecs)} ago",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 12.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MainActivity.DeckStatsCard(
  session: ReviewSession,
  transformation: SurfaceTransformation,
  modifier: Modifier,
) {
  val statsDescription =
    "${session.getTotalCards()} due" +
      (if (reviewedToday > 0) ", $reviewedToday reviewed today" else "") +
      ", progress ${session.getProgressString()}"
  // Wear's Card is always clickable; replacing its semantics stops TalkBack announcing a button
  // that does nothing.
  Card(
    onClick = {},
    transformation = transformation,
    modifier = modifier.clearAndSetSemantics { contentDescription = statsDescription },
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        StatNumeral(
          label = "Due",
          value = "${session.getTotalCards()}",
          color = MaterialTheme.colorScheme.primary,
        )
        if (reviewedToday > 0) {
          Spacer(modifier = Modifier.width(32.dp))
          StatNumeral(
            label = "Today",
            value = "$reviewedToday",
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }
      Text(
        text = "Progress ${session.getProgressString()}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * "Learn new cards" chip that expands into a 5/10/20 chooser; shared by Deck and Finished.
 * [onRequestNewCards] receives the picked count; the caller owns the request plumbing.
 */
internal fun TransformingLazyColumnScope.newCardControlItems(
  picking: Boolean,
  transformSpec: TransformationSpec,
  onRequestNewCards: (count: Int) -> Unit,
  setPicking: (Boolean) -> Unit,
) {
  if (!picking) {
    item {
      Button(
        onClick = { setPicking(true) },
        colors = ButtonDefaults.filledVariantButtonColors(),
        transformation = SurfaceTransformation(transformSpec),
        modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
      ) {
        Text("Learn new cards")
      }
    }
  } else {
    item {
      Text(
        text = "How many new?",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.transformedHeight(this, transformSpec),
      )
    }
    item {
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.transformedHeight(this, transformSpec),
      ) {
        listOf(5, 10, 20).forEach { n ->
          CompactButton(
            onClick = {
              setPicking(false)
              onRequestNewCards(n)
            },
            colors = ButtonDefaults.filledVariantButtonColors(),
            modifier = Modifier.width(56.dp),
          ) {
            Text("$n")
          }
        }
      }
    }
    item {
      CompactButton(
        onClick = { setPicking(false) },
        colors = ButtonDefaults.filledTonalButtonColors(),
        transformation = SurfaceTransformation(transformSpec),
        modifier = Modifier.transformedHeight(this, transformSpec),
      ) {
        Text("Cancel")
      }
    }
  }
}
