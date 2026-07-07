package fi.nikosavola.ankidroid.wearos.phone

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch

class AnkiDroidBridge(private val context: Context) {
  companion object {
    private const val TAG = "AnkiDroidBridge"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")

    // Schedule columns from PLANNING.md
    private const val COL_NOTE_ID = "NOTE_ID"
    private const val COL_CARD_ORD = "CARD_ORD"
    private const val COL_BUTTON_COUNT = "BUTTON_COUNT"
    private const val COL_NEXT_REVIEW_TIMES = "NEXT_REVIEW_TIMES"

    // ReviewInfo write-back columns (FlashCardsContract). Answering a card is an update on
    // SCHEDULE_URI with these keys in ContentValues and a null selection.
    private const val COL_REVIEW_NOTE_ID = "note_id"
    private const val COL_REVIEW_ORD = "ord"
    private const val COL_REVIEW_EASE = "answer_ease"
    private const val COL_REVIEW_TIME_TAKEN = "time_taken"

    // Deck selection columns and URIs
    private val DECK_SELECTED_URI = Uri.parse("content://$AUTHORITY/selected_deck")
    private val DECKS_URI = Uri.parse("content://$AUTHORITY/decks")
    private const val COL_DECK_ID = "deck_id"
    private const val COL_DECK_NAME = "deck_name"
  }

  /** Check if the AnkiDroid provider is installed and accessible. */
  fun isAnkiDroidAvailable(): Boolean {
    return try {
      val provider = context.contentResolver.acquireContentProviderClient(SCHEDULE_URI)
      if (provider != null) {
        provider.close()
        true
      } else {
        false
      }
    } catch (e: Exception) {
      false
    }
  }

