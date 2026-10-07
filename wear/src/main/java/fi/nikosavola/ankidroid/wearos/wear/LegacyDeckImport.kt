package fi.nikosavola.ankidroid.wearos.wear

import android.util.Log
import java.io.File
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.poolStoreBatch

private const val TAG = "LegacyDeckImport"
private const val DECK_PREFIX = "deck_"
private const val DECK_SUFFIX = ".json"
private const val LEGACY_FILE = "due_batch.json"

/**
 * One-time migration off the old per-deck json snapshots and into the SQLite card pool. Each file
 * that deserializes is stored and then deleted; a file that doesn't is left in place (a retry on a
 * later start is harmless) and logged. The DB must already be initialised.
 */
internal fun importLegacyDeckFiles(filesDir: File) {
  val files = filesDir.listFiles { f -> isLegacyDeckFile(f.name) } ?: return

  for (file in files) {
    try {
      poolStoreBatch(deserializeDueBatch(file.readBytes()))
      file.delete()
    } catch (e: Exception) {
      Log.w(TAG, "Skipping unreadable deck cache ${file.name}: ${e.message}")
    }
  }
}

private fun isLegacyDeckFile(name: String): Boolean =
  name == LEGACY_FILE || name.startsWith(DECK_PREFIX) && name.endsWith(DECK_SUFFIX)
