package fi.nikosavola.ankidroid.wearos.wear

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
import java.util.concurrent.TimeUnit
import uniffi.wearos_core.averageAnswerTimeMs
import uniffi.wearos_core.formatQueueEta
import uniffi.wearos_core.poolDeckSummaries

// Material3TileService owns the MaterialScope, dynamic theming and resource plumbing, so the layout
// below is written directly against the scope it hands us.
class AnkiTileService : Material3TileService() {
  override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
    val count = getDueCount()
    val caughtUp = count == 0

    // Make the whole tile tappable: open the app straight into the review flow.
    val launchApp =
      ModifiersBuilders.Clickable.Builder()
        .setId("open_ankidroid_wear")
        .setOnClick(
          ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
              ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName)
                .setClassName(MainActivity::class.java.name)
                .build()
            )
            .build()
        )
        .build()

    // Material 3 Expressive tile: primaryLayout with a labeled title slot and a stacked main slot.
    val rootLayout =
      primaryLayout(
        titleSlot = { text("AnkiDroid WearOS".layoutString, typography = Typography.LABEL_SMALL) },
        onClick = launchApp,
        mainSlot = {
          LayoutElementBuilders.Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .addContent(
              text(
                count.toString().layoutString,
                typography = Typography.DISPLAY_LARGE,
                color = if (caughtUp) colorScheme.onSurfaceVariant else colorScheme.primary,
              )
            )
            .addContent(
              text(
                (if (count == 1) "card due today" else "cards due today").layoutString,
                typography = Typography.BODY_MEDIUM,
              )
            )
            .addContent(
              text(
                etaText(count).layoutString,
                typography = Typography.BODY_SMALL,
                color = colorScheme.onSurfaceVariant,
              )
            )
            .build()
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

  private fun getDueCount(): Int {
    return try {
      val nowMs = systemWatchClock.nowMs()
      poolDeckSummaries(nowMs, utcOffsetSecs(nowMs)).sumOf { it.dueNow }
    } catch (e: Exception) {
      0
    }
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
