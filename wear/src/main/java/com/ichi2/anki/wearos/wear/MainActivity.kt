package com.ichi2.anki.wearos.wear

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextToggleButton
import androidx.wear.compose.material3.TextToggleButtonDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.curvedText
import com.google.android.gms.wearable.Wearable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uniffi.wearos_core.*

/** A deck shown in the picker; [dueNow] is null when the deck has not been pooled on the watch yet. */
data class DeckEntry(val id: Long, val name: String, val dueNow: Int?)

class MainActivity : ComponentActivity() {
  companion object {
    private const val TAG = "WatchMainActivity"
    // How long to keep the sync-progress arc up waiting for the phone's batch before giving up.
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
  }

  internal var reviewSession by mutableStateOf<ReviewSession?>(null)
  internal var cardCompletedTrigger by mutableStateOf(0)
  internal var outstandingAnswersCount by mutableStateOf(0)
  // Age (seconds) of the oldest un-synced review, to nudge the user when reviews go stale.
  internal var outstandingOldestSecs by mutableLongStateOf(0L)
  // Reviews completed since local midnight (motivational counter).
  internal var reviewedToday by mutableStateOf(0)
  internal var isReviewing by mutableStateOf(false)
  internal var ttsEnabled by mutableStateOf(false)

  // User settings mirrored into Compose state (persisted via WatchSettings). Changing any of these
  // recomposes the theme / card text immediately.
  internal var themeMode by mutableStateOf(WatchSettings.ThemeMode.DARK)
  internal var fontScale by mutableFloatStateOf(1.0f)
  internal var speechRate by mutableFloatStateOf(1.0f)
  internal var cardFont by mutableStateOf(WatchSettings.CardFont.DEFAULT)
  internal var keepScreenOn by mutableStateOf(false)
  internal var hideClock by mutableStateOf(false)
  internal var hideProgress by mutableStateOf(false)
  internal var coloredGradeButtons by mutableStateOf(true)
  internal var showSettings by mutableStateOf(false)

  // Current folder in the deck picker's `::` hierarchy ("" = root). Descending sets it to a child
  // path; the physical back button and the up row pop it one level.
  internal var deckFolderPath by mutableStateOf("")

  // Deck-list + settings state lives in the ViewModel (survives config changes); this Activity stays
  // the review/sync controller (live session, TTS, Data Layer, receiver).
  internal val vm: WatchViewModel by viewModels()

  // Deck the user tapped to download; loaded automatically once its batch arrives from the phone.
  // Compose state so the UI can show a sync-progress arc while a download is in flight.
  internal var pendingDeckId: Long? by mutableStateOf(null)
  // The deck currently open in a review session (for requesting more new cards of it).
  internal var activeDeckId by mutableStateOf<Long?>(null)
  // Whether the pending request was for new cards, so we can report when none came back.
  internal var pendingIsNew = false

  internal var tts: TextToSpeech? = null
  internal val scope = CoroutineScope(Dispatchers.Main)

