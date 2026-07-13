package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CardBitmapRenderer.needsRichRendering] is the gate that decides, per card side, whether we pay for
 * a phone-side WebView bitmap render (true fidelity: images, CSS, pitch-accent) or fall back to the
 * cheap native text path. Getting it wrong is user-visible both ways: a false negative shows a rich
 * card as broken/stripped text on the watch; a false positive renders every plain card as a slow
 * bitmap. It's a pure string heuristic, so lock its contract here.
 */
@RunWith(RobolectricTestRunner::class)
class CardBitmapRendererTest {
  // The constructor only stashes the Context (no WebView until render()), so a relaxed mock is fine.
  private val renderer = CardBitmapRenderer(mockk<Context>(relaxed = true))

  @Test
  fun images_needRichRendering() {
    assertTrue(renderer.needsRichRendering("""<img src="cat.png">"""))
    // Case-insensitive: AnkiDroid/templates may emit uppercase tags.
    assertTrue(renderer.needsRichRendering("""<IMG SRC="cat.png">"""))
    assertTrue(renderer.needsRichRendering("""text <img src="x.webp"> more"""))
  }

  @Test
  fun inlineStyleAndPitchAccentCss_needRichRendering() {
    assertTrue(renderer.needsRichRendering("""<div style="color:red">x</div>"""))
    assertTrue(renderer.needsRichRendering("""<span class="overline">は</span>"""))
    assertTrue(renderer.needsRichRendering("""<span style="border-top:1px solid">x</span>"""))
    assertTrue(renderer.needsRichRendering("""<span style="text-decoration:overline">x</span>"""))
    // Kaishi pitch-accent note type is matched by name so its styled pitch line renders faithfully.
    assertTrue(renderer.needsRichRendering("<div class=\"kaishi-pitch\">x</div>"))
  }

  @Test
  fun plainAndBasicallyFormattedText_useTheCheapTextPath() {
    assertFalse(renderer.needsRichRendering("just a plain answer"))
    assertFalse(renderer.needsRichRendering("<b>bold</b> and <i>italic</i>"))
    assertFalse(renderer.needsRichRendering("<div>answer</div>"))
    assertFalse(renderer.needsRichRendering(""))
  }

  @Test
  fun furiganaAlone_usesTheTextPath_sinceCleanHtmlHandlesRuby() {
    // <ruby> without any inline style renders fine natively (html_for_display -> base(reading)),
    // so it must NOT be forced through the expensive bitmap path.
    assertFalse(renderer.needsRichRendering("<ruby>答<rt>こた</rt></ruby>える"))
  }
}
