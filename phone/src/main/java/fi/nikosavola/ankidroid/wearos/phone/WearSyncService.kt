package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.deserializeAnswers
import uniffi.wearos_core.serializeDueBatch

class WearSyncService : WearableListenerService() {
  companion object {
    private const val TAG = "PhoneWearSyncService"
    private const val PATH_ANSWERS = "/answers"
    private const val KEY_ANSWERS_BYTES = "answers_bytes"
    private const val PATH_CLEAR_ANSWERS = "/clear_answers"
    private const val PATH_REQUEST_DECK = "/request_deck"
    private const val PATH_REQUEST_DECKS = "/request_decks"
    private const val PATH_REQUEST_NEW = "/request_new"
    private const val PATH_DECKS = "/decks"
    private const val KEY_DECKS_BYTES = "decks_bytes"
    private const val PATH_DUE_BATCH = "/due_batch"

    // Mirror of the phone MainActivity settings store; read-only mode skips write-back.
    private const val SETTINGS_PREFS = "settings_prefs"
    private const val KEY_READ_ONLY = "read_only"

    // Batch is sent as an Asset (streamed over a side channel) rather than a DataMap byte array:
    // a putByteArray counts against the ~100KB DataItem limit and dense decks overflow it
    // (DATA_ITEM_TOO_LARGE). Assets have no such cap. The bytes are gzipped inside
    // serializeDueBatch (Rust), so no compression happens here.
    private const val KEY_BATCH_ASSET = "batch_asset"
  }

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  private lateinit var bridge: AnkiDroidBridge
  private lateinit var renderer: CardBitmapRenderer

  override fun onCreate() {
    super.onCreate()
    bridge = AnkiDroidBridge(applicationContext)
    renderer = CardBitmapRenderer(applicationContext)
    Log.d(TAG, "WearSyncService created on Phone Companion.")
  }

  override fun onDestroy() {
    super.onDestroy()
    job.cancel()
  }

