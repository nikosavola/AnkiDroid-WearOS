package com.ichi2.anki.wearos.phone

/** Deck names, searches and media filenames are the user's data; keep them out of release logs. */
internal fun redact(value: Any?): String = if (BuildConfig.DEBUG) value.toString() else "<redacted>"
