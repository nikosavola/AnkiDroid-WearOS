@file:SuppressLint("RestrictedApi") // Remote Compose is LIBRARY_GROUP-restricted while in alpha.

package fi.nikosavola.ankidroid.wearos.wear

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteText
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.wearos_core.poolDeckSummaries

/** Wear OS 7 widget (small and large) with the cards due across the decks stored on the watch. */
@AssociateWithGlanceWearWidget(DueCardsWidget::class)
class DueCardsWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = DueCardsWidget()
}

class DueCardsWidget : GlanceWearWidget() {
  override suspend fun provideWidgetData(
    context: Context,
    params: WearWidgetParams,
  ): WearWidgetData = dueCardsDocument(params, loadSnapshot(), timeFormatter(context))

  // Called on the main thread; the summary hits SQLite through the FFI.
  private suspend fun loadSnapshot(): DueWidgetSnapshot =
    withContext(Dispatchers.IO) {
      try {
        val nowMs = systemWatchClock.nowMs()
        dueWidgetSnapshot(poolDeckSummaries(nowMs, utcOffsetSecs(nowMs)))
      } catch (e: Exception) {
        Log.w(TAG, "Widget summary failed: ${e.message}")
        DueWidgetSnapshot.NoDecks
      }
    }

  companion object {
    private const val TAG = "DueCardsWidget"

    // Process-lifetime scope so the update outlives the onStop that asked for it.
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire-and-forget [update], for callers whose process stays alive (the activity). */
    fun requestUpdate(context: Context) {
      val appContext = context.applicationContext
      updateScope.launch { update(appContext) }
    }

    /** Best-effort push of fresh content to every added instance of the widget. */
    suspend fun update(context: Context) {
      try {
        DueCardsWidget().triggerUpdateAll(context.applicationContext)
      } catch (e: CancellationException) {
        throw e
      } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
      ) {
        // Looking up active widgets needs the optional wear-sdk library; without it (older
        // watches, Robolectric) this is a NoClassDefFoundError rather than an Exception.
        Log.w(TAG, "Widget update request failed: ${e.message}")
      }
    }
  }
}

internal fun timeFormatter(context: Context): (Long) -> String {
  val format = DateFormat.getTimeFormat(context)
  return { ms -> format.format(Date(ms)) }
}

/** Builds the widget document; shared by the live widget, previews and tests. */
internal fun dueCardsDocument(
  params: WearWidgetParams,
  snapshot: DueWidgetSnapshot,
  formatTime: (Long) -> String,
): WearWidgetData {
  val large = params.containerType == ContainerInfo.CONTAINER_TYPE_LARGE
  val deckRows = if (large) deckRowsFor(params) else 0
  val later = laterLine(snapshot, formatTime)
  return WearWidgetDocument(
    background = WearWidgetBrush.color(RemoteColorScheme().surfaceContainer)
  ) {
    RemoteMaterialTheme {
      if (large) {
        LargeDueContent(snapshot, later, deckRows)
      } else {
        SmallDueContent(snapshot, later)
      }
    }
  }
}

// Rows of about 20dp under a ~40dp header, from 1 on the smallest large container up to 3.
private fun deckRowsFor(params: WearWidgetParams): Int =
  ((params.heightDp - 2 * params.verticalPaddingDp - 40f) / 20f).toInt().coerceIn(1, WIDGET_MAX_DECKS)

