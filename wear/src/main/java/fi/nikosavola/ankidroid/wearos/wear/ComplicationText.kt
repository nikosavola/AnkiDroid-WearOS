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
