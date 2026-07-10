package fi.nikosavola.ankidroid.wearos.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
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
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.lazy.ResponsiveTransformationSpec
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.TransformationVariableSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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
    // How long to keep the sync-progress arc up waiting for the phone's batch before giving up.
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
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
  private var speechRate by mutableFloatStateOf(1.0f)
  private var cardFont by mutableStateOf(WatchSettings.CardFont.DEFAULT)
  private var keepScreenOn by mutableStateOf(false)
  private var showSettings by mutableStateOf(false)

  // Current folder in the deck picker's `::` hierarchy ("" = root). Descending sets it to a child
  // path; the physical back button and the up row pop it one level.
  private var deckFolderPath by mutableStateOf("")

  // Deck-list + settings state lives in the ViewModel (survives config changes); this Activity stays
  // the review/sync controller (live session, TTS, Data Layer, receiver).
  private val vm: WatchViewModel by viewModels()

  // Deck the user tapped to download; loaded automatically once its batch arrives from the phone.
  // Compose state so the UI can show a sync-progress arc while a download is in flight.
  private var pendingDeckId: Long? by mutableStateOf(null)
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
            vm.loadDecks()
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
            // The phone just applied our answers to AnkiDroid; reconcile the last-reviewed deck's
            // cached queue with AnkiDroid's real post-answer schedule (the in-session requeue was a
            // local approximation).
            activeDeckId?.let { silentlyRefreshDeck(it) }
          }
          WearSyncService.ACTION_DECKS_UPDATED -> {
            vm.loadDecks()
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
          // speechRate is loaded synchronously below, before this async callback can fire.
          tts?.setSpeechRate(speechRate)
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
    ttsEnabled = WatchSettings.ttsEnabled(this)
    speechRate = WatchSettings.speechRate(this)

    // 4. Load the list of decks (cached locally + known from the phone). Start on the picker.
    vm.loadDecks()
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
    if (vm.cachedDecks.any { it.id == deckId }) {
      loadDeckSession(deckId)
    } else {
      requestDeckFromPhone(deckId)
    }
  }

  /**
   * Reconcile a deck's cached queue with AnkiDroid's real schedule: re-request its batch WITHOUT
   * opening a session or showing the sync arc (no pendingDeckId). The arriving batch is fetched live
   * from AnkiDroid - reflecting the answers just written back - and refreshes the local cache, so the
   * next open shows the true next-due instead of the in-session requeue approximation. Best-effort:
   * on failure the deck still converges on the next manual open/refresh.
   */
  private fun silentlyRefreshDeck(deckId: Long) {
    scope.launch(Dispatchers.IO) {
      try {
        val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
        if (nodes.isEmpty()) return@launch
        val messageClient = Wearable.getMessageClient(this@MainActivity)
        for (node in nodes) {
          messageClient.sendMessage(node.id, "/request_deck", deckId.toString().toByteArray()).await()
        }
        Log.d(TAG, "Requested reconciled batch for deck $deckId after write-back.")
      } catch (e: Exception) {
        Log.w(TAG, "Reconcile refresh for deck $deckId failed; converges on next open: ${e.message}")
      }
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
            pendingDeckId = null // stop the sync arc; nothing is coming
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
          startDownloadTimeout(deckId)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to request deck from phone: ${e.message}", e)
        launch(Dispatchers.Main) {
          pendingDeckId = null
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

  /** Stop the sync-progress arc if the phone never sends the batch, instead of spinning forever. */
  private fun startDownloadTimeout(deckId: Long) {
    scope.launch {
      kotlinx.coroutines.delay(DOWNLOAD_TIMEOUT_MS)
      if (pendingDeckId == deckId) {
        pendingDeckId = null
        pendingIsNew = false
        Toast.makeText(this@MainActivity, "Phone didn't respond — try again.", Toast.LENGTH_SHORT)
          .show()
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
            pendingDeckId = null
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
          startDownloadTimeout(deckId)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to request new cards: ${e.message}", e)
        launch(Dispatchers.Main) {
          pendingDeckId = null
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

  /**
   * Epoch seconds at the device's local midnight today (passed to the Rust "reviews today" count).
   */
  private fun todayMidnightSecs(): Long =
    java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()

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

  /**
   * Undo the last grade: Rust steps the queue back and drops the buffered answer; refresh the UI.
   */
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

  /**
   * The M3 Expressive edge-morph spec for our lists. Same as the default TransformingLazyColumn
   * transform but with a higher container/content alpha floor at the bezel (0.75 instead of the
   * default 0.5), so cards, switches and buttons stay legible near the top/bottom edges rather than
   * fading half-out. targetValue stays 1.0 (fully opaque at the vertical center).
   */
  @Composable
  private fun rememberEdgeMorphSpec(): TransformationSpec {
    val edgeAlpha = TransformationVariableSpec(0.75f)
    return rememberTransformationSpec(
      ResponsiveTransformationSpec.smallScreen(containerAlpha = edgeAlpha, contentAlpha = edgeAlpha),
      ResponsiveTransformationSpec.largeScreen(containerAlpha = edgeAlpha, contentAlpha = edgeAlpha),
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

      Box(modifier = Modifier.fillMaxSize()) {
        AppScaffold {
          if (showSettings) {
            SettingsScreen()
          } else if (session == null) {
            WelcomeScreen()
          } else {
            // Keying on cardCompletedTrigger subscribes this scope, so answering/undoing a card
            // recomposes it onto the next card (and switches to the finished screen when the queue
            // empties) without a throwaway "unused variable" read.
            val currentCard = remember(session, cardCompletedTrigger) { session.getCurrentCard() }
            if (currentCard == null) {
              FinishedScreen(session)
            } else if (!isReviewing) {
              DeckScreen(session)
            } else {
              ReviewCardScreen(session, currentCard)
            }
          }
        }
        // While a deck/new-card batch is downloading from the phone, sweep an indeterminate arc
        // around the round edge (the Data Layer transfer exposes no fraction). Cleared when the
        // batch arrives or the request times out.
        if (pendingDeckId != null) {
          androidx.wear.compose.material3.CircularProgressIndicator(
            modifier = Modifier.fillMaxSize().padding(2.dp)
          )
        }
      }
    }
  }

  @Composable
  fun WelcomeScreen() {
    val listState = rememberTransformingLazyColumnState()
    val transformSpec = rememberEdgeMorphSpec()
    // Memoize on the underlying deck state, not on a freshly-built list: vm.deckEntries() returns a new
    // List each call, so keying the remembers on it would defeat them and re-run the deckChildren FFI
    // + the maps on every recomposition. Now they recompute only when the decks/folder actually change.
    val entries = remember(vm.cachedDecks, vm.knownDecks) { vm.deckEntries() }
    val countById = remember(entries) { entries.associate { it.id to it.cardCount } }
    // Resolve one folder level from the flat deck list in Rust; card counts stay a UI concern here.
    val level =
      remember(entries, deckFolderPath) {
        deckChildren(entries.map { DeckInfo(it.id, it.name) }, deckFolderPath)
      }
    // Reset scroll to the top whenever we descend into or pop out of a folder.
    LaunchedEffect(deckFolderPath) { listState.scrollToItem(0) }

    val title =
      if (deckFolderPath.isEmpty()) "AnkiDroid WearOS" else deckFolderPath.substringAfterLast("::")

    ScreenScaffold(scrollState = listState) { contentPadding ->
      // M3 Expressive edge-hugging list: TransformingLazyColumn morphs each item container toward the
      // round bezel, so deck cards visibly widen and round as they approach the vertical center and
      // shrink at the top/bottom edges. SurfaceTransformation + transformedHeight drive the morph.
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
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
              DeckCard(
                id = own.id,
                label = "Study ${own.name}",
                cardCount = countById[own.id],
                transformation = SurfaceTransformation(transformSpec),
                modifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec),
              )
            }
          }

          items(level.children) { node ->
            val transformation = SurfaceTransformation(transformSpec)
            val cardModifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec)
            if (node.isFolder) {
              FolderCard(node, transformation, cardModifier)
            } else {
              DeckCard(node.id, node.name, countById[node.id], transformation, cardModifier)
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
              vm.loadDecks()
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

  /**
   * A folder row in the deck picker: a Material folder icon + name; taps descend into the folder.
   */
  @Composable
  private fun FolderCard(
    node: DeckNodeInfo,
    transformation: SurfaceTransformation,
    modifier: Modifier,
  ) {
    Card(
      onClick = { deckFolderPath = node.path },
      transformation = transformation,
      modifier = modifier,
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
  }

  /** A studyable deck row: taps open it offline if cached, else request it from the phone. */
  @Composable
  private fun DeckCard(
    id: Long,
    label: String,
    cardCount: Int?,
    transformation: SurfaceTransformation,
    modifier: Modifier,
  ) {
    Card(onClick = { selectDeck(id) }, transformation = transformation, modifier = modifier) {
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

  /**
   * A glanceable stat: a small caps label above a bold M3 Numeral (tabular digits). Used for the
   * deck/session counts where the number is the point.
   */
  @Composable
  private fun StatNumeral(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
      Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(text = value, style = MaterialTheme.typography.numeralMedium, color = color)
    }
  }

  @Composable
  fun DeckScreen(session: ReviewSession) {
    var pickingNewCount by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()
    val transformSpec = rememberEdgeMorphSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
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
          Card(
            onClick = {},
            transformation = SurfaceTransformation(transformSpec),
            modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                StatNumeral(
                  label = "Due",
                  value = "${session.getTotalCards()}",
                  color = MaterialTheme.colorScheme.primary,
                )
                if (reviewedToday > 0) {
                  Spacer(modifier = Modifier.width(32.dp))
                  StatNumeral(label = "Today", value = "$reviewedToday", color = Color(0xFFFFA726))
                }
              }
              Text(
                text = "Progress ${session.getProgressString()}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
              )
            }
          }
        }
        item {
          Button(
            onClick = { isReviewing = true },
            colors = ButtonDefaults.buttonColors(),
            transformation = SurfaceTransformation(transformSpec),
            modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
          ) {
            Text("Start Review")
          }
        }
        // Study new cards ahead of the daily queue limit. Only available when we know the deck id.
        if (activeDeckId != null) {
          newCardControlItems(pickingNewCount, transformSpec) { pickingNewCount = it }
        }
        item {
          SwitchButton(
            checked = ttsEnabled,
            onCheckedChange = {
              ttsEnabled = it
              WatchSettings.setTtsEnabled(this@MainActivity, it)
            },
            label = { Text("Voice Reviews") },
            secondaryLabel = { Text(if (ttsEnabled) "Speaker On 🔊" else "Speaker Off 🔇") },
            transformation = SurfaceTransformation(transformSpec),
            modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
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
              transformation = SurfaceTransformation(transformSpec),
              modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
            ) {
              Text("Sync $outstandingAnswersCount Reviews")
            }
          }
          // Nudge when reviews have been waiting to reach the phone for a day or more.
          if (outstandingOldestSecs >= 86_400) {
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
  fun ReviewCardScreen(session: ReviewSession, card: WearosCard) {
    val cardStartTime = remember(card.id, card.ord) { System.currentTimeMillis() }
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
        ReviewFront(session, card, displayFront, curvedStyle, scrollState) {
          session.revealAnswer()
          isRevealed = true
        }
      } else {
        ReviewAnswer(
          session,
          card,
          displayFront,
          displayBack,
          scrollState,
          cardStartTime,
          gradeEnabled = !isAnswering,
          curvedStyle = curvedStyle,
        ) { ease, duration ->
          if (!isAnswering) {
            isAnswering = true
            // Confirm the grade with a short haptic tick (eyes-free / glanceable feedback).
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Answers just buffer locally here; they sync on the Finished screen, the deck-screen
            // "Sync N reviews" action, or background sync — not after every grade (too noisy).
            answerCardSafely(session, ease, duration) { isAnswering = false }
          }
        }
      }
    }
  }

  /**
   * The N/M session progress as curved text hugging the top arc (matching the curved answer hint +
   * grade buttons), inset radially so it clears the ScreenScaffold clock at the very top.
   */
  @Composable
  private fun TopCurvedProgress(text: String, style: CurvedTextStyle) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    CurvedLayout(modifier = Modifier.fillMaxSize(), anchor = 270f, anchorType = AnchorType.Center) {
      curvedText(
        text = text,
        style = style,
        color = color,
        modifier = CurvedModifier.padding(outer = 22.dp, inner = 0.dp, before = 0.dp, after = 0.dp),
      )
    }
  }

  /**
   * Front of a review card: progress, an optional undo, the tappable card body, and a reveal hint.
   */
  @Composable
  private fun BoxScope.ReviewFront(
    session: ReviewSession,
    card: WearosCard,
    displayFront: String,
    curvedStyle: androidx.wear.compose.foundation.CurvedTextStyle,
    scrollState: androidx.compose.foundation.ScrollState,
    onReveal: () -> Unit,
  ) {
    TopCurvedProgress(session.getProgressString(), curvedStyle)
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(scrollState)
          .rotaryScroll(scrollState)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) {
            onReveal()
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
    // Undo the previous grade (steps back a card and drops its buffered answer). A bare glyph with
    // no background, tucked just left of the progress text at the top. Drawn LAST so it sits above
    // the full-screen reveal-clickable Column, which would otherwise eat the tap. The trailing
    // padding both spaces the glyph and widens the tap target.
    if (session.canUndo()) {
      Text(
        text = "↩",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
          Modifier.align(Alignment.TopCenter)
            .offset(x = (-30).dp, y = 8.dp)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) {
              undoLastAnswer(session)
            }
            .padding(8.dp),
      )
    }
  }

  /** Revealed card: front + answer scrolling under the curved grade buttons. */
  @Composable
  private fun BoxScope.ReviewAnswer(
    session: ReviewSession,
    card: WearosCard,
    displayFront: String,
    displayBack: String,
    scrollState: androidx.compose.foundation.ScrollState,
    cardStartTime: Long,
    gradeEnabled: Boolean,
    curvedStyle: CurvedTextStyle,
    onGrade: (ease: Int, durationMs: Long) -> Unit,
  ) {
    val duration = System.currentTimeMillis() - cardStartTime
    TopCurvedProgress(session.getProgressString(), curvedStyle)
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(scrollState)
          .rotaryScroll(scrollState)
          // Extra bottom space so the answer can scroll past the overlay buttons and every line can
          // be brought fully into the clear above them.
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
    GradeEdgeButtons(card = card, enabled = gradeEnabled) { ease -> onGrade(ease, duration) }
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
        HtmlText(html = fallbackHtml, fontScale = fontScale, cardFont = cardFont, modifier = modifier)
      }
    } else {
      HtmlText(html = fallbackHtml, fontScale = fontScale, cardFont = cardFont, modifier = modifier)
    }
  }

  /** "Learn new cards" chip that expands into a 5/10/20 chooser; shared by Deck and Finished. */
  private fun TransformingLazyColumnScope.newCardControlItems(
    picking: Boolean,
    transformSpec: TransformationSpec,
    setPicking: (Boolean) -> Unit,
  ) {
    val purpleBg = Color(0xFF7E57C2).copy(alpha = 0.3f)
    val purpleFg = Color(0xFFB39DDB)
    if (!picking) {
      item {
        Button(
          onClick = { setPicking(true) },
          colors = ButtonDefaults.buttonColors(containerColor = purpleBg, contentColor = purpleFg),
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
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
          modifier = Modifier.transformedHeight(this, transformSpec),
        )
      }
      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.transformedHeight(this, transformSpec),
        ) {
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
          transformation = SurfaceTransformation(transformSpec),
          modifier = Modifier.transformedHeight(this, transformSpec),
        ) {
          Text("Cancel")
        }
      }
    }
  }

  @Composable
  fun FinishedScreen(session: ReviewSession) {
    var pickingNewCount by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()
    val transformSpec = rememberEdgeMorphSpec()
    // Tally what was graded this session (recomputed if an undo/answer changed it).
    val summary = remember(cardCompletedTrigger) { session.getSummary() }
    ScreenScaffold(scrollState = listState) { contentPadding ->
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
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
        // Session recap: how many reviews (relearned cards count more than once), ease split, time.
        if (summary.total > 0) {
          item {
            Card(
              onClick = {},
              transformation = SurfaceTransformation(transformSpec),
              modifier = Modifier.fillMaxWidth(0.9f).transformedHeight(this, transformSpec),
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatNumeral(
                  label = "Reviews",
                  value = "${summary.total}",
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = "in ${formatDuration(summary.totalTimeMs)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
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
          newCardControlItems(pickingNewCount, transformSpec) { pickingNewCount = it }
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

  /**
   * A labelled row of mutually-exclusive choice chips; the active one is filled, the rest tonal.
   */
  @Composable
  private fun <T> settingChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
  ) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
              if (value == selected) {
                ButtonDefaults.buttonColors()
              } else {
                ButtonDefaults.filledTonalButtonColors()
              },
          ) {
            Text(text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }

  /** A full-width settings toggle row that morphs with the edge-hugging list like the other items. */
  private fun TransformingLazyColumnScope.settingSwitchItem(
    checked: Boolean,
    label: String,
    secondaryLabel: String,
    transformSpec: TransformationSpec,
    onCheckedChange: (Boolean) -> Unit,
  ) {
    item {
      SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = { Text(label) },
        secondaryLabel = { Text(secondaryLabel) },
        transformation = SurfaceTransformation(transformSpec),
        modifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec),
      )
    }
  }

  @Composable
  fun SettingsScreen() {
    val listState = rememberTransformingLazyColumnState()
    val transformSpec = rememberEdgeMorphSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
      TransformingLazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().rotaryScroll(listState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item {
          Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 6.dp).transformedHeight(this, transformSpec),
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
            modifier = Modifier.transformedHeight(this, transformSpec),
          ) { mode ->
            themeMode = mode
            WatchSettings.setThemeMode(this@MainActivity, mode)
          }
        }
        item {
          settingChoiceRow(
            label = "Text size",
            options = listOf(0.9f to "S", 1.0f to "M", 1.3f to "L", 1.6f to "XL", 1.8f to "XXL"),
            selected = fontScale,
            modifier = Modifier.transformedHeight(this, transformSpec),
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
            modifier = Modifier.transformedHeight(this, transformSpec),
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
            modifier = Modifier.transformedHeight(this, transformSpec),
          ) { font ->
            cardFont = font
            WatchSettings.setCardFont(this@MainActivity, font)
          }
        }
        settingSwitchItem(
          checked = ttsEnabled,
          label = "Voice reviews",
          secondaryLabel = "Read cards aloud (TTS)",
          transformSpec = transformSpec,
        ) {
          ttsEnabled = it
          WatchSettings.setTtsEnabled(this@MainActivity, it)
        }
        item {
          settingChoiceRow(
            label = "Speech rate",
            options = listOf(0.7f to "Slow", 1.0f to "Normal", 1.4f to "Fast"),
            selected = speechRate,
            modifier = Modifier.transformedHeight(this, transformSpec),
          ) { rate ->
            speechRate = rate
            WatchSettings.setSpeechRate(this@MainActivity, rate)
            tts?.setSpeechRate(rate)
          }
        }
        settingSwitchItem(
          checked = keepScreenOn,
          label = "Keep screen on",
          secondaryLabel = "During review · uses battery",
          transformSpec = transformSpec,
        ) {
          keepScreenOn = it
          WatchSettings.setKeepScreenOn(this@MainActivity, it)
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
          EdgeButton(onClick = { showSettings = false }, buttonSize = EdgeButtonSize.Medium) {
            Icon(painter = painterResource(id = R.drawable.ic_check), contentDescription = "Done")
          }
        }
      }
    }
  }
}
