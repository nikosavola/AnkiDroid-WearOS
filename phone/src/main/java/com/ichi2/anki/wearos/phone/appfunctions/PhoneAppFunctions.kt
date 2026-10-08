package com.ichi2.anki.wearos.phone.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.android.gms.wearable.Wearable
import com.ichi2.anki.wearos.phone.AnkiDroidBridge
import com.ichi2.anki.wearos.phone.AutoSyncWorker
import com.ichi2.anki.wearos.phone.DeckId
import com.ichi2.anki.wearos.phone.DeckSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** An AnkiDroid flashcard deck as the watch companion sees it. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PhoneDeck(
  /** AnkiDroid deck ID. Pass it to functions that act on one deck. */
  val deckId: Long,
  /** Full deck name; "::" separates a parent deck from its subdecks. */
  val name: String,
  /** Learning cards due today, as AnkiDroid counts them; -1 if AnkiDroid didn't say. */
  val learningDue: Int,
  /** Review cards due today, after the deck's daily limit; -1 if AnkiDroid didn't say. */
  val reviewsDue: Int,
  /** New cards available today, after the deck's daily limit; -1 if AnkiDroid didn't say. */
  val newDue: Int,
  /** Whether the deck is copied to the watch on each sync. */
  val syncedToWatch: Boolean,
)

/**
 * AppFunctions of the phone companion. They read deck names and counts and change only this app's
 * own sync settings: nothing here reads card content or writes to AnkiDroid's collection.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
  serviceName = "PhoneAppFunctionService",
  appFunctionXmlFileName = "phone_app_functions",
)
abstract class BasePhoneAppFunctionService : AppFunctionService() {
  /**
   * List the user's AnkiDroid flashcard decks with today's due counts and whether each one is
   * synced to the watch.
   *
   * @return Every AnkiDroid deck, in AnkiDroid's deck list order.
   * @throws AppFunctionAppUnknownException If AnkiDroid isn't installed, set up, or hasn't granted
   *   access; ask the user to open AnkiDroid and then the AnkiDroid WearOS phone app once.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun listDecks(): List<PhoneDeck> =
    withContext(Dispatchers.IO) {
      val bridge = reachableAnkiDroid()
      decksOf(bridge).map { (id, name) -> phoneDeck(bridge, id, name) }
    }

  /**
   * Choose whether a deck is copied to the watch on each sync.
   *
   * The change applies from the next sync; call "syncWatchNow" to apply it right away.
   *
   * Required workflow: Call "listDecks" first to get the deck ID.
   *
   * @param deckId AnkiDroid deck ID from "listDecks".
   * @param synced True to copy the deck to the watch, false to stop copying it.
   * @return The deck with its updated setting.
   * @throws AppFunctionElementNotFoundException If no AnkiDroid deck has that ID; call "listDecks"
   *   again for current IDs.
   * @throws AppFunctionAppUnknownException If AnkiDroid isn't installed, set up, or hasn't granted
   *   access; ask the user to open AnkiDroid and then the AnkiDroid WearOS phone app once.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun setDeckSyncedToWatch(deckId: Long, synced: Boolean): PhoneDeck =
    withContext(Dispatchers.IO) {
      val bridge = reachableAnkiDroid()
      val id = DeckId(deckId)
      val name =
        decksOf(bridge).firstOrNull { it.first == id }?.second
          ?: throw AppFunctionElementNotFoundException("No AnkiDroid deck has ID $deckId.")
      DeckSync.setDeckSynced(this@BasePhoneAppFunctionService, id, synced)
      phoneDeck(bridge, id, name)
    }

  /**
   * Copy the synced decks' due cards from AnkiDroid to the watch now, instead of waiting for the
   * next automatic sync.
   *
   * The copy runs in the background and takes up to a minute.
   *
   * @throws AppFunctionAppUnknownException If AnkiDroid isn't reachable or no watch is connected;
   *   ask the user to open AnkiDroid or bring the watch near the phone, then try again.
   */
  @AppFunctionDeclaration(isDescribedByKDoc = true)
  suspend fun syncWatchNow() {
    withContext(Dispatchers.IO) {
      reachableAnkiDroid()
      val watches = Wearable.getNodeClient(this@BasePhoneAppFunctionService).connectedNodes.await()
      if (watches.isEmpty()) throw AppFunctionAppUnknownException("No watch is connected.")
    }
    val request =
      OneTimeWorkRequestBuilder<AutoSyncWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    // KEEP: repeated requests while one sync is running don't queue more.
    WorkManager.getInstance(this)
      .enqueueUniqueWork(SYNC_NOW_WORK, ExistingWorkPolicy.KEEP, request)
  }

  private fun reachableAnkiDroid(): AnkiDroidBridge {
    val bridge = AnkiDroidBridge(this)
    if (!bridge.isAnkiDroidAvailable()) {
      throw AppFunctionAppUnknownException("AnkiDroid is not installed or has not granted access.")
    }
    return bridge
  }

  private fun decksOf(bridge: AnkiDroidBridge): List<Pair<DeckId, String>> =
    bridge.fetchDeckList()
      ?: throw AppFunctionAppUnknownException(
        "AnkiDroid couldn't list its decks; it may need to be opened once to finish setting up."
      )

  private fun phoneDeck(bridge: AnkiDroidBridge, id: DeckId, name: String): PhoneDeck {
    val counts = bridge.dueCounts(id)
    return PhoneDeck(
      deckId = id.value,
      name = name,
      learningDue = counts?.getOrNull(0) ?: -1,
      reviewsDue = counts?.getOrNull(1) ?: -1,
      newDue = counts?.getOrNull(2) ?: -1,
      syncedToWatch = DeckSync.isDeckSynced(this, id),
    )
  }

  private companion object {
    const val SYNC_NOW_WORK = "app_function_sync_now"
  }
}
