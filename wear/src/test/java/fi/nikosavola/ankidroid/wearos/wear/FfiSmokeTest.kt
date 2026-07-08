package fi.nikosavola.ankidroid.wearos.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.wearos_core.cleanHtml

/**
 * Tracer bullet: proves the Rust core loads via JNA in the host test JVM (jna.library.path points
 * at core/target/debug). If this is green, the whole FFI surface is unit-testable as plain JUnit.
 */
class FfiSmokeTest {
  @Test
  fun cleanHtml_stripsTags() {
    assertEquals("Hello World!", cleanHtml("<b>Hello</b> World!"))
  }
}
