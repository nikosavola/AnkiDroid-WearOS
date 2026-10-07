package com.ichi2.anki.wearos.wear

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.wearos_core.PoolDeckSummary
import uniffi.wearos_core.deserializeDecks
import uniffi.wearos_core.poolDeckSummaries

/**
 * UI-state holder for the watch's deck list (pooled + known decks), which is expensive to reload from
 * disk, so it survives configuration changes. The Activity stays the review/sync controller - the live
 * ReviewSession, TTS, the Wearable Data Layer send/receive, the sync receiver, and the
 * cheap-to-reload settings mirror all remain there; the Activity forwards events in (e.g. [loadDecks]
 * from the sync receiver).
 */
class WatchViewModel(app: Application) : AndroidViewModel(app) {
  companion object {
    private const val TAG = "WatchViewModel"
  }

  // Decks pooled on the watch (switchable offline) and the full list known from the phone.
  var cachedDecks by mutableStateOf<List<PoolDeckSummary>>(emptyList())
    private set

  var knownDecks by mutableStateOf<List<Pair<Long, String>>>(emptyList())
    private set

  fun loadDecks() {
    viewModelScope.launch {
      val app = getApplication<Application>()
      val nowMs = systemWatchClock.nowMs()
      val offset = utcOffsetSecs(nowMs)
      val cached = withContext(Dispatchers.IO) { poolDeckSummaries(nowMs, offset) }
      val known = withContext(Dispatchers.IO) { loadKnownDecks(app.filesDir) }
      cachedDecks = cached
      knownDecks = known
      Log.d(TAG, "Loaded ${cached.size} pooled decks, ${known.size} known decks.")
    }
  }

  /** The picker list: every known deck plus any pooled deck, annotated with what is due now. */
  fun deckEntries(): List<DeckEntry> {
    val pooledById = cachedDecks.associateBy { it.deckId }
    val byId = LinkedHashMap<Long, DeckEntry>()
    for ((id, name) in knownDecks) {
      byId[id] = DeckEntry(id, name, pooledById[id]?.dueNow)
    }
    for (deck in cachedDecks) {
      if (!byId.containsKey(deck.deckId)) {
        byId[deck.deckId] = DeckEntry(deck.deckId, deck.name, deck.dueNow)
      }
    }
    return byId.values.toList()
  }
}

/**
 * Reads the watch's known-decks list: prefers the protobuf cache, falling back to the legacy
 * "id:name" text cache only when the protobuf file doesn't exist (e.g. the phone hasn't been
 * updated to send it yet). Pure/synchronous so it's testable without a coroutine or ViewModel.
 */
internal fun loadKnownDecks(filesDir: File): List<Pair<Long, String>> =
  try {
    val protoFile = File(filesDir, "decks_cache.pb")
    val legacyFile = File(filesDir, "decks_cache.txt")
    when {
      protoFile.exists() -> deserializeDecks(protoFile.readBytes()).map { it.id to it.fullName }
      legacyFile.exists() ->
        legacyFile
          .readText(Charsets.UTF_8)
          .split("\n")
          .filter { it.isNotEmpty() }
          // Skip only the malformed line, not the whole list: a single un-parseable id used to
          // throw and blank every known deck until the next successful phone refresh.
          .mapNotNull {
            val parts = it.split(":", limit = 2)
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            Pair(id, parts.getOrElse(1) { "Deck $id" })
          }
      else -> emptyList()
    }
  } catch (e: Exception) {
    Log.e("WatchViewModel", "Failed to load decks list: ${e.message}", e)
    emptyList()
  }
