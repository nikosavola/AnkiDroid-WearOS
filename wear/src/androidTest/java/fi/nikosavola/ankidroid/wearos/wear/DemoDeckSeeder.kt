package fi.nikosavola.ankidroid.wearos.wear

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.wearos_core.DeckInfo
import uniffi.wearos_core.WearosCard
import uniffi.wearos_core.WearosDueBatch
import uniffi.wearos_core.poolForgetDecks
import uniffi.wearos_core.poolStoreBatch
import uniffi.wearos_core.serializeDecks
import uniffi.wearos_core.serializeDueBatch
import uniffi.wearos_core.deserializeDueBatch

/**
 * Fills a watch emulator with demo decks, since emulators can't pair with a phone without the
 * Play Store Wear OS app. Not a test: it is skipped unless `-e seed true` is passed, so running
 * the instrumented suite against a real watch never overwrites its decks.
 *
 * ```
 * adb shell am instrument -w -e seed true -e class fi.nikosavola.ankidroid.wearos.wear.DemoDeckSeeder \
 *   fi.nikosavola.ankidroid.wearos.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `-e scale N` multiplies the card counts (default 1), `-e clear true` removes every deck instead.
 * Grades already buffered survive a reseed, so `adb shell pm clear fi.nikosavola.ankidroid.wearos` first
 * for a fresh day.
 */
@RunWith(AndroidJUnit4::class)
class DemoDeckSeeder {
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun seed() {
    assumeTrue("pass -e seed true to seed this device", args.getString("seed") == "true")
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val decks = if (args.getString("clear") == "true") emptyList() else demoDecks()
    val scale = args.getString("scale", "1").toInt()

    writeDecksCacheProto(ctx.filesDir, serializeDecks(decks.map { DeckInfo(it.id, it.name) }))
    poolForgetDecks(decks.map { it.id })
    for (deck in decks) {
      if (deck.cards.isEmpty()) continue
      val cards = (0 until scale).flatMap { round -> deck.cards.mapIndexed { i, c -> c.card(deck.id, round, i) } }
      // Through the same bytes the phone sends, so the batch goes through the real decoder.
      val batch =
        WearosDueBatch(
          deckId = deck.id,
          deckName = deck.name,
          cards = cards,
          fetchedAtMs = System.currentTimeMillis(),
        )
      poolStoreBatch(deserializeDueBatch(serializeDueBatch(batch)))
    }
    ctx.sendBroadcast(Intent(WearSyncService.ACTION_DECKS_UPDATED).setPackage(ctx.packageName))
    runBlocking { WearSurfaceUpdater.refresh(ctx) }
  }

  private class DemoCard(val front: String, val back: String) {
    fun card(deckId: Long, round: Int, index: Int): WearosCard {
      val id = deckId * 10_000 + round * 1_000 + index
      return WearosCard(
        id = id,
        noteId = id,
        ord = 0,
        front = front,
        back = back,
        buttonCount = 4,
        nextReviewTimes = listOf("1m", "6m", "1d", "4d"),
      )
    }
  }

  private class DemoDeck(val id: Long, val name: String, val cards: List<DemoCard>)

  private fun demoDecks() =
    listOf(
      DemoDeck(
        1_700_000_000_001,
        "Japanese::Kanji",
        listOf(
          DemoCard("日", "day, sun<br>ニチ, ジツ / ひ, か"),
          DemoCard("水", "water<br>スイ / みず"),
          DemoCard("木", "tree, wood<br>ボク, モク / き"),
          DemoCard("<ruby>山<rt>やま</rt></ruby>", "mountain"),
          DemoCard("火", "fire<!-- pitch accent hidden here --> [sound:hi.mp3]<br>カ / ひ"),
        ),
      ),
      DemoDeck(
        1_700_000_000_002,
        "Japanese::Core vocabulary with a deliberately long deck name",
        listOf(
          DemoCard("<ruby>勉強<rt>べんきょう</rt></ruby>", "study"),
          DemoCard("<ruby>図書館<rt>としょかん</rt></ruby>", "library"),
          DemoCard(
            "<ruby>駅<rt>えき</rt></ruby>",
            "station<br><br>" +
              "駅まで歩いて十分ぐらいです。<br>It's about ten minutes on foot to the station.<br><br>" +
              "次の駅で降ります。<br>I get off at the next station.<br><br>" +
              "駅前に新しいカフェができました。<br>A new cafe opened in front of the station.",
          ),
        ),
      ),
      DemoDeck(
        1_700_000_000_003,
        "Spanish",
        listOf(
          DemoCard("el perro", "the dog"),
          DemoCard("la ventana", "the window"),
          DemoCard("despertarse", "to wake up"),
          DemoCard("sin embargo", "however"),
        ),
      ),
      // As the phone sends stock Cloze and type-in cards, captured from AnkiDroid.
      DemoDeck(
        1_700_000_000_005,
        "Note types",
        listOf(
          DemoCard(
            "<span class=\"cloze\" data-ordinal=\"1\" style=\"color:#add8e6\"><b>[...]</b></span>" +
              " is in <span class=\"cloze-inactive\" data-ordinal=\"2\">France</span>",
            "<span class=\"cloze\" data-ordinal=\"1\" style=\"color:#add8e6\"><b>Paris</b></span>" +
              " is in <span class=\"cloze-inactive\" data-ordinal=\"2\">France</span><br>",
          ),
          DemoCard(
            "Capital of France?\n\n",
            "Capital of France?\n\n<hr id=answer>\n\n<code id=typeans>Paris</code>",
          ),
        ),
      ),
      DemoDeck(1_700_000_000_004, "Geography::European capitals", emptyList()),
    )
}
