package fi.nikosavola.ankidroid.wearos.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.initDatabase
import uniffi.wearos_core.removeBufferedAnswers

class WearSyncService : WearableListenerService() {
  companion object {
    private const val TAG = "WatchWearSyncService"
    private const val PATH_DUE_BATCH = "/due_batch"
    // Batch arrives as an Asset now (see phone side); the old byte-array key is still read as a
    // fallback for items a pre-upgrade phone may have left on the data layer.
    private const val KEY_BATCH_ASSET = "batch_asset"
    private const val KEY_BATCH_BYTES = "batch_bytes"
    private const val PATH_CLEAR_ANSWERS = "/clear_answers"
    private const val PATH_DECKS = "/decks"
    private const val KEY_DECKS_BYTES = "decks_bytes"

    const val ACTION_BATCH_UPDATED = "fi.nikosavola.ankidroid.wearos.ACTION_BATCH_UPDATED"
    const val ACTION_ANSWERS_CLEARED = "fi.nikosavola.ankidroid.wearos.ACTION_ANSWERS_CLEARED"
    const val ACTION_DECKS_UPDATED = "fi.nikosavola.ankidroid.wearos.ACTION_DECKS_UPDATED"
    const val EXTRA_DECK_ID = "deck_id"
  }

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

  override fun onDataChanged(dataEvents: DataEventBuffer) {
    Log.d(TAG, "onDataChanged received on watch: ${dataEvents.count} events.")

    for (event in dataEvents) {
      if (event.type == DataEvent.TYPE_CHANGED) {
        val uri = event.dataItem.uri
        val path = uri.path
        Log.d(TAG, "Data changed on path: $path")

        if (path != null && path.startsWith(PATH_DUE_BATCH)) {
          val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
          val asset = dataMap.getAsset(KEY_BATCH_ASSET)
          if (asset != null) {
            // Run synchronously (this callback is already on a background thread) so the service
            // survives long enough to finish the read + cache. Launching on a service scope let
            // onDestroy cancel it mid-flight, dropping the batch. Mirrors the phone /answers fix.
            runBlocking {
              // deserializeDueBatch (Rust) inflates the compressed bytes, so pass them through.
              val raw = readAssetBytes(asset)
              if (raw != null) saveBatchCache(raw)
            }
          } else {
            val bytes = dataMap.getByteArray(KEY_BATCH_BYTES)
            if (bytes != null) {
              runBlocking { saveBatchCache(bytes) }
            }
          }
        } else if (path == PATH_DECKS) {
          val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
          val bytes = dataMapItem.dataMap.getByteArray(KEY_DECKS_BYTES)

          if (bytes != null) {
            saveDecksCache(bytes)
          }
        } else if (path != null && path.startsWith("/card_image/")) {
          val parts = path.split("/")
          if (parts.size >= 4) {
            val cardIdStr = parts[2]
            val side = parts[3]
            val cardId = cardIdStr.toLongOrNull()
            if (cardId != null) {
              val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
              val asset = dataMapItem.dataMap.getAsset("image_asset")
              if (asset != null) {
                runBlocking {
                  Log.d(TAG, "Received image asset for card $cardId ($side) on path: $path")
                  CardImageCache.saveImage(this@WearSyncService, cardId, side, asset)
                }
              }
            }
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
        // Synchronous: the buffer removal must not be cancelled at teardown, or the settled
        // answers stay buffered and get re-sent, forcing the phone's dedupe store to be the only
        // thing standing between them and a double-apply.
        runBlocking {
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

  /** Pull an Asset's bytes over the data-layer side channel. Returns null on failure. */
  private suspend fun readAssetBytes(asset: Asset): ByteArray? {
    return try {
      val fd = Wearable.getDataClient(this).getFdForAsset(asset).await()
      fd.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read batch asset: ${e.message}", e)
      null
    }
  }

  private suspend fun saveBatchCache(bytes: ByteArray) {
    try {
      Log.d(TAG, "Validating received batch bytes via Rust FFI...")
      // 1. Validate the byte array by deserializing it (takes ByteArray directly)
      val batch = deserializeDueBatch(bytes)
      Log.i(
        TAG,
        "Successfully validated batch: '${batch.deckName}' with ${batch.cards.size} cards.",
      )

      // 3. Persist per-deck so multiple decks can be cached and switched offline
      DeckCache.saveBatch(this, batch.deckId, bytes)
      Log.i(TAG, "Saved deck ${batch.deckId} ('${batch.deckName}') to local cache.")

      // Drop cached bitmaps for cards no longer in any cached deck (fresh ones for current cards are
      // kept, and re-sends overwrite in place). Runs after saveBatch so the new cards count as valid.
      CardImageCache.pruneImages(this, DeckCache.allCachedCardIds(this))

      // 4. Broadcast to the MainActivity UI that new cards are ready, tagged with the deck id
      val intent =
        Intent(ACTION_BATCH_UPDATED).apply {
          setPackage(packageName)
          putExtra(EXTRA_DECK_ID, batch.deckId)
        }
      sendBroadcast(intent)
      Log.d(TAG, "Broadcast sent: ACTION_BATCH_UPDATED for deck ${batch.deckId}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to cache received batch: ${e.message}", e)
    }
  }

  private fun saveDecksCache(bytes: ByteArray) {
    try {
      val cacheFile = File(filesDir, "decks_cache.txt")
      cacheFile.writeBytes(bytes)
      Log.i(TAG, "Saved decks list cache locally.")

      // Prune cached due batches for decks no longer in the list (deselected on the phone) so they
      // stop showing as stray decks. Format is "id:name" per line. Guard against a malformed empty
      // parse wiping everything: only prune when the list is genuinely empty or we parsed real ids.
      val text = String(bytes, Charsets.UTF_8)
      val validIds =
        text.lineSequence().mapNotNull { it.substringBefore(':').trim().toLongOrNull() }.toSet()
      if (text.isBlank() || validIds.isNotEmpty()) {
        DeckCache.pruneOrphans(this, validIds)
        // A pruned deck's card images are now orphans; drop them too.
        CardImageCache.pruneImages(this, DeckCache.allCachedCardIds(this))
      }

      // Broadcast to MainActivity that new decks list is ready
      val intent = Intent(ACTION_DECKS_UPDATED).apply { setPackage(packageName) }
      sendBroadcast(intent)
      Log.d(TAG, "Broadcast sent: ACTION_DECKS_UPDATED")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save decks list: ${e.message}", e)
    }
  }
}
