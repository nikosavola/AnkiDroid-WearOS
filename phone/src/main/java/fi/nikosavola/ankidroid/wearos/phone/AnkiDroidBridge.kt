package fi.nikosavola.ankidroid.wearos.phone

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.TimeZone
import uniffi.wearos_core.CardScheduleState
import uniffi.wearos_core.DeckScheduleConfig
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.dayIndex
import uniffi.wearos_core.defaultRolloverHour
import uniffi.wearos_core.elapsedDaysBetween
import uniffi.wearos_core.parseDeckScheduleConfig
import uniffi.wearos_core.parseReviewTimes

/**
 * Result of a write-back pass. [settled] are the ids safe to clear from the watch buffer (applied, or
 * terminally unmatched); [unplaced] are ids the phone examined but could not place this pass, reported
 * so the watch counts a real failed placement rather than every push.
 */
data class WriteBackOutcome(val settled: List<String>, val unplaced: List<String>) {
  companion object {
    val NONE = WriteBackOutcome(emptyList(), emptyList())
  }
}

class AnkiDroidBridge(
  private val context: Context,
  private val clock: () -> Long = System::currentTimeMillis,
  private val timeZone: TimeZone = TimeZone.getDefault(),
) {
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

    // Card scheduler-state columns (FlashCardsContract.Card). The watch recomputes interval
    // previews from these once a card is graded and requeued, since the shipped labels go stale.
    private const val COL_QUEUE = "queue"
    // Card type (0 new / 1 learning / 2 review / 3 relearning): decides learn vs relearn steps, which
    // the queue cannot (a lapsed card sits in the learning queue).
    private const val COL_CARD_TYPE = "type"
    private const val COL_INTERVAL = "interval"
    private const val COL_LEFT = "left"
    private const val COL_FSRS_STABILITY = "fsrs_stability"
    private const val COL_FSRS_DIFFICULTY = "fsrs_difficulty"
    // SM-2 ease factor, stored as an integer scaled by 10 (2500 = 250%): Anki's review scheduling
    // multiplies by it, so previews need it to match.
    private const val COL_SM2_FACTOR = "sm2_factor"
    private const val COL_LAST_REVIEW_SECS = "last_review_time_secs"
    // Review count, bumped by a real answerCard. Write-back re-reads it to confirm an answer stuck,
    // since the provider swallows a scheduler error and still reports a row (see writeAnswersBack).
    private const val COL_REPS = "reps"
    // Raw due value: an Anki day number for review/day-learning cards, epoch seconds for intraday
    // learning cards. The pool uses it for new-card ordering and intraday due times.
    private const val COL_DUE = "due"

    // Everything readCardRow needs. Explicit, because the provider's default Card projection carries
    // no scheduler columns: a null projection on the cards search would ship stateless pool cards.
    private val CARD_ROW_PROJECTION =
      arrayOf(
        "_id",
        "question",
        "answer",
        COL_NOTE_ID,
        COL_CARD_ORD,
        COL_QUEUE,
        COL_CARD_TYPE,
        COL_DUE,
        COL_INTERVAL,
        COL_LEFT,
        COL_FSRS_STABILITY,
        COL_FSRS_DIFFICULTY,
        COL_SM2_FACTOR,
        COL_LAST_REVIEW_SECS,
        COL_REPS,
      )

    // Deck scheduling config lives in the deck's OPTIONS column as rslib's schema11 JSON.
    private const val COL_DECK_OPTIONS = "options"
    private const val COL_DECK_COUNTS = "deck_count"

    /**
     * Anki's stored `left` packs two counters: `left % 1000` is the reps left before graduation,
     * `left / 1000` the reps still completable before the day cutoff. Comparing the former with the
     * deck's step count gives the card's position on the learning ladder.
     *
     * A new card (queue 0) has `left` 0 because it has never been answered, not because it is out of
     * steps, so it is on the first step. Verified against AnkiDroid by
     * `AnkiDroidProviderLiveTest.previewsAgreeWithAnkiDroidForAFreshlyAddedCard`.
     */
    fun learningStepFor(left: Long, stepCount: Int, queue: Int): Int {
      if (stepCount <= 0) return -1
      if (queue == 0) return 0
      val repsLeftBeforeGraduation = (left % 1000).toInt()
      return (stepCount - repsLeftBeforeGraduation).coerceIn(0, stepCount - 1)
    }

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

    // Cards search endpoint: the selection is an Anki browser query (col.findCards). Older AnkiDroid
    // builds lack it (query throws or returns null), so the pool then falls back to today's batch.
    private val CARDS_URI = Uri.parse("content://$AUTHORITY/cards")

    // Dedupe store: ids of answers already applied to the collection, so a re-send after a missed
    // clear_answers can never double-apply (e.g. a relearn card re-presented in the same session).
    // Persisted (re-sends span separate syncs / process restarts) and capped to the most recent N.
    private const val DEDUPE_PREFS = "writeback_applied"
    private const val KEY_APPLIED_IDS = "applied_ids"
    private const val MAX_APPLIED_IDS = 500
  }

  /**
   * Shared columns of one card-cursor row, parsed once for both the per-card fetch and the pool
   * searches. [due] is the raw `due` column when the cursor exposes it.
   */
  private class CardRow(
    val id: Long,
    val noteId: Long,
    val ord: Int,
    val front: String,
    val back: String,
    val due: Long?,
    val state: CardScheduleState?,
  )

  /**
   * Read one card cursor row's shared columns (id, note_id, ord, question, answer, scheduler state).
   * note_id/ord come from the cursor only when it actually carries them: the per-card fetch's
   * projection predates them and passes the schedule row's [fallbackNoteId]/[fallbackOrd] instead.
   */
  private fun readCardRow(
    cursor: Cursor,
    fallbackNoteId: Long,
    fallbackOrd: Int,
    config: DeckScheduleConfig?,
  ): CardRow {
    fun index(name: String): Int = cursor.getColumnIndex(name)

    fun longOr(name: String, fallback: Long): Long =
      index(name).let { if (it != -1 && !cursor.isNull(it)) cursor.getLong(it) else fallback }

    val names = cursor.columnNames ?: emptyArray<String>()
    return CardRow(
      id = longOr("_id", 0L),
      noteId = if ("note_id" in names) longOr(COL_NOTE_ID, fallbackNoteId) else fallbackNoteId,
      ord =
        if ("ord" in names) {
          index(COL_CARD_ORD).let { if (it != -1 && !cursor.isNull(it)) cursor.getInt(it) else fallbackOrd }
        } else {
          fallbackOrd
        },
      front = index("question").let { if (it != -1 && !cursor.isNull(it)) cursor.getString(it) else "No Front Text" },
      back = index("answer").let { if (it != -1 && !cursor.isNull(it)) cursor.getString(it) else "No Back Text" },
      due = index(COL_DUE).let { if (it != -1 && !cursor.isNull(it)) cursor.getLong(it) else null },
      state = readCardState(cursor, config),
    )
  }

  /**
   * Deck OPTIONS payloads captured while listing decks, keyed by deck id (null = that deck row has
   * none). Replaced wholesale after each listing, so readers see either the previous map or the new
   * one, never a half-updated one.
   */
  @Volatile private var deckOptionsByDeck: Map<Long, String?> = emptyMap()

  /** Each deck's `[learn, review, new]` due counts from the same listing, limits applied. */
  @Volatile private var deckCountsByDeck: Map<Long, List<Int>> = emptyMap()

  /**
   * The deck's scheduling config from the deck's OPTIONS column. AnkiDroid exposes deck options
   * only through the all-decks URI (there is no per-deck item URI), so this scans the deck list for
   * our id. Returns null (previews stay as fetched) when the deck is missing or unparseable.
   */
  private fun fetchDeckScheduleConfig(deckId: Long): DeckScheduleConfig? {
    // A deck missing from the map was never listed (fetch it); a deck mapped to null was listed
    // and genuinely has no options.
    val raw =
      if (deckOptionsByDeck.containsKey(deckId)) {
        deckOptionsByDeck[deckId]
      } else {
        readDeckOptions(deckId)
      }
    if (raw == null) {
      Log.w(TAG, "No deck options for deck $deckId; interval previews stay as AnkiDroid sent them.")
      return null
    }
    return try {
      val config = parseDeckScheduleConfig(raw)
      // One line per sync: the watch's offline previews are only as correct as this config.
      Log.i(
        TAG,
        "Deck config: steps=${config.learnSteps}, relearn=${config.relearnSteps}, " +
          "ints=${config.graduatingIntervalDays}/${config.easyIntervalDays}, " +
          "fsrs=${config.fsrsEnabled()} (${config.fsrsWeights.size} weights, " +
          "retention=${config.desiredRetention})",
      )
      config
    } catch (e: Exception) {
      Log.w(TAG, "Deck options unparseable (${e.message}); previews stay as fetched.", e)
      null
    }
  }

  /** Fallback for a deck that was never listed: read its OPTIONS row directly. */
  private fun readDeckOptions(deckId: Long): String? =
    try {
      context.contentResolver.query(DECKS_URI, null, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndex(COL_DECK_ID)
        val optionsIdx = cursor.getColumnIndex(COL_DECK_OPTIONS)
        while (cursor.moveToNext()) {
          if (cursor.getLong(idIdx) != deckId) continue
          return if (cursor.isNull(optionsIdx)) null else cursor.getString(optionsIdx)
        }
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Deck options unavailable (${e.message}); previews stay as fetched.", e)
      null
    }

  /** One card's scheduler state; null when the provider exposes none of the columns. */
  private fun readCardState(
    cursor: Cursor,
    config: DeckScheduleConfig?,
  ): CardScheduleState? {
    val queueIdx = cursor.getColumnIndex(COL_QUEUE)
    if (queueIdx == -1) return null

    val queue = cursor.getInt(queueIdx)
    val leftIdx = cursor.getColumnIndex(COL_LEFT)
    val typeIdxForSteps = cursor.getColumnIndex(COL_CARD_TYPE)
    val cardType =
      if (typeIdxForSteps != -1 && !cursor.isNull(typeIdxForSteps)) {
        cursor.getInt(typeIdxForSteps)
      } else {
        -1
      }
    val stepCount = if (cardType == 3) config?.relearnSteps?.size ?: 0 else config?.learnSteps?.size ?: 0
    val learningStep =
      if (leftIdx != -1 && !cursor.isNull(leftIdx)) {
        learningStepFor(cursor.getLong(leftIdx), stepCount, queue)
      } else {
        -1
      }

    val typeIdx = cursor.getColumnIndex(COL_CARD_TYPE)
    return CardScheduleState(
      queue = queue,
      cardType = if (typeIdx != -1 && !cursor.isNull(typeIdx)) cursor.getInt(typeIdx) else -1,
      easeFactor =
        cursor.getColumnIndex(COL_SM2_FACTOR).let {
          if (it != -1 && !cursor.isNull(it)) cursor.getDouble(it) / 1000.0 else 0.0
        },
      intervalDays =
        cursor.getColumnIndex(COL_INTERVAL).let {
          if (it != -1 && !cursor.isNull(it)) cursor.getDouble(it) else 0.0
        },
      learningStep = learningStep,
      stability =
        cursor.getColumnIndex(COL_FSRS_STABILITY).let {
          if (it != -1 && !cursor.isNull(it)) cursor.getDouble(it) else 0.0
        },
      difficulty =
        cursor.getColumnIndex(COL_FSRS_DIFFICULTY).let {
          if (it != -1 && !cursor.isNull(it)) cursor.getDouble(it) else 0.0
        },
      elapsedDays = elapsedDaysFor(cursor),
      lastReviewSecs = lastReviewSecsFor(cursor),
      fuzzSeed = fuzzSeedFor(cursor),
    )
  }

  /** rslib's fuzz seed for the card's next answer, card id + reps; null when `reps` is missing. */
  private fun fuzzSeedFor(cursor: Cursor): Long? {
    fun longAt(name: String): Long? =
      cursor.getColumnIndex(name).takeIf { it != -1 && !cursor.isNull(it) }?.let(cursor::getLong)
    val id = longAt("_id") ?: return null
    return longAt(COL_REPS)?.let { id + it }
  }

  /** The card's raw last-review epoch seconds, or 0 when unknown. */
  private fun lastReviewSecsFor(cursor: Cursor): Long {
    val idx = cursor.getColumnIndex(COL_LAST_REVIEW_SECS)
    if (idx == -1 || cursor.isNull(idx)) return 0L
    return cursor.getLong(idx).coerceAtLeast(0L)
  }

  /** Anki days since the card's last review, counted across day rollovers like rslib does. */
  private fun elapsedDaysFor(cursor: Cursor): Double {
    val lastSeconds = lastReviewSecsFor(cursor)
    if (lastSeconds <= 0) return 0.0
    val now = clock()
    return elapsedDaysBetween(lastSeconds, now, timeZone.getOffset(now) / 1000, defaultRolloverHour())
      .toDouble()
  }

  /**
   * The card's review count (reps) from the provider, or null when the query fails, returns no row,
   * or the provider has no `reps` column (older AnkiDroid). Write-back uses null to fall back to the
   * update's row count, since a scheduler error the provider swallows still counts a row but never
   * bumps reps.
   */
  private fun readCardReps(contentResolver: ContentResolver, noteId: Long, ord: Int): Long? =
    try {
      contentResolver
        .query(
          Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord"),
          arrayOf(COL_REPS),
          null,
          null,
          null,
        )
        ?.use { cursor ->
          val idx = cursor.getColumnIndex(COL_REPS)
          if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
    } catch (e: Exception) {
      Log.w(TAG, "Card reps unavailable for note $noteId/$ord; falling back to the row count.", e)
      null
    }

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
    // The deck listing already carries every column, including OPTIONS, so the scheduling config
    // costs no extra provider query: capture it here and let fetchDeckScheduleConfig reuse it.
    val options = mutableMapOf<Long, String?>()
    val counts = mutableMapOf<Long, List<Int>>()
    // Unavailable provider returns an empty list, never fake decks: substituting mocks would
    // overwrite the user's real cached decks and offer phantom decks to sync.
    if (!isAnkiDroidAvailable()) return emptyList()
    try {
      val cursor = context.contentResolver.query(DECKS_URI, null, null, null, null)
      cursor?.use {
        val idIdx = it.getColumnIndex(COL_DECK_ID)
        val nameIdx = it.getColumnIndex(COL_DECK_NAME)
        val optionsIdx = it.getColumnIndex(COL_DECK_OPTIONS)
        val countsIdx = it.getColumnIndex(COL_DECK_COUNTS)
        while (it.moveToNext()) {
          if (idIdx != -1 && nameIdx != -1) {
            val deckId = it.getLong(idIdx)
            val deckName = it.getString(nameIdx)
            list.add(Pair(DeckId(deckId), deckName))
            if (optionsIdx != -1) {
              options[deckId] = if (it.isNull(optionsIdx)) null else it.getString(optionsIdx)
            }
            if (countsIdx != -1 && !it.isNull(countsIdx)) {
              parseDueCounts(it.getString(countsIdx))?.let { triple -> counts[deckId] = triple }
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to fetch all decks from AnkiDroid: ${e.message}", e)
    }
    if (list.isEmpty()) {
      list.add(Pair(DeckId(1L), "Default"))
    }
    deckOptionsByDeck = options
    deckCountsByDeck = counts
    return list
  }

  /**
   * Best-effort per-deck due counts from the Deck provider's counts column, when the installed
   * AnkiDroid exposes one (`FlashCardsContract.Deck.DECK_COUNTS`, whose column is named `deck_count`,
   * a JSON `[learn, review, new]`). Returns
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
        val countsIdx = c.getColumnIndex(COL_DECK_COUNTS)
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

  /** Fetch the due cards batch. Returns mock cards only when [forceMock] is set (offline demo). */
  fun fetchDueBatch(deckId: DeckId, limit: Int, forceMock: Boolean = false): WearosDueBatch {
    if (forceMock) {
      Log.i(TAG, "Mock mode forced. Returning mock due batch.")
      return getMockDueBatch(deckId)
    }
    // Unavailable -> empty batch, never mocks: mock cards stamped with the real deck id would
    // clobber the cached deck and, if graded, enter the buffer as answers that never settle.
    if (!isAnkiDroidAvailable()) {
      Log.w(TAG, "AnkiDroid unavailable; returning empty batch for deck ${deckId.value}.")
      return WearosDueBatch(deckId = deckId.value, deckName = "Deck ${deckId.value}", cards = emptyList())
    }

    try {
      val allDecks = fetchAllDecks()
      val matchedDeck = allDecks.find { it.first == deckId }
      if (matchedDeck == null) {
        // Requested deck no longer exists (deleted / deselected). Return an empty batch tagged with
        // the REQUESTED id, never a substitute deck: the watch matches the arrival on the id it asked
        // for, so a different deck just leaves it waiting out the full download timeout.
        Log.w(TAG, "Requested deck ${deckId.value} not found; returning empty batch.")
        return WearosDueBatch(
          deckId = deckId.value,
          deckName = "Deck ${deckId.value}",
          cards = emptyList(),
        )
      }
      val (resolvedDeckId, resolvedDeckName) = matchedDeck
      Log.i(TAG, "Using AnkiDroid deck: $resolvedDeckName (ID: $resolvedDeckId)")

      // The deck's scheduling config (learning steps, graduation intervals, FSRS weights) drives
      // the watch's offline preview recompute. A missing config is not fatal: previews then stay
      // as AnkiDroid shipped them.
      val scheduleConfig = fetchDeckScheduleConfig(resolvedDeckId.value)

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
          val cardUri = Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord")
          var row =
            CardRow(
              id = 0L,
              noteId = noteId,
              ord = ord,
              front = "No Front Text",
              back = "No Back Text",
              due = null,
              state = null,
            )
          val cardCursor =
            contentResolver.query(
              cardUri,
              CARD_ROW_PROJECTION,
              null,
              null,
              null,
            )

          cardCursor?.use { cCursor ->
            if (cCursor.moveToFirst()) row = readCardRow(cCursor, noteId, ord, scheduleConfig)
          }

          cards.add(
            WearosCard(
              id = row.id,
              noteId = row.noteId,
              ord = row.ord,
              front = row.front,
              back = row.back,
              buttonCount = buttonCount,
              nextReviewTimes = nextReviewTimes,
              state = row.state,
            )
          )
        }
      }

      return WearosDueBatch(
        deckId = resolvedDeckId.value,
        deckName = resolvedDeckName,
        cards = cards,
        scheduleConfig = scheduleConfig,
      )
    } catch (e: Exception) {
      // Return an empty batch (not mocks) so a transient provider error can't overwrite the
      // real cached deck with phantom cards.
      Log.e(TAG, "Failed to query AnkiDroid ContentProvider: ${e.message}. Returning empty batch.", e)
      return WearosDueBatch(deckId = deckId.value, deckName = "Deck ${deckId.value}", cards = emptyList())
    }
  }

  /**
   * Fetch a multi-day card pool for [deckId]: today's scheduler queue plus the next [horizonDays]
   * days of due cards, so the watch can keep reviewing offline for days. Today's cards keep
   * AnkiDroid's exact interval labels; the rest carry their scheduler state and absolute due day and
   * the watch recomputes the labels from the deck config.
   *
   * Falls back to today's batch alone in mock mode, when AnkiDroid is unreachable, when the deck no
   * longer exists, or on an AnkiDroid too old to expose the cards search endpoint.
   */
  fun fetchDuePool(
    deckId: DeckId,
    todayLimit: Int,
    horizonDays: Int = POOL_HORIZON_DAYS,
    forceMock: Boolean = false,
  ): WearosDueBatch {
    if (forceMock || !isAnkiDroidAvailable()) return fetchDueBatch(deckId, todayLimit, forceMock)
    val allDecks = fetchAllDecks()
    val (resolvedDeckId, resolvedDeckName) =
      allDecks.find { it.first == deckId } ?: return fetchDueBatch(deckId, todayLimit, forceMock)
    // Ask AnkiDroid for its whole queue for today (order, limits and deck overrides applied), not
    // just a first slice, so the watch's day matches the phone's.
    val dueToday = deckCountsByDeck[resolvedDeckId.value]
    val wanted = dueToday?.sum()?.coerceIn(todayLimit, MAX_TODAY_QUEUE) ?: todayLimit
    val today = fetchDueBatch(deckId, wanted, forceMock)
    // did: matches one deck exactly, while today's scheduler batch includes subdecks; list them too.
    val deckIds =
      allDecks
        .filter { (id, name) -> id == resolvedDeckId || name.startsWith("$resolvedDeckName::") }
        .joinToString(",") { it.first.value.toString() }

    val presetConfig = fetchDeckScheduleConfig(resolvedDeckId.value)
    val now = clock()
    val utcOffsetSecs = timeZone.getOffset(now) / 1000
    // AnkiDroid doesn't expose its own "next day starts at" hour yet (ankidroid/Anki-Android#20773
    // tracks that), so the pool uses Anki's default rollover to number its days.
    val rollover = defaultRolloverHour()
    val todayIndex = dayIndex(now, utcOffsetSecs, rollover)
    // Kept last and parenthesized: on AnkiDroid 2.25.1 a search that starts with did: ignores every
    // term after it (verified on-device), which would ship the whole deck as "due tomorrow".
    val deckSelector = "(did:$deckIds)"

    // Ids already in the pool, so a card that shows up in more than one search (or in today's queue)
    // is carried once, under the earliest group.
    val collected: MutableSet<Long> = today.cards.mapTo(HashSet<Long>()) { it.id }
    val todayCards = today.cards.map { it.copy(queuedDay = todayIndex) }
    val queueComplete = isCompleteQueue(today.cards.size, wanted, dueToday)

    val newPositions = searchCardPositions("is:new -is:suspended -is:buried $deckSelector")
    // The preset's new.perDay misses deck-level overrides; infer the deck's real one from what
    // AnkiDroid queued today. Only days after today use it (today is AnkiDroid's own queue).
    val config =
      presetConfig?.let { c ->
        val inferred =
          effectiveNewPerDay(
            presetNewPerDay = c.newPerDay,
            newQueuedToday = dueToday?.getOrNull(2),
            introducedToday = countCards("introduced:1 $deckSelector"),
            newAvailable = newPositions.size,
          )
        if (inferred != c.newPerDay) Log.i(TAG, "Effective new/day $inferred (preset ${c.newPerDay})")
        c.copy(newPerDay = inferred)
      }

    val learningRows =
      searchCards("is:learn -is:suspended -is:buried $deckSelector", config) ?: return today
    val learning =
      learningRows
        .filter { it.state?.queue == 1 }
        .filter { collected.add(it.id) }
        .map { poolCard(it, dueDay = null, dueAtSecs = it.due) }

    val upcoming = buildList {
      for (k in 1..horizonDays) {
        val rows = searchCards("prop:due=$k -is:suspended -is:buried $deckSelector", config) ?: continue
        for (row in rows) {
          if (collected.add(row.id)) add(poolCard(row, dueDay = todayIndex + k, dueAtSecs = null))
        }
      }
    }

    // No config means no known daily new limit, so ship none rather than an unbounded slice.
    val allowance = if (config == null) 0 else newCardAllowance(config.newPerDay, horizonDays)
    val newCards = fetchNewForPool(newPositions, allowance, collected, config)

    return WearosDueBatch(
      deckId = resolvedDeckId.value,
      deckName = resolvedDeckName,
      cards = orderedPool(todayCards, learning, upcoming, newCards),
      rolloverHour = rollover,
      fetchedAtMs = now,
      scheduleConfig = config,
      queueDay = if (queueComplete) todayIndex else null,
    )
  }

  /** Today's Anki day number for this bridge's clock and time zone, for date-scoped decisions. */
  fun todayDayIndex(): Long {
    val now = clock()
    return dayIndex(now, timeZone.getOffset(now) / 1000, defaultRolloverHour())
  }

  /**
   * The first [allowance] new cards by position, skipping ids already [collected]. The provider
   * renders a card's question and answer for every row that asks for them, so a deck with thousands
   * of new cards is listed by id and position first and only the shipped ones are rendered.
   */
  private fun fetchNewForPool(
    newPositions: List<Pair<Long, Long>>,
    allowance: Int,
    collected: MutableSet<Long>,
    config: DeckScheduleConfig?,
  ): List<WearosCard> {
    if (allowance <= 0) return emptyList()
    val picked =
      newPositions
        .sortedBy { it.second }
        .map { it.first }
        .filter { it !in collected }
        .take(allowance)
    if (picked.isEmpty()) return emptyList()
    val rows = searchCards("cid:${picked.joinToString(",")}", config)?.associateBy { it.id }.orEmpty()
    return picked.mapNotNull { id ->
      rows[id]?.takeIf { collected.add(id) }?.let { poolCard(it, dueDay = null, dueAtSecs = null) }
    }
  }

  /** How many cards match [search], without rendering them; null if the search failed. */
  private fun countCards(search: String): Int? =
    try {
      context.contentResolver.query(CARDS_URI, arrayOf("_id"), search, null, null)?.use { it.count }
    } catch (e: Exception) {
      Log.w(TAG, "Cards count '$search' failed (${e.message}).")
      null
    }

  /** Card ids and their `due` (a new card's queue position) for [search], without rendering. */
  private fun searchCardPositions(search: String): List<Pair<Long, Long>> =
    try {
      context.contentResolver
        .query(CARDS_URI, arrayOf("_id", COL_DUE), search, null, null)
        ?.use { cursor ->
          val idIdx = cursor.getColumnIndexOrThrow("_id")
          val dueIdx = cursor.getColumnIndexOrThrow(COL_DUE)
          buildList {
            while (cursor.moveToNext()) {
              val due = if (cursor.isNull(dueIdx)) Long.MAX_VALUE else cursor.getLong(dueIdx)
              add(cursor.getLong(idIdx) to due)
            }
          }
        }
        .orEmpty()
    } catch (e: Exception) {
      Log.w(TAG, "Cards search '$search' failed (${e.message}); shipping no new cards.")
      emptyList()
    }

  /** One Anki browser search against the cards endpoint; null when the endpoint is unavailable. */
  private fun searchCards(search: String, config: DeckScheduleConfig?): List<CardRow>? =
    try {
      context.contentResolver.query(CARDS_URI, CARD_ROW_PROJECTION, search, null, null)?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            add(readCardRow(cursor, fallbackNoteId = 0L, fallbackOrd = 0, config = config))
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Cards search '$search' failed (${e.message}); assuming no cards endpoint.")
      null
    }

  /** A pool card: no interval labels (the watch recomputes them), always four grade buttons. */
  private fun poolCard(row: CardRow, dueDay: Long?, dueAtSecs: Long?): WearosCard =
    WearosCard(
      id = row.id,
      noteId = row.noteId,
      ord = row.ord,
      front = row.front,
      back = row.back,
      buttonCount = 4,
      nextReviewTimes = emptyList(),
      dueDay = dueDay,
      dueAtSecs = dueAtSecs,
      state = row.state,
    )

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
    if (forceMock) {
      return getMockDueBatch(deckId).let { it.copy(deckName = "${it.deckName} (new)") }
    }
    if (!isAnkiDroidAvailable()) {
      return WearosDueBatch(deckId = deckId.value, deckName = "New cards", cards = emptyList())
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
    // Escape backslashes before quotes, or a deck name ending in '\' would break the quoted term.
    val escaped = deckName.replace("\\", "\\\\").replace("\"", "\\\"")
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
   * Write answers back to AnkiDroid's schedule table. Returns a [WriteBackOutcome] whose `settled`
   * ids are safe to clear from the watch buffer (applied, or terminally unmatched) and whose
   * `unplaced` ids were examined but could not be placed this pass (kept buffered for retry).
   *
   * An applied answer is confirmed by re-reading the card's reps, not by the update's row count: the
   * provider swallows a scheduler error from `col.sched.answerCard` and still reports a row, so a
   * count of 1 can mean nothing happened. A real answer bumps reps, a swallowed one does not, and
   * that answer stays buffered.
   */
  fun writeAnswersBack(answers: List<WearosAnswer>, readOnly: Boolean = false): WriteBackOutcome {
    if (readOnly) {
      // Safe/read-only test mode: exercise the full sync round-trip (including the clear_answers
      // handshake, which clears the watch buffer) without ever touching the real collection. The
      // reviews are intentionally NOT applied, so they are discarded rather than scheduled.
      Log.w(
        TAG,
        "Read-only mode ON: NOT writing ${answers.size} answers to AnkiDroid; reporting them " +
          "settled so the watch buffer clears.",
      )
      return WriteBackOutcome(settled = answers.map { it.id }, unplaced = emptyList())
    }
    if (!isAnkiDroidAvailable()) {
      // Provider unreachable (not installed, disabled, mid-update, or the read/write permission
      // not yet granted - all normal first-run states). This is transient, NOT a terminal outcome:
      // settling here would send clear_answers and permanently drop the user's buffered reviews.
      // Keep everything buffered for a later retry.
      Log.w(TAG, "AnkiDroid unavailable; keeping ${answers.size} answers buffered for retry.")
      return WriteBackOutcome.NONE
    }

    // IDs to clear from the watch buffer = terminal outcomes: applied, or the card no longer exists
    // (0 rows). A thrown exception is transient and keeps everything buffered for a later retry.
    val settledIds = mutableListOf<String>()
    var applied = 0
    var transientFailure = false
    val contentResolver: ContentResolver = context.contentResolver

    // Dedupe defense-in-depth: an answer id we've already applied must never be applied again, even
    // if the watch re-sends it (a missed clear_answers) while its card happens to be presented again
    // (a relearn re-presented this session). Pre-filter those out and report them settled so the
    // watch buffer clears; the drain loop below then only sees not-yet-applied answers.
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

    // rslib rejects a ReviewInfo update only while its study queues are built ("not at top of
    // queue"), and the provider swallows that rejection: it still reports a row while rslib logs and
    // spends seconds sending a crash report, so failures are avoided rather than retried blindly.
    // AnkiDroid drops those queues when the selected deck actually changes (selecting the deck that
    // is already selected does nothing), and any schedule query rebuilds them. So one toggle - select
    // another deck, select ours back - before the first answer frees the whole pass to apply in any
    // order, learning chains included. Answers are addressed by (note_id, ord), so the deck no longer
    // matters for placement.
    //
    // A single-deck collection has nothing to toggle to, so it falls back to priming the scheduler
    // per deck and answering only the head card it presents (see primeAndDrain).
    val selectedDeckId = readSelectedDeckId(contentResolver)
    val toggleDeckId =
      selectedDeckId?.let { selected ->
        // Reuse the deck listing: any deck other than the current one flips the selection.
        fetchAllDecks().firstOrNull { (deck, _) -> deck.value != selected }?.first?.value
      }
    val togglePlan: Pair<Long, Long>? =
      if (toggleDeckId != null &&
        selectedDeckId != null &&
        dropStudyQueues(contentResolver, toggleDeckId, selectedDeckId)
      ) {
        toggleDeckId to selectedDeckId
      } else {
        null
      }

    if (togglePlan == null) {
      val fallback = primeAndDrain(contentResolver, remaining, settledIds, appliedIdStore)
      applied += fallback.applied
      transientFailure = fallback.transientFailure
      // A half-done toggle (the second select failed) must not leave the user on the toggle deck.
      if (toggleDeckId != null && selectedDeckId != null) selectDeck(contentResolver, selectedDeckId)
    } else {
      val (toggleDeck, originalDeck) = togglePlan
      // Cards whose chain is already broken: once one grade for a (note_id, ord) can't be placed,
      // every later grade for it is skipped (a learning chain must not apply out of order).
      val blockedCards = mutableSetOf<Pair<Long, Int>>()
      try {
        var i = 0
        while (i < remaining.size) {
          val answer = remaining[i]
          if (answer.noteId to answer.ord in blockedCards) {
            // A chain already broke on this card, so apply nothing further for it.
            i++
            continue
          }
          var result = applyOneAnswer(contentResolver, answer, appliedIdStore)
          // Something rebuilt the queues mid-pass (e.g. AnkiDroid's own UI): drop them again and retry
          // this one answer exactly once.
          if (result == ApplyResult.NOT_APPLIED &&
            dropStudyQueues(contentResolver, toggleDeck, originalDeck)
          ) {
            result = applyOneAnswer(contentResolver, answer, appliedIdStore)
          }
          when (result) {
            ApplyResult.APPLIED -> {
              applied++
              settledIds.add(answer.id)
              remaining.removeAt(i)
              // removeAt shifts the following answers down, so i stays for the next one.
              continue
            }
            ApplyResult.TERMINAL -> {
              settledIds.add(answer.id)
              remaining.removeAt(i)
              continue
            }
            ApplyResult.NOT_APPLIED -> {
              Log.w(TAG, "Could not place answer ${answer.id} (note ${answer.noteId}/${answer.ord}).")
              blockedCards.add(answer.noteId to answer.ord)
            }
          }
          i++
        }
      } catch (e: Exception) {
        Log.e(TAG, "Transient failure writing back; keeping the rest for retry: ${e.message}", e)
        transientFailure = true
      } finally {
        // Leave the collection on the deck the user had selected before the pass.
        selectDeck(contentResolver, originalDeck)
      }
    }

    Log.i(TAG, "Write-back: $applied applied, ${remaining.size} kept for retry/later.")
    // A transient exception says nothing about whether the leftovers are placeable, so report them
    // unplaced only when the drain finished normally.
    val unplaced = if (transientFailure) emptyList() else remaining.map { it.id }
    return WriteBackOutcome(settledIds, unplaced)
  }

  /** Outcome of answering one card: either it landed (or is terminal), or it stayed unplaced. */
  private enum class ApplyResult {
    APPLIED,
    TERMINAL,
    NOT_APPLIED,
  }

  private data class DrainResult(val applied: Int, val transientFailure: Boolean)

  /**
   * The currently selected deck id, or null when it can't be read (provider absent, no row, no
   * deck_id column). Null sends write-back down its single-deck fallback path.
   */
  private fun readSelectedDeckId(contentResolver: ContentResolver): Long? =
    try {
      contentResolver
        .query(DECK_SELECTED_URI, arrayOf(COL_DECK_ID), null, null, null)
        ?.use { cursor ->
          val idx = cursor.getColumnIndex(COL_DECK_ID)
          if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
    } catch (e: Exception) {
      Log.w(TAG, "Selected deck unavailable (${e.message}); using the head-gated fallback.")
      null
    }

  /** Select [deckId]; true when the provider reports the deck exists (rows > 0). */
  private fun selectDeck(contentResolver: ContentResolver, deckId: Long): Boolean =
    try {
      val values = ContentValues().apply { put(COL_DECK_ID, deckId) }
      contentResolver.update(DECK_SELECTED_URI, values, null, null) > 0
    } catch (e: Exception) {
      Log.w(TAG, "Failed to select deck $deckId: ${e.message}")
      false
    }

  /**
   * Drop AnkiDroid's study queues by flipping the selected deck away and back. rslib only rejects an
   * answer that is not at the head of those queues, and only builds them on a schedule query, so with
   * none issued in between a run of answers applies in any order. False if either selection didn't
   * take (a missing toggle deck), leaving the caller on the fallback path.
   */
  private fun dropStudyQueues(
    contentResolver: ContentResolver,
    toggleDeckId: Long,
    selectedDeckId: Long,
  ): Boolean =
    selectDeck(contentResolver, toggleDeckId) && selectDeck(contentResolver, selectedDeckId)

  /**
   * Answer one card via FlashCardsContract.ReviewInfo (fields in ContentValues, null selection) and
   * confirm it landed by re-reading reps. The provider swallows a scheduler error and still reports a
   * row, so the row count alone proves nothing: a real answerCard bumps reps, a swallowed one does
   * not. Zero rows is terminal (already answered / removed); a row with no reps bump stays unplaced.
   */
  private fun applyOneAnswer(
    contentResolver: ContentResolver,
    answer: WearosAnswer,
    appliedIdStore: LinkedHashSet<String>,
  ): ApplyResult {
    // Clamp ease to the 1..4 the scheduler accepts; a corrupt/out-of-range wire value must never
    // reach col.sched.answerCard.
    val values =
      ContentValues().apply {
        put(COL_REVIEW_NOTE_ID, answer.noteId)
        put(COL_REVIEW_ORD, answer.ord)
        put(COL_REVIEW_EASE, answer.ease.coerceIn(1, 4))
        put(COL_REVIEW_TIME_TAKEN, answer.timeTakenMs)
      }
    val repsBefore = readCardReps(contentResolver, answer.noteId, answer.ord)
    val rowsUpdated = contentResolver.update(SCHEDULE_URI, values, null, null)
    val repsAfter = readCardReps(contentResolver, answer.noteId, answer.ord)
    if (rowsUpdated == 0) {
      Log.w(TAG, "Answer for note ${answer.noteId} wrote 0 rows; treating as terminal.")
      return ApplyResult.TERMINAL
    }
    // No reps column (older AnkiDroid): trust the row count as before.
    val appliedNow =
      if (repsBefore != null && repsAfter != null) repsAfter > repsBefore else true
    if (!appliedNow) {
      Log.w(
        TAG,
        "Provider reported $rowsUpdated row(s) but reps did not advance for note " +
          "${answer.noteId}/${answer.ord}; keeping the answer buffered.",
      )
      return ApplyResult.NOT_APPLIED
    }
    appliedIdStore.add(answer.id)
    // Persist immediately: if the service is killed mid-drain, an applied-but-unrecorded id would be
    // re-applied on the next sync. Saving per-apply narrows that window to a single update.
    saveAppliedIds(appliedIdStore)
    return ApplyResult.APPLIED
  }

  /**
   * Single-deck fallback: prime the scheduler per deck and answer only the head card it presents.
   * Kept so a collection with one deck (nothing to toggle to) never does worse than before. deck_id 0
   * = unknown (older buffers / cross-version wire): primed with a null selection = whatever deck is
   * current. See [primeScheduler].
   */
  private fun primeAndDrain(
    contentResolver: ContentResolver,
    remaining: MutableList<WearosAnswer>,
    settledIds: MutableList<String>,
    appliedIdStore: LinkedHashSet<String>,
  ): DrainResult {
    var applied = 0
    var transientFailure = false
    val deckOrder = remaining.map { it.deckId }.distinct().sortedBy { if (it == 0L) 0 else 1 }
    deckLoop@ for (deckId in deckOrder) {
      while (remaining.isNotEmpty()) {
        val current = primeScheduler(contentResolver, deckId) ?: break
        val idx = remaining.indexOfFirst { it.noteId == current.first && it.ord == current.second }
        if (idx < 0) {
          // Scheduler presenting a card we have no buffered answer for: applying any of ours now would
          // grade the wrong card. Move on to the next deck (its answers stay buffered for retry).
          Log.i(TAG, "Scheduler on note ${current.first}/${current.second} (not buffered); next deck.")
          break
        }
        val answer = remaining[idx]
        try {
          when (applyOneAnswer(contentResolver, answer, appliedIdStore)) {
            ApplyResult.APPLIED -> {
              applied++
              settledIds.add(answer.id)
              remaining.removeAt(idx)
            }
            ApplyResult.TERMINAL -> {
              settledIds.add(answer.id)
              remaining.removeAt(idx)
            }
            // Keep it buffered and stop this deck rather than re-priming the same card forever.
            ApplyResult.NOT_APPLIED -> {
              break
            }
          }
        } catch (e: Exception) {
          Log.e(
            TAG,
            "Transient failure writing note ${answer.noteId}; keeping it + the rest for retry: " +
              "${e.message}",
            e,
          )
          transientFailure = true
          break@deckLoop
        }
      }
    }
    return DrainResult(applied, transientFailure)
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
      // commit() (not apply()): this is called per-apply so the id is durably recorded before the
      // next scheduler update. An async apply() could lose the record if the service is killed
      // between the update and the flush, reopening the double-apply window this store exists to close.
      .commit()
  }

  /**
   * Fallback path only (see [primeAndDrain]): prime [deckId]'s scheduler and return the card it now
   * presents as (note_id, ord), or null if none is due / the query failed. Querying with the deckID=
   * selection (the same call fetchDueBatch uses) presents THIS deck's head so the following update
   * grades it; a null-selection query would revert to the collection's current deck. deckId 0
   * (unknown) uses a null selection. The caller answers only the buffered answer matching this card,
   * never the wrong one.
   */
  private fun primeScheduler(contentResolver: ContentResolver, deckId: Long): Pair<Long, Int>? {
    val selection = if (deckId != 0L) "limit=?,deckID=?" else null
    val selectionArgs = if (deckId != 0L) arrayOf("1", deckId.toString()) else null
    return try {
      contentResolver.query(SCHEDULE_URI, null, selection, selectionArgs, null)?.use { cursor ->
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
