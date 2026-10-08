package fi.nikosavola.ankidroid.provider

import android.content.ContentResolver
import android.content.ContentValues
import android.util.Log
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.CardColumns
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.DeckColumns
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.ReviewColumns
import fi.nikosavola.ankidroid.provider.AnkiDroidContract.ScheduleColumns
import fi.nikosavola.ankidroid.provider.AnkiDroidProvider.Companion.TAG

/**
 * The write-back procedure behind [AnkiDroidProvider.writeAnswers].
 *
 * An applied answer is confirmed by re-reading the card's reps, not by the update's row count: the
 * provider swallows a scheduler error from `col.sched.answerCard` and still reports a row, so a
 * count of 1 can mean nothing happened. A real answer bumps reps, a swallowed one does not, and that
 * answer stays unsettled.
 */
internal class AnswerWriter(
  private val provider: AnkiDroidProvider,
  private val resolver: ContentResolver,
  private val store: AppliedAnswerStore,
) {
  /** Outcome of answering one card: either it landed (or is terminal), or it stayed unplaced. */
  private enum class ApplyResult {
    APPLIED,
    TERMINAL,
    NOT_APPLIED,
  }

  private data class DrainResult(val applied: Int, val transientFailure: Boolean)

  fun write(answers: List<ReviewAnswer>): WriteBackOutcome {
    if (!provider.isAvailable()) {
      // Provider unreachable (not installed, disabled, mid-update, or the read/write permission
      // not yet granted - all normal first-run states). This is transient, NOT a terminal outcome:
      // settling here would make the caller drop the buffered reviews for good.
      Log.w(TAG, "AnkiDroid unavailable; keeping ${answers.size} answers buffered for retry.")
      return WriteBackOutcome.NONE
    }

    // IDs to clear from the buffer = terminal outcomes: applied, or the card no longer exists
    // (0 rows). A thrown exception is transient and keeps everything buffered for a later retry.
    val settledIds = mutableListOf<String>()
    var applied = 0
    var transientFailure = false

    // An answer id already applied must never be applied again, even if it is re-sent while its
    // card happens to be presented again (a relearn re-presented this session). Settle those up
    // front; the drain below only sees not-yet-applied answers.
    val alreadyApplied = store.appliedIds()
    val remaining = mutableListOf<ReviewAnswer>()
    for (a in answers) {
      if (a.id in alreadyApplied) {
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
    // order, learning chains included. Answers are addressed by (note_id, ord), so the deck no
    // longer matters for placement.
    //
    // A single-deck collection has nothing to toggle to, so it falls back to priming the scheduler
    // per deck and answering only the head card it presents (see primeAndDrain).
    val selectedDeckId = readSelectedDeckId()
    val toggleDeckId =
      selectedDeckId?.let { selected -> toggleCandidates().firstOrNull { it != selected } }
    val togglePlan: Pair<Long, Long>? =
      if (toggleDeckId != null && dropStudyQueues(toggleDeckId, selectedDeckId)) {
        toggleDeckId to selectedDeckId
      } else {
        null
      }

    if (togglePlan == null) {
      val fallback = primeAndDrain(remaining, settledIds)
      applied += fallback.applied
      transientFailure = fallback.transientFailure
      // A half-done toggle (the second select failed) must not leave the user on the toggle deck.
      if (toggleDeckId != null) selectDeck(selectedDeckId)
    } else {
      val result = drainToggled(remaining, settledIds, togglePlan)
      applied += result.applied
      transientFailure = result.transientFailure
    }

    Log.i(TAG, "Write-back: $applied applied, ${remaining.size} kept for retry/later.")
    // A transient exception says nothing about whether the leftovers are placeable, so report them
    // unplaced only when the drain finished normally.
    val unplaced = if (transientFailure) emptyList() else remaining.map { it.id }
    return WriteBackOutcome(settledIds, unplaced)
  }

  /**
   * Deck ids that could flip the selection. When the listing yields nothing, Anki's Default deck
   * (id 1, present in every collection) is tried.
   */
  private fun toggleCandidates(): List<Long> {
    if (!provider.isAvailable()) return emptyList()
    return provider.listDecks().orEmpty().map { it.id }.ifEmpty { listOf(DEFAULT_DECK_ID) }
  }

  /** Answer [remaining] in order with the study queues dropped, re-dropping them once if needed. */
  private fun drainToggled(
    remaining: MutableList<ReviewAnswer>,
    settledIds: MutableList<String>,
    togglePlan: Pair<Long, Long>,
  ): DrainResult {
    val (toggleDeck, originalDeck) = togglePlan
    var applied = 0
    var transientFailure = false
    // Cards whose chain is already broken: once one grade for a (note_id, ord) can't be placed,
    // every later grade for it is skipped (a learning chain must not apply out of order).
    val blockedCards = mutableSetOf<Pair<Long, Int>>()
    try {
      var i = 0
      while (i < remaining.size) {
        val answer = remaining[i]
        if (answer.noteId to answer.ord in blockedCards) {
          i++
          continue
        }
        var result = applyOneAnswer(answer)
        // Something rebuilt the queues mid-pass (e.g. AnkiDroid's own UI): drop them again and
        // retry this one answer exactly once.
        if (result == ApplyResult.NOT_APPLIED && dropStudyQueues(toggleDeck, originalDeck)) {
          result = applyOneAnswer(answer)
        }
        when (result) {
          ApplyResult.APPLIED -> {
            applied++
            settledIds.add(answer.id)
            // removeAt shifts the following answers down, so i stays for the next one.
            remaining.removeAt(i)
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
      selectDeck(originalDeck)
    }
    return DrainResult(applied, transientFailure)
  }

  /**
   * The card's review count, or null when the query fails, returns no row, or the provider has no
   * `reps` column (older AnkiDroid). Null falls back to the update's row count.
   */
  private fun readCardReps(noteId: Long, ord: Int): Long? =
    try {
      resolver
        .query(
          AnkiDroidContract.cardUri(noteId, ord),
          arrayOf(CardColumns.REPS),
          null,
          null,
          null,
        )
        ?.use { cursor ->
          val idx = cursor.getColumnIndex(CardColumns.REPS)
          if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
    } catch (e: Exception) {
      Log.w(TAG, "Card reps unavailable for note $noteId/$ord; falling back to the row count.", e)
      null
    }

  /** The selected deck id, or null when unreadable; null takes the single-deck fallback path. */
  private fun readSelectedDeckId(): Long? =
    try {
      resolver
        .query(AnkiDroidContract.SELECTED_DECK_URI, arrayOf(DeckColumns.ID), null, null, null)
        ?.use { cursor ->
          val idx = cursor.getColumnIndex(DeckColumns.ID)
          if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
    } catch (e: Exception) {
      Log.w(TAG, "Selected deck unavailable (${e.message}); using the head-gated fallback.")
      null
    }

  /** Select [deckId]; true when the provider reports the deck exists (rows > 0). */
  private fun selectDeck(deckId: Long): Boolean =
    try {
      val values = ContentValues().apply { put(DeckColumns.ID, deckId) }
      resolver.update(AnkiDroidContract.SELECTED_DECK_URI, values, null, null) > 0
    } catch (e: Exception) {
      Log.w(TAG, "Failed to select deck $deckId: ${e.message}")
      false
    }

  /**
   * Drop AnkiDroid's study queues by flipping the selected deck away and back. rslib only rejects an
   * answer that is not at the head of those queues, and only builds them on a schedule query, so
   * with none issued in between a run of answers applies in any order. False if either selection
   * didn't take (a missing toggle deck), leaving the caller on the fallback path.
   */
  private fun dropStudyQueues(toggleDeckId: Long, selectedDeckId: Long): Boolean =
    selectDeck(toggleDeckId) && selectDeck(selectedDeckId)

  /**
   * Answer one card via ReviewInfo (fields in ContentValues, null selection) and confirm it landed
   * by re-reading reps. Zero rows is terminal (already answered / removed); a row with no reps bump
   * stays unplaced. Provider errors from the update propagate as transient.
   */
  private fun applyOneAnswer(answer: ReviewAnswer): ApplyResult {
    // Clamp ease to the 1..4 the scheduler accepts; a corrupt/out-of-range value must never reach
    // col.sched.answerCard.
    val values =
      ContentValues().apply {
        put(ReviewColumns.NOTE_ID, answer.noteId)
        put(ReviewColumns.ORD, answer.ord)
        put(ReviewColumns.EASE, answer.ease.coerceIn(1, 4))
        put(ReviewColumns.TIME_TAKEN, answer.timeTakenMs)
      }
    val repsBefore = readCardReps(answer.noteId, answer.ord)
    val rowsUpdated = resolver.update(AnkiDroidContract.SCHEDULE_URI, values, null, null)
    val repsAfter = readCardReps(answer.noteId, answer.ord)
    if (rowsUpdated == 0) {
      Log.w(TAG, "Answer for note ${answer.noteId} wrote 0 rows; treating as terminal.")
      return ApplyResult.TERMINAL
    }
    // No reps column (older AnkiDroid): trust the row count.
    val appliedNow = if (repsBefore != null && repsAfter != null) repsAfter > repsBefore else true
    if (!appliedNow) {
      Log.w(
        TAG,
        "Provider reported $rowsUpdated row(s) but reps did not advance for note " +
          "${answer.noteId}/${answer.ord}; keeping the answer buffered.",
      )
      return ApplyResult.NOT_APPLIED
    }
    // Recorded per apply: if the process dies mid-drain, an applied-but-unrecorded id would be
    // re-applied on the next pass.
    store.markApplied(answer.id)
    return ApplyResult.APPLIED
  }

  /**
   * Single-deck fallback: prime the scheduler per deck and answer only the head card it presents.
   * Kept so a collection with one deck (nothing to toggle to) never does worse. deck_id 0 = unknown:
   * primed with a null selection = whatever deck is current. See [primeScheduler].
   */
  private fun primeAndDrain(
    remaining: MutableList<ReviewAnswer>,
    settledIds: MutableList<String>,
  ): DrainResult {
    var applied = 0
    var transientFailure = false
    val deckOrder = remaining.map { it.deckId }.distinct().sortedBy { if (it == 0L) 0 else 1 }
    deckLoop@ for (deckId in deckOrder) {
      while (remaining.isNotEmpty()) {
        val current = primeScheduler(deckId) ?: break
        val idx = remaining.indexOfFirst { it.noteId == current.first && it.ord == current.second }
        if (idx < 0) {
          // Scheduler presenting a card we have no answer for: applying any of ours now would grade
          // the wrong card. Move on to the next deck (its answers stay buffered for retry).
          Log.i(TAG, "Scheduler on note ${current.first}/${current.second} (not buffered); next deck.")
          break
        }
        val answer = remaining[idx]
        try {
          when (applyOneAnswer(answer)) {
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

  /**
   * Fallback path only (see [primeAndDrain]): prime [deckId]'s scheduler and return the card it now
   * presents as (note_id, ord), or null if none is due / the query failed. The deckID= selection
   * presents THIS deck's head so the following update grades it; a null-selection query would
   * revert to the collection's current deck. deckId 0 (unknown) uses a null selection.
   */
  private fun primeScheduler(deckId: Long): Pair<Long, Int>? {
    val selection = if (deckId != 0L) "limit=?,deckID=?" else null
    val selectionArgs = if (deckId != 0L) arrayOf("1", deckId.toString()) else null
    return try {
      resolver.query(AnkiDroidContract.SCHEDULE_URI, null, selection, selectionArgs, null)?.use {
        cursor ->
        if (!cursor.moveToFirst()) return null
        val noteIdIdx = cursor.getColumnIndex(ScheduleColumns.NOTE_ID)
        val ordIdx = cursor.getColumnIndex(ScheduleColumns.ORD)
        if (noteIdIdx < 0 || ordIdx < 0) null else cursor.getLong(noteIdIdx) to cursor.getInt(ordIdx)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Scheduler prime query failed; skipping write-back this pass: ${e.message}")
      null
    }
  }

  private companion object {
    const val DEFAULT_DECK_ID = 1L
  }
}
