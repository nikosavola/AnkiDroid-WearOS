package fi.nikosavola.ankidroid.wearos.wear

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort push refresh for the tile and complication after something that could change the
 * due-card count (a new batch cached, or decks pruned), instead of waiting out their periodic
 * refresh interval. Both calls cross a Binder boundary that may not exist yet (no tile/complication
 * added to a watch face) or under Robolectric, so failures are swallowed.
 *
 * Not debounced: a full sync pushes up to 15 due batches, each triggering a call here, and both
 * update APIs are system-rate-limited (excess requests are dropped, not queued) - so it's possible
 * for the final, complete-count request to be among the ones dropped, leaving an intermediate count
 * showing until the periodic refresh catches up. A trailing debounce was considered but not built:
 * WearableListenerService instances aren't guaranteed to survive between onDataChanged calls within
 * a burst, so in-instance debounce state (a Handler, a job) isn't reliable, and there's no existing
 * "sync finished" signal from the phone to key a time-based guard off without risking suppressing
 * the last request instead of the earlier ones. Fixing this properly needs a phone->watch
 * sync-complete message, which is a real feature, not a one-line guard.
 */
internal object WearSurfaceUpdater {
  private const val TAG = "WearSurfaceUpdater"
  private const val WIDGET_UPDATE_TIMEOUT_MS = 5_000L

  fun requestRefresh(context: Context) {
    refreshTileAndComplication(context)
    DueCardsWidget.requestUpdate(context)
  }

  /**
   * [requestRefresh] that waits for the widget push, for the sync listener: its process can be
   * killed as soon as it returns, which drops a widget update still in flight.
   */
  suspend fun refresh(context: Context) {
    refreshTileAndComplication(context)
    withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS) { DueCardsWidget.update(context) }
  }

  private fun refreshTileAndComplication(context: Context) {
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
