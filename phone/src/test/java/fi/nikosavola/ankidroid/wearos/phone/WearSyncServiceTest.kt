package fi.nikosavola.ankidroid.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosAnswer

/**
 * Guards the decision [WearSyncService] makes on a received answer batch: which ids to clear from the
 * watch buffer. The GMS send itself (node/message clients) is thin glue, but the "when to clear"
 * choice is safety-critical - clearing a grade that didn't actually apply silently loses the user's
 * review. [settledIdsToClear] isolates that choice so it's testable without the Data Layer.
 */
@RunWith(RobolectricTestRunner::class)
class WearSyncServiceTest {
  private fun answer(id: String) =
    WearosAnswer(id = id, noteId = 111L, ord = 0, ease = 3, timeTakenMs = 1000L, deckId = 0L)

  @Test
  fun emptyBatch_clearsNothing_andNeverCallsWriteBack() {
    var called = false
    val clear = settledIdsToClear(emptyList(), readOnly = false) { _, _ -> called = true; listOf("x") }
    assertTrue(clear.isEmpty())
    assertTrue("write-back must not run for an empty batch", !called)
  }

  @Test
  fun transientFailure_settlesNothing_soNoClearIsSent() {
    // writeAnswersBack returning empty == nothing reached a terminal state (transient write failure).
    // The service must then send NO clear_answers, or the watch would drop grades it never synced.
    val clear = settledIdsToClear(listOf(answer("a1"), answer("a2")), readOnly = false) { _, _ ->
      emptyList()
    }
    assertTrue("nothing settled -> nothing to clear", clear.isEmpty())
  }

  @Test
  fun partialSettle_clearsOnlyTheSettledIds() {
    // a1 applied, a2 kept for retry -> only a1 is cleared; a2 stays buffered for the next sync.
    val clear = settledIdsToClear(listOf(answer("a1"), answer("a2")), readOnly = false) { answers, _ ->
      answers.map { it.id }.filter { it == "a1" }
    }
    assertEquals(listOf("a1"), clear)
  }

  @Test
  fun readOnlyFlag_isForwardedToWriteBack() {
    var seenReadOnly: Boolean? = null
    settledIdsToClear(listOf(answer("a1")), readOnly = true) { answers, ro ->
      seenReadOnly = ro
      answers.map { it.id }
    }
    assertEquals(true, seenReadOnly)
  }

  @Test
  fun allSettled_clearsEveryId() {
    val batch = listOf(answer("a1"), answer("a2"), answer("a3"))
    val clear = settledIdsToClear(batch, readOnly = true) { answers, _ -> answers.map { it.id } }
    assertEquals(listOf("a1", "a2", "a3"), clear)
  }
}
