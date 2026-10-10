package com.ichi2.anki.wearos.wear

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.layout.size
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.wearos_core.*

/** A deck shown in the picker; [dueNow] is null when the deck has not been pooled on the watch yet. */
internal data class DeckEntry(val id: Long, val name: String, val dueNow: Int?)

/**
 * A screen on the wear back stack. [Decks] carries the `::` folder being browsed, so descending into
 * a folder pushes another [Decks] and the up row / back gesture pops it.
 */
internal sealed interface WatchScreen {
  data class Decks(val folderPath: String) : WatchScreen

  data object Settings : WatchScreen

  /** Deck overview; shows the finished recap once the session has no current card. */
  data object Session : WatchScreen

  /** The card in front of the user; falls back to the finished recap if the queue empties. */
  data object Review : WatchScreen
}

class MainActivity : ComponentActivity() {
  companion object {
    private const val TAG = "WatchMainActivity"
    // How long to keep the sync-progress arc up waiting for the phone's batch before giving up.
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
    /** Deck to open on launch, set by the AppFunctions "startReview" intent. */
    internal const val EXTRA_OPEN_DECK_ID = "open_deck_id"
  }

  // Deck-list, screen-stack and live-session state lives in the ViewModel so it survives
  // recreation; this Activity stays the review/sync controller (TTS, Data Layer, receiver).
  internal val vm: WatchViewModel by viewModels()

  internal val reviewSession: ReviewSession?
    get() = vm.reviewSession

  internal var cardCompletedTrigger: Int
    get() = vm.cardCompletedTrigger
    set(value) {
      vm.cardCompletedTrigger = value
    }

  internal var outstandingAnswersCount by mutableStateOf(0)
  // Age (seconds) of the oldest un-synced review, to nudge the user when reviews go stale.
  internal var outstandingOldestSecs by mutableLongStateOf(0L)
  // Reviews completed since local midnight (motivational counter).
  internal var reviewedToday by mutableStateOf(0)
  internal var ttsEnabled by mutableStateOf(false)

  // User settings mirrored into Compose state (persisted via WatchSettings). Changing any of these
  // recomposes the card text immediately.
  internal var fontScale by mutableFloatStateOf(1.0f)
  internal var speechRate by mutableFloatStateOf(1.0f)
  internal var cardFont by mutableStateOf(WatchSettings.CardFont.DEFAULT)
  internal var keepScreenOn by mutableStateOf(false)
  internal var hideClock by mutableStateOf(false)
  internal var hideProgress by mutableStateOf(false)
  internal var coloredGradeButtons by mutableStateOf(true)
  // True while the review's type-in field is focused (the IME is up); see WearAppScreen.
  internal var typeAnswerFocused by mutableStateOf(false)

  // The back stack of screens, rooted at the deck picker; descending into folders, opening settings,
  // entering a review and its card screen each push an entry.
  internal val backStack: MutableList<WatchScreen>
    get() = vm.backStack

  // Deck the user tapped to download; loaded automatically once its batch arrives from the phone.
  // Compose state so the UI can show a sync-progress arc while a download is in flight.
  internal var pendingDeckId: Long? by mutableStateOf(null)
  // The deck currently open in a review session (for requesting more new cards of it).
  internal val activeDeckId: Long?
    get() = vm.activeDeckId
  // Whether the pending request was for new cards, so we can report when none came back.
  internal var pendingIsNew = false
  // Phone-communication failure to surface as a transient M3 dialog; null means no failure showing.
  internal var phoneFailure by mutableStateOf<String?>(null)

  internal var tts: TextToSpeech? = null
  internal val scope
    get() = lifecycleScope

