package com.ichi2.anki.wearos.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure progress-math checks for the foreground sync (no service instance needed). */
class SyncServiceTest {
  @Test
  fun syncProgressPercent_rampsFromSetupToNearlyDone() {
    // First deck of 15 sits just above the 15% setup floor; the last hits the 95% ceiling.
    assertEquals(20, SyncService.syncProgressPercent(pushed = 1, prePushCap = 15))
    assertEquals(95, SyncService.syncProgressPercent(pushed = 15, prePushCap = 15))
    // Monotonic in between.
    assertEquals(57, SyncService.syncProgressPercent(pushed = 8, prePushCap = 15))
  }

  @Test
  fun syncProgressPercent_neverExceeds95() {
    // Overshooting the cap (more pushed than expected) still clamps to 95, not 100+.
    assertEquals(95, SyncService.syncProgressPercent(pushed = 30, prePushCap = 15))
  }

  @Test
  fun syncProgressPercent_guardsZeroCap() {
    // A zero/negative cap must not divide by zero.
    assertEquals(95, SyncService.syncProgressPercent(pushed = 3, prePushCap = 0))
    assertEquals(95, SyncService.syncProgressPercent(pushed = 3, prePushCap = -5))
  }
}
