package fi.nikosavola.ankidroid.wearos.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.wearos_core.averageAnswerTimeMs
import uniffi.wearos_core.formatQueueEta
import uniffi.wearos_core.poolDeckSummaries
import uniffi.wearos_core.reviewsSince

/** Watch-face complication showing the due-card count, same source of truth as [AnkiTileService]. */
class AnkiComplicationService : SuspendingComplicationDataSourceService() {
  private data class DueState(val due: Int, val reviewedToday: Int, val eta: String)

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    build(type, DueState(due = 12, reviewedToday = 28, eta = "~6 min to finish"))

  // The framework calls this suspend fn on the main dispatcher; summarising the pool hits SQLite via
  // FFI, so it must not run there.
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
    build(request.complicationType, withContext(Dispatchers.IO) { loadState() })

  private fun loadState(): DueState {
    val due =
      try {
        val nowMs = systemWatchClock.nowMs()
        poolDeckSummaries(nowMs, utcOffsetSecs(nowMs)).sumOf { it.dueNow }
      } catch (e: Exception) {
        0
      }
    val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    val reviewed = runCatching { reviewsSince(midnight) }.getOrDefault(0)
    val avgMs = runCatching { averageAnswerTimeMs() }.getOrNull()
    return DueState(due, reviewed, if (due > 0) formatQueueEta(due, avgMs) else "")
  }

  private fun build(type: ComplicationType, state: DueState): ComplicationData? {
    val (shortText, description) = dueCountComplicationText(state.due)
    val contentDescription = PlainComplicationText.Builder(description).build()
    val icon =
      MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_cards)).build()
    val (progress, progressMax) = todayProgressRange(state.reviewedToday, state.due)
    return when (type) {
      ComplicationType.SHORT_TEXT ->
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(shortText).build(),
            contentDescription = contentDescription,
          )
          .setMonochromaticImage(icon)
          .setTapAction(launchAppPendingIntent())
          .build()
      ComplicationType.LONG_TEXT ->
        LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(dueLongText(state.due, state.eta)).build(),
            contentDescription = contentDescription,
          )
          .setMonochromaticImage(icon)
          .setTapAction(launchAppPendingIntent())
          .build()
      ComplicationType.RANGED_VALUE ->
        RangedValueComplicationData.Builder(
            value = progress,
            min = 0f,
            max = progressMax,
            contentDescription =
              PlainComplicationText.Builder(
                  "$description, ${state.reviewedToday} reviewed today"
                )
                .build(),
          )
          .setText(PlainComplicationText.Builder(shortText).build())
          .setMonochromaticImage(icon)
          .setTapAction(launchAppPendingIntent())
          .build()
      else -> null
    }
  }

  private fun launchAppPendingIntent(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
}
