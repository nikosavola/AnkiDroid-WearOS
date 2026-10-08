package fi.nikosavola.ankidroid.provider

import android.net.Uri

/** AnkiDroid's flashcard ContentProvider: authority, URIs and the column names this library reads. */
object AnkiDroidContract {
  const val PACKAGE_NAME = "com.ichi2.anki"
  const val AUTHORITY = "com.ichi2.anki.flashcards"
  const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

  val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")
  val DECKS_URI: Uri = Uri.parse("content://$AUTHORITY/decks")
  val SELECTED_DECK_URI: Uri = Uri.parse("content://$AUTHORITY/selected_deck")

  /** Notes search: the selection is an Anki browser query (`col.findNotes`). */
  val NOTES_URI: Uri = Uri.parse("content://$AUTHORITY/notes")

  /** Cards search: the selection is an Anki browser query (`col.findCards`). Newer AnkiDroid only. */
  val CARDS_URI: Uri = Uri.parse("content://$AUTHORITY/cards")

  fun noteCardsUri(noteId: Long): Uri = Uri.parse("content://$AUTHORITY/notes/$noteId/cards")

  fun cardUri(noteId: Long, ord: Int): Uri =
    Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord")

  object DeckColumns {
    const val ID = "deck_id"
    const val NAME = "deck_name"

    /** rslib's schema11 deck config JSON. */
    const val OPTIONS = "options"

    /** JSON `[learn, review, new]`, daily limits applied. */
    const val COUNTS = "deck_count"
  }

  object ScheduleColumns {
    const val NOTE_ID = "note_id"

    // Literally "ord", not "card_ord".
    const val ORD = "ord"
    const val BUTTON_COUNT = "button_count"
    const val NEXT_REVIEW_TIMES = "next_review_times"
  }

  object CardColumns {
    const val ID = "_id"
    const val NOTE_ID = "note_id"
    const val ORD = "ord"
    const val QUESTION = "question"
    const val ANSWER = "answer"
    const val QUEUE = "queue"
    const val TYPE = "type"
    const val DUE = "due"
    const val INTERVAL = "interval"
    const val LEFT = "left"
    const val FSRS_STABILITY = "fsrs_stability"
    const val FSRS_DIFFICULTY = "fsrs_difficulty"
    const val SM2_FACTOR = "sm2_factor"
    const val LAST_REVIEW_SECS = "last_review_time_secs"
    const val REPS = "reps"
  }

  /** Answering a card is an update on [SCHEDULE_URI] with these keys and a null selection. */
  object ReviewColumns {
    const val NOTE_ID = "note_id"
    const val ORD = "ord"
    const val EASE = "answer_ease"
    const val TIME_TAKEN = "time_taken"
  }
}
