package fi.nikosavola.ankidroid.wearos.phone

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.parseReviewTimes

class AnkiDroidBridge(private val context: Context) {
  companion object {
    private const val TAG = "AnkiDroidBridge"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")

    fun stripBase64Images(html: String): String {
      val regex = Regex("data:image/[^;]+;base64,[a-zA-Z0-9+/=\\s\\r\\n]+")
      return html.replace(regex, "data:image/placeholder")
    }

    /**
     * A copy of [batch] with inline base64 image data stripped from every card's front and back (the
     * watch renders from separately-synced bitmaps, so the heavy data URIs must not ride the wire).
     */
    fun stripBase64Batch(batch: WearosDueBatch): WearosDueBatch =
      batch.copy(
        cards =
          batch.cards.map {
            it.copy(front = stripBase64Images(it.front), back = stripBase64Images(it.back))
          }
      )

    // Schedule/Card columns (FlashCardsContract). getColumnIndex is case-insensitive, but the
    // ordinal column is literally "ord" (not "card_ord"), so it must match exactly.
    private const val COL_NOTE_ID = "note_id"
    private const val COL_CARD_ORD = "ord"
    private const val COL_BUTTON_COUNT = "button_count"
    private const val COL_NEXT_REVIEW_TIMES = "next_review_times"

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

    // Notes search endpoint: the selection string is an Anki browser query (col.findNotes).
    private val NOTES_URI = Uri.parse("content://$AUTHORITY/notes")

    // Dedupe store: ids of answers already applied to the collection, so a re-send after a missed
    // clear_answers can never double-apply (e.g. a relearn card re-presented in the same session).
    // Persisted (re-sends span separate syncs / process restarts) and capped to the most recent N.
    private const val DEDUPE_PREFS = "writeback_applied"
    private const val KEY_APPLIED_IDS = "applied_ids"
    private const val MAX_APPLIED_IDS = 500
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
  fun fetchAllDecks(): List<Pair<DeckId, String>> {
    val list = mutableListOf<Pair<DeckId, String>>()
    if (!isAnkiDroidAvailable()) {
      return listOf(
        Pair(DeckId(1L), "WearOS Dev (Mock Deck)"),
        Pair(DeckId(123L), "Japanese Vocab (Mock)"),
        Pair(DeckId(456L), "Medical Anatomy (Mock)"),
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
            list.add(Pair(DeckId(deckId), deckName))
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to fetch all decks from AnkiDroid: ${e.message}", e)
    }
    if (list.isEmpty()) {
      list.add(Pair(DeckId(1L), "Default"))
    }
    return list
  }

  /**
   * Best-effort per-deck due counts from the Deck provider's counts column, when the installed
   * AnkiDroid exposes one (`deck_counts`, typically a bracketed `[learn, review, new]`). Returns
   * `deckId -> total` (sum of whatever integers the column holds). Empty map when the column is
   * absent, AnkiDroid is unavailable, or anything fails — callers MUST treat a missing entry as
   * "unknown", never as zero. This does one provider query, but the provider may compute counts for
   * the whole collection, so call it off the main thread.
   */
  fun fetchDeckCounts(): Map<DeckId, Int> {
    if (!isAnkiDroidAvailable()) return emptyMap()
    val out = mutableMapOf<DeckId, Int>()
    try {
      context.contentResolver.query(DECKS_URI, null, null, null, null)?.use { c ->
        val idIdx = c.getColumnIndex(COL_DECK_ID)
        val countsIdx = c.getColumnIndex("deck_counts")
        if (idIdx == -1 || countsIdx == -1) return emptyMap()
        while (c.moveToNext()) {
          val id = c.getLong(idIdx)
          val raw = c.getString(countsIdx) ?: continue
          // Sum every integer in the value, tolerant of the exact bracket/format the version uses.
          val nums = Regex("\\d+").findAll(raw).map { it.value.toIntOrNull() ?: 0 }.toList()
          if (nums.isNotEmpty()) out[DeckId(id)] = nums.sum()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read deck counts (column may not exist): ${e.message}")
      return emptyMap()
    }
    return out
  }

  /** Query the user's active, currently selected deck from AnkiDroid dynamically. */
  fun getSelectedDeck(): Pair<DeckId, String> {
    try {
      val cursor = context.contentResolver.query(DECK_SELECTED_URI, null, null, null, null)
      cursor?.use {
        if (it.moveToFirst()) {
          val idIdx = it.getColumnIndex(COL_DECK_ID)
          val nameIdx = it.getColumnIndex(COL_DECK_NAME)
          if (idIdx != -1 && nameIdx != -1) {
            val deckId = it.getLong(idIdx)
            val deckName = it.getString(nameIdx)
            return Pair(DeckId(deckId), deckName)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query selected deck from AnkiDroid: ${e.message}", e)
    }
    return Pair(DeckId(1L), "Default")
  }

  /** Fetch the due cards batch. Automatically falls back to mock cards if AnkiDroid is missing. */
  fun fetchDueBatch(deckId: DeckId, limit: Int, forceMock: Boolean = false): WearosDueBatch {
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
          arrayOf(limit.toString(), resolvedDeckId.value.toString()),
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

      return WearosDueBatch(
        deckId = resolvedDeckId.value,
        deckName = resolvedDeckName,
        cards = cards,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query AnkiDroid ContentProvider: ${e.message}. Using mocks.", e)
      return getMockDueBatch(deckId)
    }
  }

  /**
   * Fetch up to [limit] NEW cards from a deck so the user can study ahead from the watch, bypassing
   * the scheduler's daily new-card queue limit. Answering these later via ReviewInfo graduates
   * them.
   *
   * To avoid rescheduling a card the user didn't intend, only notes with a single card are included
   * (a single-card note matched by `is:new` is provably the new card); multi-card notes are skipped
   * since the Card projection doesn't expose per-card queue state.
   */
  fun fetchNewCards(deckId: DeckId, limit: Int, forceMock: Boolean = false): WearosDueBatch {
    if (forceMock || !isAnkiDroidAvailable()) {
      return getMockDueBatch(deckId).let { it.copy(deckName = "${it.deckName} (new)") }
    }
    return try {
      val (resolvedDeckId, resolvedDeckName) =
        fetchAllDecks().find { it.first == deckId } ?: getSelectedDeck()
      val noteIds = searchNewCardNoteIds(resolvedDeckName, limit)
      // Single-card notes only (see fetchSingleNewCard); the rest are skipped.
      val cards = noteIds.mapNotNull { fetchSingleNewCard(it) }
      val skipped = noteIds.size - cards.size
      if (skipped > 0) {
        Log.i(TAG, "Skipped $skipped multi-card notes to avoid rescheduling non-new cards.")
      }
      WearosDueBatch(
        deckId = resolvedDeckId.value,
        deckName = "$resolvedDeckName (new)",
        cards = cards,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to fetch new cards: ${e.message}", e)
      WearosDueBatch(deckId = deckId.value, deckName = "New cards", cards = emptyList())
    }
  }

  /** Note ids of up to [limit] new cards in [deckName], via the notes search endpoint. */
  private fun searchNewCardNoteIds(deckName: String, limit: Int): List<Long> {
    val escaped = deckName.replace("\"", "\\\"")
    val search = "deck:\"$escaped\" is:new"
    Log.i(TAG, "Searching new cards: $search")
    val ids = mutableListOf<Long>()
    context.contentResolver.query(NOTES_URI, arrayOf("_id"), search, null, null)?.use { cursor ->
      val idIdx = cursor.getColumnIndex("_id")
      while (cursor.moveToNext() && ids.size < limit) {
        if (idIdx != -1) ids.add(cursor.getLong(idIdx))
      }
    }
    Log.d(TAG, "Found ${ids.size} new-card notes (capped at $limit).")
    return ids
  }

  /**
   * Build a [WearosCard] for a single-card new note, or null when the note has any other number of
   * cards (skipped, since the Card projection can't say which card is the new one).
   */
  private fun fetchSingleNewCard(noteId: Long): WearosCard? {
    val cr = context.contentResolver
    val ords = mutableListOf<Int>()
    cr.query(Uri.parse("content://$AUTHORITY/notes/$noteId/cards"), null, null, null, null)?.use { c
      ->
      val ordIdx = c.getColumnIndex(COL_CARD_ORD)
      while (c.moveToNext()) if (ordIdx != -1) ords.add(c.getInt(ordIdx))
    }
    val ord = ords.singleOrNull() ?: return null

    var front = "No Front Text"
    var back = "No Back Text"
    var cardId = 0L
    cr
      .query(
        Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord"),
        arrayOf("_id", "question", "answer"),
        null,
        null,
        null,
      )
      ?.use { cc ->
        if (cc.moveToFirst()) {
          cc.getColumnIndex("_id").let { if (it != -1) cardId = cc.getLong(it) }
          cc.getColumnIndex("question").let { if (it != -1) front = cc.getString(it) }
          cc.getColumnIndex("answer").let { if (it != -1) back = cc.getString(it) }
        }
      }
    return WearosCard(
      id = cardId,
      noteId = noteId,
      ord = ord,
      front = front,
      back = back,
      buttonCount = 4,
      // New cards aren't in the scheduler queue, so we don't have per-ease previews.
      nextReviewTimes = listOf("1m", "10m", "1d", "4d"),
    )
  }

  /**
   * Write answers back to AnkiDroid's schedule table. Returns the ids of the answers that were
   * actually applied, so only those get cleared from the watch buffer (failed ones stay for retry).
   */
  fun writeAnswersBack(answers: List<WearosAnswer>, readOnly: Boolean = false): List<String> {
    if (readOnly) {
      // Safe/read-only test mode: exercise the full sync round-trip (including the clear_answers
      // handshake, which clears the watch buffer) without ever touching the real collection. The
      // reviews are intentionally NOT applied, so they are discarded rather than scheduled.
      Log.w(
        TAG,
        "Read-only mode ON: NOT writing ${answers.size} answers to AnkiDroid; reporting them " +
          "settled so the watch buffer clears.",
      )
      return answers.map { it.id }
    }
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
    val contentResolver: ContentResolver = context.contentResolver

    // Dedupe defense-in-depth: an answer id we've already applied must never be applied again, even
    // if the watch re-sends it (a missed clear_answers) while its card happens to be presented again
    // (a relearn re-presented this session). Pre-filter those out and report them settled so the
    // watch buffer clears; the drain loop below then only sees not-yet-applied answers. The
    // current-card gating already stops a mis-grade; this stops a *double*-grade of the same review.
    val appliedIdStore = loadAppliedIds()
    val remaining = mutableListOf<WearosAnswer>()
    for (a in answers) {
      if (a.id in appliedIdStore) {
        Log.i(TAG, "Answer ${a.id} already applied (re-send); settling without re-writing.")
        settledIds.add(a.id)
      } else {
        remaining.add(a)
      }
    }

    // Drain the buffer one deck at a time. The v3 backend answers whatever card the *selected* deck's
    // scheduler currently presents (the update uses a null selection), so for a buffer spanning decks
    // (several reviewed offline, synced together) we must select each answer's deck before draining
    // it - otherwise only the currently-selected deck's answers would ever apply and the rest would
    // linger. deck_id 0 = unknown (older buffers / cross-version wire): drained first against whatever
    // AnkiDroid currently has selected, preserving the pre-deck_id behaviour.
    //
    // Within a deck we prime the scheduler, read the current card, and apply ONLY the buffered answer
    // for that exact (note_id, ord). If the scheduler's order diverges from the buffer - a card
    // relearned within one session, or an out-of-order buffer - blindly updating would grade the WRONG
    // card, so answers we can't place this pass stay buffered for a later sync. Priming is also
    // required for correctness: a bare update() no-ops on the rslib/v3 backend until a schedule query
    // has reset the scheduler and rebuilt its queue (query-then-update graduates the card, reps++ and
    // a revlog row; a bare update leaves it due).
    val deckOrder = remaining.map { it.deckId }.distinct().sortedBy { if (it == 0L) 0 else 1 }
    deckLoop@ for (deckId in deckOrder) {
      if (deckId != 0L) {
        try {
          selectDeckForWriteBack(contentResolver, deckId)
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't select deck $deckId; leaving its answers buffered: ${e.message}")
          continue@deckLoop
        }
      }
      while (remaining.isNotEmpty()) {
        val current = primeScheduler(contentResolver) ?: break
        val idx = remaining.indexOfFirst { it.noteId == current.first && it.ord == current.second }
        if (idx < 0) {
          // Scheduler presenting a card we have no buffered answer for: applying any of ours now would
          // grade the wrong card. Move on to the next deck (its answers stay buffered for retry).
          Log.i(TAG, "Scheduler on note ${current.first}/${current.second} (not buffered); next deck.")
          break
        }
        val answer = remaining[idx]
        try {
          // Answer via FlashCardsContract.ReviewInfo: fields in ContentValues, null selection. The
          // provider calls the real scheduler (col.sched.answerCard) on the current card, which we've
          // just confirmed is this answer's card.
          val values =
            ContentValues().apply {
              put(COL_REVIEW_NOTE_ID, answer.noteId)
              put(COL_REVIEW_ORD, answer.ord)
              put(COL_REVIEW_EASE, answer.ease)
              put(COL_REVIEW_TIME_TAKEN, answer.timeTakenMs)
            }
          val rowsUpdated = contentResolver.update(SCHEDULE_URI, values, null, null)
          remaining.removeAt(idx)
          settledIds.add(answer.id)
          if (rowsUpdated > 0) {
            applied++
            appliedIdStore.add(answer.id)
          } else {
            // Matched the current card but nothing was written (already answered / removed): terminal.
            Log.w(TAG, "Answer for note ${answer.noteId} matched the current card but wrote 0 rows.")
          }
        } catch (e: Exception) {
          Log.e(
            TAG,
            "Transient failure writing note ${answer.noteId}; keeping it + the rest for retry: " +
              "${e.message}",
            e,
          )
          break@deckLoop
        }
      }
    }

    if (applied > 0) saveAppliedIds(appliedIdStore)
    Log.i(TAG, "Write-back: $applied applied, ${remaining.size} kept for retry/later.")
    return settledIds
  }

  /** Ids of answers already applied to the collection, most-recent last. See [DEDUPE_PREFS]. */
  private fun loadAppliedIds(): LinkedHashSet<String> {
    val prefs = context.getSharedPreferences(DEDUPE_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_APPLIED_IDS, "").orEmpty()
    return LinkedHashSet(raw.split(",").filter { it.isNotEmpty() })
  }

  /** Persist [ids], trimmed to the most recent [MAX_APPLIED_IDS] so the store can't grow unbounded. */
  private fun saveAppliedIds(ids: LinkedHashSet<String>) {
    val trimmed = if (ids.size > MAX_APPLIED_IDS) ids.toList().takeLast(MAX_APPLIED_IDS) else ids
    context
      .getSharedPreferences(DEDUPE_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_APPLIED_IDS, trimmed.joinToString(","))
      .apply()
  }

  /**
   * Reset AnkiDroid's scheduler by querying the schedule URI (the side effect - col.sched.reset +
   * rebuilding the review queue - is what makes the following ReviewInfo update actually answer the
   * card; without it the update no-ops), and return the card the scheduler now presents as
   * (note_id, ord), or null if none is due / the query failed. The caller answers only the buffered
   * answer matching this card, so it never grades the wrong one.
   */
  private fun primeScheduler(contentResolver: ContentResolver): Pair<Long, Int>? {
    return try {
      contentResolver.query(SCHEDULE_URI, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        val noteIdIdx = cursor.getColumnIndex(COL_NOTE_ID)
        val ordIdx = cursor.getColumnIndex(COL_CARD_ORD)
        if (noteIdIdx < 0 || ordIdx < 0) {
          null
        } else {
          cursor.getLong(noteIdIdx) to cursor.getInt(ordIdx)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Scheduler prime query failed; skipping write-back this pass: ${e.message}")
      null
    }
  }

  /**
   * Make [deckId] AnkiDroid's selected deck before draining its buffered answers. Querying the
   * schedule with a deckID selection has the side effect of selecting that deck (same call
   * fetchDueBatch uses), so the following null-selection [primeScheduler] presents this deck's cards.
   * The cursor's rows are irrelevant here - we only want the selection side effect.
   */
  private fun selectDeckForWriteBack(contentResolver: ContentResolver, deckId: Long) {
    contentResolver
      .query(SCHEDULE_URI, null, "limit=?,deckID=?", arrayOf("1", deckId.toString()), null)
      ?.use {}
  }

  private fun getMockDueBatch(deckId: DeckId): WearosDueBatch {
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
      deckId = deckId.value,
      deckName =
        if (deckId == DeckId(123L)) {
          "Japanese Vocab (Mock)"
        } else if (deckId == DeckId(456L)) {
          "Medical Anatomy (Mock)"
        } else {
          "WearOS Dev (Mock Deck)"
        },
      cards = mockCards,
    )
  }
}
