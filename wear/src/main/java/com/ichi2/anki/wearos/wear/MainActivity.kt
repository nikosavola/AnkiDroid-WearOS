package com.ichi2.anki.wearos.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uniffi.wearos_core.*

/** A deck shown in the picker; [cardCount] is null when the deck has not been downloaded yet. */
data class DeckEntry(val id: Long, val name: String, val cardCount: Int?)

class MainActivity : ComponentActivity() {
  companion object {
    private const val TAG = "WatchMainActivity"
    private const val PATH_ANSWERS = "/answers"
    private const val KEY_ANSWERS_BYTES = "answers_bytes"
  }

  private var reviewSession by mutableStateOf<ReviewSession?>(null)
  private var cardCompletedTrigger by mutableStateOf(0)
  private var outstandingAnswersCount by mutableStateOf(0)
  // Age (seconds) of the oldest un-synced review, to nudge the user when reviews go stale.
  private var outstandingOldestSecs by mutableLongStateOf(0L)
  // Reviews completed since local midnight (motivational counter).
  private var reviewedToday by mutableStateOf(0)
  private var isReviewing by mutableStateOf(false)
  private var ttsEnabled by mutableStateOf(false)

  // User settings mirrored into Compose state (persisted via WatchSettings). Changing any of these
  // recomposes the theme / card text immediately.
  private var themeMode by mutableStateOf(WatchSettings.ThemeMode.DARK)
  private var fontScale by mutableFloatStateOf(1.0f)
  private var cardFont by mutableStateOf(WatchSettings.CardFont.DEFAULT)
  private var keepScreenOn by mutableStateOf(false)
  private var showSettings by mutableStateOf(false)

  // Current folder in the deck picker's `::` hierarchy ("" = root). Descending sets it to a child
  // path; the physical back button and the up row pop it one level.
  private var deckFolderPath by mutableStateOf("")

  // Decks downloaded to the watch (switchable offline) and the full list known from the phone.
  private var cachedDecks by mutableStateOf<List<DeckCache.CachedDeck>>(emptyList())
  private var knownDecks by mutableStateOf<List<Pair<Long, String>>>(emptyList())

  // Deck the user tapped to download; loaded automatically once its batch arrives from the phone.
  private var pendingDeckId: Long? = null
  // The deck currently open in a review session (for requesting more new cards of it).
  private var activeDeckId by mutableStateOf<Long?>(null)
  // Whether the pending request was for new cards, so we can report when none came back.
  private var pendingIsNew = false

  private var tts: TextToSpeech? = null
  private val scope = CoroutineScope(Dispatchers.Main)

