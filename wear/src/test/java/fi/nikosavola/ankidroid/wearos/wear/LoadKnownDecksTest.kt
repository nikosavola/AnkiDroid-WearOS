package fi.nikosavola.ankidroid.wearos.wear

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.DeckInfo
import uniffi.wearos_core.serializeDecks

/**
 * [loadKnownDecks] needs a real files dir, so it runs under Robolectric. Also exercises
 * [writeDecksCacheProto]/[writeDecksCacheLegacyText] together with it, since the proto<->legacy
 * transition is exactly where a stale cache could otherwise keep shadowing fresh data.
 */
@RunWith(RobolectricTestRunner::class)
class LoadKnownDecksTest {
  private val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir

  @Test
  fun protoCacheExists_isPreferredOverLegacyText() {
    File(filesDir, "decks_cache.pb")
      .writeBytes(serializeDecks(listOf(DeckInfo(1, "Proto Deck"))))
    File(filesDir, "decks_cache.txt").writeText("2:Legacy Deck")

    assertEquals(listOf(1L to "Proto Deck"), loadKnownDecks(filesDir))
  }

  @Test
  fun onlyLegacyTextExists_fallsBackToIt() {
    File(filesDir, "decks_cache.txt").writeText("1:Alpha\n2:Beta")

    assertEquals(listOf(1L to "Alpha", 2L to "Beta"), loadKnownDecks(filesDir))
  }

  @Test
  fun legacyText_skipsOnlyTheMalformedLine() {
    File(filesDir, "decks_cache.txt").writeText("1:Alpha\nnot-a-valid-line\n2:Beta")

    assertEquals(listOf(1L to "Alpha", 2L to "Beta"), loadKnownDecks(filesDir))
  }

  @Test
  fun neitherCacheExists_returnsEmpty() {
    assertTrue(loadKnownDecks(filesDir).isEmpty())
  }

  @Test
  fun corruptProtoCache_returnsEmptyRatherThanCrashing() {
    // Decode failure is not currently expected to fall back to legacy text (the write side writes
    // decks_cache.pb atomically, so a torn write can't leave it corrupt); it should degrade safely.
    File(filesDir, "decks_cache.pb").writeBytes(byteArrayOf(0x7, 0x2, 0x1))
    File(filesDir, "decks_cache.txt").writeText("1:Alpha")

    assertTrue(loadKnownDecks(filesDir).isEmpty())
  }

  @Test
  fun protoThenLegacySave_legacyWins_notShadowedByStaleProto() {
    // A phone falling back to the legacy format (e.g. reinstalled from an older build) must not
    // have its data hidden behind a now-stale protobuf cache from a previous, newer phone build.
    writeDecksCacheProto(filesDir, serializeDecks(listOf(DeckInfo(1, "Proto Deck"))))
    writeDecksCacheLegacyText(filesDir, "2:Legacy Deck".toByteArray(Charsets.UTF_8))

    assertEquals(listOf(2L to "Legacy Deck"), loadKnownDecks(filesDir))
    assertTrue(
      "stale proto cache must be cleared, not just shadowed",
      !File(filesDir, "decks_cache.pb").exists(),
    )
  }

  @Test
  fun legacyThenProtoSave_protoWins_legacyCacheCleared() {
    writeDecksCacheLegacyText(filesDir, "1:Legacy Deck".toByteArray(Charsets.UTF_8))
    writeDecksCacheProto(filesDir, serializeDecks(listOf(DeckInfo(2, "Proto Deck"))))

    assertEquals(listOf(2L to "Proto Deck"), loadKnownDecks(filesDir))
    assertTrue(
      "stale legacy cache must be cleared, not just shadowed",
      !File(filesDir, "decks_cache.txt").exists(),
    )
  }
}
