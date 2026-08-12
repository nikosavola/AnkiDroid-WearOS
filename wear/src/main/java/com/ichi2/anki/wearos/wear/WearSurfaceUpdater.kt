package com.ichi2.anki.wearos.wear

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Best-effort push refresh for the tile and complication after something that could change the
 * due-card count (a new batch cached, or decks pruned), instead of waiting out their periodic
 * refresh interval. Both calls cross a Binder boundary that may not exist yet (no tile/complication
 * added to a watch face) or under Robolectric, so failures are swallowed.
 */
internal object WearSurfaceUpdater {
  private const val TAG = "WearSurfaceUpdater"

  fun requestRefresh(context: Context) {
    try {
      TileService.getUpdater(context).requestUpdate(AnkiTileService::class.java)
    } catch (e: Exception) {
      Log.w(TAG, "Tile update request failed: ${e.message}")
    }
    try {
      ComplicationDataSourceUpdateRequester.create(
          context,
          ComponentName(context, AnkiComplicationService::class.java),
        )
        .requestUpdateAll()
    } catch (e: Exception) {
      Log.w(TAG, "Complication update request failed: ${e.message}")
    }
  }
}
