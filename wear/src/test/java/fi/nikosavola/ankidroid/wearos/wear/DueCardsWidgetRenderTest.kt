package fi.nikosavola.ankidroid.wearos.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.RectangularAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.RoundAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.SquircleAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures the widget document for every preview container shape and state and plays it back with
 * the Remote Compose player, so a layout the player can't handle fails here rather than on a watch.
 * Set WIDGET_PREVIEW_DIR=<dir> to also save the PNGs that back the picker's previewImage drawables.
 */
@RunWith(RobolectricTestRunner::class)
// Tall window: every case is stacked in one column and captureToImage needs it on screen.
@Config(qualifiers = "w400dp-h3600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DueCardsWidgetRenderTest {
  @get:Rule val composeRule = createComposeRule()

  private class FixedWidget(private val snapshot: DueWidgetSnapshot) : GlanceWearWidget() {
    override suspend fun provideWidgetData(
      context: Context,
      params: WearWidgetParams,
    ): WearWidgetData = dueCardsDocument(params, snapshot) { "14:35" }
  }

  private val params =
    (SquircleAllWidgetPreviewParams().values +
        RoundAllWidgetPreviewParams().values +
        RectangularAllWidgetPreviewParams().values)
      .toList()

  private val due =
    DueWidgetSnapshot(
      hasDecks = true,
      dueNow = 23,
      learningLater = 4,
      nextLearningDueMs = 0L,
      topDecks =
        listOf(
          WidgetDeck(1, "Kanji", 12),
          WidgetDeck(2, "Spanish vocabulary with a long name", 8),
          WidgetDeck(3, "Biology", 3),
        ),
    )

  private val states =
    listOf(
      "due" to due,
      "caught_up" to DueWidgetSnapshot(true, 0, 2, 0L, emptyList()),
      "no_decks" to DueWidgetSnapshot.NoDecks,
    )

  @Test
  fun rendersEveryContainerAndState() {
    val cases = states.flatMap { (state, snapshot) -> params.map { Triple(state, snapshot, it) } }
    composeRule.setContent {
      Column {
        cases.forEachIndexed { i, (_, snapshot, p) ->
          WearWidgetPreview(FixedWidget(snapshot), p, Modifier.testTag("case$i"))
        }
      }
    }
    val outDir = System.getenv("WIDGET_PREVIEW_DIR")?.let(::File)
    cases.forEachIndexed { i, (state, _, p) ->
      val bitmap = composeRule.onNodeWithTag("case$i").captureToImage().asAndroidBitmap()
      assertTrue("case $i ($state) drew nothing", bitmap.hasNonBackgroundPixels())
      outDir?.let { dir ->
        dir.mkdirs()
        val name = "${state}_${p.containerType}_${p.widthDp.toInt()}x${p.heightDp.toInt()}_$i.png"
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      }
    }
  }

  private fun Bitmap.hasNonBackgroundPixels(): Boolean {
    val first = getPixel(0, 0)
    for (y in 0 until height step 2) {
      for (x in 0 until width step 2) {
        val px = getPixel(x, y)
        if (px != first && Color.alpha(px) != 0) return true
      }
    }
    return false
  }
}
