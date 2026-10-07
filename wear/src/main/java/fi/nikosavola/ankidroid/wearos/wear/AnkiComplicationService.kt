package fi.nikosavola.ankidroid.wearos.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.wearos_core.poolDeckSummaries

/** Watch-face complication showing the due-card count, same source of truth as [AnkiTileService]. */
class AnkiComplicationService : SuspendingComplicationDataSourceService() {
  override fun getPreviewData(type: ComplicationType): ComplicationData? {
    if (type != ComplicationType.SHORT_TEXT) return null
    return buildComplicationData(count = 7)
  }

  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    if (request.complicationType != ComplicationType.SHORT_TEXT) return null
    // The framework calls this suspend fn on the main dispatcher; summarising the pool hits SQLite
    // via FFI, so it must not run there.
    return buildComplicationData(getDueCount())
  }

  private suspend fun getDueCount(): Int =
    withContext(Dispatchers.IO) {
      try {
        val nowMs = systemWatchClock.nowMs()
        poolDeckSummaries(nowMs, utcOffsetSecs(nowMs)).sumOf { it.dueNow }
      } catch (e: Exception) {
        0
      }
    }

  private fun buildComplicationData(count: Int): ComplicationData {
    val (text, description) = dueCountComplicationText(count)
    return ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text).build(),
        contentDescription = PlainComplicationText.Builder(description).build(),
      )
      .setTapAction(launchAppPendingIntent())
      .build()
  }

  private fun launchAppPendingIntent(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
}
