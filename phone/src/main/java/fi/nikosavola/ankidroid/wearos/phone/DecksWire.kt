package fi.nikosavola.ankidroid.wearos.phone

import uniffi.wearos_core.DeckInfo
import uniffi.wearos_core.serializeDecks

/**
 * DataMap key for the protobuf-encoded `/decks` payload. The legacy "id:name" text key
 * (`decks_bytes`) is deliberately NOT also sent - dual-writing both would roughly double this
 * DataMap's size, and this codebase has already hit DATA_ITEM_TOO_LARGE once (see the batch Asset
 * comment in WearSyncService). A not-yet-updated watch simply keeps its last cached deck list until
 * it reads this key; only the watch's read side still tolerates the legacy key, for a phone that
 * hasn't been updated yet.
 */
internal const val KEY_DECKS_PROTO_BYTES = "decks_proto_bytes"

/** Encode the filtered deck list into the shared protobuf wire format for `/decks`. */
internal fun encodeDecksProto(filteredDecks: List<Pair<DeckId, String>>): ByteArray =
  serializeDecks(filteredDecks.map { (id, name) -> DeckInfo(id.value, name) })
