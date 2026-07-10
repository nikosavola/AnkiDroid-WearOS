package fi.nikosavola.ankidroid.wearos.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service for the *user-initiated* sync that pushes the selected decks to the watch.
 * Runs independently of the Activity (survives backgrounding) and reports progress both as a
 * notification and via a local broadcast the Activity listens to. The recurring background sync
 * lives in [AutoSyncWorker] instead; both share the actual work in [DeckSync].
 */
class SyncService : Service() {
  companion object {
    private const val TAG = "PhoneSyncService"
    private const val CHANNEL_ID = "sync"
    private const val NOTIF_ID = 1001

    // Local broadcast the Activity uses to mirror progress in-app while it's alive.
    const val ACTION_PROGRESS = "fi.nikosavola.ankidroid.wearos.SYNC_PROGRESS"
    const val EXTRA_TEXT = "text"
    const val EXTRA_PERCENT = "percent"
    const val EXTRA_DONE = "done"
    const val EXTRA_ERROR = "error"

    // One sync at a time; a second start while running is ignored.
    private val running = AtomicBoolean(false)

    /**
     * Progress percent while pushing decks: setup work occupies 0–15%, then each of the
     * [prePushCap] decks advances the bar up to 95% (the final "done" step sets 100). Extracted as
     * a pure function so it can be unit-tested without a running service. Guards a zero/negative
     * cap.
     */
    fun syncProgressPercent(pushed: Int, prePushCap: Int): Int {
      if (prePushCap <= 0) return 95
      return (15 + 80 * pushed / prePushCap).coerceIn(15, 95)
    }
  }

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must call startForeground within a few seconds of start; post the notification first.
    createChannel()
    startForegroundCompat(buildNotification("Starting sync…", 0, indeterminate = true))

    if (!running.compareAndSet(false, true)) {
      Log.d(TAG, "Sync already running; ignoring extra start.")
      return START_NOT_STICKY
    }

    scope.launch {
      try {
        runSync()
      } catch (e: Exception) {
        Log.e(TAG, "Sync failed: ${e.message}", e)
        publish("Sync failed: ${e.message}", 0, done = true, error = true)
        notify(buildNotification("Sync failed", 0, indeterminate = false, ongoing = false))
      } finally {
        running.set(false)
        ServiceCompat.stopForeground(this@SyncService, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  private suspend fun runSync() {
    // The actual push lives in DeckSync (shared with the background AutoSyncWorker); this service
    // just surfaces its progress as a notification + in-app broadcast.
    val summary = DeckSync.run(applicationContext) { text, percent -> progress(text, percent) }
    val summaryText =
      summary?.message() ?: "Nothing to sync — AnkiDroid not reachable and mock mode off."
    publish(summaryText, 100, done = true, error = false)
    notify(buildNotification(summaryText, 100, indeterminate = false, ongoing = false))
    Log.i(TAG, summaryText)
  }

  /** Update both the notification and the in-app broadcast for a mid-sync step. */
  private fun progress(text: String, percent: Int) {
    notify(buildNotification(text, percent, indeterminate = false, ongoing = true))
    publish(text, percent, done = false, error = false)
  }

  private fun publish(text: String, percent: Int, done: Boolean, error: Boolean) {
    sendBroadcast(
      Intent(ACTION_PROGRESS).apply {
        setPackage(packageName)
        putExtra(EXTRA_TEXT, text)
        putExtra(EXTRA_PERCENT, percent)
        putExtra(EXTRA_DONE, done)
        putExtra(EXTRA_ERROR, error)
      }
    )
  }

  private fun buildNotification(
    text: String,
    percent: Int,
    indeterminate: Boolean,
    ongoing: Boolean = true,
  ): Notification {
    val contentIntent =
      android.app.PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Syncing cards to watch")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentIntent(contentIntent)
      .setOngoing(ongoing)
      .setOnlyAlertOnce(true)
      // Classic linear bar for pre-Android-16 shades; ProgressStyle below renders the segmented
      // Android 16 bar on API 36+ and no-ops beneath it, so both are set on the ongoing notification.
      .apply {
        if (ongoing) {
          setProgress(100, percent, indeterminate)
          setStyle(progressStyle(percent, indeterminate))
        } else {
          setProgress(0, 0, false)
        }
      }
      .build()
  }

  /**
   * Android 16 segmented progress bar. The two segments mirror [syncProgressPercent]'s phases -
   * setup (0-15%) then the per-deck push (15-100%) - so the split in the bar lines up with where the
   * work actually is. Renders on API 36+; the NotificationCompat wrapper degrades to nothing below,
   * where the classic [setProgress] bar carries the display.
   */
  private fun progressStyle(percent: Int, indeterminate: Boolean): NotificationCompat.ProgressStyle {
    val style = NotificationCompat.ProgressStyle()
    if (indeterminate) return style.setProgressIndeterminate(true)
    return style
      .setProgressSegments(
        listOf(
          NotificationCompat.ProgressStyle.Segment(15).setColor(0xFF80CBC4.toInt()),
          NotificationCompat.ProgressStyle.Segment(85).setColor(0xFF66BB6A.toInt()),
        )
      )
      .setProgress(percent)
  }

  private fun notify(n: Notification) {
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
  }

  private fun startForegroundCompat(n: Notification) {
    ServiceCompat.startForeground(
      this,
      NOTIF_ID,
      n,
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      },
    )
  }

  private fun createChannel() {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Card sync", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    job.cancel()
  }
}