  internal val syncReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "Sync broadcast received: ${intent?.action}")
        when (intent?.action) {
          WearSyncService.ACTION_BATCH_UPDATED -> {
            vm.loadDecks()
            val arrived = intent.getLongExtra(WearSyncService.EXTRA_DECK_ID, -1L)
            val pending = pendingDeckId
            if (pending != null) {
              scope.launch {
                try {
                  val pooled =
                    arrived == pending ||
                      withContext(Dispatchers.IO) { poolHasDeck(pending) }
                  if (pooled) {
                    val wasNew = pendingIsNew
                    pendingDeckId = null
                    pendingIsNew = false
                    // toastIfEmptyNew runs the "no studyable new cards" check after the async load.
                    loadDeckSession(pending, toastIfEmptyNew = wasNew)
                  }
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to check pooled deck $pending: ${e.message}", e)
                }
              }
            }
          }
          WearSyncService.ACTION_ANSWERS_CLEARED -> {
            updateOutstandingCount()
            // The phone just applied our answers to AnkiDroid; reconcile the last-reviewed deck
            // with AnkiDroid's real schedule, but only once its pool has aged (see the core).
            activeDeckId?.let { reconcileIfStale(it) }
          }
          WearSyncService.ACTION_DECKS_UPDATED -> {
            vm.loadDecks()
          }
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Rust core database is initialised by WearApp.onCreate.

    // 1. Initialize TTS Engine
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

    // 2. Load persisted user settings (theme, card font) into Compose state.
    themeMode = WatchSettings.themeMode(this)
    fontScale = WatchSettings.fontScale(this)
    cardFont = WatchSettings.cardFont(this)
    keepScreenOn = WatchSettings.keepScreenOn(this)
    hideClock = WatchSettings.hideClock(this)
    hideProgress = WatchSettings.hideProgress(this)
    coloredGradeButtons = WatchSettings.coloredGradeButtons(this)
    ttsEnabled = WatchSettings.ttsEnabled(this)
    speechRate = WatchSettings.speechRate(this)

    // 3. Load the list of decks (pooled locally + known from the phone). Start on the picker.
    vm.loadDecks()
    updateOutstandingCount()

    // Catch-up flush: grades from a previous session that never got an explicit sync (backed out of
    // a review, or the send failed with the phone out of range) would otherwise sit buffered until
    // the user notices the nudge. The worker no-ops when the buffer is empty, and once the
    // putDataItem succeeds the Data Layer guarantees delivery to the phone.
    AnswerUploadWorker.scheduleNow(this)

    // 4. Register Receiver
    val filter =
      IntentFilter().apply {
        addAction(WearSyncService.ACTION_BATCH_UPDATED)
        addAction(WearSyncService.ACTION_ANSWERS_CLEARED)
        addAction(WearSyncService.ACTION_DECKS_UPDATED)
      }
    // Context.RECEIVER_NOT_EXPORTED itself is API 33+; ContextCompat.registerReceiver handles the
    // pre-33 case correctly instead of passing that flag straight to the platform (minSdk is 30).
    androidx.core.content.ContextCompat.registerReceiver(
      this,
      syncReceiver,
      filter,
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
    )

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

  override fun onStop() {
    super.onStop()
    // Leaving the app mid-session still hands the buffered grades over, rather than waiting for the
    // next launch.
    AnswerUploadWorker.scheduleNow(this)
  }

  /** Free the native [ReviewSession] before dropping the reference (see onDestroy). */
  internal fun clearSession() {
    reviewSession?.destroy()
    reviewSession = null
  }

  /** Parent of a `::` deck path; "" when already at a top-level segment. */
  internal fun parentFolder(path: String): String = path.substringBeforeLast("::", "")

  /**
   * Build a review session from the deck's pooled cards, entirely offline. When [toastIfEmptyNew]
   * is set (a "learn new cards" request), warn if nothing studyable came back once the session is
   * loaded.
   */
  private fun loadDeckSession(deckId: Long, toastIfEmptyNew: Boolean = false) {
    scope.launch {
      try {
        val nowMs = systemWatchClock.nowMs()
        val offset = utcOffsetSecs(nowMs)
        // Building today's queue from the pool is blocking SQLite work; keep it off the main thread.
        val session =
          withContext(Dispatchers.IO) { ReviewSession.fromPool(deckId, nowMs, offset) }
        // Free any previous native session before replacing it.
        reviewSession?.destroy()
        reviewSession = session
        activeDeckId = deckId
        isReviewing = false
        Log.i(
          TAG,
          "Loaded deck '${session.getDeckName()}' with ${session.getTotalCards()} cards from pool.",
        )
        if (toastIfEmptyNew && session.getTotalCards() == 0) {
          Toast.makeText(
              this@MainActivity,
              "No studyable new cards (multi-template notes are skipped).",
              Toast.LENGTH_LONG,
            )
            .show()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load pooled deck $deckId: ${e.message}", e)
        Toast.makeText(this@MainActivity, "Could not open deck.", Toast.LENGTH_SHORT).show()
      }
    }
  }

  /** Pick a deck: open it offline if it is pooled, otherwise download it from the phone. */
  internal fun selectDeck(deckId: Long) {
    scope.launch {
      try {
        val pooled = withContext(Dispatchers.IO) { poolHasDeck(deckId) }
        if (pooled) {
          loadDeckSession(deckId)
        } else {
          requestDeckFromPhone(deckId)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to check pooled deck $deckId: ${e.message}", e)
        requestDeckFromPhone(deckId)
      }
    }
  }

  /**
   * Reconcile a deck's cached queue with AnkiDroid's real schedule: re-request its batch WITHOUT
   * opening a session or showing the sync arc (no pendingDeckId). The arriving batch is fetched live
   * from AnkiDroid - reflecting the answers just written back - and refreshes the local cache, so the
   * next open shows the true next-due instead of the in-session requeue approximation. Best-effort:
   * on failure the deck still converges on the next manual open/refresh.
   */
  private fun toast(message: String, length: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, length).show()
  }

  /**
   * Send [payload] to [path] on every connected phone node, off the main thread. Callbacks run on
   * the main thread: [onNotConnected] when no node is reachable, [onSent] after a successful
   * broadcast, [onError] on failure (all default to no-ops for fire-and-forget sends).
   */
  private fun sendToPhone(
    path: String,
    payload: ByteArray = ByteArray(0),
    onNotConnected: () -> Unit = {},
    onSent: () -> Unit = {},
    onError: (Exception) -> Unit = {},
  ) {
    scope.launch(Dispatchers.IO) {
      try {
        val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
        if (nodes.isEmpty()) {
          launch(Dispatchers.Main) { onNotConnected() }
          return@launch
        }
        val messageClient = Wearable.getMessageClient(this@MainActivity)
        for (node in nodes) {
          messageClient.sendMessage(node.id, path, payload).await()
          Log.d(TAG, "Sent $path to node: ${node.displayName}")
        }
        launch(Dispatchers.Main) { onSent() }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to send $path to phone: ${e.message}", e)
        launch(Dispatchers.Main) { onError(e) }
      }
    }
  }

  private fun reconcileIfStale(deckId: Long) {
    scope.launch {
      val stale =
        withContext(Dispatchers.IO) {
          try {
            poolNeedsReconcile(deckId, systemWatchClock.nowMs())
          } catch (e: CoreException) {
            Log.w(TAG, "Couldn't check deck $deckId's pool age: ${e.message}")
            false
          }
        }
      if (stale) silentlyRefreshDeck(deckId)
    }
  }

  /** Silent reconcile refresh after write-back; failures converge on the next open, so no toasts. */
  private fun silentlyRefreshDeck(deckId: Long) {
    sendToPhone("/request_deck", deckId.toString().toByteArray())
  }

  private fun requestDeckFromPhone(deckId: Long) {
    pendingDeckId = deckId
    pendingIsNew = false
    sendToPhone(
      "/request_deck",
      deckId.toString().toByteArray(),
      onNotConnected = {
        pendingDeckId = null // stop the sync arc; nothing is coming
        toast("Phone not connected! Check Bluetooth.")
      },
      onSent = {
        toast("Syncing deck from phone...")
        startDownloadTimeout(deckId)
      },
      onError = { e ->
        pendingDeckId = null
        toast("Failed to contact phone: ${e.message}")
      },
    )
  }

  /** Stop the sync-progress arc if the phone never sends the batch, instead of spinning forever. */
  private fun startDownloadTimeout(deckId: Long) {
    scope.launch {
      kotlinx.coroutines.delay(DOWNLOAD_TIMEOUT_MS)
      if (pendingDeckId == deckId) {
        pendingDeckId = null
        pendingIsNew = false
        toast("Phone didn't respond — try again.")
      }
    }
  }

  /** Ask the phone for [count] new cards of [deckId] to study ahead; auto-loads when it arrives. */
  private fun requestNewCardsFromPhone(deckId: Long, count: Int) {
    pendingDeckId = deckId
    pendingIsNew = true
    sendToPhone(
      "/request_new",
      "$deckId:$count".toByteArray(),
      onNotConnected = {
        pendingDeckId = null
        toast("Phone not connected!")
      },
      onSent = {
        toast("Fetching $count new cards...")
        startDownloadTimeout(deckId)
      },
      onError = {
        pendingDeckId = null
        toast("Failed to contact phone.")
      },
    )
  }

  internal fun requestDecksFromPhone() {
    sendToPhone(
      "/request_decks",
      onNotConnected = { toast("Phone not connected! Check Bluetooth.") },
      onSent = { toast("Refreshing decks list...") },
      onError = { e -> toast("Failed to contact phone: ${e.message}") },
    )
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

  /**
   * Send every buffered answer to the phone over the Data Layer, with user-facing toasts. [quiet]
   * suppresses them for background flushes where "No reviews to sync!" would be noise; failures are
   * still logged and the answers stay buffered for the next attempt.
   */
  internal fun triggerAnswersSync(quiet: Boolean = false) {
    scope.launch {
      try {
        val message =
          when (uploadBufferedAnswers(this@MainActivity)) {
            UploadResult.Empty -> "No reviews to sync!"
            is UploadResult.Sent -> "Syncing reviews..."
          }
        if (!quiet) {
          Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Sync back failed: ${e.message}", e)
        if (!quiet) {
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
        // The grade moved the card in the pool, so the picker's due counts are stale.
        vm.loadDecks()
        // Hand the grade over soon without putting on every single answer (each put triggers a full
        // write-back pass on the phone).
        AnswerUploadWorker.scheduleDebounced(this@MainActivity)
        cardCompletedTrigger++ // Increment to force recomposition of the review screen!
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // The core restores the card on a failed buffer write (loud-failure contract) so the user
        // can re-grade; tell them, otherwise the screen just sits on the same card looking dead.
        Log.e(TAG, "Failed to answer card securely via Rust FFI: ${e.message}", e)
        launch(Dispatchers.Main) {
          Toast.makeText(this@MainActivity, "Couldn't save review, try again", Toast.LENGTH_SHORT)
            .show()
        }
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
        // Undo reverted the card in the pool, so the picker's due counts are stale.
        vm.loadDecks()
        cardCompletedTrigger++ // recompose the review screen onto the restored card
      } catch (e: CancellationException) {
        throw e
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

  /**
   * Determinate progress ring hugging the round edge, filling clockwise from the top in proportion to
   * cards graduated this session (matching the N/M label from [TopCurvedProgress]). Drawn first in the
   * review Box so the scrolling card body sits inside it; the theme primary keeps it consistent with
   * the indeterminate download ring on the deck screen.
   */
  @Composable
  private fun ReviewProgressRing(session: ReviewSession) {
    androidx.wear.compose.material3.CircularProgressIndicator(
      progress = { session.getProgressFraction() },
      modifier = Modifier.fillMaxSize().padding(2.dp),
    )
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
  internal fun BoxScope.ReviewFront(
    session: ReviewSession,
    card: WearosCard,
    displayFront: String,
    curvedStyle: androidx.wear.compose.foundation.CurvedTextStyle,
    scrollState: androidx.compose.foundation.ScrollState,
    onReveal: () -> Unit,
  ) {
    if (!hideProgress) {
      ReviewProgressRing(session)
      TopCurvedProgress(session.getProgressString(), curvedStyle)
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
  internal fun BoxScope.ReviewAnswer(
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
    if (!hideProgress) {
      ReviewProgressRing(session)
      TopCurvedProgress(session.getProgressString(), curvedStyle)
    }
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
      GradeIntervalLabels(labels = card.nextReviewTimes)
    }
    GradeEdgeButtons(card = card, enabled = gradeEnabled, colored = coloredGradeButtons) { ease ->
      onGrade(ease, duration)
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
        HtmlText(html = fallbackHtml, fontScale = fontScale, cardFont = cardFont, modifier = modifier)
      }
    } else {
      HtmlText(html = fallbackHtml, fontScale = fontScale, cardFont = cardFont, modifier = modifier)
    }
  }

  /** "Learn new cards" chip that expands into a 5/10/20 chooser; shared by Deck and Finished. */
  internal fun TransformingLazyColumnScope.newCardControlItems(
    picking: Boolean,
    transformSpec: TransformationSpec,
    setPicking: (Boolean) -> Unit,
  ) {
    if (!picking) {
      item {
        Button(
          onClick = { setPicking(true) },
          colors = ButtonDefaults.filledVariantButtonColors(),
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
              colors = ButtonDefaults.filledVariantButtonColors(),
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
    val transformSpec = rememberEdgeMorphSpec()
    // Tally what was graded this session (recomputed if an undo/answer changed it).
    val summary = remember(cardCompletedTrigger) { session.getSummary() }
    // Learning cards of this deck that come due later today, so finishing the queue can say when.
    val pooledDeck = activeDeckId?.let { id -> vm.cachedDecks.firstOrNull { it.deckId == id } }
    val nowSecs = systemWatchClock.nowMs() / 1000
    FinishedScreenContent(
      summary = summary,
      reviewedToday = reviewedToday,
      outstandingCount = outstandingAnswersCount,
      learningLater = pooledDeck?.learningLater ?: 0,
      learningDueInMinutes =
        pooledDeck?.nextLearningDueSecs?.let { ((it - nowSecs) / 60).coerceAtLeast(0) },
      transformSpec = transformSpec,
      onSyncAndBack = {
        triggerAnswersSync()
        isReviewing = false
        clearSession() // return to decks selector list
      },
      // Learn new cards right after finishing the due queue (needs the new-card request plumbing).
      // Present only when a deck is active; null hides the controls.
      newCardControls =
        activeDeckId?.let {
          { newCardControlItems(pickingNewCount, transformSpec) { picking -> pickingNewCount = picking } }
        },
    )
  }

  /**
   * A labelled group of mutually-exclusive [TextToggleButton]s; the selected one is checked and
   * morphs. Groups of more than three options wrap onto extra rows so the labels stay legible at
   * watch width.
   */
  @Composable
  internal fun <T> settingChoiceRow(
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
      options.chunked(3).forEach { row ->
        ButtonGroup(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
          row.forEach { (value, text) ->
            val interactionSource = remember { MutableInteractionSource() }
            TextToggleButton(
              checked = value == selected,
              onCheckedChange = { onSelect(value) },
              shapes = TextToggleButtonDefaults.variantAnimatedShapes(),
              interactionSource = interactionSource,
              modifier = Modifier.weight(1f).animateWidth(interactionSource),
            ) {
              Text(text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }

  /** A full-width settings toggle row that morphs with the edge-hugging list like the other items. */
  internal fun TransformingLazyColumnScope.settingSwitchItem(
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

  /** The minimal-view appearance toggles (grade-button color + hide clock/progress in review). */
  internal fun TransformingLazyColumnScope.appearanceToggles(transformSpec: TransformationSpec) {
    settingSwitchItem(
      checked = coloredGradeButtons,
      label = "Grade colors",
      secondaryLabel = "Color the buttons",
      transformSpec = transformSpec,
    ) {
      coloredGradeButtons = it
      WatchSettings.setColoredGradeButtons(this@MainActivity, it)
    }
    settingSwitchItem(
      checked = hideProgress,
      label = "Hide progress",
      secondaryLabel = "The N/M count",
      transformSpec = transformSpec,
    ) {
      hideProgress = it
      WatchSettings.setHideProgress(this@MainActivity, it)
    }
    settingSwitchItem(
      checked = hideClock,
      label = "Hide clock",
      secondaryLabel = "The time",
      transformSpec = transformSpec,
    ) {
      hideClock = it
      WatchSettings.setHideClock(this@MainActivity, it)
    }
  }
}
