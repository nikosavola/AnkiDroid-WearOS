package com.ichi2.anki.wearos.wear

import java.util.TimeZone

/** Injectable "now" so day-rollover and due-time logic can be driven from a test without real time. */
internal fun interface WatchClock {
  fun nowMs(): Long
}

/** The process default: the device's wall clock in epoch milliseconds. */
internal val systemWatchClock: WatchClock = WatchClock { System.currentTimeMillis() }

/** Local UTC offset in seconds at [nowMs], which the core needs to place the 4am Anki day rollover. */
internal fun utcOffsetSecs(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
  zone.getOffset(nowMs) / 1000
