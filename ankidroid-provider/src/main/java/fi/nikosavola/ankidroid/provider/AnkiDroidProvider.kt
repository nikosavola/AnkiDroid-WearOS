package fi.nikosavola.ankidroid.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.util.Log
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.CardColumns
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.DeckColumns
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.ScheduleColumns

/**
 * Blocking client for AnkiDroid's flashcard ContentProvider. Every call is a provider round trip
 * (some render card HTML), so call it off the main thread. The caller needs the
 * [AnkiDroidContract.PERMISSION] runtime permission granted.
 */
class AnkiDroidProvider(private val resolver: ContentResolver) {
  constructor(context: Context) : this(context.contentResolver)

  /** Whether AnkiDroid's provider is installed and reachable. */
  fun isAvailable(): Boolean =
    try {
      val client = resolver.acquireContentProviderClient(AnkiDroidContract.SCHEDULE_URI)
      if (client != null) {
        client.close()
        true
      } else {
        false
      }
    } catch (e: Exception) {
      false
    }

  /**
   * Every deck AnkiDroid lists, or null when the listing failed (no cursor, or the provider threw;
   * e.g. AnkiDroid not set up yet). Never substitutes a stand-in deck.
   */
  fun listDecks(): List<Deck>? =
    try {
      resolver.query(AnkiDroidContract.DECKS_URI, null, null, null, null)?.use { readDecks(it) }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to fetch all decks from AnkiDroid: ${e.message}", e)
      null
    }

  private fun readDecks(cursor: Cursor): List<Deck> {
    val idIdx = cursor.getColumnIndex(DeckColumns.ID)
    val nameIdx = cursor.getColumnIndex(DeckColumns.NAME)
    val optionsIdx = cursor.getColumnIndex(DeckColumns.OPTIONS)
    val countsIdx = cursor.getColumnIndex(DeckColumns.COUNTS)
    return buildList {
      while (cursor.moveToNext()) {
        if (idIdx == -1 || nameIdx == -1) continue
        val id = cursor.getLong(idIdx)
        val name = cursor.getString(nameIdx)
        val options =
          if (optionsIdx != -1 && !cursor.isNull(optionsIdx)) cursor.getString(optionsIdx) else null
        val counts =
          if (countsIdx != -1 && !cursor.isNull(countsIdx)) {
            DueCounts.parse(cursor.getString(countsIdx))
          } else {
            null
          }
        add(Deck(id, name, counts, options, hasOptionsColumn = optionsIdx != -1))
      }
    }
  }