  override fun onDataChanged(dataEvents: DataEventBuffer) {
    Log.d(TAG, "onDataChanged received ${dataEvents.count} events.")

    for (event in dataEvents) {
      if (event.type == DataEvent.TYPE_CHANGED) {
        val uri = event.dataItem.uri
        val path = uri.path
        Log.d(TAG, "Data layer item changed: $path")

        if (path == PATH_ANSWERS) {
          val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
          val bytes = dataMapItem.dataMap.getByteArray(KEY_ANSWERS_BYTES)

          if (bytes != null) {
            // Run synchronously (this callback is already on a background thread) so the service
            // stays alive through the whole write-back + clear_answers handshake. Launching on the
            // service scope let onDestroy cancel it mid-flight ("Job was cancelled") before the
            // clear was sent, so the watch never cleared its buffer and re-sent the same answers -
            // the phone then applied them twice.
            runBlocking { processAnswers(bytes) }
          }
        }
      }
    }
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    Log.d(TAG, "onMessageReceived on phone: ${messageEvent.path}")

    if (messageEvent.path == PATH_REQUEST_DECK) {
      val deckIdString = String(messageEvent.data)
      try {
        val deckId = deckIdString.toLong()
        Log.i(TAG, "Smartwatch requested card batch sync for deck ID: $deckId")

        scope.launch {
          try {
            // 1. Fetch due cards for this specific deck (no mock forced, respect local available)
            val batch = bridge.fetchDueBatch(deckId = deckId, limit = 20)
            Log.d(TAG, "Fetched ${batch.cards.size} due cards dynamically.")

            // 2. Serialize via Rust using lightweight metadata
            val cleanedCards =
              batch.cards.map { card ->
                card.copy(
                  front = AnkiDroidBridge.stripBase64Images(card.front),
                  back = AnkiDroidBridge.stripBase64Images(card.back),
                )
              }
            val cleanedBatch = batch.copy(cards = cleanedCards)
            val bytes = serializeDueBatch(cleanedBatch)

            // 3. Broadcast back to Watch
            val dataClient = Wearable.getDataClient(this@WearSyncService)
            val putDataMapReq =
              PutDataMapRequest.create(PATH_DUE_BATCH).apply {
                dataMap.putAsset(KEY_BATCH_ASSET, Asset.createFromBytes(bytes))
                // Vary the item so an identical re-request still propagates to the watch.
                dataMap.putLong("synced_at", System.currentTimeMillis())
                setUrgent()
              }
            val putDataReq = putDataMapReq.asPutDataRequest()
            dataClient.putDataItem(putDataReq).await()
            Log.i(TAG, "Successfully synced requested deck $deckId back to watch!")

            // Sync card bitmaps independently, path-separated, asynchronously
            renderer.syncBitmapsForBatch(batch)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to compile and sync requested deck $deckId: ${e.message}", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Malformed deck selection request payload: ${e.message}")
      }
    }

    if (messageEvent.path == PATH_REQUEST_DECKS) {
      Log.i(TAG, "Smartwatch requested fresh decks list refresh.")
      scope.launch {
        try {
          val decks = bridge.fetchAllDecks()
          val sharedPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
          val filteredDecks = decks.filter { sharedPrefs.getBoolean("deck_${it.first}", true) }
          val decksStr = filteredDecks.joinToString("\n") { "${it.first}:${it.second}" }
          val deckBytes = decksStr.toByteArray(Charsets.UTF_8)

          val dataClient = Wearable.getDataClient(this@WearSyncService)
          val putDecksReq =
            PutDataMapRequest.create(PATH_DECKS)
              .apply {
                dataMap.putByteArray(KEY_DECKS_BYTES, deckBytes)
                setUrgent()
              }
              .asPutDataRequest()
          dataClient.putDataItem(putDecksReq).await()
          Log.i(TAG, "Successfully synced filtered decks list back to watch background!")
        } catch (e: Exception) {
          Log.e(TAG, "Failed to refresh and sync decks list to watch: ${e.message}", e)
        }
      }
    }

    if (messageEvent.path == PATH_REQUEST_NEW) {
      // Payload is "deckId:count" — study N new cards ahead of the daily queue limit.
      val parts = String(messageEvent.data).split(":")
      try {
        val deckId = parts[0].toLong()
        val count = parts.getOrNull(1)?.toIntOrNull() ?: 10
        Log.i(TAG, "Smartwatch requested $count new cards for deck ID: $deckId")

        scope.launch {
          try {
            val batch = bridge.fetchNewCards(deckId = deckId, limit = count)
            Log.d(TAG, "Fetched ${batch.cards.size} new cards for '${batch.deckName}'.")
            val cleanedCards =
              batch.cards.map { card ->
                card.copy(
                  front = AnkiDroidBridge.stripBase64Images(card.front),
                  back = AnkiDroidBridge.stripBase64Images(card.back),
                )
              }
            val cleanedBatch = batch.copy(cards = cleanedCards)
            val bytes = serializeDueBatch(cleanedBatch)

            val dataClient = Wearable.getDataClient(this@WearSyncService)
            val putDataMapReq =
              PutDataMapRequest.create("$PATH_DUE_BATCH/$deckId").apply {
                dataMap.putAsset(KEY_BATCH_ASSET, Asset.createFromBytes(bytes))
                dataMap.putLong("synced_at", System.currentTimeMillis())
                setUrgent()
              }
            val putDataReq = putDataMapReq.asPutDataRequest()
            dataClient.putDataItem(putDataReq).await()
            Log.i(TAG, "Synced ${batch.cards.size} new cards for deck $deckId back to watch.")

            // Sync card bitmaps independently, path-separated, asynchronously
            renderer.syncBitmapsForBatch(batch)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch/sync new cards for deck $deckId: ${e.message}", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Malformed request_new payload: ${e.message}")
      }
    }
  }

  private suspend fun processAnswers(bytes: ByteArray) {
    try {
      Log.d(TAG, "Deserializing watch answers via Rust core...")
      val answers = deserializeAnswers(bytes)
      Log.i(TAG, "Received ${answers.size} answers from Wear OS watch.")

      if (answers.isNotEmpty()) {
        val readOnly =
          getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_READ_ONLY, false)
        // Returns the ids that reached a terminal state (applied or no-matching-card); transient
        // failures are omitted so they stay buffered for a later retry. See writeAnswersBack.
        val settledIds = bridge.writeAnswersBack(answers, readOnly = readOnly)

        if (settledIds.isNotEmpty()) {
          val idsString = settledIds.joinToString(",")
          val nodeClient = Wearable.getNodeClient(this@WearSyncService)
          val messageClient = Wearable.getMessageClient(this@WearSyncService)

          val nodes = nodeClient.connectedNodes.await()
          Log.d(TAG, "Broadcasting clear_answers to ${nodes.size} nodes.")
          for (node in nodes) {
            try {
              messageClient
                .sendMessage(node.id, PATH_CLEAR_ANSWERS, idsString.toByteArray())
                .await()
              Log.d(TAG, "Sent clear_answers confirmation to node: ${node.displayName}")
            } catch (e: Exception) {
              Log.e(TAG, "Failed to send clear_answers to node ${node.id}: ${e.message}")
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to process answers: ${e.message}", e)
    }
  }
}
