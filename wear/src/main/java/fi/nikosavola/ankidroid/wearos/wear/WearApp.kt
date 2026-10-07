package fi.nikosavola.ankidroid.wearos.wear

import android.app.Application
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.wearos_core.initDatabase

// Shared by the Activity, tile and sync service so every component opens the same answer buffer.
internal const val BUFFER_DB_NAME = "wearos_buffer.db"

/**
 * Process entry point: initialises the Rust core's SQLite database once (the tile, complication and
 * sync service may run without the Activity) and, on a background thread, folds any pre-pool
 * `deck_<id>.json` snapshots into the card pool.
 */
class WearApp : Application() {
  companion object {
    private const val TAG = "WearApp"
  }

  // Process-lifetime scope for the one-time migration. Application has no onTerminate in production,
  // so there is nothing to cancel it against.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    try {
      initDatabase(File(filesDir, BUFFER_DB_NAME).absolutePath)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Rust database: ${e.message}", e)
    }
    scope.launch { importLegacyDeckFiles(filesDir) }
  }
}
