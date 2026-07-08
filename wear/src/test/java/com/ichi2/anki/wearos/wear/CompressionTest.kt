package com.ichi2.anki.wearos.wear

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CompressionTest {
  @Test
  fun gunzip_inversesGzip() {
    val original = "the quick brown fox".repeat(50).toByteArray()
    val gzipped =
      ByteArrayOutputStream()
        .also { out -> GZIPOutputStream(out).use { it.write(original) } }
        .toByteArray()
    assertArrayEquals(original, gunzip(gzipped))
  }
}
