package fi.nikosavola.ankidroid.wearos.phone

import android.content.ContentResolver
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CardBitmapRenderer.needsRichRendering] is the gate that decides, per card side, whether we pay for
 * a phone-side WebView bitmap render (true fidelity: images, borders, overlines) or fall back to the
 * cheap native text path. Getting it wrong is user-visible both ways: a false negative shows a rich
 * card as broken/stripped text on the watch; a false positive renders every plain card as a slow
 * bitmap. It's a pure string heuristic over [prepareForRender]'s output, so lock both contracts here.
 */
@RunWith(RobolectricTestRunner::class)
class CardBitmapRendererTest {
  // The constructor only stashes the Context (no WebView until render()), so a relaxed mock is fine.
  private val renderer = CardBitmapRenderer(mockk<Context>(relaxed = true))

  @Test
  fun images_needRichRendering_onlyWhenTheProviderCanServeThem() {
    assertTrue(renderer.needsRichRendering("""<img src="cat.png">""", mediaReadable = true))
    assertTrue(renderer.needsRichRendering("""text <img src="x.webp"> more""", mediaReadable = true))
    // AnkiDroid's provider serves no media on most installs, so an <img> would render as a broken
    // image; the native text path the watch already handles is the better answer.
    assertFalse(renderer.needsRichRendering("""<img src="cat.png">""", mediaReadable = false))
    // Case-insensitive: AnkiDroid/templates may emit uppercase tags.
    assertFalse(renderer.needsRichRendering("""<IMG SRC="cat.png">""", mediaReadable = false))
  }

  @Test
  fun providerWithoutMediaSupport_isDetectedFromARealImageFilename() {
    val resolver = mockk<ContentResolver>()
    val context = mockk<Context>(relaxed = true)
    every { context.contentResolver } returns resolver
    val unsupported =
      FileNotFoundException("No files supported by provider at content://x/media/cat.png")
    every { resolver.openInputStream(any()) } throws unsupported

    val probingRenderer = CardBitmapRenderer(context)

    assertFalse(probingRenderer.needsRichRendering("""<img src="cat.png">"""))
  }

  @Test
  fun overlinesAndBorders_needRichRendering_inlineOrFromTheStylesheet() {
    assertTrue(renderer.needsRichRendering("""<span style="text-decoration:overline">x</span>"""))
    assertTrue(renderer.needsRichRendering("""<span style="border-top:1px solid">x</span>"""))
    assertTrue(renderer.needsRichRendering("""<table style="border: 1px solid">x</table>"""))
    assertTrue(renderer.needsRichRendering("""<svg viewBox="0 0 1 1"></svg>"""))
    // A <style> block inside the card HTML itself, e.g. from a field.
    val styled = "<style>.pitch { text-decoration-line: overline; }</style><span class=pitch>x</span>"
    assertTrue(renderer.needsRichRendering(styled))
  }

  @Test
  fun classAndNoteTypeNames_neverDecideOnTheirOwn() {
    assertFalse(renderer.needsRichRendering("""<div class="kaishi-pitch">x</div>"""))
    assertFalse(renderer.needsRichRendering("""<span class="overline">x</span>"""))
  }

  @Test
  fun commonStylesheetResets_useTheTextPath() {
    val resets =
      "<style>a { text-decoration: none; } input { border: none; } td { border:0; }" +
        " .card { border-radius: 4px; border-collapse: collapse; }</style>answer"
    assertFalse(renderer.needsRichRendering(resets))
  }

  @Test
  fun overline_staysRich_withoutMediaSupport() {
    // Styling isn't an image, so dropping images must not drop the card with it.
    val overline = """<span style="text-decoration:overline">x</span>"""
    assertTrue(renderer.needsRichRendering(overline, mediaReadable = false))
  }

