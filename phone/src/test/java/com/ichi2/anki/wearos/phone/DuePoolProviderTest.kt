package com.ichi2.anki.wearos.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.dayIndex
import uniffi.wearos_core.defaultRolloverHour

/**
 * Drives [AnkiDroidBridge.fetchDuePool] against a fake AnkiDroid provider that answers the cards
 * search endpoint. Robolectric routes real ContentResolver queries to it, so the search selections,
 * the per-card fetch, and the fall-back to today's batch all run for real.
 */
@RunWith(RobolectricTestRunner::class)
class DuePoolProviderTest {
  private lateinit var context: Context
  private lateinit var provider: FakeCardsProvider

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    provider =
      Robolectric.setupContentProvider(FakeCardsProvider::class.java, "com.ichi2.anki.flashcards")
  }

  private fun bridge() =
    AnkiDroidBridge(
      context,
      clock = { FIXED_NOW_MS },
      timeZone = TimeZone.getTimeZone("UTC"),
    )

  private fun todayIndex(): Long = dayIndex(FIXED_NOW_MS, 0, defaultRolloverHour())

  @Test
  fun `upcoming cards carry the day they are due`() {
    provider.dueCardsByOffset =
      mapOf(
        1L to listOf(FakeCardRow(31L, 1031L, 0, due = 0)),
        3L to listOf(FakeCardRow(33L, 1033L, 0, due = 0)),
      )

    val cards = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20).cards.associateBy { it.id }

    assertEquals(todayIndex() + 1, cards.getValue(31L).dueDay)
    assertEquals(todayIndex() + 3, cards.getValue(33L).dueDay)
    assertNull(cards.getValue(31L).dueAtSecs)
    assertTrue("pool cards carry no interval labels", cards.getValue(31L).nextReviewTimes.isEmpty())
    assertEquals(4, cards.getValue(31L).buttonCount)
  }

  @Test
  fun `elapsed days count Anki day rollovers, not wall-clock time`() {
    // A minute before the rollover 19 Anki days ago: barely 18 days of wall clock, 19 for Anki.
    val todayStartSecs = todayIndex() * 86_400L + defaultRolloverHour() * 3_600L
    val lastReview = todayStartSecs - 18 * 86_400L - 60L
    provider.dueCardsByOffset =
      mapOf(1L to listOf(FakeCardRow(31L, 1031L, 0, lastReviewSecs = lastReview)))

    val card =
      bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20).cards.single { it.id == 31L }

    assertEquals(19.0, card.state!!.elapsedDays, 0.0)
  }

  @Test
  fun `intraday learning cards carry their due time`() {
    provider.learningCards = listOf(FakeCardRow(21L, 1021L, 0, due = 1_700_000_600L, queue = 1))

    val card =
      bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20).cards.single { it.id == 21L }

    assertEquals(1_700_000_600L, card.dueAtSecs)
    assertNull(card.dueDay)
    assertEquals(1, card.state?.queue)
  }

  @Test
  fun `new cards are ordered by their scheduler position`() {
    provider.newCards =
      listOf(
        FakeCardRow(41L, 1041L, 0, due = 5),
        FakeCardRow(42L, 1042L, 0, due = 1),
      )

    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)
    val newIds = pool.cards.map { it.id }.filter { it == 41L || it == 42L }

    assertEquals(listOf(42L, 41L), newIds)
    assertEquals("today's card still leads the pool", TODAY_CARD, pool.cards.first().id)
  }

  @Test
  fun `only the new cards the pool ships are rendered`() {
    provider.newCards = (100L until 160L).map { FakeCardRow(it, it + 1000, 0, due = it) }

    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)
    val shipped = pool.cards.map { it.id }.filter { it >= 100L }

    assertEquals((100L until 100L + shipped.size).toList(), shipped)
    assertTrue("a few days' worth, not the whole deck", shipped.size in 1..20)
    assertEquals(1, provider.renderedSearches.size)
    assertEquals(shipped.size, provider.renderedSearches.single().split(",").size)
  }

  @Test
  fun `a deck with no new cards per day renders none`() {
    provider.newPerDay = 0
    provider.newCards = listOf(FakeCardRow(100L, 1100L, 0, due = 1))

    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)

    assertTrue(pool.cards.none { it.id == 100L })
    assertTrue(provider.renderedSearches.isEmpty())
  }

  @Test
  fun `a provider without the cards endpoint returns today's batch only`() {
    provider.exposeCardsEndpoint = false

    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)

    assertEquals(listOf(TODAY_CARD), pool.cards.map { it.id })
  }

  @Test
  fun `the whole of today's queue is fetched and marked complete when the deck counts cover it`() {
    provider.dueCounts = "[0,1,0]"

    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)

    assertEquals(20, provider.scheduleLimit)
    assertEquals(todayIndex(), pool.queueDay)
    assertEquals(todayIndex(), pool.cards.first { it.id == TODAY_CARD }.queuedDay)
  }

  @Test
  fun `a big day asks AnkiDroid for all of it, up to the cap`() {
    provider.dueCounts = "[2,150,30]"
    assertEquals(182, run {
      bridge().fetchDuePool(DeckId(DECK_ID), 20)
      provider.scheduleLimit
    })

    provider.dueCounts = "[0,900,30]"
    bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)
    assertEquals(MAX_TODAY_QUEUE, provider.scheduleLimit)
  }

  @Test
  fun `without deck counts today's cards are marked but the day is not claimed complete`() {
    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)

    assertEquals(20, provider.scheduleLimit)
    assertNull(pool.queueDay)
    assertEquals(todayIndex(), pool.cards.first { it.id == TODAY_CARD }.queuedDay)
  }

  @Test
  fun `a deck-level new limit is read off today's queue for later days`() {
    // The preset says none, yet AnkiDroid queued 3 of the 10 available new cards today.
    provider.newPerDay = 0
    provider.dueCounts = "[0,1,3]"
    provider.newCards = (100L until 110L).map { FakeCardRow(it, it + 1000, 0, due = it) }

    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)

    assertEquals(3, pool.scheduleConfig?.newPerDay)
    assertEquals(10, pool.cards.count { it.id >= 100L })
  }

  @Test
  fun `the pool records the rollover hour and fetch time`() {
    val pool = bridge().fetchDuePool(DeckId(DECK_ID), todayLimit = 20)

    assertEquals(defaultRolloverHour(), pool.rolloverHour)
    assertEquals(FIXED_NOW_MS, pool.fetchedAtMs)
  }

  private companion object {
    const val DECK_ID = 42L
    const val TODAY_NOTE = 1001L
    const val TODAY_CARD = 11L

    /** 2026-10-07T00:00:00Z; a fixed instant so the day numbers are deterministic. */
    const val FIXED_NOW_MS = 1_791_331_200_000L
  }
}