  // The check reads fragment 1.1.0 off the compile classpath (via the complications library), but
  // this is a ComponentActivity, not a FragmentActivity, so the old-fragment bug can't occur.
  @SuppressLint("InvalidFragmentVersionForActivityResult")
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) refreshOngoing()
    }

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
    installSplashScreen().setKeepOnScreenCondition { !vm.decksLoaded }
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
    // AppScaffold paints the background; the window's own would be a second full-screen fill under
    // it on every frame. The starting window still uses the theme background.
    window.setBackgroundDrawable(null)
    // After process death the relaunch intent still carries the deck extra; the ViewModel has
    // already restored that session, so only a fresh launch opens the deck from it.
    if (savedInstanceState == null) openDeckFrom(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    openDeckFrom(intent)
  }

  /** Open the deck an assistant asked for (the AppFunctions "startReview" intent). */
  private fun openDeckFrom(intent: Intent) {
    val deckId = intent.getLongExtra(EXTRA_OPEN_DECK_ID, -1L)
    if (deckId < 0) return
    intent.removeExtra(EXTRA_OPEN_DECK_ID)
    // The ongoing-activity chip carries the deck too; tapping it must not restart a live session.
    if (vm.hasSessionFor(deckId)) return
    loadDeckSession(deckId)
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(syncReceiver)
    tts?.stop()
    tts?.shutdown()
    // The ViewModel frees the native session; recreation must keep it.
    if (isFinishing && !isChangingConfigurations) ReviewOngoing.hide(this)
  }

  override fun onStop() {
    super.onStop()
    // Leaving the app mid-session still hands the buffered grades over, rather than waiting for the
    // next launch.
    AnswerUploadWorker.scheduleNow(this)
    // Grades change the due counts the tile, complication and widget show; refresh them once on the
    // way out instead of after every card.
    WearSurfaceUpdater.requestRefresh(this)
  }

  /** Push a screen onto the back stack (folders, settings, session, review). */
  internal fun pushScreen(screen: WatchScreen) {
    backStack.add(screen)
  }

  /**
   * Pop the top entry and run the side effects a system back would: leaving the deck overview ends
   * the session. Pop and clear live together here so the two can't drift at the call sites.
   */
  internal fun popBackStackAndRunSideEffects() {
    if (backStack.size <= 1) return
    if (backStack.last() == WatchScreen.Session) {
      clearSession()
    } else {
      backStack.removeAt(backStack.lastIndex)
    }
  }

  /** Free the native [ReviewSession] and drop its [WatchScreen.Session]/[WatchScreen.Review] entries. */
  internal fun clearSession() {
    vm.replaceSession(null, null)
    backStack.removeAll { it == WatchScreen.Session || it == WatchScreen.Review }
    ReviewOngoing.hide(this)
  }

  /** Mirror the open session into the ongoing activity; hidden once the queue is done. */
  internal fun refreshOngoing() {
    val session = reviewSession
    val deckId = activeDeckId
    if (session == null || deckId == null || session.getCurrentCard() == null) {
      ReviewOngoing.hide(this)
    } else {
      ReviewOngoing.show(this, deckId, session.getDeckName(), session.getProgressString())
    }
  }

  /** Ask for notifications in context (starting a review), which the ongoing activity needs. */
  internal fun requestReviewNotificationsIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReviewOngoing.canPost(this)) {
      notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  /**
   * Build a review session from the deck's pooled cards, entirely offline. When [toastIfEmptyNew]
   * is set (a "learn new cards" request), warn if nothing studyable came back once the session is
   * loaded.
   */
  private fun loadDeckSession(deckId: Long, toastIfEmptyNew: Boolean = false) {
    val app = applicationContext
    // viewModelScope: a load in flight across a recreation still lands in the kept session.
    vm.viewModelScope.launch {
      try {
        val nowMs = systemWatchClock.nowMs()
        val offset = utcOffsetSecs(nowMs)
        // Building today's queue from the pool is blocking SQLite work; keep it off the main thread.
        val session =
          withContext(Dispatchers.IO) { ReviewSession.fromPool(deckId, nowMs, offset) }
        vm.replaceSession(session, deckId)
        // A fresh session lands on the deck overview: drop any Review entry left from the previous
        // one, then push Session unless it is already on top (a learn-new-cards reload).
        backStack.removeAll { it == WatchScreen.Review }
        if (backStack.lastOrNull() != WatchScreen.Session) backStack.add(WatchScreen.Session)
        Log.i(
          TAG,
          "Loaded deck '${session.getDeckName()}' with ${session.getTotalCards()} cards from pool.",
        )
        if (toastIfEmptyNew && session.getTotalCards() == 0) {
          Toast.makeText(
              app,
              "No studyable new cards (multi-template notes are skipped).",
              Toast.LENGTH_LONG,
            )
            .show()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load pooled deck $deckId: ${e.message}", e)
        Toast.makeText(app, "Could not open deck.", Toast.LENGTH_SHORT).show()
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

  /** Show a short toast; [length] defaults to short like most call sites want. */
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
        val sent = sendToConnectedPhones(this@MainActivity, path, payload)
        launch(Dispatchers.Main) { if (sent == 0) onNotConnected() else onSent() }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to send $path to phone: ${e.message}", e)
        launch(Dispatchers.Main) { onError(e) }
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
    sendToPhone(WearPaths.REQUEST_DECK, deckId.toString().toByteArray())
  }

  internal fun requestDeckFromPhone(deckId: Long) =
    requestFromPhone(deckId, WearPaths.REQUEST_DECK, deckId.toString().toByteArray(), isNew = false) {
      "Syncing deck from phone..."
    }

  /**
   * One on-demand request to the phone with the shared pending/timeout plumbing: the deck goes into
   * [pendingDeckId] so the sync arc shows until its batch lands, and [startDownloadTimeout] clears
   * it if the phone never answers. Failures clear the arc and surface as the failure dialog.
   */
  private fun requestFromPhone(
    deckId: Long,
    path: String,
    payload: ByteArray,
    isNew: Boolean,
    sentToast: () -> String,
  ) {
    pendingDeckId = deckId
    pendingIsNew = isNew
    sendToPhone(
      path,
      payload,
      onNotConnected = {
        pendingDeckId = null // stop the sync arc; nothing is coming
        phoneFailure = "Phone not connected"
      },
      onSent = {
        toast(sentToast())
        startDownloadTimeout(deckId)
      },
      onError = {
        pendingDeckId = null
        phoneFailure = "Couldn't reach phone"
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
        phoneFailure = "Phone didn't respond"
      }
    }
  }

  /**
   * Refresh several decks' pools from the phone without opening sessions or the one-deck sync arc
   * (a folder refresh). Each arriving batch lands in the pool on its own; failures surface on the
   * next single-deck refresh.
   */
  internal fun refreshDecksSilently(deckIds: List<Long>) {
    if (deckIds.isEmpty()) return
    deckIds.forEach { id -> sendToPhone(WearPaths.REQUEST_DECK, id.toString().toByteArray()) }
    toast("Refreshing ${deckIds.size} decks...")
  }

  /**
   * Drop the decks' pooled copies from the watch. They stay in the known list as "Tap to
   * download", so this frees the pool's storage without hiding the decks; a deck with its session
   * open has that session closed too.
   */
  internal fun removeDecksFromWatch(deckIds: List<Long>) {
    if (deckIds.isEmpty()) return
    scope.launch {
      try {
        // The keep list is read fresh from the pool: a stale cached list could forget a deck the
        // user never asked to remove.
        val keep =
          withContext(Dispatchers.IO) { poolSummariesNow().map { it.deckId }.filterNot { it in deckIds } }
        withContext(Dispatchers.IO) { poolForgetDecks(keep) }
        val active = activeDeckId
        if (active != null && deckIds.contains(active)) clearSession()
        vm.loadDecks()
        toast(if (deckIds.size == 1) "Removed from watch" else "Removed ${deckIds.size} decks")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Couldn't remove decks $deckIds: ${e.message}", e)
        toast("Couldn't remove")
      }
    }
  }

  /** Ask the phone for [count] new cards of [deckId] to study ahead; auto-loads when it arrives. */
  internal fun requestNewCardsFromPhone(deckId: Long, count: Int) =
    requestFromPhone(deckId, WearPaths.REQUEST_NEW, "$deckId:$count".toByteArray(), isNew = true) {
      "Fetching $count new cards..."
    }

  internal fun requestDecksFromPhone() {
    sendToPhone(
      WearPaths.REQUEST_DECKS,
      onNotConnected = { phoneFailure = "Phone not connected" },
      onSent = { toast("Refreshing decks list...") },
      onError = { phoneFailure = "Couldn't reach phone" },
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
        reviewedToday = reviewsSince(localMidnightSecs())
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

  internal fun answerCardSafely(
    session: ReviewSession,
    ease: Int,
    duration: Long,
    onDone: () -> Unit = {},
  ) {
    val app = applicationContext
    // viewModelScope so a grade in flight across a recreation still moves the kept session on.
    vm.viewModelScope.launch {
      try {
        Log.d(TAG, "Answering card with ease: $ease, duration: $duration")
        kotlinx.coroutines.withContext(Dispatchers.IO) { session.answerCard(ease, duration) }
        updateOutstandingCount()
        // The grade moved the card in the pool, so the picker's due counts are stale.
        vm.loadDecks()
        // Hand the grade over soon without putting on every single answer (each put triggers a full
        // write-back pass on the phone).
        AnswerUploadWorker.scheduleDebounced(app)
        cardCompletedTrigger++ // Increment to force recomposition of the review screen!
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // The core restores the card on a failed buffer write (loud-failure contract) so the user
        // can re-grade; tell them, otherwise the screen just sits on the same card looking dead.
        Log.e(TAG, "Failed to answer card securely via Rust FFI: ${e.message}", e)
        Toast.makeText(app, "Couldn't save review, try again", Toast.LENGTH_SHORT).show()
      } finally {
        onDone()
      }
    }
  }

  /**
   * Undo the last grade: Rust steps the queue back and drops the buffered answer; refresh the UI.
   */
  internal fun undoLastAnswer(session: ReviewSession) {
    vm.viewModelScope.launch {
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

  internal fun speak(text: String) {
    if (ttsEnabled) {
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card_tts")
    }
  }

  @Composable
  internal fun WearAppScreen() {
    AnkiWearTheme {
      Box(modifier = Modifier.fillMaxSize()) {
        AppScaffold {
          // Swipe-right-to-dismiss + predictive back come from the Wear scene strategy; hardware and
          // gesture back both route through onBack, which pops the stack (and clears the session
          // when leaving the deck overview). At the root entry back is disabled and the system exits.
          // While the type-in field is focused the gesture is off: an edge swipe mid-typing would
          // pop the review and throw away the typed text. The Wear flag cannot separate swipe from
          // back, so back goes with it; in practice BACK closes the IME first, which unfocuses the
          // field and re-enables both before the pop lands, so back still leaves.
          NavDisplay(
            backStack = backStack,
            onBack = { popBackStackAndRunSideEffects() },
            sceneStrategy =
              rememberSwipeDismissableSceneStrategy(
                isUserSwipeEnabled =
                  backStack.lastOrNull() != WatchScreen.Review || !typeAnswerFocused
              ),
            entryProvider =
              entryProvider<WatchScreen> {
                entry<WatchScreen.Decks> { WelcomeScreen(it.folderPath) }
                entry<WatchScreen.Settings> { SettingsScreen() }
                entry<WatchScreen.Session> { SessionScreen() }
                entry<WatchScreen.Review> { ReviewScreen() }
              },
          )
          // Drawn after the navigation host so a phone-communication failure overlays whichever
          // screen is on top.
          PhoneFailureDialog(phoneFailure) { phoneFailure = null }
        }
        LaunchedEffect(reviewSession, cardCompletedTrigger) { refreshOngoing() }
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

  /** Deck overview reached from the picker; shows the finished recap once the queue has emptied. */
  @Composable
  private fun SessionScreen() {
    val session = reviewSession ?: return
    // Keying on cardCompletedTrigger subscribes this scope, so answering/undoing a card recomposes
    // it onto the next card (or the finished screen when the queue empties).
    val currentCard = remember(session, cardCompletedTrigger) { session.getCurrentCard() }
    if (currentCard == null) {
      FinishedScreen(session)
    } else {
      DeckScreen(session)
    }
  }

  /** The card in front of the user; falls back to the finished recap if the queue empties. */
  @Composable
  private fun ReviewScreen() {
    val session = reviewSession ?: return
    val currentCard = remember(session, cardCompletedTrigger) { session.getCurrentCard() }
    if (currentCard == null) {
      FinishedScreen(session)
    } else {
      // Every presentation starts fresh (unrevealed, scrolled to the top, timer reset), even when
      // a requeued card comes straight back with the same id.
      key(session, cardCompletedTrigger) { ReviewCardScreen(session, currentCard) }
    }
  }
}
