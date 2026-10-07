package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
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
        Text(
          text = session.getDeckName(),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )
      }
      item {
        Card(
          onClick = {},
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
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
      item {
        Button(
          onClick = { pushScreen(WatchScreen.Review) },
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
        ) {
          Text("Start Review")
        }
      }
      // Study new cards ahead of the daily queue limit. Only available when we know the deck id.
      if (activeDeckId != null) {
        newCardControlItems(pickingNewCount, transformSpec) { pickingNewCount = it }
      }
      item {
        SwitchButton(
          checked = ttsEnabled,
          onCheckedChange = {
            ttsEnabled = it
            WatchSettings.setTtsEnabled(activity, it)
          },
          label = { Text("Voice Reviews") },
          secondaryLabel = { Text(if (ttsEnabled) "Speaker On 🔊" else "Speaker Off 🔇") },
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
