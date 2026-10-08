package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.util.Log
import fi.nikosavola.ankidroid.provider.AnkiDroidProvider
import fi.nikosavola.ankidroid.provider.AppliedAnswerStore
import fi.nikosavola.ankidroid.provider.CardPosition
import fi.nikosavola.ankidroid.provider.DeckSelector
import fi.nikosavola.ankidroid.provider.ProviderCard
import fi.nikosavola.ankidroid.provider.ReviewAnswer
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
 * Result of a write-back pass. `settled` are the ids safe to clear from the watch buffer (applied, or
 * terminally unmatched); `unplaced` are ids the phone examined but could not place this pass, reported
 * so the watch counts a real failed placement rather than every push.
 */
typealias WriteBackOutcome = fi.nikosavola.ankidroid.provider.WriteBackOutcome

/**
 * The app's view of AnkiDroid: maps the provider library's raw rows onto the Rust core's `Wearos*`
 * types and adds the watch-specific logic (due pool, previews state, mock decks, read-only mode).
 */
class AnkiDroidBridge(
  private val context: Context,
  private val clock: () -> Long = System::currentTimeMillis,
  private val timeZone: TimeZone = TimeZone.getDefault(),
) {
  companion object {
    private const val TAG = "AnkiDroidBridge"

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

    // Card type 3 is relearning: it decides learn vs relearn steps, which the queue cannot (a lapsed
    // card sits in the learning queue).
    private const val CARD_TYPE_RELEARNING = 3

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
  }

  // Resolved lazily, like the per-call context.contentResolver it replaces.
  private val provider by lazy { AnkiDroidProvider(context.contentResolver) }

  /**
   * Shared columns of one card row, parsed once for both the per-card fetch and the pool searches.
   * [due] is the raw `due` column when the cursor exposes it.
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

  private fun rowOf(card: ProviderCard, config: DeckScheduleConfig?): CardRow =
    CardRow(
      id = card.id ?: 0L,
      noteId = card.noteId,
      ord = card.ord,
      front = card.question ?: "No Front Text",
      back = card.answer ?: "No Back Text",
      due = card.due,
      state = stateOf(card, config),
    )

  /**
   * Deck OPTIONS payloads captured while listing decks, keyed by deck id (null = that deck row has
   * none). Replaced wholesale after each listing, so readers see either the previous map or the new
   * one, never a half-updated one.
   */
  @Volatile private var deckOptionsByDeck: Map<Long, String?> = emptyMap()

  /** Each deck's `[learn, review, new]` due counts from the same listing, limits applied. */
  @Volatile private var deckCountsByDeck: Map<Long, List<Int>> = emptyMap()

  /** [deckId]'s `[learn, review, new]` counts from the last [fetchAllDecks]; null if unknown. */
  fun dueCounts(deckId: DeckId): List<Int>? = deckCountsByDeck[deckId.value]

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
        provider.deckOptions(deckId)
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

  /** One card's scheduler state; null when the provider exposes none of the columns. */
  private fun stateOf(card: ProviderCard, config: DeckScheduleConfig?): CardScheduleState? {
    val queue = card.queue ?: return null
    val cardType = card.type ?: -1
    val steps = if (cardType == CARD_TYPE_RELEARNING) config?.relearnSteps else config?.learnSteps
    val learningStep = card.left?.let { learningStepFor(it, steps?.size ?: 0, queue) } ?: -1
    val lastReviewSecs = card.lastReviewSecs?.coerceAtLeast(0L) ?: 0L
    return CardScheduleState(
      queue = queue,
      cardType = cardType,
      easeFactor = card.sm2Factor?.let { it / 1000.0 } ?: 0.0,
      intervalDays = card.interval ?: 0.0,
      learningStep = learningStep,
      stability = card.fsrsStability ?: 0.0,
      difficulty = card.fsrsDifficulty ?: 0.0,
      elapsedDays = elapsedDaysSince(lastReviewSecs),
      lastReviewSecs = lastReviewSecs,
      // rslib's fuzz seed for the card's next answer: card id + reps.
      fuzzSeed = card.id?.let { id -> card.reps?.let { id + it } },
    )
  }

  /** Anki days since [lastSeconds], counted across day rollovers like rslib does. */
  private fun elapsedDaysSince(lastSeconds: Long): Double {
    if (lastSeconds <= 0) return 0.0
    val now = clock()
    return elapsedDaysBetween(lastSeconds, now, timeZone.getOffset(now) / 1000, defaultRolloverHour())
      .toDouble()
  }

  fun isAnkiDroidAvailable(): Boolean = provider.isAvailable()

  /** Query all available decks in AnkiDroid dynamically. */
  fun fetchAllDecks(): List<Pair<DeckId, String>> {
    // Unavailable provider returns an empty list, never fake decks: substituting mocks would
    // overwrite the user's real cached decks and offer phantom decks to sync.
    if (!isAnkiDroidAvailable()) return emptyList()
    return fetchDeckList().orEmpty().ifEmpty { listOf(Pair(DeckId(1L), "Default")) }
  }

  /**
   * AnkiDroid's decks exactly as it lists them, or null when the listing failed (AnkiDroid not set
   * up yet, provider error), with no stand-in deck either way.
   */
  fun fetchDeckList(): List<Pair<DeckId, String>>? {
    // The deck listing already carries every column, including OPTIONS, so the scheduling config
    // costs no extra provider query: capture it here and let fetchDeckScheduleConfig reuse it.
    val decks = provider.listDecks() ?: return null
    deckOptionsByDeck = decks.filter { it.hasOptionsColumn }.associate { it.id to it.optionsJson }
    deckCountsByDeck =
      decks.mapNotNull { deck -> deck.dueCounts?.let { deck.id to it.toList() } }.toMap()
    return decks.map { Pair(DeckId(it.id), it.name) }
  }

  /**
   * Best-effort per-deck due counts from the Deck provider's counts column, when the installed
   * AnkiDroid exposes one (`deck_count`, a JSON `[learn, review, new]`). Returns `deckId -> total`.
   * Empty map when the column is absent, AnkiDroid is unavailable, or anything fails; callers MUST
   * treat a missing entry as "unknown", never as zero. Call it off the main thread.
   */
  fun fetchDeckCounts(): Map<DeckId, Int> {
    if (!isAnkiDroidAvailable()) return emptyMap()
    return provider.deckDueTotals().mapKeys { DeckId(it.key) }
  }

  /** Query the user's active, currently selected deck from AnkiDroid dynamically. */
  fun getSelectedDeck(): Pair<DeckId, String> =
    provider.selectedDeck()?.let { Pair(DeckId(it.id), it.name) } ?: Pair(DeckId(1L), "Default")

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
      Log.i(TAG, "Using AnkiDroid deck: ${redact(resolvedDeckName)} (ID: $resolvedDeckId)")

      // The deck's scheduling config (learning steps, graduation intervals, FSRS weights) drives
      // the watch's offline preview recompute. A missing config is not fatal: previews then stay
      // as AnkiDroid shipped them.
      val scheduleConfig = fetchDeckScheduleConfig(resolvedDeckId.value)

      val cards =
        provider.schedule(resolvedDeckId.value, limit).map { scheduled ->
          val nextReviewTimes = parseReviewTimes(scheduled.nextReviewTimes ?: "1m, 10m, 4d, 7d")
          val row =
            provider.card(scheduled.noteId, scheduled.ord)?.let { rowOf(it, scheduleConfig) }
              ?: CardRow(
                id = 0L,
                noteId = scheduled.noteId,
                ord = scheduled.ord,
                front = "No Front Text",
                back = "No Back Text",
                due = null,
                state = null,
              )
          WearosCard(
            id = row.id,
            noteId = row.noteId,
            ord = row.ord,
            front = row.front,
            back = row.back,
            buttonCount = scheduled.buttonCount ?: 4,
            nextReviewTimes = nextReviewTimes,
            state = row.state,
          )
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
    val decks =
      DeckSelector(
        allDecks
          .filter { (id, name) -> id == resolvedDeckId || name.startsWith("$resolvedDeckName::") }
          .map { it.first.value }
      )

    val presetConfig = fetchDeckScheduleConfig(resolvedDeckId.value)
    val now = clock()
    val utcOffsetSecs = timeZone.getOffset(now) / 1000
    // AnkiDroid doesn't expose its own "next day starts at" hour yet (ankidroid/Anki-Android#20773
    // tracks that), so the pool uses Anki's default rollover to number its days.
    val rollover = defaultRolloverHour()
    val todayIndex = dayIndex(now, utcOffsetSecs, rollover)

    // Ids already in the pool, so a card that shows up in more than one search (or in today's queue)
    // is carried once, under the earliest group.
    val collected: MutableSet<Long> = today.cards.mapTo(HashSet<Long>()) { it.id }
    val todayCards = today.cards.map { it.copy(queuedDay = todayIndex) }
    val queueComplete = isCompleteQueue(today.cards.size, wanted, dueToday)

    // The preset's new.perDay misses deck-level overrides; infer the deck's real one from what
    // AnkiDroid queued today. Only days after today use it (today is AnkiDroid's own queue).
    val newQueuedToday = dueToday?.getOrNull(2)
    val introducedToday = provider.countCards(decks.scope("introduced:1"))
    // At most the pool's worth of new cards plus one, which is all the inference needs to tell a
    // binding limit from a deck that ran out.
    val newCeiling = maxOf(presetConfig?.newPerDay ?: 0, (newQueuedToday ?: 0) + (introducedToday ?: 0))
    val newPositions =
      if (presetConfig == null || newCeiling == 0) {
        emptyList()
      } else {
        // AnkiDroid's queued new cards are the first unstudied ones, so start right before them.
        val queuedNew = today.cards.filter { it.state?.queue == 0 }.map { it.id }
        val firstQueued =
          if (queuedNew.isEmpty()) {
            null
          } else {
            provider.searchCardPositions("cid:${queuedNew.joinToString(",")}").minOfOrNull {
              it.position
            }
          }
        provider.searchNewCardPositions(
          decks,
          wanted = newCardAllowance(newCeiling, horizonDays) + (newQueuedToday ?: 0) + 1,
          after = (firstQueued ?: 0L) - 1,
        )
      }
    val config =
      presetConfig?.let { c ->
        val inferred =
          effectiveNewPerDay(
            presetNewPerDay = c.newPerDay,
            newQueuedToday = newQueuedToday,
            introducedToday = introducedToday,
            newAvailable = newPositions.size,
            reviewLimitReached = dueToday?.getOrNull(1) ?: 0 >= c.reviewsPerDay,
          )
        if (inferred != c.newPerDay) Log.i(TAG, "Effective new/day $inferred (preset ${c.newPerDay})")
        c.copy(newPerDay = inferred)
      }

    val learningRows =
      searchRows(decks.scope("is:learn -is:suspended -is:buried"), config) ?: return today
    val learning =
      learningRows
        .filter { it.state?.queue == 1 }
        .filter { collected.add(it.id) }
        .map { poolCard(it, dueDay = null, dueAtSecs = it.due) }

    val upcoming = buildList {
      for (k in 1..horizonDays) {
        val rows = searchRows(decks.scope("prop:due=$k -is:suspended -is:buried"), config) ?: continue
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
    newPositions: List<CardPosition>,
    allowance: Int,
    collected: MutableSet<Long>,
    config: DeckScheduleConfig?,
  ): List<WearosCard> {
    if (allowance <= 0) return emptyList()
    val picked =
      newPositions
        .sortedBy { it.position }
        .map { it.cardId }
        .filter { it !in collected }
        .take(allowance)
    if (picked.isEmpty()) return emptyList()
    val rows = searchRows("cid:${picked.joinToString(",")}", config)?.associateBy { it.id }.orEmpty()
    return picked.mapNotNull { id ->
      rows[id]?.takeIf { collected.add(id) }?.let { poolCard(it, dueDay = null, dueAtSecs = null) }
    }
  }

  /** One cards search as pool rows; null when the cards endpoint is unavailable. */
  private fun searchRows(search: String, config: DeckScheduleConfig?): List<CardRow>? =
    provider.searchCards(search)?.map { rowOf(it, config) }

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
    Log.i(TAG, "Searching new cards: ${redact(search)}")
    val ids = provider.searchNoteIds(search, limit)
    Log.d(TAG, "Found ${ids.size} new-card notes (capped at $limit).")
    return ids
  }

  /**
   * Build a [WearosCard] for a single-card new note, or null when the note has any other number of
   * cards (skipped, since the Card projection can't say which card is the new one).
   */
  private fun fetchSingleNewCard(noteId: Long): WearosCard? {
    val ord = provider.cardOrds(noteId).singleOrNull() ?: return null
    val card = provider.card(noteId, ord, includeScheduling = false)
    return WearosCard(
      id = card?.id ?: 0L,
      noteId = noteId,
      ord = ord,
      front = card?.question ?: "No Front Text",
      back = card?.answer ?: "No Back Text",
      buttonCount = 4,
      // New cards aren't in the scheduler queue, so we don't have per-ease previews.
      nextReviewTimes = listOf("1m", "10m", "1d", "4d"),
    )
  }

  /**
   * Write answers back to AnkiDroid's schedule table. Returns a [WriteBackOutcome] whose `settled`
   * ids are safe to clear from the watch buffer (applied, or terminally unmatched) and whose
   * `unplaced` ids were examined but could not be placed this pass (kept buffered for retry). The
   * procedure itself (deck toggle, reps confirmation, retry semantics) lives in the provider library.
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
    val reviews =
      answers.map {
        ReviewAnswer(
          id = it.id,
          noteId = it.noteId,
          ord = it.ord,
          ease = it.ease,
          timeTakenMs = it.timeTakenMs,
          deckId = it.deckId,
        )
      }
    return provider.writeAnswers(reviews, PrefsAppliedAnswerStore(context))
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

/**
 * SharedPreferences-backed [AppliedAnswerStore]: ids of answers already applied to the collection, so
 * a re-send after a missed clear_answers can never double-apply (e.g. a relearn card re-presented in
 * the same session). Persisted (re-sends span separate syncs / process restarts) and capped to the
 * most recent [MAX_APPLIED_IDS]. One instance per write-back pass.
 */
private class PrefsAppliedAnswerStore(private val context: Context) : AppliedAnswerStore {
  private val ids: LinkedHashSet<String> by lazy {
    val raw = prefs().getString(KEY_APPLIED_IDS, "").orEmpty()
    LinkedHashSet(raw.split(",").filter { it.isNotEmpty() })
  }

  private fun prefs() = context.getSharedPreferences(DEDUPE_PREFS, Context.MODE_PRIVATE)

  override fun appliedIds(): Set<String> = ids

  override fun markApplied(id: String) {
    ids.add(id)
    val trimmed = if (ids.size > MAX_APPLIED_IDS) ids.toList().takeLast(MAX_APPLIED_IDS) else ids
    prefs()
      .edit()
      .putString(KEY_APPLIED_IDS, trimmed.joinToString(","))
      // commit() (not apply()): the id must be durably recorded before the next scheduler update.
      // An async apply() could lose the record if the service is killed between the update and the
      // flush, reopening the double-apply window this store exists to close.
      .commit()
  }

  private companion object {
    const val DEDUPE_PREFS = "writeback_applied"
    const val KEY_APPLIED_IDS = "applied_ids"
    const val MAX_APPLIED_IDS = 500
  }
}
