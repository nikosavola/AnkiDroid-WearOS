package fi.nikosavola.ankidroid.wearos.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import uniffi.wearos_core.averageAnswerTimeMs
import uniffi.wearos_core.formatQueueEta
import uniffi.wearos_core.initDatabase

class AnkiTileService : TileService() {
  override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> {
    val count = getDueCount(this)
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

    val content =
      LayoutElementBuilders.Column.Builder()
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launchApp).build())
        .addContent(
          Text.Builder(this, count.toString())
            .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
            .setColor(argb(if (caughtUp) 0xFF9E9E9E.toInt() else 0xFF66BB6A.toInt()))
            .build()
        )
        .addContent(
          Text.Builder(this, if (count == 1) "card due today" else "cards due today")
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .setColor(argb(0xFFFFFFFF.toInt()))
            .build()
        )
        .addContent(
          Text.Builder(this, etaText(this, count))
            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
            .setColor(argb(0xFF9E9E9E.toInt()))
            .build()
        )
        .build()

    // Manually bridge the legacy Tiles DeviceParameters to the modern ProtoLayout DeviceParameters
    val adkParams = requestParams.deviceParameters!!
    val protoDeviceParams =
      DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp(adkParams.screenWidthDp)
        .setScreenHeightDp(adkParams.screenHeightDp)
        .setScreenShape(adkParams.screenShape)
        .build()

    val rootLayout =
      PrimaryLayout.Builder(protoDeviceParams)
        .setPrimaryLabelTextContent(
          Text.Builder(this, "AnkiDroid Wear")
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .setColor(argb(0xFF66BB6A.toInt()))
            .build()
        )
        .setContent(content)
        .build()

    val tile =
      Tile.Builder()
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

    return ImmediateFuture(tile)
  }

  override fun onTileResourcesRequest(
    requestParams: ResourcesRequest
  ): ListenableFuture<ResourceBuilders.Resources> {
    val resources = ResourceBuilders.Resources.Builder().setVersion("1").build()
    return ImmediateFuture(resources)
  }

  private fun getDueCount(context: Context): Int {
    return try {
      DeckCache.totalDue(context)
    } catch (e: Exception) {
      0
    }
  }

  /**
   * Estimated time to clear the queue. The Rust core formats it from the user's real average answer
   * time (the mean of the locally-buffered reviews) when we have any, and falls back to a flat
   * per-card estimate otherwise. Reading the buffer requires the Rust DB be initialized; the Tile
   * process may not have gone through MainActivity, so point the core at the shared buffer first.
   */
  private fun etaText(context: Context, count: Int): String {
    val avgMs =
      try {
        initDatabase(File(context.filesDir, BUFFER_DB_NAME).absolutePath)
        averageAnswerTimeMs()
      } catch (e: Exception) {
        null
      }
    return formatQueueEta(count, avgMs)
  }

  companion object {
    // Must match the buffer file MainActivity initializes, so both read the same answer history.
    private const val BUFFER_DB_NAME = "wearos_buffer.db"
  }

  private fun argb(color: Int): androidx.wear.protolayout.ColorBuilders.ColorProp {
    return androidx.wear.protolayout.ColorBuilders.ColorProp.Builder(color).build()
  }

  /**
   * A lightweight, self-contained implementation of ImmediateFuture to avoid importing heavy Guava
   * library.
   */
  private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor) {
      executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = true

    override fun get(): T = value

    override fun get(timeout: Long, unit: TimeUnit): T = value
  }
}
