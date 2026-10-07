package com.ichi2.anki.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosAnswer

/**
 * Guards the decision [WearSyncService] makes on a received answer batch: which ids settle (clear
 * from the watch buffer) and which the phone reports unplaced (so the watch counts only real failed
 * placements against the orphan cap). The GMS send itself (node/message clients) is thin glue, but the
 * "when to settle" choice is safety-critical - clearing a grade that didn't actually apply silently
 * loses the user's review. [writeBackOutcome] isolates that choice so it's testable without the Data
 * Layer.
 */
@RunWith(RobolectricTestRunner::class)
class WearSyncServiceTest {
  private fun answer(id: String) =
    WearosAnswer(id = id, noteId = 111L, ord = 0, ease = 3, timeTakenMs = 1000L, deckId = 0L)

  @Test
  fun emptyBatch_settlesNothing_andNeverCallsWriteBack() {
    var called = false
    val outcome =
      writeBackOutcome(emptyList(), readOnly = false) { _, _ ->
        called = true
        WriteBackOutcome(settled = listOf("x"), unplaced = emptyList())
      }
    assertTrue(outcome.settled.isEmpty())
    assertTrue(outcome.unplaced.isEmpty())
    assertTrue("write-back must not run for an empty batch", !called)
  }

  @Test
  fun transientFailure_settlesNothing_soNoClearIsSent() {
    // Write-back reporting nothing settled == nothing reached a terminal state (transient write
    // failure). The service must then send NO clear_answers, or the watch would drop grades it never
    // synced.
    val outcome =
      writeBackOutcome(listOf(answer("a1"), answer("a2")), readOnly = false) { _, _ ->
        WriteBackOutcome.NONE
      }
    assertTrue("nothing settled -> nothing to clear", outcome.settled.isEmpty())
  }

  @Test
  fun transientFailure_reportsNothingUnplaced() {
    // A transient failure says nothing about whether the leftovers were placeable, so nothing is
    // reported unplaced and the watch keeps everything buffered.
    val outcome =
      writeBackOutcome(listOf(answer("a1")), readOnly = false) { _, _ -> WriteBackOutcome.NONE }
    assertTrue(outcome.settled.isEmpty())
    assertTrue("a transient failure must not count an attempt", outcome.unplaced.isEmpty())
  }

  @Test
  fun partialSettle_clearsOnlyTheSettledIds() {
    // a1 applied, a2 kept for retry -> only a1 is cleared; a2 stays buffered for the next sync.
    val outcome =
      writeBackOutcome(listOf(answer("a1"), answer("a2")), readOnly = false) { answers, _ ->
        WriteBackOutcome(
          settled = answers.map { it.id }.filter { it == "a1" },
          unplaced = emptyList(),
        )
      }
    assertEquals(listOf("a1"), outcome.settled)
  }

  @Test
  fun unplacedIds_areReportedSeparatelyFromSettledIds() {
    // The phone placed a1 but couldn't place a2 this pass: a1 clears, and a2 is reported unplaced so
    // the watch counts it against the orphan cap.
    val outcome =
      writeBackOutcome(listOf(answer("a1"), answer("a2")), readOnly = false) { _, _ ->
        WriteBackOutcome(settled = listOf("a1"), unplaced = listOf("a2"))
      }
    assertEquals(listOf("a1"), outcome.settled)
    assertEquals(listOf("a2"), outcome.unplaced)
  }

  @Test
  fun readOnlyFlag_isForwardedToWriteBack() {
    var seenReadOnly: Boolean? = null
    writeBackOutcome(listOf(answer("a1")), readOnly = true) { answers, ro ->
      seenReadOnly = ro
      WriteBackOutcome(settled = answers.map { it.id }, unplaced = emptyList())
    }
    assertEquals(true, seenReadOnly)
  }

  @Test
  fun allSettled_clearsEveryId() {
    val batch = listOf(answer("a1"), answer("a2"), answer("a3"))
    val outcome =
      writeBackOutcome(batch, readOnly = true) { answers, _ ->
        WriteBackOutcome(settled = answers.map { it.id }, unplaced = emptyList())
      }
    assertEquals(listOf("a1", "a2", "a3"), outcome.settled)
  }
}
