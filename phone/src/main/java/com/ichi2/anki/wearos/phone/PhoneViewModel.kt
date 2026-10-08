package com.ichi2.anki.wearos.phone

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Status-line color role, mapped to the theme's color scheme in the composable. */
enum class StatusTone {
  NEUTRAL,
  CONNECTED,
  ERROR,
}

/** Which modal dialog is open, if any. */
enum class PhoneDialog {
  NONE,
  SYNC_HELP,
  SETTINGS,
  CONNECTION_INFO,
}

/** Where the AnkiDroid card-access permission stands, driving the in-app explainer card. */
enum class CardAccess {
  UNKNOWN,
  GRANTED,

  /** Denied once; the system will still show its dialog, so explain and offer to ask again. */
  DENIED,

  /** "Don't ask again": only the app's system settings page can grant it now. */
  BLOCKED,

  /** The permission is defined by AnkiDroid, so it doesn't exist until AnkiDroid is installed. */
  ANKIDROID_MISSING,
}

/** The user-editable settings, snapshotted off the main thread for the dialog and the summary. */
data class PhoneSettings(
  val forceMock: Boolean,
  val readOnly: Boolean,
  val autoSync: Boolean,
  val intervalHours: Int,
  val prePushCap: Int,
) {
  companion object {
    const val PREFS = "settings_prefs"
    const val KEY_FORCE_MOCK = "force_mock"
    const val KEY_PREPUSH_CAP = "prepush_cap"
    const val KEY_READ_ONLY = "read_only"
    const val DEFAULT_PREPUSH_DECKS = 15
    const val PREPUSH_MIN = 5
    const val PREPUSH_MAX = 30

    /** Blocking: reads prefs and asks the provider whether AnkiDroid is there. Call off main. */
    fun load(context: Context, ankiDroidAvailable: Boolean): PhoneSettings {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      return PhoneSettings(
        forceMock =
          prefs.getBoolean(
            KEY_FORCE_MOCK,
            DeckSync.defaultForceMock(context, ankiDroidAvailable),
          ),
        readOnly = prefs.getBoolean(KEY_READ_ONLY, false),
        autoSync = AutoSync.isEnabled(context),
        intervalHours = AutoSync.intervalHours(context),
        prePushCap =
          prefs.getInt(KEY_PREPUSH_CAP, DEFAULT_PREPUSH_DECKS).coerceIn(PREPUSH_MIN, PREPUSH_MAX),
      )
    }
  }
}

/**
 * UI-state holder for the phone companion. The real logic + state machine live in the Rust core; per
 * the Rust-first mandate this ViewModel only owns the Compose-observable UI state (deck list,
 * selection, sync-status/progress, dialogs) and the light orchestration around it. Framework-bound
 * work - permission prompts, the SyncService broadcast receiver, starting the foreground sync -
 * stays in the Activity, which forwards events in here (e.g. [onSyncProgress]). Anything the user
 * would expect back after process death (search text, open folders, open dialog) also goes through
 * [savedState].
 */
