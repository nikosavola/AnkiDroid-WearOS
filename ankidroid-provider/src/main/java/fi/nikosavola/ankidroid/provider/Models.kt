package fi.nikosavola.ankidroid.provider

/**
 * A deck as AnkiDroid lists it. [optionsJson] is the deck's raw schema11 config, null when the deck
 * has none; [hasOptionsColumn] is false when the provider didn't expose the column at all, so the
 * options are unknown rather than absent.
 */
data class Deck(
  val id: Long,
  val name: String,
  val dueCounts: DueCounts? = null,
  val optionsJson: String? = null,
  val hasOptionsColumn: Boolean = false,
)

/** A deck's due counts for today, with AnkiDroid's daily limits applied. */
data class DueCounts(val learn: Int, val review: Int, val new: Int) {
  fun toList(): List<Int> = listOf(learn, review, new)

  companion object {
    /** A `deck_count` value (`[learn, review, new]`), or null if it isn't three non-negatives. */
    fun parse(raw: String?): DueCounts? {
      val nums = Regex("-?\\d+").findAll(raw ?: return null).map { it.value.toInt() }.toList()
      if (nums.size != 3 || nums.any { it < 0 }) return null
      return DueCounts(nums[0], nums[1], nums[2])
    }
  }
}

/**
 * One row of AnkiDroid's scheduler queue. [buttonCount] and [nextReviewTimes] (the raw
 * comma-separated interval labels) are null when the provider omits them.
 */
data class ScheduledCard(
  val noteId: Long,
  val ord: Int,
  val buttonCount: Int?,
  val nextReviewTimes: String?,
)

/**
 * A card's raw provider columns. Every nullable field is null when the provider doesn't expose
 * that column (or the value is SQL NULL), except [queue], which is null only when the column is
 * missing. [sm2Factor] is the ease in permille (2500 = 250%); [left] is Anki's packed counter.
 */
data class ProviderCard(
  val id: Long?,
  val noteId: Long,
  val ord: Int,
  val question: String?,
  val answer: String?,
  val queue: Int?,
  val type: Int?,
  val due: Long?,
  val interval: Double?,
  val left: Long?,
  val sm2Factor: Double?,
  val fsrsStability: Double?,
  val fsrsDifficulty: Double?,
  val fsrsDesiredRetention: Double? = null,
  val fsrsDecay: Double? = null,
  val reps: Long?,
  val lastReviewSecs: Long?,
)

/** A note's field values in note-type order, and the note type they belong to. */
data class NoteFields(val noteId: Long, val modelId: Long, val values: List<String>)

/** A note type's field names in order and its stylesheet (null when the provider has none). */
data class NoteType(val id: Long, val fieldNames: List<String>, val css: String?)

/** A card id and its raw `due`, which for a new card is its queue position. */
data class CardPosition(val cardId: Long, val position: Long)

/** A grade to write back. [deckId] 0 means unknown: the deck AnkiDroid has selected is used. */
data class ReviewAnswer(
  val id: String,
  val noteId: Long,
  val ord: Int,
  val ease: Int,
  val timeTakenMs: Long,
  val deckId: Long,
)

/**
 * Result of a write-back pass. [settled] ids are safe to drop from the caller's buffer (applied,
 * or terminally unmatched); [unplaced] ids were examined but could not be placed this pass.
 */
data class WriteBackOutcome(val settled: List<String>, val unplaced: List<String>) {
  companion object {
    val NONE = WriteBackOutcome(emptyList(), emptyList())
  }
}

/**
 * Ids of answers already applied, so a re-sent answer is never applied twice. The write-back reads
 * [appliedIds] once per pass, before writing anything.
 */
interface AppliedAnswerStore {
  fun appliedIds(): Set<String>

  /** Record [id] durably before returning: the next answer is written right after. */
  fun markApplied(id: String)
}
