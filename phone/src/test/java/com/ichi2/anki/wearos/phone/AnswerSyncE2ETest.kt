package com.ichi2.anki.wearos.phone

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosAnswer
import uniffi.wearos_core.deserializeAnswers
import uniffi.wearos_core.serializeAnswers

/**
 * End-to-end coverage of the answer-sync path that writes reviews back into AnkiDroid - the one place
 * we touch the user's real collection, so a regression here is exactly the "syncing problem we might
 * accidentally cause". A real two-device Data Layer round-trip can't run in JUnit, so this exercises
 * everything either side of the Bluetooth hop: the watch's Rust serialization
 * ([serializeAnswers]) -> the phone's Rust deserialization ([deserializeAnswers]) -> the real
 * [AnkiDroidBridge.writeAnswersBack] gating, with the ContentProvider faked via MockK.
 *
 * The invariants pinned here are the safety-critical ones: the wire format is loss-free, read-only
 * mode never writes, and only the card the scheduler is actually presenting is ever answered.
 */
@RunWith(RobolectricTestRunner::class)
class AnswerSyncE2ETest {
  private fun answer(id: String, noteId: Long, ord: Int, ease: Int) =
    WearosAnswer(id = id, noteId = noteId, ord = ord, ease = ease, timeTakenMs = 4200L)

  /** A schedule-query cursor presenting [noteId]/[ord] as the scheduler's current card. */
  private fun scheduleCursor(noteId: Long, ord: Int) =
    mockk<Cursor>(relaxed = true) {
      every { moveToFirst() } returns true
      every { getColumnIndex("note_id") } returns 0
      every { getColumnIndex("ord") } returns 1
      every { getLong(0) } returns noteId
      every { getInt(1) } returns ord
    }

  private fun noCardCursor() =
    mockk<Cursor>(relaxed = true) { every { moveToFirst() } returns false }

  private fun availableResolver(block: ContentResolver.() -> Unit): ContentResolver {
    val providerClient = mockk<ContentProviderClient>(relaxed = true)
    return mockk<ContentResolver>(relaxed = true) {
      every { acquireContentProviderClient(any<Uri>()) } returns providerClient
      block()
    }
  }

  private fun bridge(resolver: ContentResolver): AnkiDroidBridge {
    val context = mockk<Context>(relaxed = true) { every { contentResolver } returns resolver }
    return AnkiDroidBridge(context)
  }

  /** Serialize on the watch side, deserialize on the phone side - the actual Bluetooth payload. */
  private fun overTheWire(answers: List<WearosAnswer>): List<WearosAnswer> =
    deserializeAnswers(serializeAnswers(answers))

  @Test
  fun wireRoundTrip_preservesEveryFieldAndOrder() {
    val buffered =
      listOf(
        answer("a1", noteId = 111L, ord = 0, ease = 1),
        answer("a2", noteId = 222L, ord = 3, ease = 4),
        answer("a3", noteId = 333L, ord = 1, ease = 3),
      )

    val received = overTheWire(buffered)

    // A lossy wire (dropped/reordered/mangled ids or eases) is how a grade lands on the wrong card
    // or with the wrong ease in the real collection - assert exact fidelity.
    assertEquals(buffered, received)
  }

  @Test
  fun fullSync_writesOnlyMatchingCard_withCorrectContentValues() {
    val values = slot<ContentValues>()
    val resolver =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(222L, 3)
        every { update(any(), capture(values), any(), any()) } returns 1
      }

    val received = overTheWire(listOf(answer("a2", noteId = 222L, ord = 3, ease = 4)))
    val settled = bridge(resolver).writeAnswersBack(received, readOnly = false)

