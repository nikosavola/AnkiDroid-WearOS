package fi.nikosavola.ankidroid.wearos.wear

import android.app.Application
import android.util.Log
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.wearos_core.PoolDeckSummary
import uniffi.wearos_core.ReviewSession
import uniffi.wearos_core.deserializeDecks
import uniffi.wearos_core.poolDeckSummaries

/**
 * UI state that has to outlive the Activity: the deck list (pooled + known decks), the screen stack
 * and the live [ReviewSession]. A configuration change keeps all of it as is; after process death
 * the stack and the open deck come back from [SavedStateHandle] and the session is rebuilt from the
 * pool (grades are already in SQLite, so nothing buffered is lost). TTS, the Data Layer and the sync
 * receiver stay in the Activity, which forwards events in (e.g. [loadDecks]).
 */
class WatchViewModel(app: Application, private val savedState: SavedStateHandle) :
  AndroidViewModel(app) {
  companion object {
    private const val TAG = "WatchViewModel"
    private const val KEY_NAV = "nav"
    private const val KEY_SCREENS = "screens"
    private const val KEY_DECK = "active_deck"
  }

  internal val backStack = mutableStateListOf<WatchScreen>()

  var reviewSession by mutableStateOf<ReviewSession?>(null)
  var activeDeckId by mutableStateOf<Long?>(null)
  // Bumped after each grade/undo so screens reading the session recompose onto the next card.
  var cardCompletedTrigger by mutableIntStateOf(0)
  // Deck whose session is being rebuilt after process death, until it lands.
  private var restoringDeckId: Long? = null

  init {
    val saved = savedState.get<Bundle>(KEY_NAV)
    val screens = saved?.getStringArrayList(KEY_SCREENS)?.mapNotNull(::decodeScreen).orEmpty()
    // Session screens are only pushed back once the session has been rebuilt, so nothing ever
    // renders an empty session screen.
    backStack.addAll(screens.filter { it is WatchScreen.Decks || it == WatchScreen.Settings })
    if (backStack.firstOrNull() != WatchScreen.Decks("")) backStack.add(0, WatchScreen.Decks(""))
    val deckId = saved?.getLong(KEY_DECK, -1L)?.takeIf { it >= 0 }
    if (deckId != null && WatchScreen.Session in screens) {
      restoreSession(deckId, reopenReview = WatchScreen.Review in screens)
    }
    savedState.setSavedStateProvider(KEY_NAV) {
      Bundle().apply {
        putStringArrayList(KEY_SCREENS, ArrayList(backStack.map(::encodeScreen)))
        if (reviewSession != null) activeDeckId?.let { putLong(KEY_DECK, it) }
      }
    }
  }

  /** Whether [deckId]'s session is open, or about to be after process death. */
  fun hasSessionFor(deckId: Long): Boolean =
    reviewSession != null && activeDeckId == deckId || restoringDeckId == deckId

  private fun restoreSession(deckId: Long, reopenReview: Boolean) {
    restoringDeckId = deckId
    viewModelScope.launch {
      try {
        val nowMs = systemWatchClock.nowMs()
        val session =
          withContext(Dispatchers.IO) {
            ReviewSession.fromPool(deckId, nowMs, utcOffsetSecs(nowMs))
          }
        replaceSession(session, deckId)
        backStack.removeAll { it == WatchScreen.Settings }
        if (WatchScreen.Session !in backStack) backStack.add(WatchScreen.Session)
        if (reopenReview && session.getCurrentCard() != null && WatchScreen.Review !in backStack) {
          backStack.add(WatchScreen.Review)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't restore deck $deckId's session: ${e.message}")
      } finally {
        restoringDeckId = null
      }
    }
  }

  /** Swap in [session], freeing the previous native one. */
  fun replaceSession(session: ReviewSession?, deckId: Long?) {
    reviewSession?.destroy()
    reviewSession = session
    if (deckId != null) activeDeckId = deckId
  }

  override fun onCleared() {
    // ReviewSession is backed by a Rust handle the JVM GC can't size; free it explicitly.
    reviewSession?.destroy()
    reviewSession = null
  }

  // Decks pooled on the watch (switchable offline) and the full list known from the phone.
  var cachedDecks by mutableStateOf<List<PoolDeckSummary>>(emptyList())
    private set

  var knownDecks by mutableStateOf<List<Pair<Long, String>>>(emptyList())
    private set

  // Holds the splash screen until the first load, so a cold start doesn't flash the empty state.
  var decksLoaded by mutableStateOf(false)
    private set

  fun loadDecks() {
    viewModelScope.launch {
      val app = getApplication<Application>()
      val nowMs = systemWatchClock.nowMs()
      val offset = utcOffsetSecs(nowMs)
      try {
        val cached = withContext(Dispatchers.IO) { poolDeckSummaries(nowMs, offset) }
        val known = withContext(Dispatchers.IO) { loadKnownDecks(app.filesDir) }
        cachedDecks = cached
        knownDecks = known
        Log.d(TAG, "Loaded ${cached.size} pooled decks, ${known.size} known decks.")
      } finally {
        decksLoaded = true
      }
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

internal fun encodeScreen(screen: WatchScreen): String =
  when (screen) {
    is WatchScreen.Decks -> "decks:${screen.folderPath}"
    WatchScreen.Settings -> "settings"
    WatchScreen.Session -> "session"
    WatchScreen.Review -> "review"
  }

internal fun decodeScreen(encoded: String): WatchScreen? =
  when {
    encoded.startsWith("decks:") -> WatchScreen.Decks(encoded.removePrefix("decks:"))
    encoded == "settings" -> WatchScreen.Settings
    encoded == "session" -> WatchScreen.Session
    encoded == "review" -> WatchScreen.Review
    else -> null
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