class PhoneViewModel(app: Application, private val savedState: SavedStateHandle) :
  AndroidViewModel(app) {
  private companion object {
    const val KEY_FILTER = "deck_filter"
    const val KEY_EXPANDED = "expanded_folders"
    const val KEY_DIALOG = "active_dialog"
    const val ANKIDROID_PACKAGE = "com.ichi2.anki"
    const val DONE_HOLD_MS = 1500L
  }

  val bridge = AnkiDroidBridge(app)

  var allDecks by mutableStateOf<List<Pair<DeckId, String>>>(emptyList())
    private set

  // Raw search text, spaces and all; trimmed only when filtering so multi-word names stay typeable.
  var deckFilter by mutableStateOf(savedState.get<String>(KEY_FILTER).orEmpty())
    private set

  var expandedFolders by
    mutableStateOf(savedState.get<ArrayList<String>>(KEY_EXPANDED)?.toSet() ?: emptySet())
    private set

  var activeDialog by
    mutableStateOf(
      savedState.get<String>(KEY_DIALOG)?.let { runCatching { PhoneDialog.valueOf(it) }.getOrNull() }
        ?: PhoneDialog.NONE
    )
    private set

  // Best-effort per-deck due totals (deckId -> count); empty when AnkiDroid doesn't expose them.
  var deckCounts by mutableStateOf<Map<DeckId, Int>>(emptyMap())
    private set

  // Observable mirror of the deck_sync_prefs "deck_<id>" selection, so the Compose checkboxes react.
  val deckSelection = mutableStateMapOf<DeckId, Boolean>()

  var statusMessage by mutableStateOf("Checking permissions…")
    private set

  var statusTone by mutableStateOf(StatusTone.NEUTRAL)
    private set

  var cardAccess by mutableStateOf(CardAccess.UNKNOWN)

  var syncEnabled by mutableStateOf(false)

  var syncStatusMessage by mutableStateOf<String?>(null)

  var progressVisible by mutableStateOf(false)

  var progressIndeterminate by mutableStateOf(false)

  var progressFraction by mutableStateOf(0f)

  var lastSyncLabel by mutableStateOf("Not synced yet")
    private set

  /** Last-saved settings, for the summary card; null until the first off-main load finishes. */
  var settings by mutableStateOf<PhoneSettings?>(null)
    private set

  /** The Settings dialog's unsaved edits. Lives here so a rotation doesn't drop them. */
  var settingsDraft by mutableStateOf<PhoneSettings?>(null)

  // Null until the provider has been asked once; used for the mock-mode default.
  private var ankiDroidAvailable: Boolean? = null

  init {
    refreshSettings()
    updateLastSyncLabel()
  }

  fun setStatus(message: String, tone: StatusTone) {
    statusMessage = message
    statusTone = tone
  }

  fun updateDeckFilter(text: String) {
    deckFilter = text
    savedState[KEY_FILTER] = text
  }

  fun toggleFolder(path: String) {
    expandedFolders = if (path in expandedFolders) expandedFolders - path else expandedFolders + path
    savedState[KEY_EXPANDED] = ArrayList(expandedFolders)
  }

  fun openDialog(dialog: PhoneDialog) {
    activeDialog = dialog
    savedState[KEY_DIALOG] = dialog.name
    if (dialog != PhoneDialog.SETTINGS) settingsDraft = null
  }

  fun closeDialog() = openDialog(PhoneDialog.NONE)

  /** Decks matching the trimmed search text (all of them when it's blank). */
  fun filteredDecks(): List<Pair<DeckId, String>> {
    val query = deckFilter.trim()
    return if (query.isEmpty()) {
      allDecks
    } else {
      allDecks.filter { it.second.contains(query, ignoreCase = true) }
    }
  }

  /**
   * Load decks + due counts off the main thread once card-access permission is granted. Mirrors the
   * persisted selection so the checkboxes reflect what's actually synced.
   */
  fun loadDecks() {
    syncEnabled = true
    viewModelScope.launch {
      val available = withContext(Dispatchers.IO) { bridge.isAnkiDroidAvailable() }
      ankiDroidAvailable = available
      if (available) {
        setStatus("Connected to AnkiDroid", StatusTone.CONNECTED)
      } else {
        setStatus("Standalone mock mode — AnkiDroid not found", StatusTone.NEUTRAL)
      }
      val decks = withContext(Dispatchers.IO) { bridge.fetchAllDecks() }
      showDecks(decks, withContext(Dispatchers.IO) { readSelection(decks) })
      // Due counts (if the provider exposes them) can be slow; load them after the tree is drawn.
      deckCounts = withContext(Dispatchers.IO) { bridge.fetchDeckCounts() }
    }
  }

  @VisibleForTesting
  internal fun showDecks(decks: List<Pair<DeckId, String>>, selection: Map<DeckId, Boolean>) {
    allDecks = decks
    deckSelection.putAll(selection)
  }

  private fun deckPrefs() =
    getApplication<Application>().getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)

  // Key is the raw long ("deck_<id>"), matching DeckSync - DeckId.value, not the wrapper toString.
  private fun readSelection(decks: List<Pair<DeckId, String>>): Map<DeckId, Boolean> {
    val prefs = deckPrefs()
    return decks.associate { (id, _) -> id to prefs.getBoolean("deck_${id.value}", true) }
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
    viewModelScope.launch {
      val ms =
        withContext(Dispatchers.IO) {
          getApplication<Application>()
            .getSharedPreferences(PhoneSettings.PREFS, Context.MODE_PRIVATE)
            .getLong(DeckSync.KEY_LAST_SYNC_MS, 0L)
        }
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
  }

  private suspend fun loadSettings(): PhoneSettings =
    withContext(Dispatchers.IO) {
      val app = getApplication<Application>()
      val available = ankiDroidAvailable ?: bridge.isAnkiDroidAvailable().also {
        ankiDroidAvailable = it
      }
      PhoneSettings.load(app, available)
    }

  fun refreshSettings() {
    viewModelScope.launch { settings = loadSettings() }
  }

  /** Seed the Settings dialog from disk unless a draft survived a configuration change. */
  fun ensureSettingsDraft() {
    if (settingsDraft != null) return
    viewModelScope.launch { if (settingsDraft == null) settingsDraft = loadSettings() }
  }

  fun saveSettings(new: PhoneSettings) {
    settings = new
    settingsDraft = null
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      app
        .getSharedPreferences(PhoneSettings.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PhoneSettings.KEY_FORCE_MOCK, new.forceMock)
        .putBoolean(PhoneSettings.KEY_READ_ONLY, new.readOnly)
        .putInt(PhoneSettings.KEY_PREPUSH_CAP, new.prePushCap)
        .apply()
      // Persists the flag + interval and (re)schedules the WorkManager job.
      AutoSync.apply(app, new.autoSync, new.intervalHours)
    }
  }

  /**
   * The copyable debug report behind the status chip. Everything here touches IPC or disk
   * (provider, package manager, prefs, the Wearable node list), so it is built on IO.
   */
  suspend fun connectionReport(): String =
    withContext(Dispatchers.IO) {
      val app = getApplication<Application>()
      val available = bridge.isAnkiDroidAvailable().also { ankiDroidAvailable = it }
      val s = PhoneSettings.load(app, available)
      val permGranted = granted(app, ANKIDROID_PERMISSION)
      val notifGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
          granted(app, android.Manifest.permission.POST_NOTIFICATIONS)
      val selection = readSelection(allDecks)
      val selectedCount = selection.count { it.value }
      val selectedDeck =
        try {
          if (available) bridge.getSelectedDeck().let { "${it.second} (${it.first})" } else "n/a"
        } catch (e: Exception) {
          "error"
        }
      val watches =
        try {
          Wearable.getNodeClient(app)
            .connectedNodes
            .await()
            .joinToString(", ") { node -> node.displayName + if (node.isNearby) " (nearby)" else "" }
            .ifEmpty { "none" }
        } catch (e: Exception) {
          "error: ${e.message}"
        }
      listOf(
          "▸ App",
          "   Version: ${appVersionString(app, app.packageName)}",
          "   Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
          "   Device: ${Build.MANUFACTURER} ${Build.MODEL}",
          "",
          "▸ AnkiDroid",
          "   Provider: ${if (available) "connected" else "not found"}",
          "   App: ${appVersionString(app, ANKIDROID_PACKAGE)}",
          "   Authority: com.ichi2.anki.flashcards",
          "   Read/write permission: ${if (permGranted) "granted" else "denied"}",
          "   Selected deck: $selectedDeck",
          "   Decks visible: ${allDecks.size}",
          "   Decks selected to sync: $selectedCount / ${allDecks.size}",
          "",
          "▸ Settings",
          "   Force mock mode: ${if (s.forceMock) "on" else "off"}",
          "   Read-only mode: ${if (s.readOnly) "on" else "off"}",
          "   Auto-sync (background): ${if (s.autoSync) "every ${s.intervalHours}h" else "off"}",
          "   Pre-push cap: ${s.prePushCap} decks",
          "   Notifications: ${if (notifGranted) "granted" else "denied"}",
          "",
          "▸ Watch link",
          "   Connected watches: $watches",
        )
        .joinToString("\n")
    }

  private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  /** "versionName (versionCode)" for [pkg], or "not installed" when it isn't present. */
  private fun appVersionString(context: Context, pkg: String): String =
    try {
      val info = context.packageManager.getPackageInfo(pkg, 0)
      // PackageInfoCompat picks longVersionCode / versionCode by API level, so no deprecated call.
      "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (e: Exception) {
      if (pkg == context.packageName) "?" else "not installed"
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
        viewModelScope.launch {
          delay(DONE_HOLD_MS)
          progressVisible = false
          syncStatusMessage = null
        }
      }
    }
  }
}

const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
