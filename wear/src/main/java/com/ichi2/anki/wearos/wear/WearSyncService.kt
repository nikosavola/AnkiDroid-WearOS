package com.ichi2.anki.wearos.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.initDatabase
import uniffi.wearos_core.removeBufferedAnswers

class WearSyncService : WearableListenerService() {
  companion object {
    private const val TAG = "WatchWearSyncService"
    private const val PATH_DUE_BATCH = "/due_batch"
    private const val KEY_BATCH_BYTES = "batch_bytes"
    private const val PATH_CLEAR_ANSWERS = "/clear_answers"
    private const val PATH_DECKS = "/decks"
    private const val KEY_DECKS_BYTES = "decks_bytes"

    const val ACTION_BATCH_UPDATED = "com.ichi2.anki.wearos.ACTION_BATCH_UPDATED"
    const val ACTION_ANSWERS_CLEARED = "com.ichi2.anki.wearos.ACTION_ANSWERS_CLEARED"
    const val ACTION_DECKS_UPDATED = "com.ichi2.anki.wearos.ACTION_DECKS_UPDATED"
  }

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "WearSyncService created on Wear OS Watch.")
    try {
      val dbPath = File(filesDir, "wearos_buffer.db").absolutePath
      initDatabase(dbPath)
      Log.d(TAG, "Rust Core SQLite database initialized at: $dbPath")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Rust database: ${e.message}", e)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    job.cancel()
  }

  override fun onDataChanged(dataEvents: DataEventBuffer) {
    Log.d(TAG, "onDataChanged received on watch: ${dataEvents.count} events.")

    for (event in dataEvents) {
      if (event.type == DataEvent.TYPE_CHANGED) {
        val uri = event.dataItem.uri
        val path = uri.path
        Log.d(TAG, "Data changed on path: $path")

        if (path == PATH_DUE_BATCH) {
          val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
          val bytes = dataMapItem.dataMap.getByteArray(KEY_BATCH_BYTES)

          if (bytes != null) {
            scope.launch { saveBatchCache(bytes) }
          }
        } else if (path == PATH_DECKS) {
          val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
          val bytes = dataMapItem.dataMap.getByteArray(KEY_DECKS_BYTES)

          if (bytes != null) {
            scope.launch { saveDecksCache(bytes) }
          }
        }
      }
    }
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    Log.d(TAG, "onMessageReceived on watch: ${messageEvent.path}")

    if (messageEvent.path == PATH_CLEAR_ANSWERS) {
      val idsString = String(messageEvent.data)
      val ids = idsString.split(",").filter { it.isNotEmpty() }
      Log.d(TAG, "Received request to clear ${ids.size} answers from SQLite buffer.")

      if (ids.isNotEmpty()) {
        scope.launch {
          try {
            removeBufferedAnswers(ids)
            Log.i(TAG, "Successfully removed ${ids.size} synced answers from local cache.")

            // Notify the UI that outstanding answers have changed
            val intent = Intent(ACTION_ANSWERS_CLEARED).apply { setPackage(packageName) }
            sendBroadcast(intent)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to clear answers from database: ${e.message}", e)
          }
        }
      }
    }
  }

  private fun saveBatchCache(bytes: ByteArray) {
    try {
      Log.d(TAG, "Validating received batch bytes via Rust FFI...")
      // 1. Validate the byte array by deserializing it (takes ByteArray directly)
      val batch = deserializeDueBatch(bytes)
      Log.i(
        TAG,
        "Successfully validated batch: '${batch.deckName}' with ${batch.cards.size} cards.",
      )

      // 2. Persist locally to cache file
      val cacheFile = File(filesDir, "due_batch.json")
      cacheFile.writeBytes(bytes)
      Log.i(TAG, "Saved due batch cache locally to: ${cacheFile.absolutePath}")

      // 3. Broadcast to the MainActivity UI that new cards are ready
      val intent = Intent(ACTION_BATCH_UPDATED).apply { setPackage(packageName) }
      sendBroadcast(intent)
      Log.d(TAG, "Broadcast sent: ACTION_BATCH_UPDATED")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to cache received batch: ${e.message}", e)
    }
  }

  private fun saveDecksCache(bytes: ByteArray) {
    try {
      val cacheFile = File(filesDir, "decks_cache.txt")
      cacheFile.writeBytes(bytes)
      Log.i(TAG, "Saved decks list cache locally.")

      // Broadcast to MainActivity that new decks list is ready
      val intent = Intent(ACTION_DECKS_UPDATED).apply { setPackage(packageName) }
      sendBroadcast(intent)
      Log.d(TAG, "Broadcast sent: ACTION_DECKS_UPDATED")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save decks list: ${e.message}", e)
    }
  }
}
