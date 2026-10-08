package fi.nikosavola.ankidroid.wearos.wear

/**
 * Short text + content description for the due-card-count complication, as a pure function so the
 * count->text mapping is testable without the complications-data-source framework.
 */
internal fun dueCountComplicationText(count: Int): Pair<String, String> =
  if (count > 0) {
    count.toString() to if (count == 1) "1 card due" else "$count cards due"
  } else {
    "✓" to "No cards due"
  }

/** LONG_TEXT line: the due count plus the time to clear it, e.g. "12 due, ~6 min to finish". */
internal fun dueLongText(count: Int, eta: String): String =
  if (count > 0) "$count due, $eta" else "No cards due"

/**
 * Today's progress for the RANGED_VALUE complication as (value, max), min being 0: reviews done
 * today out of done + still due. Nothing done and nothing due reads as complete rather than empty,
 * and max never hits 0 (the builder needs min < max).
 */
internal fun todayProgressRange(reviewedToday: Int, due: Int): Pair<Float, Float> {
  val done = reviewedToday.coerceAtLeast(0)
  val total = done + due.coerceAtLeast(0)
  return if (total == 0) 1f to 1f else done.toFloat() to total.toFloat()
}
