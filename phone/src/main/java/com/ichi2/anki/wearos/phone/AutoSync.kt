package com.ichi2.anki.wearos.phone

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
  const val DEFAULT_ENABLED = true

  // WorkManager clamps periodic intervals to a 15-minute minimum; 6h is a sensible review cadence.
  private const val INTERVAL_HOURS = 6L

  fun isEnabled(context: Context): Boolean =
    context
      .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
      .getBoolean(KEY_AUTO_SYNC, DEFAULT_ENABLED)

  /** Persist the preference and (un)schedule accordingly. */
  fun setEnabled(context: Context, enabled: Boolean) {
    context
      .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_AUTO_SYNC, enabled)
      .apply()
    if (enabled) schedule(context) else cancel(context)
  }

  /** Apply the persisted preference — call on app start. */
  fun applyFromSettings(context: Context) {
    if (isEnabled(context)) schedule(context) else cancel(context)
  }

  private fun schedule(context: Context) {
    // Don't drain a low battery for a convenience sync; no network constraint since the Wearable
    // Data Layer rides Bluetooth/companion, not the internet.
    val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
    val request =
      PeriodicWorkRequestBuilder<AutoSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context)
      .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  private fun cancel(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
  }
}
