package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.serializeDueBatch

/** Outcome of a deck sync run, used to build the final progress/notification summary. */
data class SyncSummary(val decksPushed: Int, val cardsPushed: Int, val decksRemaining: Int) {
  fun message(): String {
    val extra = if (decksRemaining > 0) " Others download on demand." else ""
    return "Done — $decksPushed decks, $cardsPushed cards.$extra"
  }
}

/**
 * The shared "push due decks to the watch" routine, used by both the user-initiated [SyncService]
 * (foreground, with a progress notification) and the background [AutoSyncWorker] (WorkManager).
 *
 * It reads the deck selection + settings, pushes the filtered deck list and each non-empty due
 * batch to the watch as a zstd-compressed Asset (compression lives in the Rust `serializeDueBatch`),
 * renders rich card bitmaps, and reports intermediate steps through [onProgress] (text, percent
 * 0..100).
 *
 * Returns `null` (a no-op) when AnkiDroid isn't reachable and mock mode isn't explicitly forced, so
 * a background run never silently ships mock decks to the watch.
 */
object DeckSync {
  private const val TAG = "DeckSync"
  private const val PATH_DUE_BATCH = "/due_batch"
  private const val KEY_BATCH_ASSET = "batch_asset"
  private const val PATH_DECKS = "/decks"

  /** Epoch-millis of the last completed sync (foreground or background); 0 if never. */
  const val KEY_LAST_SYNC_MS = "last_sync_ms"

  private const val SETTINGS_PREFS = "settings_prefs"
  private const val DECK_PREFS = "deck_sync_prefs"
  private const val KEY_FORCE_MOCK = "force_mock"
  private const val KEY_PREPUSH_CAP = "prepush_cap"
  private const val DEFAULT_PREPUSH_DECKS = 15
  private const val MAX_SCAN_DECKS = 60
  private const val CARDS_PER_DECK = 20

  suspend fun run(context: Context, onProgress: (String, Int) -> Unit = { _, _ -> }): SyncSummary? {
    val bridge = AnkiDroidBridge(context)
    val renderer = CardBitmapRenderer(context)
    val settings = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    val deckPrefs = context.getSharedPreferences(DECK_PREFS, Context.MODE_PRIVATE)

    val available = bridge.isAnkiDroidAvailable()
    val isForced = settings.getBoolean(KEY_FORCE_MOCK, !available)
    if (!available && !isForced) {
      Log.i(TAG, "AnkiDroid not reachable and mock mode not forced; skipping sync.")
      return null
    }
    val prePushCap = settings.getInt(KEY_PREPUSH_CAP, DEFAULT_PREPUSH_DECKS)
    val dataClient = Wearable.getDataClient(context)

    onProgress("Syncing deck list…", 5)

    val decks = bridge.fetchAllDecks()
    // .value everywhere: the prefs key ("deck_<id>") must use the raw long, not DeckId.toString().
    val filteredDecks = decks.filter { deckPrefs.getBoolean("deck_${it.first.value}", true) }
    dataClient
      .putDataItem(
        PutDataMapRequest.create(PATH_DECKS)
          .apply {
            // Legacy KEY_DECKS_BYTES text is intentionally not sent: dual-writing it alongside the
            // protobuf payload roughly doubles the size of this DataMap, and this codebase has
            // already hit DATA_ITEM_TOO_LARGE once (see the batch Asset comment above). The watch
            // still knows how to read the legacy key if it ever receives it from an older-built
            // phone.
            dataMap.putByteArray(KEY_DECKS_PROTO_BYTES, encodeDecksProto(filteredDecks))
            setUrgent()
          }
          .asPutDataRequest()
      )
      .await()

    onProgress("Fetching due cards…", 15)

    var pushed = 0
    var totalCards = 0
    var scanned = 0
    for ((deckId, deckName) in filteredDecks) {
      if (pushed >= prePushCap || scanned >= MAX_SCAN_DECKS) break
      scanned++
      val batch =
        bridge.fetchDueBatch(deckId = deckId, limit = CARDS_PER_DECK, forceMock = isForced)
      if (batch.cards.isEmpty()) continue

      val bytes = serializeDueBatch(AnkiDroidBridge.stripBase64Batch(batch))
      dataClient
        .putDataItem(
          PutDataMapRequest.create("$PATH_DUE_BATCH/${deckId.value}")
            .apply {
              // serializeDueBatch already zstd-compresses; ship the bytes straight into the Asset.
              dataMap.putAsset(KEY_BATCH_ASSET, Asset.createFromBytes(bytes))
              dataMap.putLong("synced_at", System.currentTimeMillis())
              setUrgent()
            }
            .asPutDataRequest()
        )
        .await()

      // render() switches to the main thread internally, so this is safe from any coroutine.
      renderer.syncBitmapsForBatch(batch)

      pushed++
      totalCards += batch.cards.size
      onProgress(
        "Copying $deckName… ($pushed decks, $totalCards cards)",
        SyncService.syncProgressPercent(pushed, prePushCap),
      )
      Log.d(TAG, "Pushed deck $deckId ('$deckName') with ${batch.cards.size} cards.")
    }

    settings.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()
    val summary = SyncSummary(pushed, totalCards, filteredDecks.size - scanned)
    Log.i(TAG, "Sync complete: ${summary.message()}")
    return summary
  }
}