/** One row the fake cards endpoint serves. */
data class FakeCardRow(
  val id: Long,
  val noteId: Long,
  val ord: Int,
  val due: Long = 0L,
  val queue: Int = 2,
  val cardType: Int = 2,
  val lastReviewSecs: Long = 0L,
  val front: String = "front $id",
  val back: String = "back $id",
)

/**
 * Minimal stand-in for AnkiDroid's flashcard provider: a deck listing, today's scheduler row, the
 * per-card fetch, and the cards search endpoint the pool reads its upcoming/learning/new cards from.
 * [exposeCardsEndpoint] off models an AnkiDroid too old to have `/cards`, whose query is null.
 */
class FakeCardsProvider : ContentProvider() {
  var exposeCardsEndpoint = true
  /** Off models an AnkiDroid that can't list its decks yet (collection not set up). */
  var exposeDecks = true
  var learningCards: List<FakeCardRow> = emptyList()
  var dueCardsByOffset: Map<Long, List<FakeCardRow>> = emptyMap()
  var newCards: List<FakeCardRow> = emptyList()
  val renderedSearches = mutableListOf<String>()
  var newPerDay = 2
  /** The deck's `deck_count` value; null leaves the column out, like an older AnkiDroid. */
  var dueCounts: String? = null
  /** The `limit` the bridge asked the schedule for. */
  var scheduleLimit: Int? = null

