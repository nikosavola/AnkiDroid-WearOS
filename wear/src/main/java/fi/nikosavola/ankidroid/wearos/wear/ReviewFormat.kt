package fi.nikosavola.ankidroid.wearos.wear

// Pure, side-effect-free formatting used by the review UI. Kept out of MainActivity so it can be
// unit-tested directly (no Activity/Compose instance) — the Activity just calls into these.

/** Compact "5s"/"12m"/"3h"/"2d" age for the oldest un-synced review. */
fun humanAge(secs: Long): String =
  when {
    secs < 60 -> "${secs}s"
    secs < 3600 -> "${secs / 60}m"
    secs < 86_400 -> "${secs / 3600}h"
    else -> "${secs / 86_400}d"
  }

/** Human review duration: "45s" under a minute, else "3m 20s". */
fun formatDuration(ms: Long): String {
  val totalSecs = ms / 1000
  return if (totalSecs < 60) "${totalSecs}s" else "${totalSecs / 60}m ${totalSecs % 60}s"
}