private fun openAppIntent(context: Context, deckId: Long?): PendingIntent {
  val intent =
    Intent(context, MainActivity::class.java).apply {
      if (deckId != null) putExtra(MainActivity.EXTRA_OPEN_DECK_ID, deckId)
    }
  // PendingIntents differing only in extras are the same intent, so each deck needs its own code.
  val requestCode = deckId?.let { REQUEST_OPEN_DECK_BASE + it.hashCode() } ?: REQUEST_OPEN_APP
  return PendingIntent.getActivity(
    context,
    requestCode,
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

private const val REQUEST_OPEN_APP = 7100
private const val REQUEST_OPEN_DECK_BASE = 7200

@Composable private fun openAppAction(): Action = pendingIntentAction { openAppIntent(it, null) }

@Composable
private fun openDeckAction(deckId: Long): Action = pendingIntentAction {
  openAppIntent(it, deckId)
}

@RemoteComposable
@Composable
private fun SmallDueContent(snapshot: DueWidgetSnapshot, later: String?) {
  val colors = RemoteMaterialTheme.colorScheme
  val type = RemoteMaterialTheme.typography
  RemoteRow(
    modifier = RemoteModifier.fillMaxSize().clickable(openAppAction()),
    horizontalArrangement = RemoteArrangement.SpaceBetween,
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    if (snapshot.dueNow > 0) {
      RemoteColumn(modifier = RemoteModifier.weight(1f)) {
        RemoteText(
          snapshot.dueNow.toString().rs,
          style = type.numeralSmall,
          color = colors.primary,
          maxLines = 1,
        )
        RemoteText(
          dueLabel(snapshot.dueNow).rs,
          style = type.labelSmall,
          color = colors.onSurfaceVariant,
          maxLines = 1,
        )
      }
      ReviewPill()
    } else {
      EmptyState(snapshot, later)
    }
  }
}

@RemoteComposable
@Composable
private fun ReviewPill() {
  val colors = RemoteMaterialTheme.colorScheme
  // Drawn rather than clip()+background(): the 1.6 widget renderer ignores rounded clips.
  val pill = RemotePaint().apply { color = colors.primary }
  RemoteBox(
    modifier =
      RemoteModifier.drawWithContent {
          val radius = size.height * 0.5f
          drawRoundRect(paint = pill, cornerRadius = RemoteOffset(radius, radius))
          drawContent()
        }
        .padding(horizontal = 12.rdp, vertical = 8.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      "Review".rs,
      style = RemoteMaterialTheme.typography.labelMedium,
      color = colors.onPrimary,
      maxLines = 1,
    )
  }
}

@RemoteComposable
@Composable
private fun EmptyState(snapshot: DueWidgetSnapshot, later: String?) {
  val colors = RemoteMaterialTheme.colorScheme
  RemoteColumn(
    modifier = RemoteModifier.fillMaxWidth(),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    RemoteText(
      emptyTitle(snapshot).rs,
      style = RemoteMaterialTheme.typography.titleSmall,
      color = colors.onSurface,
      maxLines = 1,
    )
    if (later != null) {
      RemoteText(
        later.rs,
        style = RemoteMaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@RemoteComposable
@Composable
private fun LargeDueContent(snapshot: DueWidgetSnapshot, later: String?, deckRows: Int) {
  val colors = RemoteMaterialTheme.colorScheme
  val type = RemoteMaterialTheme.typography
  if (snapshot.dueNow == 0) {
    RemoteBox(
      modifier = RemoteModifier.fillMaxSize().clickable(openAppAction()),
      contentAlignment = RemoteAlignment.Center,
    ) {
      EmptyState(snapshot, later)
    }
    return
  }
  // Header and deck rows are sibling tap targets; a clickable parent swallows the rows' taps.
  RemoteColumn(
    modifier = RemoteModifier.fillMaxSize(),
    verticalArrangement = RemoteArrangement.Center,
  ) {
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth().clickable(openAppAction()),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      RemoteText(
        snapshot.dueNow.toString().rs,
        style = type.numeralSmall,
        color = colors.primary,
        maxLines = 1,
        modifier = RemoteModifier.padding(end = 8.rdp),
      )
      RemoteColumn(modifier = RemoteModifier.weight(1f)) {
        RemoteText(
          dueLabel(snapshot.dueNow).rs,
          style = type.labelMedium,
          color = colors.onSurface,
          maxLines = 1,
        )
        if (later != null) {
          RemoteText(
            later.rs,
            style = type.bodyExtraSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    for (deck in snapshot.topDecks.take(deckRows)) {
      DeckRow(deck)
    }
  }
}

@RemoteComposable
@Composable
private fun DeckRow(deck: WidgetDeck) {
  val colors = RemoteMaterialTheme.colorScheme
  val type = RemoteMaterialTheme.typography
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth().padding(top = 4.rdp).clickable(openDeckAction(deck.id)),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteText(
      deck.name.rs,
      style = type.bodySmall,
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = RemoteModifier.weight(1f),
    )
    RemoteText(
      deck.dueNow.toString().rs,
      style = type.labelSmall,
      color = colors.primary,
      maxLines = 1,
      modifier = RemoteModifier.padding(start = 6.rdp),
    )
  }
}