  /** Query all available decks in AnkiDroid dynamically. */
  fun fetchAllDecks(): List<Pair<Long, String>> {
    val list = mutableListOf<Pair<Long, String>>()
    if (!isAnkiDroidAvailable()) {
      return listOf(
        Pair(1L, "WearOS Dev (Mock Deck)"),
        Pair(123L, "Japanese Vocab (Mock)"),
        Pair(456L, "Medical Anatomy (Mock)"),
      )
    }
    try {
      val cursor = context.contentResolver.query(DECKS_URI, null, null, null, null)
      cursor?.use {
        val idIdx = it.getColumnIndex(COL_DECK_ID)
        val nameIdx = it.getColumnIndex(COL_DECK_NAME)
        while (it.moveToNext()) {
          if (idIdx != -1 && nameIdx != -1) {
            val deckId = it.getLong(idIdx)
            val deckName = it.getString(nameIdx)
            list.add(Pair(deckId, deckName))
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to fetch all decks from AnkiDroid: ${e.message}", e)
    }
    if (list.isEmpty()) {
      list.add(Pair(1L, "Default"))
    }
    return list
  }

  /** Query the user's active, currently selected deck from AnkiDroid dynamically. */
  fun getSelectedDeck(): Pair<Long, String> {
    try {
      val cursor = context.contentResolver.query(DECK_SELECTED_URI, null, null, null, null)
      cursor?.use {
        if (it.moveToFirst()) {
          val idIdx = it.getColumnIndex(COL_DECK_ID)
          val nameIdx = it.getColumnIndex(COL_DECK_NAME)
          if (idIdx != -1 && nameIdx != -1) {
            val deckId = it.getLong(idIdx)
            val deckName = it.getString(nameIdx)
            return Pair(deckId, deckName)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query selected deck from AnkiDroid: ${e.message}", e)
    }
    return Pair(1L, "Default")
  }

  /** Fetch the due cards batch. Automatically falls back to mock cards if AnkiDroid is missing. */
  fun fetchDueBatch(deckId: Long, limit: Int, forceMock: Boolean = false): WearosDueBatch {
    if (forceMock || !isAnkiDroidAvailable()) {
      Log.i(TAG, "AnkiDroid not found or Mock Mode forced. Falling back to Mock Due Batch.")
      return getMockDueBatch(deckId)
    }

    try {
      val allDecks = fetchAllDecks()
      val matchedDeck = allDecks.find { it.first == deckId }
      val (resolvedDeckId, resolvedDeckName) =
        if (matchedDeck != null) {
          matchedDeck
        } else {
          getSelectedDeck()
        }
      Log.i(TAG, "Using AnkiDroid selected deck: $resolvedDeckName (ID: $resolvedDeckId)")

      val cards = mutableListOf<WearosCard>()
      val contentResolver: ContentResolver = context.contentResolver

      // 1. Query the schedule table using the comma-separated selection format parsed by
      // CardContentProvider
      val scheduleCursor =
        contentResolver.query(
          SCHEDULE_URI,
          null,
          "limit=?,deckID=?",
          arrayOf(limit.toString(), resolvedDeckId.toString()),
          null,
        )

      scheduleCursor?.use { cursor ->
        val noteIdIdx = cursor.getColumnIndex(COL_NOTE_ID)
        val ordIdx = cursor.getColumnIndex(COL_CARD_ORD)
        val buttonCountIdx = cursor.getColumnIndex(COL_BUTTON_COUNT)
        val reviewTimesIdx = cursor.getColumnIndex(COL_NEXT_REVIEW_TIMES)

        while (cursor.moveToNext()) {
          val noteId = if (noteIdIdx != -1) cursor.getLong(noteIdIdx) else 0L
          val ord = if (ordIdx != -1) cursor.getInt(ordIdx) else 0
          val buttonCount = if (buttonCountIdx != -1) cursor.getInt(buttonCountIdx) else 4
          val reviewTimesRaw =
            if (reviewTimesIdx != -1) {
              cursor.getString(reviewTimesIdx)
            } else {
              "1m, 10m, 4d, 7d"
            }

          val nextReviewTimes = parseReviewTimes(reviewTimesRaw)

          // 2. Fetch Card Front/Back dynamically by path-appended card URI
          var front = "No Front Text"
          var back = "No Back Text"
          var cardId = 0L

          val cardUri = Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord")
          val cardCursor =
            contentResolver.query(cardUri, arrayOf("_id", "question", "answer"), null, null, null)

          cardCursor?.use { cCursor ->
            if (cCursor.moveToFirst()) {
              val idIdx = cCursor.getColumnIndex("_id")
              val qIdx = cCursor.getColumnIndex("question")
              val aIdx = cCursor.getColumnIndex("answer")

              if (idIdx != -1) cardId = cCursor.getLong(idIdx)
              if (qIdx != -1) front = cCursor.getString(qIdx)
              if (aIdx != -1) back = cCursor.getString(aIdx)
            }
          }

          cards.add(
            WearosCard(
              id = cardId,
              noteId = noteId,
              ord = ord,
              front = front,
              back = back,
              buttonCount = buttonCount,
              nextReviewTimes = nextReviewTimes,
            )
          )
        }
      }

      return WearosDueBatch(deckId = resolvedDeckId, deckName = resolvedDeckName, cards = cards)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query AnkiDroid ContentProvider: ${e.message}. Using mocks.", e)
      return getMockDueBatch(deckId)
    }
  }

  /**
   * Write answers back to AnkiDroid's schedule table. Returns the ids of the answers that were
   * actually applied, so only those get cleared from the watch buffer (failed ones stay for retry).
   */
  fun writeAnswersBack(answers: List<WearosAnswer>): List<String> {
    if (!isAnkiDroidAvailable()) {
      Log.i(
        TAG,
        "Mock Writeback: Successfully simulated writing ${answers.size} answers to AnkiDroid.",
      )
      return answers.map { it.id }
    }

    // IDs to clear from the watch buffer = terminal outcomes: either applied (rows > 0) or the card
    // no longer matches (rows == 0: already answered / removed / not due). Only a thrown exception
    // is
    // treated as transient and kept buffered for a later retry, so orphaned answers can't pile up.
    val settledIds = mutableListOf<String>()
    var applied = 0
    var orphaned = 0
    val contentResolver: ContentResolver = context.contentResolver

    for (answer in answers) {
      try {
        // Answer a card via FlashCardsContract.ReviewInfo: the fields go in ContentValues with a
        // null selection. The provider calls the real scheduler (col.sched.answerCard).
        val values =
          ContentValues().apply {
            put(COL_REVIEW_NOTE_ID, answer.noteId)
            put(COL_REVIEW_ORD, answer.ord)
            put(COL_REVIEW_EASE, answer.ease)
            put(COL_REVIEW_TIME_TAKEN, answer.timeTakenMs)
          }
        val rowsUpdated = contentResolver.update(SCHEDULE_URI, values, null, null)
        settledIds.add(answer.id)
        if (rowsUpdated > 0) {
          applied++
        } else {
          orphaned++
          Log.w(TAG, "Answer for note ${answer.noteId} matched no card; dropping it.")
        }
      } catch (e: Exception) {
        Log.e(
          TAG,
          "Transient failure writing note ${answer.noteId}; keeping for retry: ${e.message}",
          e,
        )
      }
    }

    Log.i(
      TAG,
      "Write-back: $applied applied, $orphaned dropped (no match), " +
        "${answers.size - settledIds.size} kept for retry.",
    )
    return settledIds
  }

  /**
   * NEXT_REVIEW_TIMES comes back as a JSON array string with bidi isolate chars around numbers
   * (e.g. ["⁨10⁩m",...]). Parse it into clean values like "10m" so the watch buttons don't show
   * fragments like ["10m".
   */
  private fun parseReviewTimes(raw: String): List<String> {
    // Strip Unicode bidi isolates (LRI/RLI/FSI/PDI) Anki wraps around numbers.
    val cleaned = raw.replace(Regex("[⁦⁧⁨⁩]"), "")
    return try {
      val arr = JSONArray(cleaned.trim())
      (0 until arr.length()).map { arr.getString(it).trim() }
    } catch (e: Exception) {
      cleaned
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .split(",")
        .map { it.trim().trim('"') }
        .filter { it.isNotEmpty() }
    }
  }

  private fun getMockDueBatch(deckId: Long): WearosDueBatch {
    val mockCards =
      listOf(
        WearosCard(
          id = 101,
          noteId = 1001,
          ord = 0,
          front = "What is the primary architectural language of the WearOS core?",
          back = "Rust! For high safety, memory efficiency, and native speeds on our watch.",
          buttonCount = 4,
          nextReviewTimes = listOf("1m", "10m", "4d", "7d"),
        ),
        WearosCard(
          id = 102,
          noteId = 1002,
          ord = 0,
          front = "How do the phone and watch synchronize offline-first?",
          back = "Using the Google Wearable DataClient API with local SQLite buffers.",
          buttonCount = 4,
          nextReviewTimes = listOf("2m", "15m", "5d", "9d"),
        ),
        WearosCard(
          id = 103,
          noteId = 1003,
          ord = 0,
          front = "Is AnkiDroid WearOS affiliated with the abandoned 'wlky/AnkiDroid-Wear'?",
          back =
            "No, it is a clean, blank-slate re-architecture designed against modern WearOS 6 APIs.",
          buttonCount = 3,
          nextReviewTimes = listOf("1m", "8m", "3d"),
        ),
        WearosCard(
          id = 104,
          noteId = 1004,
          ord = 0,
          front = "What FSRS stands for in modern spacing repetition?",
          back = "Free Spaced Repetition Scheduler (the successor of SM-2 algorithm).",
          buttonCount = 4,
          nextReviewTimes = listOf("1m", "12m", "6d", "12d"),
        ),
      )

    return WearosDueBatch(
      deckId = deckId,
      deckName =
        if (deckId == 123L) {
          "Japanese Vocab (Mock)"
        } else if (deckId == 456L) {
          "Medical Anatomy (Mock)"
        } else {
          "WearOS Dev (Mock Deck)"
        },
      cards = mockCards,
    )
  }
}
