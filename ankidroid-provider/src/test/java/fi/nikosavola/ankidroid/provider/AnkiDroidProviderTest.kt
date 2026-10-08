package fi.nikosavola.ankidroid.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnkiDroidProviderTest {
  private lateinit var fake: FakeAnkiProvider
  private lateinit var provider: AnkiDroidProvider

  @Before
  fun setUp() {
    fake = Robolectric.setupContentProvider(FakeAnkiProvider::class.java, AnkiDroidContract.AUTHORITY)
    provider = AnkiDroidProvider(ApplicationProvider.getApplicationContext<Context>())
  }

  @Test
  fun listDecksReadsCountsAndOptions() {
    val decks = provider.listDecks()

    assertEquals(
      listOf(
        Deck(1L, "Default", DueCounts(0, 2, 5), """{"new":{}}""", hasOptionsColumn = true),
        Deck(2L, "Japani", null, null, hasOptionsColumn = true),
      ),
      decks,
    )
  }

  @Test
  fun listDecksIsNullWithoutACursor() {
    fake.mode = FakeAnkiProvider.Mode.NO_CURSOR

    assertNull(provider.listDecks())
  }

  @Test
  fun listDecksIsNullWhenTheProviderThrows() {
    fake.mode = FakeAnkiProvider.Mode.THROW

    assertNull(provider.listDecks())
  }

  @Test
  fun searchesCarryTheDeckSelectorLast() {
    val decks = DeckSelector(listOf(2L))

    provider.countCards(decks.scope("introduced:1"))
    val positions = provider.searchNewCardPositions(decks, wanted = 1, after = -1)

    assertEquals(listOf(CardPosition(5L, 3L)), positions)
    assertEquals(
      listOf(
        "introduced:1 (did:2)",
        "is:new -is:suspended -is:buried prop:pos>-1 prop:pos<=63 (did:2)",
      ),
      fake.searches,
    )
  }

  @Test
  fun answersAreConfirmedByRepsAndRecorded() {
    val store =
      object : AppliedAnswerStore {
        val marked = mutableListOf<String>()

        override fun appliedIds(): Set<String> = setOf("old")

        override fun markApplied(id: String) {
          marked += id
        }
      }
    val answers =
      listOf(
        ReviewAnswer("old", noteId = 10L, ord = 0, ease = 3, timeTakenMs = 1L, deckId = 1L),
        ReviewAnswer("new", noteId = 10L, ord = 0, ease = 3, timeTakenMs = 1L, deckId = 1L),
      )

    val outcome = provider.writeAnswers(answers, store)

    assertEquals(listOf("old", "new"), outcome.settled)
    assertEquals(listOf("new"), store.marked)
    assertEquals(1, fake.answered)
    assertEquals("the user's deck is selected again", 1L, fake.selectedDeck)
  }
}

/** Decks, selected deck, cards search and answering, enough for the library-level contract. */
class FakeAnkiProvider : ContentProvider() {
  enum class Mode {
    NORMAL,
    NO_CURSOR,
    THROW,
  }

  var mode = Mode.NORMAL
  val searches = mutableListOf<String>()
  var selectedDeck = 1L
  var answered = 0

  override fun onCreate() = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? {
    if (mode == Mode.THROW) throw IllegalStateException("collection not open")
    if (mode == Mode.NO_CURSOR) return null
    val segments = uri.pathSegments
    return when (segments.firstOrNull()) {
      "decks" -> {
        MatrixCursor(arrayOf("deck_id", "deck_name", "options", "deck_count")).apply {
          addRow(arrayOf<Any?>(1L, "Default", """{"new":{}}""", "[0,2,5]"))
          addRow(arrayOf<Any?>(2L, "Japani", null, null))
        }
      }
      "selected_deck" -> {
        MatrixCursor(arrayOf("deck_id", "deck_name")).apply {
          addRow(arrayOf<Any>(selectedDeck, "Deck $selectedDeck"))
        }
      }
      "cards" -> {
        searches += selection.orEmpty()
        MatrixCursor(arrayOf("_id", "due")).apply {
          if (selection.orEmpty().startsWith("is:new")) addRow(arrayOf<Any>(5L, 3L))
        }
      }
      "notes" -> {
        MatrixCursor(arrayOf("reps")).apply { addRow(arrayOf<Any>(answered.toLong())) }
      }
      else -> {
        null
      }
    }
  }

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int =
    when (uri.pathSegments.firstOrNull()) {
      "selected_deck" -> {
        selectedDeck = values?.getAsLong("deck_id") ?: selectedDeck
        1
      }
      "schedule" -> {
        answered++
        1
      }
      else -> {
        0
      }
    }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
