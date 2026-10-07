package com.ichi2.anki.wearos.wear

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import uniffi.wearos_core.deserializeDueBatch

/**
 * On-watch cache of downloaded decks. Each synced deck is stored as its own `deck_<id>.json` so the
 * user can switch between already-downloaded decks entirely offline, without a phone round-trip.
 */
object DeckCache {
  private const val PREFIX = "deck_"
  private const val SUFFIX = ".json"
  private const val TMP_SUFFIX = ".tmp"

  // The sync service (phone batches) and the activity (session progress) write the same files from
  // different threads; one lock keeps the compare-then-write atomic and the temp file unshared.
  private val writeLock = Any()
  private const val LEGACY = "due_batch.json"

  data class CachedDeck(val id: Long, val name: String, val cardCount: Int)

  fun deckFile(context: Context, deckId: Long): File =
    File(context.filesDir, "$PREFIX$deckId$SUFFIX")

  /**
   * Persist a deck batch atomically: write a temp file, fsync it, then rename over the final path so
   * a crash can't leave a right-length file of zeros behind a successful-looking write.
   */
  fun saveBatch(context: Context, deckId: Long, bytes: ByteArray): Boolean =
    synchronized(writeLock) { writeAtomically(context, deckId, bytes) }

  private fun writeAtomically(context: Context, deckId: Long, bytes: ByteArray): Boolean {
    val target = deckFile(context, deckId)
    val tmp = File(context.filesDir, "${target.name}$TMP_SUFFIX")
    return try {
      FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
      }
      if (tmp.renameTo(target)) {
        true
      } else {
        tmp.delete()
        false
      }
    } catch (_: IOException) {
      tmp.delete()
      false
    }
  }

  /**
   * Save a session's remaining cards, but only when [expectedCurrent] still matches what is on disk.
   * A mismatch means the phone delivered a fresh batch while the session was open, and that fresh
   * batch must win over the session's now-stale queue, so this is skipped.
   */
  fun saveSessionProgress(
    context: Context,
    deckId: Long,
    expectedCurrent: ByteArray,
    bytes: ByteArray,
  ): Boolean {
    val target = deckFile(context, deckId)
    return synchronized(writeLock) {
      target.exists() &&
        target.readBytes().contentEquals(expectedCurrent) &&
        writeAtomically(context, deckId, bytes)
    }
  }

  /**
   * All decks currently downloaded to the watch, migrating the old single-file cache on first run.
   */
  fun listCached(context: Context): List<CachedDeck> {
    migrateLegacy(context)
    val decks = mutableListOf<CachedDeck>()
    context.filesDir
      .listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
      ?.forEach { f ->
        try {
          val batch = deserializeDueBatch(f.readBytes())
          decks.add(CachedDeck(batch.deckId, batch.deckName, batch.cards.size))
        } catch (_: Exception) {
          // Skip corrupt cache files rather than crashing the picker.
        }
      }
    return decks.sortedBy { it.name.lowercase() }
  }

  fun totalDue(context: Context): Int = listCached(context).sumOf { it.cardCount }

  /** Card ids across every currently-cached deck, for pruning stale per-card artifacts (images). */
  fun allCachedCardIds(context: Context): Set<Long> {
    val ids = mutableSetOf<Long>()
    context.filesDir
      .listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
      ?.forEach { f ->
        try {
          deserializeDueBatch(f.readBytes()).cards.forEach { ids.add(it.id) }
        } catch (_: Exception) {
          // Skip corrupt cache files rather than crashing.
        }
      }
    return ids
  }

  /**
   * Drop cached due-batch files for decks that are no longer in the current list (i.e. deselected
   * on the phone). Without this, `listCached` keeps surfacing them as stray top-level decks even
   * though the deck picker's list no longer includes them.
   */
  fun pruneOrphans(context: Context, validDeckIds: Set<Long>) {
    context.filesDir
      .listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
      ?.forEach { f ->
        val id = f.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull()
        if (id != null && id !in validDeckIds) {
          f.delete()
        }
      }
  }

  private fun migrateLegacy(context: Context) {
    val legacy = File(context.filesDir, LEGACY)
    if (!legacy.exists()) return
    try {
      val batch = deserializeDueBatch(legacy.readBytes())
      val perDeck = deckFile(context, batch.deckId)
      if (!perDeck.exists()) legacy.copyTo(perDeck)
      legacy.delete()
    } catch (_: Exception) {
      // Leave a malformed legacy file in place; nothing reads it anymore.
    }
  }
}
