package fi.nikosavola.ankidroid.wearos.phone

import kotlin.math.pow
import kotlin.math.roundToLong

// Comparing AnkiDroid's shipped interval labels against our computed seconds.
private const val SECS_PER_DAY = 86_400.0

// rslib timespan.rs: a label month is a twelfth of a year.
private const val SECS_PER_MONTH = 365.0 / 12.0 * SECS_PER_DAY

/** rslib `fuzz_delta`: whole days Anki may move an interval either way, built up per band. */
internal fun fuzzDeltaDays(days: Double): Double =
  if (days < 2.5) {
    0.0
  } else {
    1.0 +
      0.15 * (days.coerceAtMost(7.0) - 2.5).coerceAtLeast(0.0) +
      0.10 * (days.coerceAtMost(20.0) - 7.0).coerceAtLeast(0.0) +
      0.05 * (days - 20.0).coerceAtLeast(0.0)
  }

/**
 * The range AnkiDroid's label may show for our interval: rslib's `fuzz_bounds` (the load balancer
 * picks anywhere inside it), widened by the label's own rounding ("5.5m" shown as "6m"). rslib
 * fuzzes the unrounded interval while ours is already whole days, so the bounds are taken from
 * either end of the half day that rounds to it.
 */
internal fun allowedRange(oursSecs: Double, shipped: String): ClosedFloatingPointRange<Double> {
  val days = oursSecs / SECS_PER_DAY
  val slack = labelSlackSeconds(shipped)
  val low = days - 0.5
  val high = days + 0.49
  if (fuzzDeltaDays(high) == 0.0) return (oursSecs - slack)..(oursSecs + slack)
  val lower = (low - fuzzDeltaDays(low)).roundToLong().coerceAtMost(days.roundToLong())
  val upper = (high + fuzzDeltaDays(high)).roundToLong()
  return (lower * SECS_PER_DAY - slack)..(upper * SECS_PER_DAY + slack)
}

/** Half the last shown digit of a label, in seconds: "6m" -> 30, "2.2mo" -> ~1.5 days. */
internal fun labelSlackSeconds(label: String): Double {
  val unit = label.takeLastWhile { it.isLetter() }
  val number = label.dropLast(unit.length)
  val decimals = number.substringAfter('.', "").length
  val unitSecs =
    when (unit) {
      "s" -> 1.0
      "m" -> 60.0
      "h" -> 3600.0
      "mo" -> SECS_PER_MONTH
      "y" -> 365 * SECS_PER_DAY
      else -> SECS_PER_DAY
    }
  return unitSecs / 10.0.pow(decimals) / 2.0
}

internal fun parseLabelToSeconds(label: String): Double? {
  val unit = label.takeLastWhile { it.isLetter() }
  val value = label.dropLast(unit.length).toDoubleOrNull() ?: return null
  return when (unit) {
    "s" -> value
    "m" -> value * 60
    "h" -> value * 3600
    "d" -> value * 86_400
    "mo" -> value * SECS_PER_MONTH
    "y" -> value * 365 * 86_400
    else -> null
  }
}
