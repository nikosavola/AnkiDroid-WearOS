package fi.nikosavola.ankidroid.wearos.wear

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** Reverse of the phone's [gzip]: inflate batch bytes received over the Data Layer. */
internal fun gunzip(bytes: ByteArray): ByteArray =
  GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
