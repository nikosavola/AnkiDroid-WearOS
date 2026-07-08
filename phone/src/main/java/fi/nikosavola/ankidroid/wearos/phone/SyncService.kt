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
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uniffi.wearos_core.serializeDueBatch

/**
 * Foreground service that pushes the selected decks to the watch. Runs independently of the
 * Activity (survives backgrounding) and reports progress both as a notification and via a local
 * broadcast the Activity listens to. The sync is user-initiated and short, so a foreground service
 * fits better than WorkManager.
 */
class SyncService : Service() {
  companion object {
    private const val TAG = "PhoneSyncService"
    private const val CHANNEL_ID = "sync"
    private const val NOTIF_ID = 1001

    private const val PATH_DUE_BATCH = "/due_batch"
    private const val KEY_BATCH_ASSET = "batch_asset"

    private const val SETTINGS_PREFS = "settings_prefs"
    private const val KEY_FORCE_MOCK = "force_mock"
    private const val KEY_PREPUSH_CAP = "prepush_cap"
    private const val DEFAULT_PREPUSH_DECKS = 15
    private const val MAX_SCAN_DECKS = 60

    // Local broadcast the Activity uses to mirror progress in-app while it's alive.
    const val ACTION_PROGRESS = "fi.nikosavola.ankidroid.wearos.SYNC_PROGRESS"
    const val EXTRA_TEXT = "text"
    const val EXTRA_PERCENT = "percent"
    const val EXTRA_DONE = "done"
    const val EXTRA_ERROR = "error"

    // One sync at a time; a second start while running is ignored.
    private val running = AtomicBoolean(false)
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
    val bridge = AnkiDroidBridge(applicationContext)
    val renderer = CardBitmapRenderer(applicationContext)
    val settings = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    val deckPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    val isForced = settings.getBoolean(KEY_FORCE_MOCK, !bridge.isAnkiDroidAvailable())
    val prePushCap = settings.getInt(KEY_PREPUSH_CAP, DEFAULT_PREPUSH_DECKS)
    val dataClient = Wearable.getDataClient(this)

    progress("Syncing deck list…", 5)

    val decks = bridge.fetchAllDecks()
    val filteredDecks = decks.filter { deckPrefs.getBoolean("deck_${it.first}", true) }
    val decksStr = filteredDecks.joinToString("\n") { "${it.first}:${it.second}" }
    dataClient
      .putDataItem(
        PutDataMapRequest.create("/decks")
          .apply {
            dataMap.putByteArray("decks_bytes", decksStr.toByteArray(Charsets.UTF_8))
            setUrgent()
          }
          .asPutDataRequest()
      )
      .await()

    progress("Fetching due cards…", 15)

    var pushed = 0
    var totalCards = 0
    var scanned = 0
    for ((deckId, deckName) in filteredDecks) {
      if (pushed >= prePushCap || scanned >= MAX_SCAN_DECKS) break
      scanned++
      val batch = bridge.fetchDueBatch(deckId = deckId, limit = 20, forceMock = isForced)
      if (batch.cards.isEmpty()) continue

      val cleanedBatch =
        batch.copy(
          cards =
            batch.cards.map {
              it.copy(
                front = AnkiDroidBridge.stripBase64Images(it.front),
                back = AnkiDroidBridge.stripBase64Images(it.back),
              )
            }
        )
      val bytes = serializeDueBatch(cleanedBatch)
      dataClient
        .putDataItem(
          PutDataMapRequest.create("$PATH_DUE_BATCH/$deckId")
            .apply {
              // serializeDueBatch already gzips; ship the bytes straight into the Asset.
              dataMap.putAsset(KEY_BATCH_ASSET, Asset.createFromBytes(bytes))
              dataMap.putLong("synced_at", System.currentTimeMillis())
              setUrgent()
            }
            .asPutDataRequest()
        )
        .await()

      // WebView-backed rendering needs the main thread.
      withContext(Dispatchers.Main) { renderer.syncBitmapsForBatch(batch) }

      pushed++
      totalCards += batch.cards.size
      val percent = 15 + 80 * pushed / prePushCap
      progress("Copying $deckName… ($pushed decks, $totalCards cards)", percent.coerceAtMost(95))
      Log.d(TAG, "Pushed deck $deckId ('$deckName') with ${batch.cards.size} cards.")
    }

    val remaining = filteredDecks.size - scanned
    val extra = if (remaining > 0) " Others download on demand." else ""
    val summary = "Done — $pushed decks, $totalCards cards.$extra"
    publish(summary, 100, done = true, error = false)
    notify(buildNotification(summary, 100, indeterminate = false, ongoing = false))
    Log.i(TAG, "Synced $pushed decks / $totalCards cards to watch.$extra")
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
      .apply { if (ongoing) setProgress(100, percent, indeterminate) else setProgress(0, 0, false) }
      .build()
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
