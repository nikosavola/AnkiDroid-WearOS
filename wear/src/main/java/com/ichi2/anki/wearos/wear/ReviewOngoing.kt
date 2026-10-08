package com.ichi2.anki.wearos.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import java.util.concurrent.TimeUnit

/**
 * The ongoing activity shown while a review session is open, so the watch face and recents can take
 * the user back into it after a wrist-down. A plain ongoing notification, not a foreground service:
 * nothing runs once the user leaves, the session just waits in the Activity.
 */
internal object ReviewOngoing {
  private const val TAG = "ReviewOngoing"
  private const val CHANNEL_ID = "review_session"
  private const val NOTIFICATION_ID = 7001
  // Distinct from the complication's tap intent (request code 0); PendingIntent identity ignores
  // extras, so a shared code would let this one rewrite the complication's tap.
  private const val REQUEST_CODE = 7001
  // A chip orphaned by process death shouldn't linger for the rest of the day.
  private val TIMEOUT_MS = TimeUnit.MINUTES.toMillis(45)

  // Covers both the API 33+ runtime permission and a user who turned the app's notifications off.
  fun canPost(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

  /** Post or refresh the ongoing activity for [deckId] with [progress] (e.g. "12/40"). */
  fun show(context: Context, deckId: Long, deckName: String, progress: String) {
    if (!canPost(context)) return
    try {
      ensureChannel(context)
      val touch =
        PendingIntent.getActivity(
          context,
          REQUEST_CODE,
          Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Reopens the deck if the process died; a live session for the same deck is kept.
            .putExtra(MainActivity.EXTRA_OPEN_DECK_ID, deckId),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_cards)
          .setContentTitle(deckName)
          .setContentText("Reviewing $progress")
          .setCategory(NotificationCompat.CATEGORY_PROGRESS)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
          .setContentIntent(touch)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setSilent(true)
          .setTimeoutAfter(TIMEOUT_MS)
      val status =
        Status.Builder()
          .addTemplate("#deck# #progress#")
          .addPart("deck", Status.TextPart(deckName))
          .addPart("progress", Status.TextPart(progress))
          .build()
      OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
        .setStaticIcon(R.drawable.ic_cards)
        .setTouchIntent(touch)
        .setStatus(status)
        .build()
        .apply(context)
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    } catch (e: SecurityException) {
      Log.w(TAG, "Notification permission revoked: ${e.message}")
    }
  }

  fun hide(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }

  private fun ensureChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(
          CHANNEL_ID,
          context.getString(R.string.review_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply { description = context.getString(R.string.review_channel_description) }
    )
  }
}
