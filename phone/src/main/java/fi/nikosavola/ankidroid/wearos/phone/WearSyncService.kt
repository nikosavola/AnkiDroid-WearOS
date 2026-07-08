package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.util.Log
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
    private const val KEY_BATCH_BYTES = "batch_bytes"
  }

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  private lateinit var bridge: AnkiDroidBridge

  override fun onCreate() {
    super.onCreate()
    bridge = AnkiDroidBridge(applicationContext)
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
            scope.launch { processAnswers(bytes) }
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

            // 2. Serialize via Rust
            val bytes = serializeDueBatch(batch)

            // 3. Broadcast back to Watch
            val dataClient = Wearable.getDataClient(this@WearSyncService)
            val putDataReq =
              PutDataMapRequest.create(PATH_DUE_BATCH)
                .apply {
                  dataMap.putByteArray(KEY_BATCH_BYTES, gzip(bytes))
                  setUrgent()
                }
                .asPutDataRequest()

            dataClient.putDataItem(putDataReq).await()
            Log.i(TAG, "Successfully synced requested deck $deckId back to watch!")
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
            val bytes = serializeDueBatch(batch)

            val dataClient = Wearable.getDataClient(this@WearSyncService)
            val putDataReq =
              PutDataMapRequest.create("$PATH_DUE_BATCH/$deckId")
                .apply {
                  dataMap.putByteArray(KEY_BATCH_BYTES, gzip(bytes))
                  dataMap.putLong("synced_at", System.currentTimeMillis())
                  setUrgent()
                }
                .asPutDataRequest()
            dataClient.putDataItem(putDataReq).await()
            Log.i(TAG, "Synced ${batch.cards.size} new cards for deck $deckId back to watch.")
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
        // Returns the ids that reached a terminal state (applied or no-matching-card); transient
        // failures are omitted so they stay buffered for a later retry. See writeAnswersBack.
        val settledIds = bridge.writeAnswersBack(answers)

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