  private val syncReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "Sync broadcast received: ${intent?.action}")
        when (intent?.action) {
          WearSyncService.ACTION_BATCH_UPDATED -> {
            loadDecks()
            val arrived = intent.getLongExtra(WearSyncService.EXTRA_DECK_ID, -1L)
            val pending = pendingDeckId
            if (
              pending != null &&
                (arrived == pending || DeckCache.deckFile(this@MainActivity, pending).exists())
            ) {
              val wasNew = pendingIsNew
              pendingDeckId = null
              pendingIsNew = false
              // toastIfEmptyNew runs the "no studyable new cards" check after the async load.
              loadDeckSession(pending, toastIfEmptyNew = wasNew)
            }
          }
          WearSyncService.ACTION_ANSWERS_CLEARED -> {
            updateOutstandingCount()
          }
          WearSyncService.ACTION_DECKS_UPDATED -> {
            loadDecks()
          }
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 1. Initialize Rust Core Database
    try {
      val dbPath = File(filesDir, "wearos_buffer.db").absolutePath
      initDatabase(dbPath)
      Log.d(TAG, "Rust database initialized at: $dbPath")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Rust database: ${e.message}", e)
    }

    // 2. Initialize TTS Engine
    tts =
      TextToSpeech(this) { status ->
        if (status == TextToSpeech.SUCCESS) {
          tts?.language = Locale.getDefault()
          Log.d(TAG, "TTS Engine initialized successfully.")
        } else {
          Log.e(TAG, "TTS Initialization failed.")
        }
      }

    // 3. Load persisted user settings (theme, card font) into Compose state.
    themeMode = WatchSettings.themeMode(this)
    fontScale = WatchSettings.fontScale(this)
    cardFont = WatchSettings.cardFont(this)
    keepScreenOn = WatchSettings.keepScreenOn(this)

    // 4. Load the list of decks (cached locally + known from the phone). Start on the picker.
    loadDecks()
    updateOutstandingCount()

    // 5. Register Receiver
    val filter =
      IntentFilter().apply {
        addAction(WearSyncService.ACTION_BATCH_UPDATED)
        addAction(WearSyncService.ACTION_ANSWERS_CLEARED)
        addAction(WearSyncService.ACTION_DECKS_UPDATED)
      }
    registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

    setContent { WearAppScreen() }
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(syncReceiver)
    tts?.stop()
    tts?.shutdown()
    // ReviewSession is a UniFFI Object backed by a Rust handle the JVM GC can't size; free it
    // explicitly rather than leaking the native allocation until a GC eventually runs.
    clearSession()
  }

  /** Free the native [ReviewSession] before dropping the reference (see onDestroy). */
  private fun clearSession() {
    reviewSession?.destroy()
    reviewSession = null
  }

  /**
   * Refresh both the locally-downloaded decks and the full deck list synced from the phone. Reading
   * + Rust-deserializing every cached deck (DeckCache.listCached) is real I/O, so do it off the
   *   main thread and publish the Compose state back on the main thread.
   */
  private fun loadDecks() {
    scope.launch {
      val cached = withContext(Dispatchers.IO) { DeckCache.listCached(this@MainActivity) }
      val known =
        withContext(Dispatchers.IO) {
          try {
            val cacheFile = File(filesDir, "decks_cache.txt")
            if (cacheFile.exists()) {
              cacheFile
                .readText(Charsets.UTF_8)
                .split("\n")
                .filter { it.isNotEmpty() }
                .map {
                  val parts = it.split(":", limit = 2)
                  Pair(parts[0].toLong(), parts.getOrElse(1) { "Deck ${parts[0]}" })
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

  /**
   * The picker list: every known deck plus any cached deck, annotated with its downloaded state.
   */
  private fun deckEntries(): List<DeckEntry> {
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

  /** Flatten the known/cached decks into the id+name pairs the Rust folder resolver expects. */
  private fun deckInfos(): List<DeckInfo> = deckEntries().map { DeckInfo(it.id, it.name) }

  /** Parent of a `::` deck path; "" when already at a top-level segment. */
  private fun parentFolder(path: String): String = path.substringBeforeLast("::", "")

  /**
   * Load an already-downloaded deck into a review session entirely offline. When [toastIfEmptyNew]
   * is set (a "learn new cards" request), warn if nothing studyable came back once the session is
   * loaded.
   */
  private fun loadDeckSession(deckId: Long, toastIfEmptyNew: Boolean = false) {
    scope.launch {
      try {
        // File read + Rust deserialize is I/O; keep it off the main thread.
        val batch =
          withContext(Dispatchers.IO) {
            deserializeDueBatch(DeckCache.deckFile(this@MainActivity, deckId).readBytes())
          }
        val session = ReviewSession(batch)
        // Free any previous native session before replacing it.
        reviewSession?.destroy()
        reviewSession = session
        activeDeckId = deckId
        isReviewing = false
        Log.i(TAG, "Loaded deck '${batch.deckName}' with ${batch.cards.size} cards from cache.")
        if (toastIfEmptyNew && session.getTotalCards() == 0) {
          Toast.makeText(
              this@MainActivity,
              "No studyable new cards (multi-template notes are skipped).",
              Toast.LENGTH_LONG,
            )
            .show()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load cached deck $deckId: ${e.message}", e)
        Toast.makeText(this@MainActivity, "Could not open deck.", Toast.LENGTH_SHORT).show()
      }
    }
  }

  /** Pick a deck: open it offline if cached, otherwise download it from the phone. */
  private fun selectDeck(deckId: Long) {
    if (cachedDecks.any { it.id == deckId }) {
      loadDeckSession(deckId)
    } else {
      requestDeckFromPhone(deckId)
    }
  }

  private fun requestDeckFromPhone(deckId: Long) {
    pendingDeckId = deckId
    pendingIsNew = false
    scope.launch(Dispatchers.IO) {
      try {
        val messageClient = Wearable.getMessageClient(this@MainActivity)
        val nodeClient = Wearable.getNodeClient(this@MainActivity)
        val nodes = nodeClient.connectedNodes.await()

        if (nodes.isEmpty()) {
          launch(Dispatchers.Main) {
            Toast.makeText(
                this@MainActivity,
                "Phone not connected! Check Bluetooth.",
                Toast.LENGTH_SHORT,
              )
              .show()
          }
          return@launch
        }

        for (node in nodes) {
          messageClient
            .sendMessage(node.id, "/request_deck", deckId.toString().toByteArray())
            .await()
          Log.d(TAG, "Sent request_deck ($deckId) to node: ${node.displayName}")
        }

        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Syncing deck from phone...", Toast.LENGTH_SHORT).show()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to request deck from phone: ${e.message}", e)
        launch(Dispatchers.Main) {
          Toast.makeText(
              this@MainActivity,
              "Failed to contact phone: ${e.message}",
              Toast.LENGTH_SHORT,
            )
            .show()
        }
      }
    }
  }

  /** Ask the phone for [count] new cards of [deckId] to study ahead; auto-loads when it arrives. */
  private fun requestNewCardsFromPhone(deckId: Long, count: Int) {
    pendingDeckId = deckId
    pendingIsNew = true
    scope.launch(Dispatchers.IO) {
      try {
        val messageClient = Wearable.getMessageClient(this@MainActivity)
        val nodeClient = Wearable.getNodeClient(this@MainActivity)
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
          launch(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Phone not connected!", Toast.LENGTH_SHORT).show()
          }
          return@launch
        }
        for (node in nodes) {
          messageClient.sendMessage(node.id, "/request_new", "$deckId:$count".toByteArray()).await()
          Log.d(TAG, "Sent request_new ($deckId:$count) to node: ${node.displayName}")
        }
        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Fetching $count new cards...", Toast.LENGTH_SHORT)
            .show()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to request new cards: ${e.message}", e)
        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Failed to contact phone.", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private fun requestDecksFromPhone() {
    scope.launch(Dispatchers.IO) {
      try {
        val messageClient = Wearable.getMessageClient(this@MainActivity)
        val nodeClient = Wearable.getNodeClient(this@MainActivity)
        val nodes = nodeClient.connectedNodes.await()

        if (nodes.isEmpty()) {
          launch(Dispatchers.Main) {
            Toast.makeText(
                this@MainActivity,
                "Phone not connected! Check Bluetooth.",
                Toast.LENGTH_SHORT,
              )
              .show()
          }
          return@launch
        }

        for (node in nodes) {
          messageClient.sendMessage(node.id, "/request_decks", ByteArray(0)).await()
          Log.d(TAG, "Sent request_decks to node: ${node.displayName}")
        }

        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Refreshing decks list...", Toast.LENGTH_SHORT).show()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to request decks from phone: ${e.message}", e)
        launch(Dispatchers.Main) {
          Toast.makeText(
              this@MainActivity,
              "Failed to contact phone: ${e.message}",
              Toast.LENGTH_SHORT,
            )
            .show()
        }
      }
    }
  }

  /** Compact "5s"/"12m"/"3h"/"2d" age for the oldest un-synced review. */
  private fun humanAge(secs: Long): String =
    when {
      secs < 60 -> "${secs}s"
      secs < 3600 -> "${secs / 60}m"
      secs < 86400 -> "${secs / 3600}h"
      else -> "${secs / 86400}d"
    }

  /** Epoch seconds at the device's local midnight today (passed to the Rust "reviews today" count). */
  private fun todayMidnightSecs(): Long {
    val cal =
      java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
      }
    return cal.timeInMillis / 1000
  }

  /** Human review duration: "45s" under a minute, else "3m 20s". */
  private fun formatDuration(ms: Long): String {
    val totalSecs = ms / 1000
    return if (totalSecs < 60) "${totalSecs}s" else "${totalSecs / 60}m ${totalSecs % 60}s"
  }

  private fun updateOutstandingCount() {
    scope.launch(Dispatchers.IO) {
      try {
        val stats = bufferedAnswerStats()
        outstandingAnswersCount = stats.count
        outstandingOldestSecs = stats.oldestAgeSecs
        reviewedToday = reviewsSince(todayMidnightSecs())
        Log.d(TAG, "Outstanding buffered reviews: ${stats.count}, oldest ${stats.oldestAgeSecs}s")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to query buffered answers: ${e.message}")
      }
    }
  }

  private fun triggerAnswersSync() {
    scope.launch(Dispatchers.IO) {
      try {
        val answers = getBufferedAnswers()
        if (answers.isEmpty()) {
          launch(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "No reviews to sync!", Toast.LENGTH_SHORT).show()
          }
          return@launch
        }

        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Syncing reviews...", Toast.LENGTH_SHORT).show()
        }

        val bytes = serializeAnswers(answers)
        val dataClient = Wearable.getDataClient(this@MainActivity)
        val putDataReq =
          PutDataMapRequest.create(PATH_ANSWERS)
            .apply {
              dataMap.putByteArray(KEY_ANSWERS_BYTES, bytes)
              // Vary the item every send so re-sending the same buffered answers (e.g. after a
              // failed write-back) isn't dropped by DataClient's dedup and can be retried.
              dataMap.putLong("synced_at", System.currentTimeMillis())
              setUrgent()
            }
            .asPutDataRequest()

        dataClient.putDataItem(putDataReq).await()
        Log.i(TAG, "Broadcasted ${answers.size} answers to Phone Companion.")
      } catch (e: Exception) {
        Log.e(TAG, "Sync back failed: ${e.message}", e)
        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  fun answerCardSafely(session: ReviewSession, ease: Int, duration: Long, onDone: () -> Unit = {}) {
    scope.launch {
      try {
        Log.d(TAG, "Answering card with ease: $ease, duration: $duration")
        kotlinx.coroutines.withContext(Dispatchers.IO) { session.answerCard(ease, duration) }
        updateOutstandingCount()
        cardCompletedTrigger++ // Increment to force recomposition of the review screen!
      } catch (e: Exception) {
        Log.e(TAG, "Failed to answer card securely via Rust FFI: ${e.message}", e)
      } finally {
        onDone()
      }
    }
  }

  /** Undo the last grade: Rust steps the queue back and drops the buffered answer; refresh the UI. */
  fun undoLastAnswer(session: ReviewSession) {
    scope.launch {
      try {
        withContext(Dispatchers.IO) { session.undoLastAnswer() }
        updateOutstandingCount()
        cardCompletedTrigger++ // recompose the review screen onto the restored card
      } catch (e: Exception) {
        Log.e(TAG, "Undo failed: ${e.message}", e)
      }
    }
  }

  fun speak(text: String) {
    if (ttsEnabled) {
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card_tts")
    }
  }

  /**
   * Route rotary bezel/crown input into [state] with animated scrolling and haptic detents (the
   * built-in Wear behavior). Grabs focus when composed; only one screen shows at a time so they
   * don't fight over focus.
   */
  @Composable
  private fun Modifier.rotaryScroll(state: ScrollableState): Modifier {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return this.rotaryScrollable(
      RotaryScrollableDefaults.behavior(scrollableState = state),
      focusRequester = focusRequester,
    )
  }

  @Composable
  fun WearAppScreen() {
    AnkiWearTheme(themeMode) {
      // Safe, native physical Back Button navigation logic
      val session = reviewSession
      if (showSettings) {
        androidx.activity.compose.BackHandler { showSettings = false }
      } else if (isReviewing) {
        androidx.activity.compose.BackHandler { isReviewing = false }
      } else if (session != null) {
        androidx.activity.compose.BackHandler { clearSession() }
      } else if (deckFolderPath.isNotEmpty()) {
        // On the deck picker, back goes up one folder instead of leaving the app.
        androidx.activity.compose.BackHandler { deckFolderPath = parentFolder(deckFolderPath) }
      }

      AppScaffold {
        @Suppress("UNUSED_VARIABLE")
        val trigger =
          cardCompletedTrigger // Subscribes this composable context to our recomposition trigger!
        if (showSettings) {
          SettingsScreen()
        } else if (session == null) {
          WelcomeScreen()
        } else {
          val currentCard = session.getCurrentCard()
          if (currentCard == null) {
            FinishedScreen(session)
          } else if (!isReviewing) {
            DeckScreen(session)
          } else {
            ReviewCardScreen(session, currentCard)
          }
        }
      }
    }
  }

  @Composable
  fun WelcomeScreen() {
    val listState = rememberScalingLazyListState()
    val entries = deckEntries()
    val countById = remember(entries) { entries.associate { it.id to it.cardCount } }
    // Resolve one folder level from the flat deck list in Rust; card counts stay a UI concern here.
    val level = remember(entries, deckFolderPath) { deckChildren(deckInfos(), deckFolderPath) }
    // Reset scroll to the top whenever we descend into or pop out of a folder.
    LaunchedEffect(deckFolderPath) { listState.scrollToItem(0) }

    val title =
      if (deckFolderPath.isEmpty()) "AnkiDroid Wear" else deckFolderPath.substringAfterLast("::")

    ScreenScaffold(scrollState = listState) { contentPadding ->
      ScalingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        // ScreenScaffold's contentPadding already reserves exactly the space needed at each end
        // (including room for the trailing EdgeButton); auto-centering would additionally pad the
        // list so the last item can reach the screen's vertical center, letting it be over-scrolled
        // well past its resting spot at the bezel.
        autoCentering = null,
        modifier = Modifier.fillMaxSize().rotaryScroll(listState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        item {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          )
        }

        if (entries.isEmpty()) {
          item {
            Text(
              text = "Tap Refresh to load your decks, or open the phone app and press Sync.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
              textAlign = TextAlign.Center,
            )
          }
        } else {
          // Up one folder. Only shown when we've descended below the root.
          if (deckFolderPath.isNotEmpty()) {
            item {
              CompactButton(
                onClick = { deckFolderPath = parentFolder(deckFolderPath) },
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = {
                  Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_up),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                },
                modifier = Modifier.fillMaxWidth(0.7f),
              ) {
                Text("Up")
              }
            }
          }

          // Studying the folder itself (Anki parent decks are real decks).
          level.ownDeck?.let { own ->
            item {
              DeckCard(id = own.id, label = "Study ${own.name}", cardCount = countById[own.id])
            }
          }

          items(level.children) { node ->
            if (node.isFolder) {
              Card(
                onClick = { deckFolderPath = node.path },
                modifier = Modifier.fillMaxWidth(0.95f),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    painter = painterResource(id = R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                  Spacer(modifier = Modifier.width(10.dp))
                  Column {
                    Text(
                      text = node.name,
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 2,
                      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                      text = "Folder",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            } else {
              DeckCard(id = node.id, label = node.name, cardCount = countById[node.id])
            }
          }
        }

        item {
          CompactButton(
            onClick = { showSettings = true },
            colors = ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.fillMaxWidth(0.7f),
          ) {
            Text("Settings")
          }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
          EdgeButton(
            onClick = {
              requestDecksFromPhone()
              loadDecks()
            },
            buttonSize = EdgeButtonSize.Medium,
          ) {
            Icon(
              painter = painterResource(id = R.drawable.ic_refresh),
              contentDescription = "Refresh decks",
            )
          }
        }
      }
    }
  }

  /** A studyable deck row: taps open it offline if cached, else request it from the phone. */
  @Composable
  private fun DeckCard(id: Long, label: String, cardCount: Int?) {
    Card(onClick = { selectDeck(id) }, modifier = Modifier.fillMaxWidth(0.95f)) {
      Column {
        Text(
          text = label,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
          text = if (cardCount != null) "$cardCount cards ready" else "Tap to download",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (cardCount != null) Color(0xFF66BB6A) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  @Composable
  fun DeckScreen(session: ReviewSession) {
    var pickingNewCount by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
      ScalingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        // See WelcomeScreen: avoid over-scrolling the list past its natural end.
        autoCentering = null,
        modifier = Modifier.fillMaxSize().rotaryScroll(listState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        item {
          Text(
            text = "📚 Deck Stats",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
          )
        }
        item {
          Text(
            text = session.getDeckName(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
          )
        }
        item {
          Card(onClick = {}, modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = "Due Cards: ${session.getTotalCards()}",
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = "Progress: ${session.getProgressString()}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
              )
              if (reviewedToday > 0) {
                Text(
                  text = "Reviewed today: $reviewedToday 🔥",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFFFFA726),
                )
              }
            }
          }
        }
        item {
          Button(
            onClick = { isReviewing = true },
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth(0.9f),
          ) {
            Text("Start Review")
          }
        }
        // Study new cards ahead of the daily queue limit. Only available when we know the deck id.
        if (activeDeckId != null) {
          newCardControlItems(pickingNewCount) { pickingNewCount = it }
        }
        item {
          SwitchButton(
            checked = ttsEnabled,
            onCheckedChange = { ttsEnabled = it },
            label = { Text("Voice Reviews") },
            secondaryLabel = { Text(if (ttsEnabled) "Speaker On 🔊" else "Speaker Off 🔇") },
            modifier = Modifier.fillMaxWidth(0.9f),
          )
        }
        if (outstandingAnswersCount > 0) {
          item {
            Button(
              onClick = { triggerAnswersSync() },
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF66BB6A).copy(alpha = 0.2f),
                  contentColor = Color(0xFF66BB6A),
                ),
              modifier = Modifier.fillMaxWidth(0.9f),
            ) {
              Text("Sync $outstandingAnswersCount Reviews")
            }
          }
          // Nudge when reviews have been waiting to reach the phone for a day or more.
          if (outstandingOldestSecs >= 86400) {
            item {
              Text(
                text = "⚠ Oldest unsynced ${humanAge(outstandingOldestSecs)} ago",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFA726),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
              )
            }
          }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
          EdgeButton(onClick = { clearSession() }, buttonSize = EdgeButtonSize.Medium) {
            Text("Change Deck")
          }
        }
      }
    }
  }

  @Composable
  @Suppress("LongMethod")
  fun ReviewCardScreen(session: ReviewSession, card: WearosCard) {
    var cardStartTime by
      remember(card.id, card.ord) { mutableLongStateOf(System.currentTimeMillis()) }
    var isRevealed by remember(card.id, card.ord) { mutableStateOf(session.isRevealed()) }
    var isAnswering by remember(card.id, card.ord) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val haptics = LocalHapticFeedback.current

    val labelStyle = MaterialTheme.typography.labelSmall
    val curvedStyle =
      remember(labelStyle) {
        androidx.wear.compose.foundation.CurvedTextStyle(labelStyle.copy(fontSize = 11.sp))
      }

    // Anki answers embed the question followed by <hr id=answer>; the Rust core returns just the
    // part after it so the answer view doesn't duplicate the front (whole back if no marker).
    val answerBack = remember(card.back) { answerHtml(card.back) }

    // Rich HTML for display; plain text for TTS.
    val displayFront = remember(card.front) { htmlForDisplay(card.front) }
    val displayBack = remember(answerBack) { htmlForDisplay(answerBack) }
    val cleanFront = remember(card.front) { cleanHtml(card.front) }
    val cleanBack = remember(answerBack) { cleanHtml(answerBack) }

    // TTS voice effects triggered dynamically via state change
    LaunchedEffect(card.id, card.ord) { speak(cleanFront) }
    LaunchedEffect(isRevealed) {
      if (isRevealed) {
        speak(cleanBack)
      }
    }

    // Optionally hold the screen on for the duration of the review (opt-in; battery cost). The flag
    // is cleared when we leave the review screen.
    if (keepScreenOn) {
      DisposableEffect(Unit) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
      }
    }

    ScreenScaffold(scrollState = scrollState) {
      if (!isRevealed) {
        // Front: the whole card is tappable to reveal; a small grey hint says so.
        Text(
          text = session.getProgressString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
        // Undo the previous grade (steps back a card and drops its buffered answer). Only shown once
        // at least one card has been answered. NOTE: placement near the top edge may need tuning on
        // round displays where the corners are clipped.
        if (session.canUndo()) {
          CompactButton(
            onClick = { undoLastAnswer(session) },
            colors = ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp),
          ) {
            Text("↩", style = MaterialTheme.typography.labelSmall)
          }
        }
        Column(
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(scrollState)
              .rotaryScroll(scrollState)
              .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
              ) {
                session.revealAnswer()
                isRevealed = true
              }
              .padding(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 34.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          CardSideContent(
            cardId = card.id,
            side = "front",
            fallbackHtml = displayFront,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        CurvedLayout(
          modifier = Modifier.fillMaxSize(),
          anchor = 90f,
          anchorType = AnchorType.Center,
          angularDirection = CurvedDirection.Angular.Reversed,
        ) {
          curvedText(
            text = "Tap to show answer",
            style = curvedStyle,
            color = Color.LightGray.copy(alpha = 0.7f),
          )
        }
      } else {
        // Answer: the card fills the screen and scrolls under the grade buttons, which overlay the
        // lower bezel with some transparency so the card shows through.
        val duration = System.currentTimeMillis() - cardStartTime
        Text(
          text = session.getProgressString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
        Column(
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(scrollState)
              .rotaryScroll(scrollState)
              // Extra bottom space so the answer can scroll past the overlay buttons and every
              // line can be brought fully into the clear above them.
              .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 96.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          CardSideContent(
            cardId = card.id,
            side = "front",
            fallbackHtml = displayFront,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(
            modifier =
              Modifier.padding(vertical = 6.dp)
                .fillMaxWidth(0.5f)
                .height(1.dp)
                .background(Color.DarkGray)
          )
          CardSideContent(
            cardId = card.id,
            side = "back",
            fallbackHtml = displayBack,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        GradeEdgeButtons(card = card, enabled = !isAnswering) { ease, autoSync ->
          if (!isAnswering) {
            isAnswering = true
            // Confirm the grade with a short haptic tick (eyes-free / glanceable feedback).
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            answerCardSafely(session, ease, duration) {
              if (autoSync) triggerAnswersSync() // auto sync when finishing with Easy
              isAnswering = false
            }
          }
        }
      }
    }
  }

  private data class GradeSpec(
    val text: String,
    val color: Color,
    val ease: Int,
    val autoSync: Boolean,
  )

  private fun gradeSpecs(card: WearosCard): List<GradeSpec> =
    if (card.buttonCount == 3) {
      listOf(
        GradeSpec("Again", Color(0xFFEF5350), 1, false),
        GradeSpec("Good", Color(0xFF66BB6A), 3, false),
        GradeSpec("Easy", Color(0xFF42A5F5), 4, true),
      )
    } else {
      listOf(
        GradeSpec("Again", Color(0xFFEF5350), 1, false),
        GradeSpec("Hard", Color(0xFFFFA726), 2, false),
        GradeSpec("Good", Color(0xFF66BB6A), 3, false),
        GradeSpec("Easy", Color(0xFF42A5F5), 4, true),
      )
    }

  /**
   * Grade buttons laid out along the bottom round edge with [CurvedLayout], so each chip is rotated
   * tangent to the bezel and follows the arc. The interval preview is dropped so the words fit at
   * this size (colour signals the grade); curvedComposable children stay clickable.
   */
  @Composable
  private fun GradeEdgeButtons(
    card: WearosCard,
    enabled: Boolean,
    onGrade: (ease: Int, autoSync: Boolean) -> Unit,
  ) {
    val grades = gradeSpecs(card)
    CurvedLayout(
      modifier = Modifier.fillMaxSize(),
      anchor = 90f,
      anchorType = AnchorType.Center,
      angularDirection = CurvedDirection.Angular.Reversed,
    ) {
      grades.forEachIndexed { i, g ->
        if (i > 0) {
          curvedComposable { Spacer(modifier = Modifier.size(2.dp)) }
        }
        curvedComposable {
          CompactButton(
            onClick = { if (enabled) onGrade(g.ease, g.autoSync) },
            colors =
              ButtonDefaults.buttonColors(
                containerColor = g.color.copy(alpha = 0.32f),
                contentColor = g.color,
              ),
          ) {
            Text(g.text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }

  /**
   * Renders the pre-rendered card bitmap side if cached locally on the watch, falling back to
   * standard HtmlText if not.
   */
  @Composable
  fun CardSideContent(
    cardId: Long,
    side: String,
    fallbackHtml: String,
    modifier: Modifier = Modifier,
  ) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasCachedImage = remember(cardId, side) { CardImageCache.hasImage(context, cardId, side) }

    if (hasCachedImage) {
      val bitmap =
        remember(cardId, side) {
          try {
            val file = CardImageCache.getImagePath(context, cardId, side)
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
          } catch (e: Exception) {
            null
          }
        }
      if (bitmap != null) {
        androidx.compose.foundation.Image(
          bitmap = bitmap,
          contentDescription = "Card side $side",
          modifier = modifier.fillMaxWidth(),
          contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
      } else {
        HtmlText(html = fallbackHtml, modifier = modifier)
      }
    } else {
      HtmlText(html = fallbackHtml, modifier = modifier)
    }
  }

  /**
   * Renders sanitized card HTML (bold, colors, line breaks, etc.) via HtmlCompat in a TextView.
   * Wear OS has no WebView, so this is the richest safe renderer available. Text color follows the
   * active theme (so it stays legible in light mode) and size/typeface follow the user's settings.
   */
  @Composable
  fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val scale = fontScale
    val font = cardFont
    AndroidView(
      modifier = modifier,
      factory = { ctx -> TextView(ctx).apply { gravity = Gravity.CENTER } },
      update = { tv ->
        tv.setTextColor(textColor)
        tv.textSize = 16f * scale
        tv.typeface =
          when (font) {
            WatchSettings.CardFont.SERIF -> android.graphics.Typeface.SERIF
            WatchSettings.CardFont.MONOSPACE -> android.graphics.Typeface.MONOSPACE
            WatchSettings.CardFont.DEFAULT -> android.graphics.Typeface.DEFAULT
          }
        tv.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
      },
    )
  }

  /** "Learn new cards" chip that expands into a 5/10/20 chooser; shared by Deck and Finished. */
  private fun ScalingLazyListScope.newCardControlItems(
    picking: Boolean,
    setPicking: (Boolean) -> Unit,
  ) {
    val purpleBg = Color(0xFF7E57C2).copy(alpha = 0.3f)
    val purpleFg = Color(0xFFB39DDB)
    if (!picking) {
      item {
        Button(
          onClick = { setPicking(true) },
          colors = ButtonDefaults.buttonColors(containerColor = purpleBg, contentColor = purpleFg),
          modifier = Modifier.fillMaxWidth(0.9f),
        ) {
          Text("Learn new cards")
        }
      }
    } else {
      item {
        Text(
          text = "How many new?",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(5, 10, 20).forEach { n ->
            CompactButton(
              onClick = {
                setPicking(false)
                activeDeckId?.let { requestNewCardsFromPhone(it, n) }
              },
              colors =
                ButtonDefaults.buttonColors(containerColor = purpleBg, contentColor = purpleFg),
              modifier = Modifier.width(56.dp),
            ) {
              Text("$n")
            }
          }
        }
      }
      item {
        CompactButton(
          onClick = { setPicking(false) },
          colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
          Text("Cancel")
        }
      }
    }
  }

  @Composable
  fun FinishedScreen(session: ReviewSession) {
    var pickingNewCount by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    // Tally what was graded this session (recomputed if an undo/answer changed it).
    val summary = remember(cardCompletedTrigger) { session.getSummary() }
    ScreenScaffold(scrollState = listState) { contentPadding ->
      ScalingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        // See WelcomeScreen: avoid over-scrolling the list past its natural end.
        autoCentering = null,
        modifier = Modifier.fillMaxSize().rotaryScroll(listState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        item {
          Text(
            text = "Nice Job! 🎉",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        item {
          Text(
            text = "You finished your study queue for now.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
          )
        }
        // Session recap: how many cards, the ease split, and time spent.
        if (summary.total > 0) {
          item {
            Card(onClick = {}, modifier = Modifier.fillMaxWidth(0.9f)) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  text = "${summary.total} cards · ${formatDuration(summary.totalTimeMs)}",
                  style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                  text =
                    "Again ${summary.again} · Hard ${summary.hard} · " +
                      "Good ${summary.good} · Easy ${summary.easy}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
                if (reviewedToday > 0) {
                  Text(
                    text = "Today: $reviewedToday 🔥",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFA726),
                  )
                }
              }
            }
          }
        }
        item {
          Text(
            text = "Outstanding reviews: $outstandingAnswersCount",
            style = MaterialTheme.typography.labelSmall,
            color = Color.LightGray,
            modifier = Modifier.padding(vertical = 8.dp),
          )
        }
        // Learn new cards right after finishing the due queue.
        if (activeDeckId != null) {
          newCardControlItems(pickingNewCount) { pickingNewCount = it }
        }
        // Material 3 expressive edge button hugging the bottom bezel: sync answers and go back.
        item {
          EdgeButton(
            onClick = {
              triggerAnswersSync()
              isReviewing = false
              clearSession() // return to decks selector list
            },
            buttonSize = EdgeButtonSize.Medium,
          ) {
            Icon(
              painter = painterResource(id = R.drawable.ic_check),
              contentDescription = "Sync and back to decks",
            )
          }
        }
      }
    }
  }

  /** A labelled row of mutually-exclusive choice chips; the active one is filled, the rest tonal. */
  @Composable
  private fun <T> settingChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, text) ->
          CompactButton(
            onClick = { onSelect(value) },
            colors =
              if (value == selected) ButtonDefaults.buttonColors()
              else ButtonDefaults.filledTonalButtonColors(),
          ) {
            Text(text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }

  @Composable
  fun SettingsScreen() {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
      ScalingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        autoCentering = null,
        modifier = Modifier.fillMaxSize().rotaryScroll(listState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item {
          Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 6.dp),
          )
        }
        item {
          settingChoiceRow(
            label = "Theme",
            options =
              listOf(
                WatchSettings.ThemeMode.SYSTEM to "Auto",
                WatchSettings.ThemeMode.DARK to "Dark",
                WatchSettings.ThemeMode.LIGHT to "Light",
              ),
            selected = themeMode,
          ) { mode ->
            themeMode = mode
            WatchSettings.setThemeMode(this@MainActivity, mode)
          }
        }
        item {
          settingChoiceRow(
            label = "Text size",
            options = listOf(0.9f to "S", 1.0f to "M", 1.3f to "L", 1.6f to "XL"),
            selected = fontScale,
          ) { scale ->
            fontScale = scale
            WatchSettings.setFontScale(this@MainActivity, scale)
          }
        }
        item {
          // Live preview at the chosen size + font.
          val previewTypeface =
            when (cardFont) {
              WatchSettings.CardFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
              WatchSettings.CardFont.MONOSPACE -> androidx.compose.ui.text.font.FontFamily.Monospace
              WatchSettings.CardFont.DEFAULT -> androidx.compose.ui.text.font.FontFamily.Default
            }
          Text(
            text = "あア 漢字 Aa 12",
            style =
              MaterialTheme.typography.bodyMedium.copy(
                fontSize = (16 * fontScale).sp,
                fontFamily = previewTypeface,
              ),
          )
        }
        item {
          settingChoiceRow(
            label = "Card font",
            options =
              listOf(
                WatchSettings.CardFont.DEFAULT to "Sans",
                WatchSettings.CardFont.SERIF to "Serif",
                WatchSettings.CardFont.MONOSPACE to "Mono",
              ),
            selected = cardFont,
          ) { font ->
            cardFont = font
            WatchSettings.setCardFont(this@MainActivity, font)
          }
        }
        item {
          SwitchButton(
            checked = keepScreenOn,
            onCheckedChange = {
              keepScreenOn = it
              WatchSettings.setKeepScreenOn(this@MainActivity, it)
            },
            label = { Text("Keep screen on") },
            secondaryLabel = { Text("During review · uses battery") },
            modifier = Modifier.fillMaxWidth(0.95f),
          )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
          EdgeButton(onClick = { showSettings = false }, buttonSize = EdgeButtonSize.Medium) {
            Icon(
              painter = painterResource(id = R.drawable.ic_check),
              contentDescription = "Done",
            )
          }
        }
      }
    }
  }
}
