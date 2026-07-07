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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.*
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WatchMainActivity"
        private const val PATH_ANSWERS = "/answers"
        private const val KEY_ANSWERS_BYTES = "answers_bytes"
    }

    private var reviewSession by mutableStateOf<ReviewSession?>(null)
    private var outstandingAnswersCount by mutableStateOf(0)
    private var isReviewing by mutableStateOf(false)
    private var ttsEnabled by mutableStateOf(false)
    
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Sync broadcast received: ${intent?.action}")
            when (intent?.action) {
                WearSyncService.ACTION_BATCH_UPDATED -> {
                    loadBatchCache()
                }
                WearSyncService.ACTION_ANSWERS_CLEARED -> {
                    updateOutstandingCount()
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
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                Log.d(TAG, "TTS Engine initialized successfully.")
            } else {
                Log.e(TAG, "TTS Initialization failed.")
            }
        }

        // 3. Load cached batch if any
        loadBatchCache()
        updateOutstandingCount()

        // 4. Register Receiver
        val filter = IntentFilter().apply {
            addAction(WearSyncService.ACTION_BATCH_UPDATED)
            addAction(WearSyncService.ACTION_ANSWERS_CLEARED)
        }
        registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        setContent {
            WearAppScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(syncReceiver)
        tts?.stop()
        tts?.shutdown()
    }

    private fun loadBatchCache() {
        try {
            val cacheFile = File(filesDir, "due_batch.json")
            if (cacheFile.exists()) {
                val bytes = cacheFile.readBytes()
                val batch = deserializeDueBatch(bytes)
                reviewSession = ReviewSession(batch)
                Log.i(TAG, "Successfully loaded cached due batch: '${batch.deckName}' with ${batch.cards.size} cards.")
            } else {
                reviewSession = null
                isReviewing = false
                Log.d(TAG, "No cached due batch found.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached batch: ${e.message}", e)
            reviewSession = null
            isReviewing = false
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
                val putDataReq = PutDataMapRequest.create(PATH_ANSWERS).apply {
                    dataMap.putByteArray(KEY_ANSWERS_BYTES, bytes)
                    setUrgent()
                }.asPutDataRequest()

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

    fun speak(text: String) {
        if (ttsEnabled) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card_tts")
        }
    }

    @Composable
    fun WearAppScreen() {
        MaterialTheme {
            val listState = rememberScalingLazyListState()
            Scaffold(
                positionIndicator = {
                    PositionIndicator(scalingLazyListState = listState)
                }
            ) {
                val session = reviewSession
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
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Text(
                    text = "AnkiDroid WearOS",
                    style = MaterialTheme.typography.title2,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            item {
                Text(
                    text = "Please open the companion app on your phone and click 'Sync Due Cards to Watch'.",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                Button(
                    onClick = { loadBatchCache() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Refresh")
                }
            }
        }
    }

    @Composable
    fun DeckScreen(
        listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
        session: ReviewSession
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    text = "📚 Deck Stats",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.secondary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            item {
                Text(
                    text = session.getDeckName(),
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                )
            }
            item {
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Due Cards: ${session.getTotalCards()}",
                            style = MaterialTheme.typography.body2
                        )
                        Text(
                            text = "Progress: ${session.getProgressString()}",
                            style = MaterialTheme.typography.caption2,
                            color = Color.LightGray
                        )
                    }
                }
            }
            item {
                Chip(
                    label = { Text("Start Review") },
                    onClick = { isReviewing = true },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
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
                            contentDescription = "TTS Switch"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }
            if (outstandingAnswersCount > 0) {
                item {
                    Chip(
                        label = { Text("Sync $outstandingAnswersCount Reviews") },
                        onClick = { triggerAnswersSync() },
                        colors = ChipDefaults.secondaryChipColors(
                            backgroundColor = Color(0xFF66BB6A).copy(alpha = 0.2f),
                            contentColor = Color(0xFF66BB6A)
                        ),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }
        }
    }

    @Composable
    fun ReviewCardScreen(
        listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
        session: ReviewSession,
        card: WearosCard
    ) {
        var cardStartTime by remember(card.id, card.ord) { mutableLongStateOf(System.currentTimeMillis()) }
        var isRevealed by remember(card.id, card.ord) { mutableStateOf(session.isRevealed()) }

        val cleanFront = remember(card.front) { cleanHtml(card.front) }
        val cleanBack = remember(card.back) { cleanHtml(card.back) }

        // TTS voice effects triggered dynamically via state change
        LaunchedEffect(card.id, card.ord) {
            speak(cleanFront)
        }
        LaunchedEffect(isRevealed) {
            if (isRevealed) {
                speak(cleanBack)
            }
        }

        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header Progress
            item {
                Text(
                    text = session.getProgressString(),
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.secondary
                )
            }

            // Question (Front)
            item {
                Text(
                    text = cleanFront,
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (!isRevealed) {
                // Front Reveal Action
                item {
                    Button(
                        onClick = {
                            session.revealAnswer()
                            isRevealed = true
                        },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Show Answer")
                    }
                }
            } else {
                // Divider and Answer (Back)
                item {
                    Text(
                        text = "---",
                        color = Color.Gray,
                        style = MaterialTheme.typography.caption2
                    )
                }
                item {
                    Text(
                        text = cleanBack,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                // Grading buttons
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, start = 8.dp, end = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val duration = System.currentTimeMillis() - cardStartTime
                        
                        if (card.buttonCount == 3) {
                            // 3-button ease grading (Again, Good, Easy)
                            GradeButton(text = "Again", timeStr = card.nextReviewTimes.getOrNull(0) ?: "1m", color = Color(0xFFEF5350)) {
                                scope.launch {
                                    session.answerCard(1, duration)
                                    updateOutstandingCount()
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            GradeButton(text = "Good", timeStr = card.nextReviewTimes.getOrNull(1) ?: "4d", color = Color(0xFF66BB6A)) {
                                scope.launch {
                                    session.answerCard(3, duration)
                                    updateOutstandingCount()
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            GradeButton(text = "Easy", timeStr = card.nextReviewTimes.getOrNull(2) ?: "7d", color = Color(0xFF42A5F5)) {
                                scope.launch {
                                    session.answerCard(4, duration)
                                    updateOutstandingCount()
                                    triggerAnswersSync() // auto sync on completion
                                }
                            }
                        } else {
                            // 4-button ease grading (Again, Hard, Good, Easy)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                CompactGradeButton(text = "Again", timeStr = card.nextReviewTimes.getOrNull(0) ?: "1m", color = Color(0xFFEF5350)) {
                                    scope.launch {
                                        session.answerCard(1, duration)
                                        updateOutstandingCount()
                                    }
                                }
                                CompactGradeButton(text = "Hard", timeStr = card.nextReviewTimes.getOrNull(1) ?: "10m", color = Color(0xFFFFA726)) {
                                    scope.launch {
                                        session.answerCard(2, duration)
                                        updateOutstandingCount()
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                CompactGradeButton(text = "Good", timeStr = card.nextReviewTimes.getOrNull(2) ?: "4d", color = Color(0xFF66BB6A)) {
                                    scope.launch {
                                        session.answerCard(3, duration)
                                        updateOutstandingCount()
                                    }
                                }
                                CompactGradeButton(text = "Easy", timeStr = card.nextReviewTimes.getOrNull(3) ?: "7d", color = Color(0xFF42A5F5)) {
                                    scope.launch {
                                        session.answerCard(4, duration)
                                        updateOutstandingCount()
                                        triggerAnswersSync() // auto sync on completion
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun GradeButton(text: String, timeStr: String, color: Color, onClick: () -> Unit) {
        Chip(
            label = { Text(text) },
            secondaryLabel = { Text(timeStr) },
            onClick = onClick,
            colors = ChipDefaults.chipColors(backgroundColor = color.copy(alpha = 0.25f), contentColor = color),
            modifier = Modifier.fillMaxWidth(0.9f)
        )
    }

    @Composable
    fun CompactGradeButton(text: String, timeStr: String, color: Color, onClick: () -> Unit) {
        CompactChip(
            label = { Text("$text\n$timeStr") },
            onClick = onClick,
            colors = ChipDefaults.chipColors(backgroundColor = color.copy(alpha = 0.25f), contentColor = color),
            modifier = Modifier.width(72.dp)
        )
    }

    @Composable
    fun FinishedScreen(listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Text(
                    text = "Nice Job! 🎉",
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                Text(
                    text = "You finished your study queue for now.",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item {
                Text(
                    text = "Outstanding reviews: $outstandingAnswersCount",
                    style = MaterialTheme.typography.caption2,
                    color = Color.LightGray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                Button(
                    onClick = {
                        triggerAnswersSync()
                        isReviewing = false
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Sync & Done")
                }
            }
        }
    }
}
