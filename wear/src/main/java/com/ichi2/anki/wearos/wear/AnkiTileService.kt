package com.ichi2.anki.wearos.wear

import android.content.Context
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class AnkiTileService : TileService() {
  override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> {
    val count = getDueCount(this)
    val textLayout =
      LayoutElementBuilders.Box.Builder()
        .addContent(
          Text.Builder(this, "Due: $count")
            .setTypography(Typography.TYPOGRAPHY_TITLE2)
            .setColor(argb(0xFF66BB6A.toInt()))
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
          Text.Builder(this, "AnkiDroid WearOS")
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .build()
        )
        .setContent(textLayout)
        .build()

    val tile =
      Tile.Builder()
        .setResourcesVersion("1")
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
