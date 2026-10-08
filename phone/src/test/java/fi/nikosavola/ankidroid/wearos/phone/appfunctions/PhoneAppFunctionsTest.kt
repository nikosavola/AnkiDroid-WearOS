package fi.nikosavola.ankidroid.wearos.phone.appfunctions

import android.content.Context
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.ankidroid.wearos.phone.DeckId
import fi.nikosavola.ankidroid.wearos.phone.DeckSync
import fi.nikosavola.ankidroid.wearos.phone.FakeCardsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's AppFunctions against a fake AnkiDroid provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneAppFunctionsTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val service by lazy {
    Robolectric.buildService(PhoneAppFunctionService::class.java).create().get()
  }

  private fun withAnkiDroid(counts: String? = "[1,5,2]") =
    Robolectric.setupContentProvider(FakeCardsProvider::class.java, "com.ichi2.anki.flashcards")
      .apply { dueCounts = counts }

  @Test
  fun `decks are listed with today's counts and their watch setting`() = runBlocking {
    withAnkiDroid()

    val deck = service.listDecks().single()

    assertEquals(42L, deck.deckId)
    assertEquals("Japani", deck.name)
    assertEquals(listOf(1, 5, 2), listOf(deck.learningDue, deck.reviewsDue, deck.newDue))
    assertEquals(true, deck.syncedToWatch)
  }

  @Test
  fun `unknown counts read as -1`() = runBlocking {
    withAnkiDroid(counts = null)

    assertEquals(-1, service.listDecks().single().reviewsDue)
  }

  @Test
  fun `a deck can be taken off the watch`() = runBlocking {
    withAnkiDroid()

    val deck = service.setDeckSyncedToWatch(42L, synced = false)

    assertFalse(deck.syncedToWatch)
    assertFalse(DeckSync.isDeckSynced(context, DeckId(42L)))
  }

  @Test(expected = AppFunctionElementNotFoundException::class)
  fun `an unknown deck is reported as not found`() {
    withAnkiDroid()
    runBlocking { service.setDeckSyncedToWatch(7L, synced = false) }
  }

  @Test(expected = AppFunctionAppUnknownException::class)
  fun `a deck listing AnkiDroid can't give is an error, not a stand-in deck`() {
    withAnkiDroid().exposeDecks = false
    runBlocking { service.listDecks() }
  }

  @Test(expected = AppFunctionAppUnknownException::class)
  fun `without AnkiDroid the functions say so`() {
    runBlocking { service.listDecks() }
  }
}
