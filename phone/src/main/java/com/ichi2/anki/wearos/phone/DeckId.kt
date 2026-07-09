package com.ichi2.anki.wearos.phone

/**
 * Zero-cost typed wrapper for an AnkiDroid deck id. Keeps deck ids from being mixed up with note ids
 * or card ordinals (all otherwise bare Long/Int) as they flow through the deck-selection, sync, and
 * ContentProvider code. Unwrap with [value] at FFI / ContentProvider / SharedPreferences boundaries.
 */
@JvmInline value class DeckId(val value: Long)
