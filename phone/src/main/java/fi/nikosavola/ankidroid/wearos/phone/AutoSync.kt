package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Scheduling for the recurring background deck sync ([AutoSyncWorker]).
 *
 * WorkManager is the right tool here (not a foreground Service): the work is deferrable, periodic,
 * survives reboots, and respects Doze/app-standby. The user-initiated "Sync now" button keeps using
 * the foreground [SyncService]; both share [DeckSync].
 *
 * The enabled flag lives in the same settings store the rest of the app uses, so [MainActivity]'s
 * Settings dialog toggles it. Scheduling is `KEEP`-unique, so re-applying on every app launch does
 * not reset an already-running schedule.
 */
object AutoSync {
  const val WORK_NAME = "auto_deck_sync"
  private const val SETTINGS_PREFS = "settings_prefs"
  private const val KEY_AUTO_SYNC = "auto_sync"
  private const val KEY_INTERVAL_HOURS = "auto_sync_interval_hours"
  const val DEFAULT_ENABLED = true

  // WorkManager clamps periodic intervals to a 15-minute minimum; keep the user's choice
  // comfortably
  // above that. 6h is a sensible default review cadence.
  const val DEFAULT_INTERVAL_HOURS = 6
  const val MIN_INTERVAL_HOURS = 1
  const val MAX_INTERVAL_HOURS = 24

  private fun prefs(context: Context) =
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

  fun isEnabled(context: Context): Boolean =
    prefs(context).getBoolean(KEY_AUTO_SYNC, DEFAULT_ENABLED)

  fun intervalHours(context: Context): Int =
    prefs(context)
      .getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
      .coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)

  /**
   * Persist the enabled flag + interval and reschedule so a changed interval takes effect
   * immediately (UPDATE keeps the same unique job but swaps its spec). Call this from Settings.
   */
  fun apply(context: Context, enabled: Boolean, intervalHours: Int) {
    prefs(context)
      .edit()
      .putBoolean(KEY_AUTO_SYNC, enabled)
      .putInt(KEY_INTERVAL_HOURS, intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS))
      .apply()
    if (enabled) schedule(context, ExistingPeriodicWorkPolicy.UPDATE) else cancel(context)
  }

  /**
   * Apply the persisted preference on app start. Uses KEEP so relaunching doesn't reset the timer
   * of an already-scheduled sync.
   */
  fun applyFromSettings(context: Context) {
    if (isEnabled(context)) schedule(context, ExistingPeriodicWorkPolicy.KEEP) else cancel(context)
  }

  private fun schedule(context: Context, policy: ExistingPeriodicWorkPolicy) {
    // Don't drain a low battery for a convenience sync; no network constraint since the Wearable
    // Data Layer rides Bluetooth/companion, not the internet.
    val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
    val request =
      PeriodicWorkRequestBuilder<AutoSyncWorker>(intervalHours(context).toLong(), TimeUnit.HOURS)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
  }

  private fun cancel(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
  }
}
