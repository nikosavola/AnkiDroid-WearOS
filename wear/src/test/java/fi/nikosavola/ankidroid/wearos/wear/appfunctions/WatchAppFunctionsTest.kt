package fi.nikosavola.ankidroid.wearos.wear.appfunctions

import android.content.Intent
import androidx.appfunctions.AppFunctionElementNotFoundException
import fi.nikosavola.ankidroid.wearos.wear.BUFFER_DB_NAME
import fi.nikosavola.ankidroid.wearos.wear.MainActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.initDatabase
import uniffi.wearos_core.poolStoreBatch

/** The watch's AppFunctions against the real Rust pool (host-built lib). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WatchAppFunctionsTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private val service by lazy {
    Robolectric.buildService(WatchAppFunctionService::class.java).create().get()
  }

  @Before
  fun setUp() {
    initDatabase(File(tempFolder.newFolder("files"), BUFFER_DB_NAME).absolutePath)
    poolStoreBatch(batch(42, "Japani::Kaishi", cards = 3))
  }

  private fun batch(id: Long, name: String, cards: Int) =
    WearosDueBatch(
      deckId = id,
      deckName = name,
      cards =
        (1..cards).map {
          WearosCard(
            id = id * 100 + it,
            noteId = id * 1000 + it,
            ord = 0,
            front = "Q",
            back = "A",
            buttonCount = 4,
            nextReviewTimes = listOf("1m", "6m", "10m", "4d"),
          )
        },
      fetchedAtMs = System.currentTimeMillis(),
    )

  @Test
  fun `stored decks are listed with their counts`() = runBlocking {
    val deck = service.listCachedDecks().single { it.deckId == 42L }

    assertEquals("Japani::Kaishi", deck.name)
    assertEquals(3, deck.totalCards)
    assertEquals(3, deck.dueNow)
  }

  @Test
  fun `start review opens that deck, with one intent per deck`() = runBlocking {
    poolStoreBatch(batch(43, "Ranska", cards = 1))

    val first = shadowOf(service.startReview(42)).savedIntent
    val second = shadowOf(service.startReview(43)).savedIntent

    assertEquals(MainActivity::class.java.name, first.component?.className)
    assertEquals(42L, first.getLongExtra(MainActivity.EXTRA_OPEN_DECK_ID, -1))
    assertFalse("decks must not share one PendingIntent", first.filterEquals(second))
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, first.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  @Test(expected = AppFunctionElementNotFoundException::class)
  fun `a deck that isn't on the watch is reported as not found`() {
    runBlocking { service.startReview(999) }
  }
}
