package com.ichi2.anki.wearos.phone

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip a byte array before putting it in a Wearable DataItem. Card HTML is verbose and a batch can
 * exceed the Data Layer's ~100KB per-item limit (DATA_ITEM_TOO_LARGE); gzip shrinks it
 * several-fold.
 */
internal fun gzip(bytes: ByteArray): ByteArray {
  val out = ByteArrayOutputStream()
  GZIPOutputStream(out).use { it.write(bytes) }
  return out.toByteArray()
}
