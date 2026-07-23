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

/**
 * UI-state holder for the watch's deck list (cached + known decks), which is expensive to reload from
 * disk, so it survives configuration changes. The Activity stays the review/sync controller - the live
 * ReviewSession, TTS, Rust DB init, the Wearable Data Layer send/receive, the sync receiver, and the
 * cheap-to-reload settings mirror all remain there; the Activity forwards events in (e.g. [loadDecks]
 * from the sync receiver).
 */
class WatchViewModel(app: Application) : AndroidViewModel(app) {
  companion object {
    private const val TAG = "WatchViewModel"
  }

  // Decks downloaded to the watch (switchable offline) and the full list known from the phone.
  var cachedDecks by mutableStateOf<List<DeckCache.CachedDeck>>(emptyList())
    private set

  var knownDecks by mutableStateOf<List<Pair<Long, String>>>(emptyList())
    private set

  fun loadDecks() {
    viewModelScope.launch {
      val app = getApplication<Application>()
      val cached = withContext(Dispatchers.IO) { DeckCache.listCached(app) }
      val known =
        withContext(Dispatchers.IO) {
          try {
            val cacheFile = File(app.filesDir, "decks_cache.txt")
            if (cacheFile.exists()) {
              cacheFile
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
            } else {
              emptyList()
            }
          } catch (e: Exception) {
            Log.e(TAG, "Failed to load decks list: ${e.message}", e)
            emptyList()
          }
        }
      cachedDecks = cached
      knownDecks = known
      Log.d(TAG, "Loaded ${cached.size} cached decks, ${known.size} known decks.")
    }
  }

  /** The picker list: every known deck plus any cached deck, annotated with its downloaded state. */
  fun deckEntries(): List<DeckEntry> {
    val cachedById = cachedDecks.associateBy { it.id }
    val byId = LinkedHashMap<Long, DeckEntry>()
    for ((id, name) in knownDecks) {
      byId[id] = DeckEntry(id, name, cachedById[id]?.cardCount)
    }
    for (deck in cachedDecks) {
      if (!byId.containsKey(deck.id)) {
        byId[deck.id] = DeckEntry(deck.id, deck.name, deck.cardCount)
      }
    }
    return byId.values.toList()
  }
}
