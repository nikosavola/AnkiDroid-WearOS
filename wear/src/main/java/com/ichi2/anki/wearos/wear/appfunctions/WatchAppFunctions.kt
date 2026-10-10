package com.ichi2.anki.wearos.wear.appfunctions

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.ichi2.anki.wearos.wear.MainActivity
import com.ichi2.anki.wearos.wear.WearPaths
import com.ichi2.anki.wearos.wear.sendToConnectedPhones
import com.ichi2.anki.wearos.wear.systemWatchClock
import com.ichi2.anki.wearos.wear.utcOffsetSecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.wearos_core.poolDeckSummaries
import uniffi.wearos_core.poolHasDeck

/** A flashcard deck stored on the watch for offline review. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class WatchDeck(
  /** AnkiDroid deck ID. Pass it to functions that act on one deck. */
  val deckId: Long,
  /** Full deck name; "::" separates a parent deck from its subdecks. */
  val name: String,
  /** Cards ready to review on the watch right now. */
  val dueNow: Int,
  /** Learning cards that become due later today. */
  val learningLaterToday: Int,
  /** All cards stored for this deck, including ones due on later days. */
  val totalCards: Int,
)

/**
 * AppFunctions of the watch app. Only names and counts leave the app, never card content, and
 * nothing here grades cards or writes to AnkiDroid.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
  serviceName = "WatchAppFunctionService",
  appFunctionXmlFileName = "watch_app_functions",
)
abstract class BaseWatchAppFunctionService : AppFunctionService() {
  /**
   * List the flashcard decks stored on the watch, with how many cards are due now.
   *
   * The watch reviews these decks offline; the phone keeps them up to date.
   *
   * @return Stored decks in the watch's deck list order; empty if the phone has not synced any.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun listCachedDecks(): List<WatchDeck> =
    withContext(Dispatchers.IO) {
      val nowMs = systemWatchClock.nowMs()
      poolDeckSummaries(nowMs, utcOffsetSecs(nowMs)).map {
        WatchDeck(
          deckId = it.deckId,
          name = it.name,
          dueNow = it.dueNow,
          learningLaterToday = it.learningLater,
          totalCards = it.totalCards,
        )
      }
    }

  /**
   * Open a deck on the watch, ready to start reviewing its due cards.
   *
   * Required workflow: Call "listCachedDecks" first to get the deck ID.
   *
   * @param deckId AnkiDroid deck ID from "listCachedDecks".
   * @return An intent that opens the deck's overview screen on the watch.
   * @throws AppFunctionElementNotFoundException If the deck is not stored on the watch; the user
   *   needs to sync it from the AnkiDroid WearOS phone app first.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun startReview(deckId: Long): PendingIntent {
    requireStoredDeck(deckId)
    val intent =
      Intent(this, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN_DECK_ID, deckId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Extras don't tell PendingIntents apart; without this every deck would share one.
        .setIdentifier("deck:$deckId")
    return PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  /**
   * Fetch a deck's latest due cards from the paired phone, for after the user studied elsewhere.
   *
   * The cards arrive in the background within a few seconds; "listCachedDecks" then shows the new
   * counts.
   *
   * Required workflow: Call "listCachedDecks" first to get the deck ID.
   *
   * @param deckId AnkiDroid deck ID from "listCachedDecks".
   * @throws AppFunctionElementNotFoundException If the deck is not stored on the watch; the user
   *   needs to sync it from the AnkiDroid WearOS phone app first.
   * @throws AppFunctionAppUnknownException If the phone is not connected; ask the user to bring the
   *   phone near the watch and try again.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun refreshDeck(deckId: Long) {
    requireStoredDeck(deckId)
    val reached =
      withContext(Dispatchers.IO) {
        sendToConnectedPhones(this@BaseWatchAppFunctionService, WearPaths.REQUEST_DECK, "$deckId".toByteArray())
      }
    if (reached == 0) throw AppFunctionAppUnknownException("The phone is not connected.")
  }

  private suspend fun requireStoredDeck(deckId: Long) {
    if (!withContext(Dispatchers.IO) { poolHasDeck(deckId) }) {
      throw AppFunctionElementNotFoundException("Deck $deckId is not stored on the watch.")
    }
  }
}