  @Test
  fun plainAndBasicallyFormattedText_useTheCheapTextPath() {
    assertFalse(renderer.needsRichRendering("just a plain answer"))
    assertFalse(renderer.needsRichRendering("<b>bold</b> and <i>italic</i>"))
    assertFalse(renderer.needsRichRendering("<div>answer</div>"))
    // A bare inline style is cosmetic; native text rendering is fine.
    assertFalse(renderer.needsRichRendering("""<div style="color:red">x</div>"""))
    assertFalse(renderer.needsRichRendering(""))
  }

  @Test
  fun furiganaAlone_usesTheTextPath_sinceCleanHtmlHandlesRuby() {
    // <ruby> without any inline style renders fine natively (html_for_display -> base(reading)),
    // so it must NOT be forced through the expensive bitmap path.
    assertFalse(renderer.needsRichRendering("<ruby>答<rt>こた</rt></ruby>える"))
  }

  @Test
  fun prepareForRender_stripsSoundReferences() {
    // The provider hands [sound:...] over verbatim; the native text path strips them, so the bitmap
    // path has to as well or the watch shows the raw hex filename.
    val sounds = "[sound:deadbeef.mp3][sound:other.mp3]"
    assertEquals("食べる", prepareForRender("[sound:abc123.mp3]食べる", mediaReadable = true))
    assertEquals("a  b", prepareForRender("a [sound:x.mp3] b", mediaReadable = true))
    assertEquals("", prepareForRender(sounds, mediaReadable = false))
  }

  @Test
  fun prepareForRender_removesImageTagsOnlyWhenMediaIsUnreadable() {
    val html = """<img src="cat.png">word"""
    assertEquals("word", prepareForRender(html, mediaReadable = false))
    assertEquals(html, prepareForRender(html, mediaReadable = true))
    // Uppercase and self-closing tags are removed the same way.
    assertEquals("x", prepareForRender("""<IMG SRC="a.png"/>x""", mediaReadable = false))
  }

  @Test
  fun mathJax_needsRichRendering_onlyWithAnkisDelimiters() {
    assertTrue(renderer.needsRichRendering("""Euler: \(e^{i\pi}+1=0\)"""))
    assertTrue(renderer.needsRichRendering("""\[\int_0^1 x\,dx\]"""))
    // Anki doesn't treat dollars as math, and stray backslashes or parentheses aren't math either.
    assertFalse(renderer.needsRichRendering("""costs ${'$'}5 (or ${'$'}x${'$'})"""))
    assertFalse(renderer.needsRichRendering("""C:\Users\anki (path)"""))
  }

  @Test
  fun cardPageHtml_loadsMathJaxOnlyWhenTheSideUsesIt() {
    val math = cardPageHtml("""\(x^2\)""", ord = 0)
    assertTrue(math.contains("""<script src="mathjax/tex-svg-full.js"></script>"""))
    assertTrue(math.contains("mathjax-needs-to-render"))
    assertTrue(math.contains("typesetPromise"))

    val plain = cardPageHtml("<b>plain</b>", ord = 0)
    assertFalse(plain.contains("mathjax"))
    assertFalse(plain.contains("MathJax"))
  }

  @Test
  fun cardPageHtml_usesAnkiDroidsNightModeBodyClasses() {
    assertTrue(cardPageHtml("x", ord = 0).contains("""<body class="card card1 nightMode night_mode">"""))
    assertTrue(cardPageHtml("x", ord = 2).contains("""<body class="card card3 nightMode night_mode">"""))
  }

  @Test
  fun cardPageHtml_injectsTheNoteTypeCssAfterTheBaseStyles() {
    val css = ".card { color: black; } .nightMode .cloze { color: lightblue; }"
    val page = cardPageHtml("x", ord = 0, css = css)
    // Same order as AnkiDroid's card_template.html: base (flashcard.css) styles, then the note's.
    assertTrue(page.indexOf("body.night_mode") < page.indexOf(css))
    assertTrue(page.contains("<style>$css</style>"))
    assertFalse(cardPageHtml("x", ord = 0).contains("lightblue"))
    // A stray closing tag can't end the block early.
    assertFalse(cardPageHtml("x", ord = 0, css = "a{}</style><b>").contains("</style><b>"))
  }
}
