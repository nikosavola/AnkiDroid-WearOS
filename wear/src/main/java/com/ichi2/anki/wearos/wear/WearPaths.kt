package com.ichi2.anki.wearos.wear

/**
 * Message paths the watch sends to the phone. One definition so a typo can't silently break a
 * request; the phone's WearSyncService counterparts must stay in sync.
 */
object WearPaths {
  const val REQUEST_DECK = "/request_deck"
  const val REQUEST_DECKS = "/request_decks"
  const val REQUEST_NEW = "/request_new"
}
