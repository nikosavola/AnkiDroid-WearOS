package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import uniffi.wearos_core.SessionSummary

/**
 * The "you finished the queue" screen: a congrats header, an optional session recap (reviews/ease
 * split/time/streak), the outstanding-answers count, optional learn-new-cards controls, and an edge
 * button that syncs + returns to the deck list. Top-level and stateless - all state and actions are
 * passed in - so it renders in tests. MainActivity keeps a thin FinishedScreen(session) wrapper that
 * computes the summary and wires the callbacks.
 *
 * [newCardControls] lets the Activity inject its learn-new-cards list items (which need the
 * new-card request plumbing) without this screen depending on them; null hides them.
 */
@Composable
fun FinishedScreenContent(
  summary: SessionSummary,
  reviewedToday: Int,
  outstandingCount: Int,
  transformSpec: TransformationSpec,
  onSyncAndBack: () -> Unit,
  learningLater: Int = 0,
  learningDueInMinutes: Long? = null,
  newCardControls: (TransformingLazyColumnScope.() -> Unit)? = null,
) {
  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      item {
        Text(
          text = "Nice Job! 🎉",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
      item {
        Text(
          text = "You finished your study queue for now.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      // Session recap: how many reviews (relearned cards count more than once), ease split, time.
      if (summary.total > 0) {
        item {
          Card(
            onClick = {},
            transformation = SurfaceTransformation(transformSpec),
            modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              StatNumeral(
                label = "Reviews",
                value = "${summary.total}",
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = "in ${formatDuration(summary.totalTimeMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text =
                  "Again ${summary.again} · Hard ${summary.hard} · " +
                    "Good ${summary.good} · Easy ${summary.easy}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              if (reviewedToday > 0) {
                Text(
                  text = "Today: $reviewedToday 🔥",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFFFFA726),
                )
              }
            }
          }
        }
      }
      item {
        Text(
          text = "Outstanding reviews: $outstandingCount",
          style = MaterialTheme.typography.labelSmall,
          color = Color.LightGray,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
      // Learning cards that come due later today: finishing the due queue isn't the end of the deck.
      if (learningLater > 0 && learningDueInMinutes != null) {
        item {
          Text(
            text =
              pluralStringResource(
                R.plurals.learning_cards_due_in_minutes,
                learningLater,
                learningLater,
                learningDueInMinutes,
              ),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF66BB6A),
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }
      }
      newCardControls?.invoke(this)
      // Material 3 expressive edge button hugging the bottom bezel: sync answers and go back.
      item {
        EdgeButton(onClick = onSyncAndBack, buttonSize = EdgeButtonSize.Medium) {
          Icon(
            painter = painterResource(id = R.drawable.ic_check),
            contentDescription = "Sync and back to decks",
          )
        }
      }
    }
  }
}
