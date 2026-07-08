package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background sync of due decks to the watch, scheduled by [AutoSync] via WorkManager. Runs
 * the shared [DeckSync] routine with no UI. WorkManager handles Doze/app-standby deferral and
 * re-schedules the periodic work across reboots, so no BootReceiver is needed.
 */
class AutoSyncWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
    try {
      val summary = DeckSync.run(applicationContext)
      Log.i(TAG, "Auto-sync: ${summary?.message() ?: "nothing to sync"}")
      Result.success()
    } catch (e: Exception) {
      // Transient (Bluetooth down, provider busy): let WorkManager retry with backoff.
      Log.e(TAG, "Auto-sync failed; will retry: ${e.message}", e)
      Result.retry()
    }

  companion object {
    private const val TAG = "AutoSyncWorker"
  }
}
