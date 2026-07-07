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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
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
              pendingDeckId = null
              loadDeckSession(pending)
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
            ReviewCardScreen(listState, session, currentCard)
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
      modifier = Modifier.fillMaxSize(),
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
    ScalingLazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
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
  fun ReviewCardScreen(
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    session: ReviewSession,
    card: WearosCard,
  ) {
    var cardStartTime by
      remember(card.id, card.ord) { mutableLongStateOf(System.currentTimeMillis()) }
    var isRevealed by remember(card.id, card.ord) { mutableStateOf(session.isRevealed()) }
    var isAnswering by remember(card.id, card.ord) { mutableStateOf(false) }

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

    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Header Progress & Stats
      Text(
        text = session.getProgressString(),
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.secondary,
        modifier = Modifier.padding(top = 4.dp),
      )

      // Wear OS has no WebView provider, so render the sanitized card text natively. Takes all
      // remaining vertical space and scrolls internally.
      Column(
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        HtmlText(html = displayFront, modifier = Modifier.fillMaxWidth())
        if (isRevealed) {
          Spacer(
            modifier =
              Modifier.padding(vertical = 8.dp)
                .fillMaxWidth(0.55f)
                .height(1.dp)
                .background(Color.DarkGray)
          )
          HtmlText(html = displayBack, modifier = Modifier.fillMaxWidth())
        }
      }

      // Render the actions and grading buttons at the bottom of the screen
      Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        if (!isRevealed) {
          Chip(
            label = { Text("Show Answer") },
            onClick = {
              session.revealAnswer()
              isRevealed = true
            },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth(0.9f),
          )
        } else {
          val duration = System.currentTimeMillis() - cardStartTime
          GradeGrid(card = card, enabled = !isAnswering) { ease, autoSync ->
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
  }

  private data class GradeSpec(
    val text: String,
    val time: String,
    val color: Color,
    val ease: Int,
    val autoSync: Boolean,
  )

  /**
   * Grading buttons laid out as a centered 2x2 grid so all of Again/Hard/Good/Easy stay inside the
   * round display instead of overflowing off the edges in a single row.
   */
  @Composable
  fun GradeGrid(
    card: WearosCard,
    enabled: Boolean,
    onGrade: (ease: Int, autoSync: Boolean) -> Unit,
  ) {
    fun t(i: Int, default: String) = card.nextReviewTimes.getOrNull(i) ?: default
    val grades =
      if (card.buttonCount == 3) {
        listOf(
          GradeSpec("Again", t(0, "1m"), Color(0xFFEF5350), 1, false),
          GradeSpec("Good", t(1, "4d"), Color(0xFF66BB6A), 3, false),
          GradeSpec("Easy", t(2, "7d"), Color(0xFF42A5F5), 4, true),
        )
      } else {
        listOf(
          GradeSpec("Again", t(0, "1m"), Color(0xFFEF5350), 1, false),
          GradeSpec("Hard", t(1, "10m"), Color(0xFFFFA726), 2, false),
          GradeSpec("Good", t(2, "4d"), Color(0xFF66BB6A), 3, false),
          GradeSpec("Easy", t(3, "7d"), Color(0xFF42A5F5), 4, true),
        )
      }
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      grades.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          row.forEach { g ->
            Chip(
              onClick = { if (enabled) onGrade(g.ease, g.autoSync) },
              label = { Text(g.text, maxLines = 1) },
              secondaryLabel = { Text(g.time, maxLines = 1) },
              colors =
                ChipDefaults.chipColors(
                  backgroundColor = g.color.copy(alpha = 0.30f),
                  contentColor = g.color,
                ),
              modifier = Modifier.width(90.dp),
            )
          }
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

  @Composable
  fun FinishedScreen(listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState) {
    ScalingLazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
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
