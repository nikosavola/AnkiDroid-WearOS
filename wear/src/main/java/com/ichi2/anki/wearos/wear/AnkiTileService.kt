package com.ichi2.anki.wearos.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.protolayout.material3.textEdgeButton
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.wearos_core.averageAnswerTimeMs
import uniffi.wearos_core.formatQueueEta

// Material3TileService owns the MaterialScope, dynamic theming and resource plumbing, so the layout
// below is written directly against the scope it hands us.
class AnkiTileService : Material3TileService() {
  /** What the tile shows, read off the main thread before the layout is built. */
  private data class TileData(
    val dueCount: Int,
    val eta: String,
    // Most-due deck as (id, short name), the target of the bottom edge button.
    val topDeck: Pair<Long, String>?,
    // Nothing pooled and no deck list from the phone yet: the phone app has never synced.
    val neverSynced: Boolean,
  )

  override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
    // The framework calls this on the main thread; the pool and buffer are SQLite behind FFI.
    val data = withContext(Dispatchers.IO) { loadTileData() }

    val rootLayout =
      primaryLayout(
        titleSlot = { text(getString(R.string.app_name).layoutString, typography = Typography.LABEL_SMALL) },
        onClick = launchClickable("open_ankidroid_wear", deckId = null),
        mainSlot = {
          val column =
            LayoutElementBuilders.Column.Builder()
              .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
          if (data.neverSynced) {
            column
              .addContent(text("Not synced yet".layoutString, typography = Typography.TITLE_MEDIUM))
              .addContent(
                text(
                  "Open AnkiDroid WearOS on your phone and sync".layoutString,
                  typography = Typography.BODY_SMALL,
                  color = colorScheme.onSurfaceVariant,
                  maxLines = 3,
                )
              )
          } else {
            val caughtUp = data.dueCount == 0
            column
              .addContent(
                text(
                  data.dueCount.toString().layoutString,
                  typography = Typography.DISPLAY_LARGE,
                  color = if (caughtUp) colorScheme.onSurfaceVariant else colorScheme.primary,
                )
              )
              .addContent(
                text(
                  (if (data.dueCount == 1) "card due today" else "cards due today").layoutString,
                  typography = Typography.BODY_MEDIUM,
                )
              )
              .addContent(
                text(
                  data.eta.layoutString,
                  typography = Typography.BODY_SMALL,
                  color = colorScheme.onSurfaceVariant,
                )
              )
          }
          column.build()
        },
        bottomSlot =
          data.topDeck?.let { (deckId, name) ->
            {
              textEdgeButton(onClick = launchClickable("open_deck_$deckId", deckId)) {
                text(name.layoutString, maxLines = 1)
              }
            }
          },
      )

    return Tile.Builder()
      .setResourcesVersion("1")
      // Ask the system to re-request the tile periodically so the due count/ETA don't go stale
      // while the tile sits on the carousel (it otherwise only rebuilds on explicit refresh).
      .setFreshnessIntervalMillis(TimeUnit.MINUTES.toMillis(30))
      .setTileTimeline(
        TimelineBuilders.Timeline.Builder()
          .addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder()
              .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(rootLayout).build())
              .build()
          )
          .build()
      )
      .build()
  }

  /** Opens the app, straight into [deckId]'s review when given. Ids must be unique per tile. */
  private fun launchClickable(id: String, deckId: Long?): ModifiersBuilders.Clickable {
    val activity =
      ActionBuilders.AndroidActivity.Builder()
        .setPackageName(packageName)
        .setClassName(MainActivity::class.java.name)
    if (deckId != null) {
      activity.addKeyToExtraMapping(
        MainActivity.EXTRA_OPEN_DECK_ID,
        ActionBuilders.AndroidLongExtra.Builder().setValue(deckId).build(),
      )
    }
    return ModifiersBuilders.Clickable.Builder()
      .setId(id)
      .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
      .build()
  }

  private fun loadTileData(): TileData {
    val summaries =
      try {
        poolSummariesNow()
      } catch (e: Exception) {
        emptyList()
      }
    val count = summaries.sumOf { it.dueNow }
    val top = summaries.filter { it.dueNow > 0 }.maxByOrNull { it.dueNow }
    return TileData(
      dueCount = count,
      eta = etaText(count),
      topDeck = top?.let { it.deckId to it.name.substringAfterLast("::") },
      neverSynced = summaries.isEmpty() && loadKnownDecks(filesDir).isEmpty(),
    )
  }

  /**
   * Estimated time to clear the queue. The Rust core formats it from the user's real average answer
   * time (the mean of the locally-buffered reviews) when we have any, and falls back to a flat
   * per-card estimate otherwise. WearApp.onCreate has already pointed the core at the shared buffer.
   */
  private fun etaText(count: Int): String {
    val avgMs =
      try {
        averageAnswerTimeMs()
      } catch (e: Exception) {
        null
      }
    return formatQueueEta(count, avgMs)
  }
}