    assertEquals(listOf("a2"), settled)
    val v = values.captured
    assertEquals(222L, v.getAsLong("note_id"))
    assertEquals(3, v.getAsInteger("ord"))
    assertEquals(4, v.getAsInteger("answer_ease"))
  }

  @Test
  fun fullSync_readOnly_neverWritesToProvider_evenAfterRealWireRoundTrip() {
    // The load-bearing safety net: with read-only on, a full wire round-trip must still result in
    // zero writes to AnkiDroid, so exercising the sync handshake can never mutate the real
    // collection. Everything is still reported settled so the watch buffer clears.
    val resolver = availableResolver {}
    val received =
      overTheWire(
        listOf(
          answer("a1", noteId = 111L, ord = 0, ease = 1),
          answer("a2", noteId = 222L, ord = 3, ease = 4),
        )
      )

    val settled = bridge(resolver).writeAnswersBack(received, readOnly = true)

    verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    assertEquals(listOf("a1", "a2"), settled)
  }

  @Test
  fun fullSync_onlyThePresentedCardIsAnswered_theRestStayBuffered() {
    // Three grades are buffered but the scheduler is presenting note 222 first, then has nothing more
    // this pass. Only 222 may be written; 111 and 333 must stay buffered (not settled) rather than be
    // force-applied to whatever card is up - that force-apply is exactly a mis-grade of the real
    // collection.
    val update = slot<ContentValues>()
    val resolver =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returnsMany
          listOf(scheduleCursor(222L, 3), noCardCursor())
        every { update(any(), capture(update), any(), any()) } returns 1
      }

    val received =
      overTheWire(
        listOf(
          answer("a1", noteId = 111L, ord = 0, ease = 1),
          answer("a2", noteId = 222L, ord = 3, ease = 4),
          answer("a3", noteId = 333L, ord = 1, ease = 3),
        )
      )
    val settled = bridge(resolver).writeAnswersBack(received, readOnly = false)

    assertEquals(listOf("a2"), settled)
    assertEquals(222L, update.captured.getAsLong("note_id"))
    verify(exactly = 1) { resolver.update(any(), any(), any(), any()) }
    assertTrue("unpresented grades must stay buffered", !settled.contains("a1"))
    assertTrue("unpresented grades must stay buffered", !settled.contains("a3"))
  }

  @Test
  fun resend_afterSchedulerMovedOn_doesNotDoubleApply() {
    // The failure we most want to prevent: a clear_answers is missed, the watch re-sends the same
    // grade, and it gets applied twice to the real collection. First sync answers card 111; by the
    // re-send the scheduler has moved to 222 (nothing buffered for it), so the re-sent 111 grade
    // matches no presented card and is NOT applied again. The current-card gating - not any id
    // bookkeeping - is what makes the handshake safe to retry.
    val a1 = answer("a1", noteId = 111L, ord = 0, ease = 3)

    val firstResolver =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 0)
        every { update(any(), any(), any(), any()) } returns 1
      }
    val firstSettled = bridge(firstResolver).writeAnswersBack(overTheWire(listOf(a1)), false)
    assertEquals(listOf("a1"), firstSettled)
    verify(exactly = 1) { firstResolver.update(any(), any(), any(), any()) }

    // Re-send of the identical buffered answer; scheduler is now presenting a different card.
    val resendResolver =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(222L, 0)
        every { update(any(), any(), any(), any()) } returns 1
      }
    val resendSettled = bridge(resendResolver).writeAnswersBack(overTheWire(listOf(a1)), false)

    verify(exactly = 0) { resendResolver.update(any(), any(), any(), any()) }
    assertTrue("a re-sent grade for a card no longer presented must not re-apply", resendSettled.isEmpty())
  }

  @Test
  fun resend_afterApply_isDedupedById_evenWhenTheCardIsRepresented() {
    // The case the current-card gating alone can't cover: card 111 was graded (applied), then it's a
    // relearn re-presented THIS session so the scheduler shows 111 again, and a missed clear_answers
    // makes the watch re-send the same grade (same answer id). Without id dedupe the re-send would
    // match the presented card and apply a SECOND time (a corrupt double-grade). It must not.
    // Real SharedPreferences (the dedupe store persists across the two syncs) + a mocked resolver.
    val realCtx = ApplicationProvider.getApplicationContext<Context>()
    realCtx.getSharedPreferences("writeback_applied", Context.MODE_PRIVATE).edit().clear().commit()

    val resolver1 =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 0)
        every { update(any(), any(), any(), any()) } returns 1
      }
    val ctx1 = spyk(realCtx) { every { contentResolver } returns resolver1 }
    val first = AnkiDroidBridge(ctx1).writeAnswersBack(overTheWire(listOf(answer("a1", 111L, 0, 3))), false)
    assertEquals(listOf("a1"), first)
    verify(exactly = 1) { resolver1.update(any(), any(), any(), any()) }

    // Same card still presented; re-send of the identical answer id.
    val resolver2 =
      availableResolver {
        every { query(any(), any(), any(), any(), any()) } returns scheduleCursor(111L, 0)
        every { update(any(), any(), any(), any()) } returns 1
      }
    val ctx2 = spyk(realCtx) { every { contentResolver } returns resolver2 }
    val second = AnkiDroidBridge(ctx2).writeAnswersBack(overTheWire(listOf(answer("a1", 111L, 0, 3))), false)

    verify(exactly = 0) { resolver2.update(any(), any(), any(), any()) }
    assertEquals("re-sent already-applied grade is settled, not re-applied", listOf("a1"), second)
  }

  @Test
  fun emptyBatch_wireRoundTripsAndWritesNothing() {
    val resolver = availableResolver {}
    val settled = bridge(resolver).writeAnswersBack(overTheWire(emptyList()), readOnly = false)
    verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    assertTrue(settled.isEmpty())
  }
}
