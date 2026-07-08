package fi.nikosavola.ankidroid.wearos.wear

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
  private var isReviewing by mutableStateOf(false)
  private var ttsEnabled by mutableStateOf(false)

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
              loadDeckSession(pending)
              if (wasNew && (reviewSession?.getTotalCards() ?: 0) == 0) {
                Toast.makeText(
                    this@MainActivity,
                    "No studyable new cards (multi-template notes are skipped).",
                    Toast.LENGTH_LONG,
                  )
                  .show()
              }
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

    // 3. Load the list of decks (cached locally + known from the phone). Start on the picker.
    loadDecks()
    updateOutstandingCount()

    // 4. Register Receiver
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
  }

  /** Refresh both the locally-downloaded decks and the full deck list synced from the phone. */
  private fun loadDecks() {
    cachedDecks = DeckCache.listCached(this)
    try {
      val cacheFile = File(filesDir, "decks_cache.txt")
      knownDecks =
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
      Log.d(TAG, "Loaded ${cachedDecks.size} cached decks, ${knownDecks.size} known decks.")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load decks list: ${e.message}", e)
      knownDecks = emptyList()
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

  /** Load an already-downloaded deck into a review session entirely offline. */
  private fun loadDeckSession(deckId: Long) {
    try {
      val bytes = DeckCache.deckFile(this, deckId).readBytes()
      val batch = deserializeDueBatch(bytes)
      reviewSession = ReviewSession(batch)
      activeDeckId = deckId
      isReviewing = false
      Log.i(TAG, "Loaded deck '${batch.deckName}' with ${batch.cards.size} cards from cache.")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load cached deck $deckId: ${e.message}", e)
      Toast.makeText(this, "Could not open deck.", Toast.LENGTH_SHORT).show()
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

  private fun updateOutstandingCount() {
    scope.launch(Dispatchers.IO) {
      try {
        val answers = getBufferedAnswers()
        outstandingAnswersCount = answers.size
        Log.d(TAG, "Outstanding local buffered reviews count: $outstandingAnswersCount")
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
    MaterialTheme {
      val listState = rememberScalingLazyListState()

      // Safe, native physical Back Button navigation logic
      val session = reviewSession
      if (isReviewing) {
        androidx.activity.compose.BackHandler { isReviewing = false }
      } else if (session != null) {
        androidx.activity.compose.BackHandler { reviewSession = null }
      }

      Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        @Suppress("UNUSED_VARIABLE")
        val trigger =
          cardCompletedTrigger // Subscribes this composable context to our recomposition trigger!
        if (session == null) {
          WelcomeScreen(listState)
        } else {
          val currentCard = session.getCurrentCard()
          if (currentCard == null) {
            FinishedScreen(listState)
          } else if (!isReviewing) {
            DeckScreen(listState, session)
          } else {
            ReviewCardScreen(session, currentCard)
          }
        }
      }
    }
  }

  @Composable
  fun WelcomeScreen(listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState) {
    val entries = deckEntries()
    ScalingLazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item {
        Text(
          text = "AnkiDroid Wear",
          style = MaterialTheme.typography.title3,
          modifier = Modifier.padding(bottom = 6.dp),
        )
      }

      if (entries.isEmpty()) {
        item {
          Text(
            text = "Tap Refresh to load your decks, or open the phone app and press Sync.",
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            textAlign = TextAlign.Center,
          )
        }
      } else {
        item {
          Text(
            text = "Select a Deck:",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.secondary,
            modifier = Modifier.padding(bottom = 4.dp),
          )
        }
        items(entries) { entry ->
          Card(onClick = { selectDeck(entry.id) }, modifier = Modifier.fillMaxWidth(0.95f)) {
            Column {
              Text(
                text = entry.name,
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
              )
              Text(
                text =
                  if (entry.cardCount != null) {
                    "${entry.cardCount} cards ready"
                  } else {
                    "Tap to download"
                  },
                style = MaterialTheme.typography.caption3,
                color =
                  if (entry.cardCount != null) Color(0xFF66BB6A) else MaterialTheme.colors.secondary,
              )
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(8.dp)) }
      item {
        Button(
          onClick = {
            requestDecksFromPhone()
            loadDecks()
          }
        ) {
          Icon(
            painter = painterResource(id = R.drawable.ic_refresh),
            contentDescription = "Refresh decks",
          )
        }
      }
    }
  }

  @Composable
  fun DeckScreen(
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    session: ReviewSession,
  ) {
    var pickingNewCount by remember { mutableStateOf(false) }
    ScalingLazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item {
        Text(
          text = "📚 Deck Stats",
          style = MaterialTheme.typography.caption1,
          color = MaterialTheme.colors.secondary,
          modifier = Modifier.padding(bottom = 2.dp),
        )
      }
      item {
        Text(
          text = session.getDeckName(),
          style = MaterialTheme.typography.title3,
          modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )
      }
      item {
        Card(onClick = {}, modifier = Modifier.fillMaxWidth(0.9f)) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "Due Cards: ${session.getTotalCards()}",
              style = MaterialTheme.typography.body2,
            )
            Text(
              text = "Progress: ${session.getProgressString()}",
              style = MaterialTheme.typography.caption2,
              color = Color.LightGray,
            )
          }
        }
      }
      item {
        Chip(
          label = { Text("Start Review") },
          onClick = { isReviewing = true },
          colors = ChipDefaults.primaryChipColors(),
          modifier = Modifier.fillMaxWidth(0.9f),
        )
      }
      // Study new cards ahead of the daily queue limit. Only available when we know the deck id.
      if (activeDeckId != null) {
        newCardControlItems(pickingNewCount) { pickingNewCount = it }
      }
      item {
        ToggleChip(
          checked = ttsEnabled,
          onCheckedChange = { ttsEnabled = it },
          label = { Text("Voice Reviews") },
          secondaryLabel = { Text(if (ttsEnabled) "Speaker On 🔊" else "Speaker Off 🔇") },
          toggleControl = {
            Icon(
              imageVector = ToggleChipDefaults.switchIcon(checked = ttsEnabled),
              contentDescription = "TTS Switch",
            )
          },
          modifier = Modifier.fillMaxWidth(0.9f),
        )
      }
      if (outstandingAnswersCount > 0) {
        item {
          Chip(
            label = { Text("Sync $outstandingAnswersCount Reviews") },
            onClick = { triggerAnswersSync() },
            colors =
              ChipDefaults.secondaryChipColors(
                backgroundColor = Color(0xFF66BB6A).copy(alpha = 0.2f),
                contentColor = Color(0xFF66BB6A),
              ),
            modifier = Modifier.fillMaxWidth(0.9f),
          )
        }
      }
      item {
        Chip(
          label = { Text("Change Deck") },
          onClick = { reviewSession = null },
          colors = ChipDefaults.chipColors(backgroundColor = Color.DarkGray.copy(alpha = 0.4f)),
          modifier = Modifier.fillMaxWidth(0.9f),
        )
      }
    }
  }

  @Composable
  fun ReviewCardScreen(session: ReviewSession, card: WearosCard) {
    var cardStartTime by
      remember(card.id, card.ord) { mutableLongStateOf(System.currentTimeMillis()) }
    var isRevealed by remember(card.id, card.ord) { mutableStateOf(session.isRevealed()) }
    var isAnswering by remember(card.id, card.ord) { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Anki answers usually embed the question followed by <hr id=answer>; show only the part after
    // it so the answer view doesn't duplicate the front. Falls back to the whole back if no marker.
    val answerHtml =
      remember(card.back) {
        val marker = card.back.indexOf("id=answer")
        if (marker >= 0) {
          val end = card.back.indexOf('>', marker)
          if (end >= 0) card.back.substring(end + 1) else card.back
        } else {
          card.back
        }
      }

    // Rich HTML for display; plain text for TTS.
    val displayFront = remember(card.front) { htmlForDisplay(card.front) }
    val displayBack = remember(answerHtml) { htmlForDisplay(answerHtml) }
    val cleanFront = remember(card.front) { cleanHtml(card.front) }
    val cleanBack = remember(answerHtml) { cleanHtml(answerHtml) }

    // TTS voice effects triggered dynamically via state change
    LaunchedEffect(card.id, card.ord) { speak(cleanFront) }
    LaunchedEffect(isRevealed) {
      if (isRevealed) {
        speak(cleanBack)
      }
    }

    if (!isRevealed) {
      // Front: the whole card is tappable to reveal; a small grey hint says so.
      Box(modifier = Modifier.fillMaxSize()) {
        Text(
          text = session.getProgressString(),
          style = MaterialTheme.typography.caption2,
          color = MaterialTheme.colors.secondary,
          modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
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
          HtmlText(html = displayFront, modifier = Modifier.fillMaxWidth())
        }
        Text(
          text = "Tap to show answer",
          style = MaterialTheme.typography.caption3,
          color = Color.LightGray,
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .padding(bottom = 14.dp)
              .background(Color.DarkGray.copy(alpha = 0.55f), RoundedCornerShape(50))
              .padding(horizontal = 10.dp, vertical = 3.dp),
        )
      }
    } else {
      // Answer: the card fills the screen and scrolls under the grade buttons, which overlay the
      // lower bezel with some transparency so the card shows through.
      val duration = System.currentTimeMillis() - cardStartTime
      Box(modifier = Modifier.fillMaxSize()) {
        Text(
          text = session.getProgressString(),
          style = MaterialTheme.typography.caption2,
          color = MaterialTheme.colors.secondary,
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
          HtmlText(html = displayFront, modifier = Modifier.fillMaxWidth())
          Spacer(
            modifier =
              Modifier.padding(vertical = 6.dp)
                .fillMaxWidth(0.5f)
                .height(1.dp)
                .background(Color.DarkGray)
          )
          HtmlText(html = displayBack, modifier = Modifier.fillMaxWidth())
        }
        GradeEdgeButtons(card = card, enabled = !isAnswering) { ease, autoSync ->
          if (!isAnswering) {
            isAnswering = true
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
          CompactChip(
            onClick = { if (enabled) onGrade(g.ease, g.autoSync) },
            label = { Text(g.text, maxLines = 1, style = MaterialTheme.typography.caption3) },
            colors =
              ChipDefaults.chipColors(
                backgroundColor = g.color.copy(alpha = 0.32f),
                contentColor = g.color,
              ),
          )
        }
      }
    }
  }

  /**
   * Renders sanitized card HTML (bold, colors, line breaks, etc.) via HtmlCompat in a TextView.
   * Wear OS has no WebView, so this is the richest safe renderer available.
   */
  @Composable
  fun HtmlText(html: String, modifier: Modifier = Modifier) {
    AndroidView(
      modifier = modifier,
      factory = { ctx ->
        TextView(ctx).apply {
          setTextColor(android.graphics.Color.WHITE)
          textSize = 16f
          gravity = Gravity.CENTER
        }
      },
      update = { it.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT) },
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
        Chip(
          label = { Text("Learn new cards") },
          onClick = { setPicking(true) },
          colors = ChipDefaults.chipColors(backgroundColor = purpleBg, contentColor = purpleFg),
          modifier = Modifier.fillMaxWidth(0.9f),
        )
      }
    } else {
      item {
        Text(
          text = "How many new?",
          style = MaterialTheme.typography.caption1,
          color = MaterialTheme.colors.secondary,
        )
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(5, 10, 20).forEach { n ->
            CompactChip(
              label = { Text("$n") },
              onClick = {
                setPicking(false)
                activeDeckId?.let { requestNewCardsFromPhone(it, n) }
              },
              colors = ChipDefaults.chipColors(backgroundColor = purpleBg, contentColor = purpleFg),
              modifier = Modifier.width(56.dp),
            )
          }
        }
      }
      item {
        CompactChip(
          label = { Text("Cancel") },
          onClick = { setPicking(false) },
          colors = ChipDefaults.chipColors(backgroundColor = Color.DarkGray.copy(alpha = 0.4f)),
        )
      }
    }
  }

  @Composable
  fun FinishedScreen(listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState) {
    var pickingNewCount by remember { mutableStateOf(false) }
    ScalingLazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      item {
        Text(
          text = "Nice Job! 🎉",
          style = MaterialTheme.typography.title3,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
      item {
        Text(
          text = "You finished your study queue for now.",
          style = MaterialTheme.typography.body2,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      item {
        Text(
          text = "Outstanding reviews: $outstandingAnswersCount",
          style = MaterialTheme.typography.caption2,
          color = Color.LightGray,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
      // Learn new cards right after finishing the due queue.
      if (activeDeckId != null) {
        newCardControlItems(pickingNewCount) { pickingNewCount = it }
      }
      item {
        Button(
          onClick = {
            triggerAnswersSync()
            isReviewing = false
            reviewSession = null // return to decks selector list
          },
          modifier = Modifier.padding(top = 8.dp),
        ) {
          Text("Sync & Decks")
        }
      }
    }
  }
}
