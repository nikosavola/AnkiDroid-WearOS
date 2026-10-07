package fi.nikosavola.ankidroid.wearos.wear

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.initDatabase
import uniffi.wearos_core.poolHasDeck
import uniffi.wearos_core.serializeDueBatch

/**
 * The migration runs against the real Rust pool (host-built lib), so it proves the json snapshots
 * actually land as pooled decks rather than just that a file was deleted.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyDeckImportTest {
  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = tempFolder.newFolder("files")
    initDatabase(File(dir, BUFFER_DB_NAME).absolutePath)
  }

  private fun batch(id: Long, name: String) =
    WearosDueBatch(
      deckId = id,
      deckName = name,
      cards =
        listOf(
          WearosCard(
            id = id * 10,
            noteId = id * 100,
            ord = 0,
            front = "Q",
            back = "A",
            buttonCount = 4,
            nextReviewTimes = listOf("1m"),
          )
        ),
    )

  @Test
  fun `per-deck snapshots are pooled then deleted`() {
    val file = File(dir, "deck_42.json")
    file.writeBytes(serializeDueBatch(batch(42, "Japani")))

    importLegacyDeckFiles(dir)

    assertTrue(poolHasDeck(42))
    assertFalse("imported snapshot should be deleted", file.exists())
  }

  @Test
  fun `the legacy single-file snapshot is imported too`() {
    val file = File(dir, "due_batch.json")
    file.writeBytes(serializeDueBatch(batch(7, "Old")))

    importLegacyDeckFiles(dir)

    assertTrue(poolHasDeck(7))
    assertFalse(file.exists())
  }

  @Test
  fun `an unreadable snapshot is skipped and left in place`() {
    val file = File(dir, "deck_99.json")
    file.writeBytes("not a batch".toByteArray())

    importLegacyDeckFiles(dir)

    assertFalse(poolHasDeck(99))
    assertTrue("unreadable file stays for a later retry", file.exists())
  }
}
