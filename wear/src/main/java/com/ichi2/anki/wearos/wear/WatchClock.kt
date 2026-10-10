package com.ichi2.anki.wearos.wear

import java.util.TimeZone
import uniffi.wearos_core.PoolDeckSummary
import uniffi.wearos_core.poolDeckSummaries

/** Injectable "now" so day-rollover and due-time logic can be driven from a test without real time. */
internal fun interface WatchClock {
  fun nowMs(): Long
}

/** The process default: the device's wall clock in epoch milliseconds. */
internal val systemWatchClock: WatchClock = WatchClock { System.currentTimeMillis() }

/** Local UTC offset in seconds at [nowMs], which the core needs to place the 4am Anki day rollover. */
internal fun utcOffsetSecs(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
  zone.getOffset(nowMs) / 1000

/** Epoch seconds at the device's local midnight today (for the Rust "reviews today" count). */
internal fun localMidnightSecs(): Long =
  java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()

/** The pool's per-deck summaries at the device's current time; the clock plumbing lives here. */
internal fun poolSummariesNow(): List<PoolDeckSummary> {
  val nowMs = systemWatchClock.nowMs()
  return poolDeckSummaries(nowMs, utcOffsetSecs(nowMs))
}