  /** The deck AnkiDroid has selected (only id and name filled), or null when unreadable. */
  fun selectedDeck(): Deck? {
    try {
      resolver.query(AnkiDroidContract.SELECTED_DECK_URI, null, null, null, null)?.use {
        if (it.moveToFirst()) {
          val idIdx = it.getColumnIndex(DeckColumns.ID)
          val nameIdx = it.getColumnIndex(DeckColumns.NAME)
          if (idIdx != -1 && nameIdx != -1) return Deck(it.getLong(idIdx), it.getString(nameIdx))
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query selected deck from AnkiDroid: ${e.message}", e)
    }
    return null
  }

  /** [deckId]'s raw options JSON from a fresh deck listing; null if missing or unreadable. */
  fun deckOptions(deckId: Long): String? =
    try {
      resolver.query(AnkiDroidContract.DECKS_URI, null, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndex(DeckColumns.ID)
        val optionsIdx = cursor.getColumnIndex(DeckColumns.OPTIONS)
        while (cursor.moveToNext()) {
          if (cursor.getLong(idIdx) != deckId) continue
          return if (cursor.isNull(optionsIdx)) null else cursor.getString(optionsIdx)
        }
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Deck options unavailable for deck $deckId (${e.message}).", e)
      null
    }

  /**
   * Each deck's total due count (the sum of whatever integers its `deck_count` holds), keyed by deck
   * id. Empty when the column is absent or anything fails, so treat a missing deck as unknown, not
   * zero. The provider may compute counts for the whole collection.
   */
  fun deckDueTotals(): Map<Long, Int> {
    val out = mutableMapOf<Long, Int>()
    try {
      resolver.query(AnkiDroidContract.DECKS_URI, null, null, null, null)?.use { c ->
        val idIdx = c.getColumnIndex(DeckColumns.ID)
        val countsIdx = c.getColumnIndex(DeckColumns.COUNTS)
        if (idIdx == -1 || countsIdx == -1) return emptyMap()
        while (c.moveToNext()) {
          val id = c.getLong(idIdx)
          val raw = c.getString(countsIdx) ?: continue
          // Tolerant of the exact bracket/format the AnkiDroid version uses.
          val nums = Regex("\\d+").findAll(raw).map { it.value.toIntOrNull() ?: 0 }.toList()
          if (nums.isNotEmpty()) out[id] = nums.sum()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read deck counts (column may not exist): ${e.message}")
      return emptyMap()
    }
    return out
  }

  /**
   * Up to [limit] cards of AnkiDroid's queue for [deckId] (subdecks included), in its order. The
   * query builds rslib's study queues for that deck. Empty when the provider returns no cursor;
   * provider errors propagate.
   */
  fun schedule(deckId: Long, limit: Int): List<ScheduledCard> =
    resolver
      .query(
        AnkiDroidContract.SCHEDULE_URI,
        null,
        "limit=?,deckID=?",
        arrayOf(limit.toString(), deckId.toString()),
        null,
      )
      ?.use { cursor ->
        val noteIdIdx = cursor.getColumnIndex(ScheduleColumns.NOTE_ID)
        val ordIdx = cursor.getColumnIndex(ScheduleColumns.ORD)
        val buttonCountIdx = cursor.getColumnIndex(ScheduleColumns.BUTTON_COUNT)
        val reviewTimesIdx = cursor.getColumnIndex(ScheduleColumns.NEXT_REVIEW_TIMES)
        buildList {
          while (cursor.moveToNext()) {
            add(
              ScheduledCard(
                noteId = if (noteIdIdx != -1) cursor.getLong(noteIdIdx) else 0L,
                ord = if (ordIdx != -1) cursor.getInt(ordIdx) else 0,
                buttonCount = if (buttonCountIdx != -1) cursor.getInt(buttonCountIdx) else null,
                nextReviewTimes =
                  if (reviewTimesIdx != -1) cursor.getString(reviewTimesIdx) else null,
              )
            )
          }
        }
      }
      .orEmpty()

  /**
   * One card by note id and ordinal, or null when the provider has no such row. [includeContent]
   * false skips rendering the question and answer; [includeScheduling] false skips the scheduler
   * columns. Provider errors propagate.
   */
  fun card(
    noteId: Long,
    ord: Int,
    includeContent: Boolean = true,
    includeScheduling: Boolean = true,
  ): ProviderCard? =
    resolver
      .query(
        AnkiDroidContract.cardUri(noteId, ord),
        projection(includeContent, includeScheduling),
        null,
        null,
        null,
      )
      ?.use { if (it.moveToFirst()) readCard(it, noteId, ord) else null }

  /**
   * Cards matching an Anki browser [search], or null when the cards endpoint is unavailable (older
   * AnkiDroid) or the search failed. [includeContent] false skips rendering question and answer,
   * which AnkiDroid otherwise does for every row.
   */
  fun searchCards(search: String, includeContent: Boolean = true): List<ProviderCard>? =
    try {
      resolver
        .query(
          AnkiDroidContract.CARDS_URI,
          projection(includeContent, includeScheduling = true),
          search,
          null,
          null,
        )
        ?.use { cursor ->
          buildList {
            while (cursor.moveToNext()) add(readCard(cursor, fallbackNoteId = 0L, fallbackOrd = 0))
          }
        }
    } catch (e: Exception) {
      Log.w(TAG, "Cards search '$search' failed (${e.message}); assuming no cards endpoint.")
      null
    }

  /** How many cards match [search], without rendering them; null if the search failed. */
  fun countCards(search: String): Int? =
    try {
      resolver
        .query(AnkiDroidContract.CARDS_URI, arrayOf(CardColumns.ID), search, null, null)
        ?.use { it.count }
    } catch (e: Exception) {
      Log.w(TAG, "Cards count '$search' failed (${e.message}).")
      null
    }

  /** Card ids and their raw `due` for [search], without rendering; empty if the search failed. */
  fun searchCardPositions(search: String): List<CardPosition> =
    try {
      resolver
        .query(
          AnkiDroidContract.CARDS_URI,
          arrayOf(CardColumns.ID, CardColumns.DUE),
          search,
          null,
          null,
        )
        ?.use { cursor ->
          val idIdx = cursor.getColumnIndexOrThrow(CardColumns.ID)
          val dueIdx = cursor.getColumnIndexOrThrow(CardColumns.DUE)
          buildList {
            while (cursor.moveToNext()) {
              val due = if (cursor.isNull(dueIdx)) Long.MAX_VALUE else cursor.getLong(dueIdx)
              add(CardPosition(cursor.getLong(idIdx), due))
            }
          }
        }
        .orEmpty()
    } catch (e: Exception) {
      Log.w(TAG, "Cards search '$search' failed (${e.message}).")
      emptyList()
    }

  /**
   * The first [wanted] unsuspended, unburied new cards in [decks] by queue position, past position
   * [after], in position order. Read in growing `prop:pos` windows: AnkiDroid builds every result
   * row separately, so listing a 5000-card deck's whole new queue took 35 s on a Pixel 8 Pro, while
   * windows cost about what they return.
   */
  fun searchNewCardPositions(decks: DeckSelector, wanted: Int, after: Long): List<CardPosition> {
    val found = mutableListOf<CardPosition>()
    var below = after
    var window = NEW_POSITION_WINDOW
    // Growth is capped so one window can't swallow a large part of the deck at once.
    val maxWindow = 4L * wanted + NEW_POSITION_WINDOW
    while (found.size < wanted && below < MAX_NEW_POSITION) {
      val upTo = below + window
      val rows =
        searchCardPositions(
          decks.scope("is:new -is:suspended -is:buried prop:pos>$below prop:pos<=$upTo")
        )
      found += rows
      below = upTo
      // Empty stretches (cards already studied) double the window; once new cards turn up, ask for
      // about what is still missing.
      window =
        if (rows.isEmpty()) {
          minOf(window * 2, maxWindow)
        } else {
          maxOf(NEW_POSITION_WINDOW, 2L * (wanted - found.size))
        }
    }
    return found.distinctBy { it.cardId }.sortedBy { it.position }.take(wanted)
  }

  /** Ids of up to [limit] notes matching an Anki browser [search]. Provider errors propagate. */
  fun searchNoteIds(search: String, limit: Int): List<Long> {
    val ids = mutableListOf<Long>()
    resolver.query(AnkiDroidContract.NOTES_URI, arrayOf("_id"), search, null, null)?.use { cursor ->
      val idIdx = cursor.getColumnIndex("_id")
      while (cursor.moveToNext() && ids.size < limit) {
        if (idIdx != -1) ids.add(cursor.getLong(idIdx))
      }
    }
    return ids
  }

  /** The ordinals of [noteId]'s cards. Provider errors propagate. */
  fun cardOrds(noteId: Long): List<Int> {
    val ords = mutableListOf<Int>()
    resolver.query(AnkiDroidContract.noteCardsUri(noteId), null, null, null, null)?.use { c ->
      val ordIdx = c.getColumnIndex(CardColumns.ORD)
      while (c.moveToNext()) if (ordIdx != -1) ords.add(c.getInt(ordIdx))
    }
    return ords
  }

  /**
   * Write [answers] back to AnkiDroid's scheduler, oldest first, and report which are settled. A
   * thrown provider error keeps the rest for a later retry; ids already in [store] are settled
   * without being written again. See [AnswerWriter] for the procedure.
   */
  fun writeAnswers(answers: List<ReviewAnswer>, store: AppliedAnswerStore): WriteBackOutcome =
    AnswerWriter(this, resolver, store).write(answers)

  internal companion object {
    const val TAG = "AnkiDroidProvider"

    // New cards are listed in `prop:pos` windows starting at this size.
    private const val NEW_POSITION_WINDOW = 64L
    private const val MAX_NEW_POSITION = 100_000_000L

    // Explicit, because the provider's default Card projection carries no scheduler columns.
    private val SCHEDULING_COLUMNS =
      arrayOf(
        CardColumns.NOTE_ID,
        CardColumns.ORD,
        CardColumns.QUEUE,
        CardColumns.TYPE,
        CardColumns.DUE,
        CardColumns.INTERVAL,
        CardColumns.LEFT,
        CardColumns.FSRS_STABILITY,
        CardColumns.FSRS_DIFFICULTY,
        CardColumns.SM2_FACTOR,
        CardColumns.LAST_REVIEW_SECS,
        CardColumns.REPS,
      )

    private fun projection(includeContent: Boolean, includeScheduling: Boolean): Array<String> =
      buildList {
          add(CardColumns.ID)
          if (includeContent) {
            add(CardColumns.QUESTION)
            add(CardColumns.ANSWER)
          }
          if (includeScheduling) addAll(SCHEDULING_COLUMNS)
        }
        .toTypedArray()

    /**
     * One card row. note_id/ord come from the cursor only when it actually carries them; otherwise
     * the caller's [fallbackNoteId]/[fallbackOrd] are used.
     */
    fun readCard(cursor: Cursor, fallbackNoteId: Long, fallbackOrd: Int): ProviderCard {
      fun present(name: String): Int? =
        cursor.getColumnIndex(name).takeIf { it != -1 && !cursor.isNull(it) }

      fun long(name: String): Long? = present(name)?.let(cursor::getLong)

      fun double(name: String): Double? = present(name)?.let(cursor::getDouble)

      fun string(name: String): String? = present(name)?.let(cursor::getString)

      val names = cursor.columnNames ?: emptyArray<String>()
      val queueIdx = cursor.getColumnIndex(CardColumns.QUEUE)
      return ProviderCard(
        id = long(CardColumns.ID),
        noteId =
          if (CardColumns.NOTE_ID in names) {
            long(CardColumns.NOTE_ID) ?: fallbackNoteId
          } else {
            fallbackNoteId
          },
        ord =
          if (CardColumns.ORD in names) {
            present(CardColumns.ORD)?.let(cursor::getInt) ?: fallbackOrd
          } else {
            fallbackOrd
          },
        question = string(CardColumns.QUESTION),
        answer = string(CardColumns.ANSWER),
        queue = if (queueIdx == -1) null else cursor.getInt(queueIdx),
        type = present(CardColumns.TYPE)?.let(cursor::getInt),
        due = long(CardColumns.DUE),
        interval = double(CardColumns.INTERVAL),
        left = long(CardColumns.LEFT),
        sm2Factor = double(CardColumns.SM2_FACTOR),
        fsrsStability = double(CardColumns.FSRS_STABILITY),
        fsrsDifficulty = double(CardColumns.FSRS_DIFFICULTY),
        reps = long(CardColumns.REPS),
        lastReviewSecs = long(CardColumns.LAST_REVIEW_SECS),
      )
    }
  }
}
