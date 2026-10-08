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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    private const val SUCCESS_TIMEOUT_MS = 10 * 60 * 1000L
    private const val FAILURE_TIMEOUT_MS = 60 * 60 * 1000L

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
  private var syncJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must call startForeground within a few seconds of start; post the notification first.
    createChannel()
    startForegroundCompat(buildNotification("Starting sync…", 0, indeterminate = true))

    if (!running.compareAndSet(false, true)) {
      Log.d(TAG, "Sync already running; ignoring extra start.")
      return START_NOT_STICKY
    }

    syncJob =
      scope.launch {
        try {
          runSync()
        } catch (e: CancellationException) {
          // onTimeout/onDestroy already reported the stop; don't relabel it as a failure.
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Sync failed: ${e.message}", e)
          finish("Sync failed: ${e.message}", "Sync failed", error = true)
        } finally {
          running.set(false)
          stopSelf()
        }
      }
    return START_NOT_STICKY
  }

  /**
   * dataSync foreground services get a 6h/day budget on targetSdk 35+; when it runs out the system
   * calls this and expects the service to stop within seconds or it crashes the app.
   */
  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(TAG, "dataSync time limit reached; stopping sync.")
    syncJob?.cancel()
    finish(
      "Sync paused: Android's background time limit was reached. Try again later.",
      "Sync paused",
      error = true,
    )
    running.set(false)
    stopSelf()
  }

  private suspend fun runSync() {
    // The actual push lives in DeckSync (shared with the background AutoSyncWorker); this service
    // just surfaces its progress as a notification + in-app broadcast.
    val summary = DeckSync.run(applicationContext) { text, percent -> progress(text, percent) }
    val summaryText =
      summary?.message() ?: "Nothing to sync — AnkiDroid not reachable and mock mode off."
    currentCoroutineContext().ensureActive()
    finish(summaryText, summaryText, error = false)
    Log.i(TAG, summaryText)
  }

  /**
   * Drop the foreground state first, then post the result as an ordinary notification: the
   * timeout and auto-cancel only apply once it is no longer the service's foreground notification.
   */
  private fun finish(inAppText: String, notificationText: String, error: Boolean) {
    publish(inAppText, if (error) 0 else 100, done = true, error = error)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    notify(
      buildNotification(
        notificationText,
        100,
        indeterminate = false,
        ongoing = false,
        timeoutMs = if (error) FAILURE_TIMEOUT_MS else SUCCESS_TIMEOUT_MS,
      )
    )
  }

  /** Update both the notification and the in-app broadcast for a mid-sync step. */
  private fun progress(text: String, percent: Int) {
    // A step finishing after onTimeout must not re-post an ongoing notification nobody clears.
    if (syncJob?.isActive != true) return
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
    timeoutMs: Long? = null,
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
      .setSmallIcon(R.drawable.ic_stat_sync)
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
          setAutoCancel(true)
        }
        timeoutMs?.let { setTimeoutAfter(it) }
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
