package fi.nikosavola.ankidroid.wearos.phone

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status-line color role, mapped to the theme's color scheme in the composable. */
enum class StatusTone {
  NEUTRAL,
  CONNECTED,
  ERROR,
}

/**
 * UI-state holder for the phone companion. The real logic + state machine live in the Rust core; per
 * the Rust-first mandate this ViewModel only owns the Compose-observable UI state (deck list,
 * selection, sync-status/progress) and the light orchestration around it (deck loading via the
 * bridge, selection persistence, status derivation). Framework-bound work - permissions, the
 * SyncService broadcast receiver, starting the foreground sync, dialogs - stays in the Activity,
 * which forwards events in here (e.g. [onSyncProgress]). Survives configuration changes so the deck
 * list and in-flight sync state aren't dropped.
 */
class PhoneViewModel(app: Application) : AndroidViewModel(app) {
  val bridge = AnkiDroidBridge(app)

  var allDecks by mutableStateOf<List<Pair<DeckId, String>>>(emptyList())
    private set

  // Case-insensitive deck-name filter from the search box; blank shows the full tree.
  var deckFilter by mutableStateOf("")

  // Best-effort per-deck due totals (deckId -> count); empty when AnkiDroid doesn't expose them.
  var deckCounts by mutableStateOf<Map<DeckId, Int>>(emptyMap())
    private set

  // Observable mirror of the deck_sync_prefs "deck_<id>" selection, so the Compose checkboxes react.
  val deckSelection = mutableStateMapOf<DeckId, Boolean>()

  var statusMessage by mutableStateOf("Checking permissions…")
    private set

  var statusTone by mutableStateOf(StatusTone.NEUTRAL)
    private set

  var syncEnabled by mutableStateOf(false)

  var syncStatusMessage by mutableStateOf<String?>(null)

  var progressVisible by mutableStateOf(false)

  var progressIndeterminate by mutableStateOf(false)

  var progressFraction by mutableStateOf(0f)

  var lastSyncLabel by mutableStateOf("Not synced yet")
    private set

  fun setStatus(message: String, tone: StatusTone) {
    statusMessage = message
    statusTone = tone
  }

  /**
   * Load decks + due counts off the main thread once card-access permission is granted. Mirrors the
   * persisted selection so the checkboxes reflect what's actually synced.
   */
  fun loadDecks() {
    syncEnabled = true
    viewModelScope.launch {
      val available = withContext(Dispatchers.IO) { bridge.isAnkiDroidAvailable() }
      if (available) {
        setStatus("Connected to AnkiDroid", StatusTone.CONNECTED)
      } else {
        setStatus("Standalone mock mode — AnkiDroid not found", StatusTone.NEUTRAL)
      }
      allDecks = withContext(Dispatchers.IO) { bridge.fetchAllDecks() }
      seedSelectionFromPrefs()
      // Due counts (if the provider exposes them) can be slow; load them after the tree is drawn.
      deckCounts = withContext(Dispatchers.IO) { bridge.fetchDeckCounts() }
    }
  }

  private fun deckPrefs() =
    getApplication<Application>().getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)

  private fun seedSelectionFromPrefs() {
    val prefs = deckPrefs()
    // Key is the raw long ("deck_<id>"), matching DeckSync - DeckId.value, not the wrapper toString.
    for ((id, _) in allDecks) deckSelection[id] = prefs.getBoolean("deck_${id.value}", true)
  }

  /** Persist a single deck's selection and mirror it into the observable map. */
  fun setDeckSelected(id: DeckId, checked: Boolean) {
    deckSelection[id] = checked
    deckPrefs().edit().putBoolean("deck_${id.value}", checked).apply()
  }

  /** Check or uncheck every deck at once (bulk select controls above the tree). */
  fun setAllDecksSelected(selected: Boolean) {
    deckPrefs().edit().apply { for ((id, _) in allDecks) putBoolean("deck_${id.value}", selected) }.apply()
    for ((id, _) in allDecks) deckSelection[id] = selected
  }

  /** Refresh the "Last synced …" caption from the stored timestamp. */
  fun updateLastSyncLabel() {
    val ms =
      getApplication<Application>()
        .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        .getLong(DeckSync.KEY_LAST_SYNC_MS, 0L)
    lastSyncLabel =
      if (ms <= 0L) {
        "Not synced yet"
      } else {
        "Last synced " +
          DateUtils.getRelativeTimeSpanString(
            ms,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
          )
      }
  }

  /** The Activity calls this right before it starts the foreground SyncService. */
  fun onSyncStarting() {
    syncEnabled = false
    syncStatusMessage = "Starting sync…"
    progressVisible = true
    progressIndeterminate = true
  }

  /** Forwarded from the Activity's SyncService progress receiver (which owns the framework glue). */
  fun onSyncProgress(text: String, percent: Int, done: Boolean, error: Boolean) {
    syncStatusMessage = text
    progressVisible = true
    progressIndeterminate = false
    progressFraction = percent / 100f
    if (done) {
      syncEnabled = true
      if (error) {
        progressVisible = false
      } else {
        updateLastSyncLabel()
        // Hold the "Done" state briefly, then clear.
        Handler(Looper.getMainLooper())
          .postDelayed(
            {
              progressVisible = false
              syncStatusMessage = null
            },
            1500,
          )
      }
    }
  }
}