  override fun onCreate() = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? =
    when (uri.pathSegments.firstOrNull()) {
      "decks" -> if (exposeDecks) deckCursor() else null
      "schedule" -> scheduleCursor(selectionArgs)
      "cards" -> cardsSearchCursor(selection)
      "notes" -> cardCursor(uri)
      else -> null
    }

  private fun deckCursor(): Cursor {
    val options = """{"new":{"delays":[1.0,10.0],"ints":[1,4],"perDay":$newPerDay},"rev":{},"lapse":{}}"""
    val counts = dueCounts
    return if (counts == null) {
      MatrixCursor(arrayOf("deck_id", "deck_name", "options")).apply {
        addRow(arrayOf<Any>(DECK_ID, "Japani", options))
      }
    } else {
      MatrixCursor(arrayOf("deck_id", "deck_name", "options", "deck_count")).apply {
        addRow(arrayOf<Any>(DECK_ID, "Japani", options, counts))
      }
    }
  }

  private fun scheduleCursor(selectionArgs: Array<out String>?): Cursor {
    scheduleLimit = selectionArgs?.firstOrNull()?.toIntOrNull()
    return MatrixCursor(arrayOf("note_id", "ord", "button_count", "next_review_times")).apply {
      addRow(arrayOf<Any>(TODAY_NOTE, 0, 3, "1m, 10m, 4d, 7d"))
    }
  }

  /** The per-card fetch: the card the today-schedule row points at. */
  private fun cardCursor(uri: Uri): Cursor {
    val noteId = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: TODAY_NOTE
    val ord = uri.pathSegments.getOrNull(3)?.toIntOrNull() ?: 0
    return MatrixCursor(CARD_COLUMNS).apply {
      addRow(
        arrayOf<Any>(
          TODAY_CARD,
          noteId,
          ord,
          "<b>Front</b>",
          "Back",
          2,
          2,
          10.0,
          0L,
          20.0,
          5.0,
          2500L,
          1_700_000_000L,
          0L,
        )
      )
    }
  }

  /** The `cid:` search the pool renders its chosen new cards with; recorded for the tests. */
  private fun renderedRows(search: String): List<FakeCardRow> {
    renderedSearches += search
    val ids = search.removePrefix("cid:").split(",").map { it.toLong() }.toSet()
    return (newCards + learningCards).filter { it.id in ids }
  }

  private fun dueOffset(search: String): Long? =
    search.substringAfter("prop:due=").takeWhile { it.isDigit() }.toLongOrNull()

  private fun cardsSearchCursor(selection: String?): Cursor? {
    if (!exposeCardsEndpoint) return null
    val search = selection.orEmpty()
    val rows =
      when {
        search.contains("is:learn") -> learningCards
        search.contains("is:new") -> newCards
        search.startsWith("cid:") -> renderedRows(search)
        search.contains("prop:due=") -> dueCardsByOffset[dueOffset(search)] ?: emptyList()
        else -> emptyList()
      }
    return MatrixCursor(CARD_COLUMNS).apply {
      rows.forEach {
        addRow(
          arrayOf<Any>(
            it.id,
            it.noteId,
            it.ord,
            it.front,
            it.back,
            it.queue,
            it.cardType,
            0.0,
            0L,
            0.0,
            0.0,
            2500L,
            it.lastReviewSecs,
            it.due,
          )
        )
      }
    }
  }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0

  private companion object {
    const val DECK_ID = 42L
    const val TODAY_NOTE = 1001L
    const val TODAY_CARD = 11L

    val CARD_COLUMNS =
      arrayOf(
        "_id",
        "note_id",
        "ord",
        "question",
        "answer",
        "queue",
        "type",
        "interval",
        "left",
        "fsrs_stability",
        "fsrs_difficulty",
        "sm2_factor",
        "last_review_time_secs",
        "due",
      )
  }
}
