package com.ichi2.anki.wearos.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.deserializeDecks
import uniffi.wearos_core.deserializeDueBatch
import uniffi.wearos_core.poolCardIds
import uniffi.wearos_core.poolForgetDecks
import uniffi.wearos_core.poolStoreBatch
import uniffi.wearos_core.recordUnplacedAnswers
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
    private const val PATH_UNPLACED_ANSWERS = "/unplaced_answers"
    private const val PATH_DECKS = "/decks"
    private const val PATH_CARD_IMAGE = "/card_image/"
    // Legacy "id:name" text key. A phone built from this commit onward only sends
    // KEY_DECKS_PROTO_BYTES (dual-writing both roughly doubles the DataMap size, and this codebase
    // has already hit DATA_ITEM_TOO_LARGE once); this key is read only in case an older-built phone
    // is still paired.
    private const val KEY_DECKS_BYTES = "decks_bytes"
    private const val KEY_DECKS_PROTO_BYTES = "decks_proto_bytes"

    const val ACTION_BATCH_UPDATED = "com.ichi2.anki.wearos.ACTION_BATCH_UPDATED"
    const val ACTION_ANSWERS_CLEARED = "com.ichi2.anki.wearos.ACTION_ANSWERS_CLEARED"
    const val ACTION_DECKS_UPDATED = "com.ichi2.anki.wearos.ACTION_DECKS_UPDATED"
    const val EXTRA_DECK_ID = "deck_id"
  }

  override fun onCreate() {
    super.onCreate()
    // The Rust core database is initialised by WearApp.onCreate.
    Log.d(TAG, "WearSyncService created on Wear OS Watch.")
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
          val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
          val protoBytes = dataMap.getByteArray(KEY_DECKS_PROTO_BYTES)
          if (protoBytes != null) {
            saveDecksCacheProto(protoBytes)
          } else {
            val bytes = dataMap.getByteArray(KEY_DECKS_BYTES)
            if (bytes != null) saveDecksCacheLegacyText(bytes)
          }
        } else if (path != null && path.startsWith(PATH_CARD_IMAGE)) {
          val target = cardImageTarget(path)
          if (target != null) {
            val (cardId, side) = target
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val asset = dataMap.getAsset("image_asset")
            if (asset != null) {
              runBlocking {
                Log.d(TAG, "Received image asset for card $cardId ($side) on path: $path")
                CardImageCache.saveImage(this@WearSyncService, cardId, side, asset)
              }
            }
          }
        }
      } else if (event.type == DataEvent.TYPE_DELETED) {
        // The phone withdraws a side's image once it stops needing rich rendering; drop the cached
        // PNG so the card falls back to the native text path instead of a stale bitmap.
        val path = event.dataItem.uri.path
        val target = cardImageTarget(path)
        if (target != null) {
          val (cardId, side) = target
          Log.d(TAG, "Card image withdrawn by the phone: $path")
          CardImageCache.deleteImage(this, cardId, side)
        }
      }
    }
  }

  /** Parses a "/card_image/<cardId>/<side>" path; null for any other path or a malformed one. */
  private fun cardImageTarget(path: String?): Pair<Long, String>? {
    if (path == null || !path.startsWith(PATH_CARD_IMAGE)) {
      return null
    }
    val parts = path.split("/")
    val cardId = parts.getOrNull(2)?.toLongOrNull() ?: return null
    val side = parts.getOrNull(3) ?: return null
    return cardId to side
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    Log.d(TAG, "onMessageReceived on watch: ${messageEvent.path}")

    if (messageEvent.path == PATH_UNPLACED_ANSWERS) {
      val idsString = String(messageEvent.data)
      val ids = idsString.split(",").filter { it.isNotEmpty() }
      Log.d(TAG, "Phone reported ${ids.size} unplaced answer(s).")

      if (ids.isNotEmpty()) {
        runBlocking {
          try {
            // Count one failed placement against exactly these ids; the phone only reports answers it
            // examined and couldn't place, so a watch pushing while the phone is away no longer
            // counts against the orphan cap.
            val discarded = recordUnplacedAnswers(ids)
            if (discarded > 0u) {
              Log.w(TAG, "Discarded $discarded stuck review(s) that never settled.")
            }

            // Notify the UI that outstanding answers may have changed.
            val intent = Intent(ACTION_ANSWERS_CLEARED).apply { setPackage(packageName) }
            sendBroadcast(intent)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to record unplaced answers: ${e.message}", e)
          }
        }
      }
    }

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

      // 3. Store the deck's whole pool so it can be reviewed offline on later days
      poolStoreBatch(batch)
      Log.i(TAG, "Stored deck ${batch.deckId} ('${batch.deckName}') into the pool.")

      // Drop cached bitmaps for cards no longer in the pool (fresh ones for current cards are kept).
      // The phone no longer re-sends unchanged images, so pull back any still-pooled card's image
      // we may have pruned earlier or lost. Runs after storing so the new cards count as valid.
      val ids = poolCardIds().toSet()
      CardImageCache.pruneImages(this, ids)
      CardImageCache.restoreMissingImages(this, ids)

      // 4. Broadcast to the MainActivity UI that new cards are ready, tagged with the deck id
      val intent =
        Intent(ACTION_BATCH_UPDATED).apply {
          setPackage(packageName)
          putExtra(EXTRA_DECK_ID, batch.deckId)
        }
      sendBroadcast(intent)
      Log.d(TAG, "Broadcast sent: ACTION_BATCH_UPDATED for deck ${batch.deckId}")
      WearSurfaceUpdater.refresh(this)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to cache received batch: ${e.message}", e)
    }
  }

  /** Preferred path: decode the protobuf deck list and cache it as-is (see [deserializeDecks]). */
  private fun saveDecksCacheProto(bytes: ByteArray) {
    try {
      val decks = deserializeDecks(bytes)
      if (!writeDecksCacheProto(filesDir, bytes)) {
        // Rename failed; the previous cache (still valid) was deliberately left in place.
        return
      }
      Log.i(TAG, "Saved decks list cache locally (${decks.size} decks).")

      pruneOrphanDecks(decks.map { it.id }.toSet())
      broadcastDecksUpdated()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save decks list: ${e.message}", e)
    }
  }

  /** Fallback for a not-yet-updated phone that only sends the legacy "id:name" text format. */
  private fun saveDecksCacheLegacyText(bytes: ByteArray) {
    try {
      writeDecksCacheLegacyText(filesDir, bytes)
      Log.i(TAG, "Saved legacy-text decks list cache locally.")

      // Guard against a malformed empty parse wiping everything: only prune when the list is
      // genuinely empty or we parsed real ids.
      val text = String(bytes, Charsets.UTF_8)
      val validIds =
        text.lineSequence().mapNotNull { it.substringBefore(':').trim().toLongOrNull() }.toSet()
      if (text.isBlank() || validIds.isNotEmpty()) {
        pruneOrphanDecks(validIds)
      }

      broadcastDecksUpdated()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save legacy-text decks list: ${e.message}", e)
    }
  }

  /** Drop pooled decks (and their card images) no longer in [validIds]. */
  private fun pruneOrphanDecks(validIds: Set<Long>) {
    poolForgetDecks(validIds.toList())
    val ids = poolCardIds().toSet()
    CardImageCache.pruneImages(this, ids)
    runBlocking { CardImageCache.restoreMissingImages(this@WearSyncService, ids) }
  }

  private fun broadcastDecksUpdated() {
    val intent = Intent(ACTION_DECKS_UPDATED).apply { setPackage(packageName) }
    sendBroadcast(intent)
    Log.d(TAG, "Broadcast sent: ACTION_DECKS_UPDATED")
    // Pruning can change the total due count summed across cached decks.
    runBlocking { WearSurfaceUpdater.refresh(this@WearSyncService) }
  }
}
