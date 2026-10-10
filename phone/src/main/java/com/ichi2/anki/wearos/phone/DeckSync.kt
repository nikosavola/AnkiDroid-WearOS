package com.ichi2.anki.wearos.phone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.WearosDueBatch
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
 * (foreground, with a progress notification) and the background [AutoSyncWorker] (WorkManager), plus
 * the wire-push helpers the watch's on-demand requests go through.
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
  private const val PATH_DECKS = "/decks"

  /** The Data Layer path every due batch lands on; per-deck requests suffix the deck id. */
  const val PATH_DUE_BATCH = "/due_batch"

  private const val KEY_BATCH_ASSET = "batch_asset"

  /** Epoch-millis of the last completed sync (foreground or background); 0 if never. */
  const val KEY_LAST_SYNC_MS = "last_sync_ms"

  private const val DECK_PREFS = "deck_sync_prefs"
  private const val MAX_SCAN_DECKS = 60
  private const val CARDS_PER_DECK = 20

  /**
   * Default for the mock-mode preference when the user has never toggled it. Debuggable builds
   * keep the dev-friendly auto-mock (demo cards when AnkiDroid is absent); release builds never
   * silently fabricate cards; the user must opt in via the settings toggle.
   */
  fun defaultForceMock(context: Context, ankiDroidAvailable: Boolean): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && !ankiDroidAvailable

  /** Whether the user keeps [deckId] synced to the watch (the deck list's checkbox; on by default). */
  fun isDeckSynced(context: Context, deckId: DeckId): Boolean =
    deckPrefs(context).getBoolean(deckKey(deckId), true)

  fun setDeckSynced(context: Context, deckId: DeckId, synced: Boolean) {
    deckPrefs(context).edit().putBoolean(deckKey(deckId), synced).apply()
  }

  // Key is the raw long ("deck_<id>"), matching DeckId.value, not the wrapper toString.
  fun deckKey(deckId: DeckId): String = "deck_${deckId.value}"

  internal fun deckPrefs(context: Context) =
    context.getSharedPreferences(DECK_PREFS, Context.MODE_PRIVATE)

  /**
   * Push [batch] to the watch on [path] (a plain [PATH_DUE_BATCH] or its per-deck form). The batch
   * is sent as an Asset (streamed over a side channel) rather than a DataMap byte array: a
   * putByteArray counts against the ~100KB DataItem limit and dense decks overflow it
   * (DATA_ITEM_TOO_LARGE). Assets have no such cap. The bytes are zstd-compressed inside
   * serializeDueBatch (Rust), so no compression happens here.
   */
  internal suspend fun pushDueBatch(context: Context, path: String, batch: WearosDueBatch) {
    val bytes = serializeDueBatch(AnkiDroidBridge.stripBase64Batch(batch))
    Wearable.getDataClient(context)
      .putDataItem(
        PutDataMapRequest.create(path)
          .apply {
            dataMap.putAsset(KEY_BATCH_ASSET, Asset.createFromBytes(bytes))
            // Vary the item so an identical re-request still propagates to the watch.
            dataMap.putLong("synced_at", System.currentTimeMillis())
            setUrgent()
          }
          .asPutDataRequest()
      )
      .await()
  }

  /**
   * Push the deck list the watch's picker shows. Legacy KEY_DECKS_BYTES text is intentionally not
   * sent: dual-writing it alongside the protobuf payload roughly doubles the size of this DataMap,
   * and this codebase has already hit DATA_ITEM_TOO_LARGE once (see the batch Asset comment above).
   * The watch still knows how to read the legacy key if it ever receives it from an older-built
   * phone.
   */
  internal suspend fun pushDecksList(context: Context, decks: List<Pair<DeckId, String>>) {
    Wearable.getDataClient(context)
      .putDataItem(
        PutDataMapRequest.create(PATH_DECKS)
          .apply {
            dataMap.putByteArray(KEY_DECKS_PROTO_BYTES, encodeDecksProto(decks))
            setUrgent()
          }
          .asPutDataRequest()
      )
      .await()
  }

  // A sync-now request and the periodic auto-sync can start together (both fire after an app
  // update); run one after the other so each deck is fetched and rendered once at a time.
  private val running = Mutex()

  suspend fun run(context: Context, onProgress: (String, Int) -> Unit = { _, _ -> }): SyncSummary? =
    running.withLock { runExclusive(context, onProgress) }

  private suspend fun runExclusive(context: Context, onProgress: (String, Int) -> Unit): SyncSummary? {
    val bridge = AnkiDroidBridge(context)
    val renderer = CardBitmapRenderer(context)

    val available = bridge.isAnkiDroidAvailable()
    // PhoneSettings owns the settings store; its load() reuses defaultForceMock for the mock flag.
    val settings = PhoneSettings.load(context, available)
    if (!available && !settings.forceMock) {
      Log.i(TAG, "AnkiDroid not reachable and mock mode not forced; skipping sync.")
      return null
    }
    val prePushCap = settings.prePushCap

    onProgress("Syncing deck list…", 5)

    val decks = bridge.fetchAllDecks()
    val filteredDecks = decks.filter { isDeckSynced(context, it.first) }
    pushDecksList(context, filteredDecks)

    onProgress("Fetching due cards…", 15)

    var pushed = 0
    var totalCards = 0
    var scanned = 0
    for ((deckId, deckName) in filteredDecks) {
      if (pushed >= prePushCap || scanned >= MAX_SCAN_DECKS) break
      scanned++
      val batch =
        bridge.fetchDuePool(deckId = deckId, todayLimit = CARDS_PER_DECK, forceMock = settings.forceMock)
      if (batch.cards.isEmpty()) continue

      pushDueBatch(context, "$PATH_DUE_BATCH/${deckId.value}", batch)

      // render() switches to the main thread internally, so this is safe from any coroutine. Only the
      // near horizon gets bitmaps: they're the expensive leg over Bluetooth, so rendering the whole
      // week up front would flood the Data Layer for cards the watch won't reach for days.
      renderer.syncBitmapsForBatch(
          bitmapHorizonBatch(batch, bridge.todayDayIndex()),
          bridge::noteTypeCss,
        )

      pushed++
      totalCards += batch.cards.size
      onProgress(
        "Copying $deckName… ($pushed decks, $totalCards cards)",
        SyncService.syncProgressPercent(pushed, prePushCap),
      )
      Log.d(TAG, "Pushed deck $deckId ('${redact(deckName)}') with ${batch.cards.size} cards.")
    }

    context
      .getSharedPreferences(PhoneSettings.PREFS, Context.MODE_PRIVATE)
      .edit()
      .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
      .apply()
    val summary = SyncSummary(pushed, totalCards, filteredDecks.size - scanned)
    Log.i(TAG, "Sync complete: ${summary.message()}")
    return summary
  }
}
