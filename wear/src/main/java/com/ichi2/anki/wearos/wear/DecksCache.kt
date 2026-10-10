package com.ichi2.anki.wearos.wear

import android.util.Log
import java.io.File
import uniffi.wearos_core.deserializeDecks

/**
 * Reads the watch's known-decks list: prefers the protobuf cache, falling back to the legacy
 * "id:name" text cache only when the protobuf file doesn't exist (e.g. the phone hasn't been
 * updated to send it yet). Pure/synchronous so it's testable without a coroutine or ViewModel.
 */
internal fun loadKnownDecks(filesDir: File): List<Pair<Long, String>> =
  try {
    val protoFile = File(filesDir, "decks_cache.pb")
    val legacyFile = File(filesDir, "decks_cache.txt")
    when {
      protoFile.exists() -> deserializeDecks(protoFile.readBytes()).map { it.id to it.fullName }
      legacyFile.exists() ->
        legacyFile
          .readText(Charsets.UTF_8)
          .split("\n")
          .filter { it.isNotEmpty() }
          // Skip only the malformed line, not the whole list: a single un-parseable id used to
          // throw and blank every known deck until the next successful phone refresh.
          .mapNotNull {
            val parts = it.split(":", limit = 2)
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            Pair(id, parts.getOrElse(1) { "Deck $id" })
          }
      else -> emptyList()
    }
  } catch (e: Exception) {
    Log.e("DecksCache", "Failed to load decks list: ${e.message}", e)
    emptyList()
  }

/**
 * Writes the protobuf deck cache via write-then-rename, and clears the legacy text cache so it
 * can't shadow it (mirrors [loadKnownDecks] preferring decks_cache.pb). Returns false if the
 * rename failed, in which case the previous cache is left untouched - the caller should treat the
 * whole update as failed rather than pruning/broadcasting against data that wasn't persisted.
 */
internal fun writeDecksCacheProto(filesDir: File, bytes: ByteArray): Boolean {
  val cacheFile = File(filesDir, "decks_cache.pb")
  val tmpFile = File(filesDir, "decks_cache.pb.tmp")
  tmpFile.writeBytes(bytes)
  if (!tmpFile.renameTo(cacheFile)) {
    tmpFile.delete()
    return false
  }
  File(filesDir, "decks_cache.txt").delete()
  return true
}

/**
 * Writes the legacy text deck cache, and clears the protobuf cache so a stale one doesn't keep
 * shadowing the currently-paired (older-built) phone's data - see [loadKnownDecks].
 */
internal fun writeDecksCacheLegacyText(filesDir: File, bytes: ByteArray) {
  File(filesDir, "decks_cache.txt").writeBytes(bytes)
  File(filesDir, "decks_cache.pb").delete()
}
