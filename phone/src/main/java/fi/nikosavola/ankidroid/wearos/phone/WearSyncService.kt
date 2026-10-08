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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.deserializeAnswers
import uniffi.wearos_core.serializeDueBatch

/**
 * Decide the outcome of a received batch. Empty batch -> [WriteBackOutcome.NONE] without calling
 * [writeBack]. Otherwise the outcome from [writeBack]: `settled` ids (applied, or terminally
 * unmatched) are cleared from the watch buffer, and `unplaced` ids are reported back so the watch
 * counts a real failed placement. A transient write failure reports nothing settled, so the caller
 * sends NO clear_answers - clearing a grade that wasn't applied would silently drop the user's
 * review. Extracted from [WearSyncService.processAnswers] so this guard is unit-testable without the
 * GMS node/message clients.
 */
internal fun writeBackOutcome(
  answers: List<WearosAnswer>,
  readOnly: Boolean,
  writeBack: (List<WearosAnswer>, Boolean) -> WriteBackOutcome,
): WriteBackOutcome =
  if (answers.isEmpty()) WriteBackOutcome.NONE else writeBack(answers, readOnly)

class WearSyncService : WearableListenerService() {
  companion object {
    private const val TAG = "PhoneWearSyncService"
    private const val PATH_ANSWERS = "/answers"
    private const val KEY_ANSWERS_BYTES = "answers_bytes"
    private const val PATH_CLEAR_ANSWERS = "/clear_answers"
    private const val PATH_UNPLACED_ANSWERS = "/unplaced_answers"
    private const val PATH_REQUEST_DECK = "/request_deck"
    private const val PATH_REQUEST_DECKS = "/request_decks"
    private const val PATH_REQUEST_NEW = "/request_new"
    private const val PATH_DECKS = "/decks"
    private const val PATH_DUE_BATCH = "/due_batch"

    // Mirror of the phone MainActivity settings store; read-only mode skips write-back.
    private const val SETTINGS_PREFS = "settings_prefs"
    private const val KEY_READ_ONLY = "read_only"

    // Batch is sent as an Asset (streamed over a side channel) rather than a DataMap byte array:
    // a putByteArray counts against the ~100KB DataItem limit and dense decks overflow it
    // (DATA_ITEM_TOO_LARGE). Assets have no such cap. The bytes are zstd-compressed inside
    // serializeDueBatch (Rust), so no compression happens here.
    private const val KEY_BATCH_ASSET = "batch_asset"
  }

  private lateinit var bridge: AnkiDroidBridge
  private lateinit var renderer: CardBitmapRenderer

  override fun onCreate() {
    super.onCreate()
    bridge = AnkiDroidBridge(applicationContext)
    renderer = CardBitmapRenderer(applicationContext)
    Log.d(TAG, "WearSyncService created on Phone Companion.")
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
            runBlocking {
              processAnswers(bytes)
              // Delete the processed item. It's otherwise retained on the Data Layer and redelivered
              // to a reinstalled/updated listener, whose dedupe store (SharedPreferences) was wiped
              // with the reinstall - so stale answers would replay and re-apply. The watch keeps its
              // own buffered copy until clear_answers, so nothing is lost if write-back deferred them.
              try {
                Wearable.getDataClient(this@WearSyncService).deleteDataItems(uri).await()
              } catch (e: Exception) {
                Log.w(TAG, "Failed to delete processed /answers item: ${e.message}")
              }
            }
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

        // Run synchronously: onMessageReceived is already on a background thread, and blocking here
        // keeps the service alive through the whole fetch + batch send + bitmap render. Launching on a
        // service-scoped job let onDestroy cancel it mid-flight ("Job was cancelled") right after the
        // fast batch send but before syncBitmapsForBatch finished, so card images never reached the
        // watch. Mirrors the /answers handshake fix in onDataChanged.
        runBlocking {
          try {
            // 1. Fetch a multi-day pool for this specific deck (no mock forced, respect local
            // available), so the watch can keep reviewing offline for days.
            val batch = bridge.fetchDuePool(deckId = DeckId(deckId), todayLimit = 20)
            Log.d(TAG, "Fetched ${batch.cards.size} pooled cards dynamically.")

            // 2. Serialize via Rust using lightweight metadata
            val bytes = serializeDueBatch(AnkiDroidBridge.stripBase64Batch(batch))

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

            // Sync card bitmaps independently, path-separated, asynchronously. Only the near horizon
            // is worth rendering: bitmaps are the expensive leg over Bluetooth, so a whole week up
            // front would flood the Data Layer for cards the watch won't reach for days.
            renderer.syncBitmapsForBatch(bitmapHorizonBatch(batch, bridge.todayDayIndex()))
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
      // Blocking (see /request_deck) so the send isn't cancelled when the service is torn down.
      runBlocking {
        try {
          val decks = bridge.fetchAllDecks()
          val sharedPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
          // .value: raw long for the prefs key.
          val filteredDecks = decks.filter { sharedPrefs.getBoolean("deck_${it.first.value}", true) }

          val dataClient = Wearable.getDataClient(this@WearSyncService)
          val putDecksReq =
            PutDataMapRequest.create(PATH_DECKS)
              .apply {
                // Legacy KEY_DECKS_BYTES text is intentionally not sent: dual-writing it alongside
                // the protobuf payload roughly doubles the size of this DataMap, and this codebase
                // has already hit DATA_ITEM_TOO_LARGE once (see the batch Asset comment above). The
                // watch still knows how to read the legacy key if it ever receives it from an
                // older-built phone.
                dataMap.putByteArray(KEY_DECKS_PROTO_BYTES, encodeDecksProto(filteredDecks))
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

        // Blocking (see /request_deck) so the bitmap render isn't cancelled at service teardown.
        runBlocking {
          try {
            val batch = bridge.fetchNewCards(deckId = DeckId(deckId), limit = count)
            Log.d(TAG, "Fetched ${batch.cards.size} new cards for '${redact(batch.deckName)}'.")
            val bytes = serializeDueBatch(AnkiDroidBridge.stripBase64Batch(batch))

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

      val readOnly =
        getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READ_ONLY, false)
      val outcome = writeBackOutcome(answers, readOnly) { a, r -> bridge.writeAnswersBack(a, r) }

      if (outcome.settled.isNotEmpty()) {
        sendToConnectedNodes(PATH_CLEAR_ANSWERS, outcome.settled.joinToString(","))
      }
      if (outcome.unplaced.isNotEmpty()) {
        // Report the ids the phone examined but couldn't place, so the watch counts these (and only
        // these) against the orphan cap instead of every push.
        sendToConnectedNodes(PATH_UNPLACED_ANSWERS, outcome.unplaced.joinToString(","))
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to process answers: ${e.message}", e)
    }
  }

  /** Send [payload] on [path] to every connected node, one failed node not stopping the rest. */
  private suspend fun sendToConnectedNodes(path: String, payload: String) {
    val nodeClient = Wearable.getNodeClient(this@WearSyncService)
    val messageClient = Wearable.getMessageClient(this@WearSyncService)

    val nodes = nodeClient.connectedNodes.await()
    Log.d(TAG, "Broadcasting $path to ${nodes.size} nodes.")
    for (node in nodes) {
      try {
        messageClient.sendMessage(node.id, path, payload.toByteArray()).await()
        Log.d(TAG, "Sent $path confirmation to node: ${redact(node.displayName)}")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to send $path to node ${node.id}: ${e.message}")
      }
    }
  }
}
