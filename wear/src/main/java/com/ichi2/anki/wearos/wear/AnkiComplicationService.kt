package com.ichi2.anki.wearos.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Watch-face complication showing the due-card count, same source of truth as [AnkiTileService]. */
class AnkiComplicationService : SuspendingComplicationDataSourceService() {
  override fun getPreviewData(type: ComplicationType): ComplicationData? {
    if (type != ComplicationType.SHORT_TEXT) return null
    return buildComplicationData(count = 7)
  }

  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    if (request.complicationType != ComplicationType.SHORT_TEXT) return null
    return buildComplicationData(getDueCount(this))
  }

  private fun getDueCount(context: Context): Int =
    try {
      DeckCache.totalDue(context)
    } catch (e: Exception) {
      0
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
