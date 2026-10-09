package fi.nikosavola.ankidroid.wearos.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.WorkerThread
import android.webkit.WebViewClient
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class CardBitmapRenderer(private val context: Context) {
  companion object {
    private const val TAG = "CardBitmapRenderer"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private const val KEY_CONTENT = "content_key"
    private const val IMAGE_PATH_PREFIX = "/card_image/"
    private var slowDrawEnabled = false

    // Bump when the render output changes (WebView wrapper, capture size, sanitizing) so existing
    // Data Items with an old content key are treated as stale and re-rendered. 2: wrapping body,
    // content-height capture, sound-ref stripping, images dropped when media can't be served.
    // 3: no per-template rules or language-specific fonts in the wrapper.
    // 4: AnkiDroid-style body classes and night mode, MathJax.
    private const val RENDER_VERSION = 4

    // Render at a width that covers the largest current Wear round displays (~450-480px) so the
    // watch downscales (ContentScale.Fit) to a crisp image on any device. True per-device sizing
    // would need the watch to report its width; this fixed high resolution is a safe proxy. The
    // height follows the card's own content (see captureFullHeight).
    private const val RENDER_SIZE_PX = 480

    // Whether AnkiDroid's provider serves media files at all. That's a property of the installed
    // AnkiDroid build rather than of a card, so it's probed once per process and cached; null means
    // not probed yet.
    @Volatile private var mediaReadable: Boolean? = null

    // Bounded poll for readiness (DOM complete + images loaded) instead of a flat post-load delay.
    private const val MAX_CAPTURE_ATTEMPTS = 20
    // MathJax has to parse a 2MB script and typeset before the side is ready, so math sides get a
    // longer (still bounded) wait.
    private const val MAX_MATHJAX_CAPTURE_ATTEMPTS = 100
    private const val CAPTURE_POLL_MS = 30L
    private const val PAINT_TIMEOUT_MS = 500L

    // Schemes the offscreen render is allowed to load; anything else is blocked as egress. `about`
    // covers WebView's own about:blank bootstrap.
    private val ALLOWED_SCHEMES = setOf("file", "data", "content", "about")
  }

  suspend fun render(
    html: String,
    ord: Int = 0,
    css: String? = null,
    width: Int = RENDER_SIZE_PX,
  ): ByteArray? {
    // WebView drawing has to happen on the main thread; PNG encoding is slow and must not.
    val bitmap = drawOnMain(html, ord, css, width) ?: return null
    return withContext(Dispatchers.Default) { encodePng(bitmap) }
  }

  private suspend fun drawOnMain(html: String, ord: Int, css: String?, width: Int): Bitmap? {
    return withContext(Dispatchers.Main) {
      if (!slowDrawEnabled) {
        try {
          WebView.enableSlowWholeDocumentDraw()
          slowDrawEnabled = true
        } catch (e: Exception) {
          Log.e(TAG, "Failed to enable slow whole document draw", e)
        }
      }

      val deferred = CompletableDeferred<Bitmap?>()
      var webViewRef: WebView? = null

      try {
        val webView = WebView(context)
        webViewRef = webView
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        // Configure layout params. Square to start with; captureFullHeight re-lays the view out once
        // the document reports how tall it actually is.
        webView.layout(0, 0, width, width)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.setBackgroundColor(Color.TRANSPARENT)

        val wrappedHtml = cardPageHtml(html, ord, css)
        val maxAttempts =
          if (containsMathJax(html)) MAX_MATHJAX_CAPTURE_ATTEMPTS else MAX_CAPTURE_ATTEMPTS

        webView.webViewClient =
          object : WebViewClient() {
            override fun shouldInterceptRequest(
              view: WebView?,
              request: WebResourceRequest?,
            ): WebResourceResponse? {
              val url = request?.url ?: return null

              // Egress guard: the render needs only local content - bundled assets (file:), inline
              // data: URIs, and the AnkiDroid media provider (handled below). Block every other
              // scheme so a third-party shared-deck card's HTML/JS (JS stays enabled for layout)
              // can't exfiltrate collection media or reach the network. The app declares no INTERNET
              // permission today; this keeps the invariant if that ever changes.
              val scheme = url.scheme?.lowercase()
              if (scheme != null && scheme !in ALLOWED_SCHEMES) {
                Log.w(TAG, "Blocked non-local WebView request: ${redact(url)}")
                return WebResourceResponse(
                  "text/plain",
                  "UTF-8",
                  java.io.ByteArrayInputStream(ByteArray(0)),
                )
              }

              val filename = url.lastPathSegment
              val imgExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp")
              if (
                filename != null && imgExtensions.any { filename.endsWith(it, ignoreCase = true) }
              ) {
                return try {
                  val mediaUri = Uri.parse("content://$AUTHORITY/media/$filename")
                  val inputStream = context.contentResolver.openInputStream(mediaUri)
                  val mimeType =
                    when {
                      filename.endsWith(".png") -> "image/png"
                      filename.endsWith(".gif") -> "image/gif"
                      filename.endsWith(".webp") -> "image/webp"
                      else -> "image/jpeg"
                    }
                  WebResourceResponse(mimeType, "UTF-8", inputStream)
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to resolve media file via provider: ${redact(filename)}", e)
                  null
                }
              }
              return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              captureWhenReady(webView, width, 0, maxAttempts) { deferred.complete(it) }
            }
          }

        webView.loadDataWithBaseURL(
          "file:///android_asset/",
          wrappedHtml,
          "text/html",
          "UTF-8",
          null,
        )
        deferred.await()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize and run WebView renderer: ${e.message}", e)
        null
      } finally {
        // One WebView is created per card side; destroy it (native renderer + GL context) instead of
        // leaking it. The bitmap is already drawn by the time await() returns, so it's safe.
        webViewRef?.destroy()
      }
    }
  }

  /**
   * Capture once the page is genuinely ready: DOM complete, every <img> has finished loading
   * (naturalHeight > 0) and MathJax, if any, has typeset, polled on a short interval. Falls back to
   * capturing anyway after [maxAttempts] so a stuck/missing image can't hang the render.
   */
  private fun captureWhenReady(
    webView: WebView,
    width: Int,
    attempt: Int,
    maxAttempts: Int,
    onReady: (Bitmap?) -> Unit,
  ) {
    val readinessJs =
      "(function(){return document.readyState==='complete'&&" +
        "!document.body.classList.contains('$MATHJAX_PENDING_CLASS')&&" +
        "Array.prototype.every.call(document.images,function(i){" +
        "return i.complete&&i.naturalHeight>0;});})()"
    webView.evaluateJavascript(readinessJs) { result ->
      if (result == "true" || attempt >= maxAttempts) {
        if (result != "true") {
          Log.w(TAG, "Capturing before the page reported ready")
        }
        captureFullHeight(webView, width, onReady)
      } else {
        Handler(Looper.getMainLooper())
          .postDelayed(
            { captureWhenReady(webView, width, attempt + 1, maxAttempts, onReady) },
            CAPTURE_POLL_MS,
          )
      }
    }
  }

  /**
   * Capture the whole card instead of a fixed square, which clipped tall answers. The document
   * reports CSS pixels and the viewport meta makes the CSS viewport width/density wide, so CSS
   * height maps to device pixels by the display density. Re-lay-out first or the WebView still
   * clips at the old height. Clamped so a pathological layout can't allocate an enormous bitmap.
   */
  private fun captureFullHeight(webView: WebView, width: Int, onReady: (Bitmap?) -> Unit) {
    // The body, not the document: the document is never shorter than the square viewport, which
    // left a one-line card as a line of text at the top of a mostly empty image.
    val heightJs =
      "(function(){var b=document.body.getBoundingClientRect();" +
        "return Math.max(b.bottom,document.body.scrollHeight)+window.scrollY;})()"
    webView.evaluateJavascript(heightJs) { result ->
      val density = context.resources.displayMetrics.density
      // A failed or empty probe keeps the old square capture rather than guessing at a tiny one.
      val cssHeight = result?.trim('"')?.toFloatOrNull()?.takeIf { it > 0f } ?: width / density
      val contentHeight = (cssHeight * density).toInt().coerceIn(1, width * 4)
      webView.layout(0, 0, width, contentHeight)
      drawWhenPainted(webView) { onReady(captureBitmap(webView, width, contentHeight)) }
    }
  }

  /**
   * A ready DOM isn't painted yet: drawing straight away gave fully transparent captures for every
   * side that didn't happen to poll for a while (only MathJax ones waited long enough). The visual
   * state callback fires once the current DOM is ready to draw; the timeout bounds it since an
   * offscreen WebView isn't guaranteed to deliver it.
   */
  private fun drawWhenPainted(webView: WebView, draw: () -> Unit) {
    var drawn = false
    val once = {
      if (!drawn) {
        drawn = true
        draw()
      }
    }
    webView.postVisualStateCallback(
      0L,
      object : WebView.VisualStateCallback() {
        override fun onComplete(requestId: Long) = once()
      },
    )
    Handler(Looper.getMainLooper()).postDelayed(once, PAINT_TIMEOUT_MS)
  }

  private fun captureBitmap(webView: WebView, width: Int, height: Int): Bitmap? =
    try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { webView.draw(Canvas(it)) }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to draw WebView to bitmap: ${e.message}", e)
      null
    }

  @WorkerThread
  private fun encodePng(bitmap: Bitmap): ByteArray? =
    try {
      ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream)
        stream.toByteArray()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to encode card bitmap: ${e.message}", e)
      null
    } finally {
      bitmap.recycle()
    }

  /**
   * Whether a side is worth the phone-side WebView bitmap at all. Evaluated on the prepared HTML so
   * an image the provider can't serve doesn't force a render that would only show a broken image.
   * [mediaReadable] is a test seam; production uses the cached probe.
   */
  fun needsRichRendering(html: String, mediaReadable: Boolean = isMediaReadable(html)): Boolean =
    hasRichSignals(prepareForRender(html, mediaReadable))

  /**
   * Only what native text can't draw: images, inline SVG, overlines, borders and MathJax. Matched on
   * CSS declarations in the card HTML itself (inline styles or a `<style>` block in a field; the
   * provider does not send the note type's stylesheet), never on class or note type names. A bare
   * `style=` is deliberately not a trigger: nearly every card carries one, and the bitmap path is
   * the expensive one.
   */
  private fun hasRichSignals(html: String): Boolean {
    val lower = html.lowercase()
    return lower.contains("<img") ||
      lower.contains("<svg") ||
      OVERLINE_REGEX.containsMatchIn(lower) ||
      BORDER_REGEX.containsMatchIn(lower) ||
      containsMathJax(html)
  }

  /** Whether this HTML's images can be loaded; the provider is probed only for a real filename. */
  private fun isMediaReadable(html: String): Boolean {
    val cached = mediaReadable
    if (cached != null) {
      return cached
    }
    val filename = mediaFileName(html)
    if (filename == null) {
      // Nothing the provider could serve (data: URIs decode inside the WebView), so the answer is
      // irrelevant here and not worth caching.
      return true
    }
    val readable = probeMediaSupport(filename)
    mediaReadable = readable
    return readable
  }

  /**
   * AnkiDroid's provider answers every media path with FileNotFoundException("No files supported by
   * provider") when it serves no files at all, so one probe settles it for the whole process. Any
   * other failure is per-file and doesn't prove images are unusable in general.
   */
  private fun probeMediaSupport(filename: String): Boolean =
    try {
      val mediaUri = Uri.parse("content://$AUTHORITY/media/$filename")
      context.contentResolver.openInputStream(mediaUri)?.close()
      true
    } catch (e: FileNotFoundException) {
      val unsupported = e.message?.contains("No files supported") == true
      if (unsupported) {
        Log.i(TAG, "AnkiDroid's provider serves no media; card images are omitted from bitmaps.")
      }
      !unsupported
    } catch (e: Exception) {
      Log.w(TAG, "Media probe failed for ${redact(filename)}: ${e.message}")
      true
    }

  /** Stable hex SHA-256 of everything that shapes the render: version, ord, note type CSS, HTML. */
  private fun contentKey(html: String, ord: Int, css: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$RENDER_VERSION:$ord:${css?.length ?: -1}:${css.orEmpty()}:$html"
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  /** Content keys of every card image already in the Data Layer, by path, in one query. */
  private suspend fun existingContentKeys(dataClient: DataClient): Map<String, String> {
    val uri = Uri.Builder().scheme("wear").path(IMAGE_PATH_PREFIX).build()
    val buffer = dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).await()
    try {
      return buffer
        .mapNotNull { item ->
          val key = DataMapItem.fromDataItem(item).dataMap.getString(KEY_CONTENT)
          item.uri.path?.let { path -> key?.let { path to it } }
        }
        .toMap()
    } finally {
      buffer.release()
    }
  }

  /**
   * Render [html] for one card side and put it, unless [existing] shows the Data Layer already
   * holds an item with the same content key. [html] is expected to have been through
   * [prepareForRender]. Returns true when a render and put happened.
   */
  private suspend fun syncSide(
    dataClient: DataClient,
    existing: Map<String, String>,
    cardId: Long,
    ord: Int,
    css: String?,
    side: String,
    html: String,
  ): Boolean {
    val key = contentKey(html, ord, css)
    val imagePath = "$IMAGE_PATH_PREFIX$cardId/$side"
    if (existing[imagePath] == key) {
      Log.d(TAG, "Skipping unchanged $side image for card $cardId")
      return false
    }

    Log.d(TAG, "Card $cardId ($side) needs rich rendering. Rendering offscreen...")
    val bytes = render(html, ord, css)
    if (bytes == null) {
      return false
    }
    val asset = Asset.createFromBytes(bytes)
    val putImageReq =
      PutDataMapRequest.create(imagePath)
        .apply {
          dataMap.putAsset("image_asset", asset)
          dataMap.putString(KEY_CONTENT, key)
          setUrgent()
        }
        .asPutDataRequest()
    dataClient.putDataItem(putImageReq).await()
    Log.d(TAG, "Sent $side image asset for card $cardId on path $imagePath")
    return true
  }

  /** Withdraw a side's image: the watch only drops its cached PNG when the Data Item goes away. */
  private suspend fun deleteSideImage(dataClient: DataClient, path: String): Boolean =
    try {
      dataClient.deleteDataItems(Uri.Builder().scheme("wear").path(path).build()).await()
      Log.d(TAG, "Withdrew stale card image on path $path")
      true
    } catch (e: Exception) {
      Log.w(TAG, "Failed to withdraw stale card image on path $path: ${e.message}")
      false
    }

  /**
   * [noteCss] maps a note id to its note type's CSS. AnkiDroid's provider leaves the stylesheet out
   * of the card HTML, so the caller looks it up; null renders with the wrapper styles only. Called
   * at most once per note, and only for notes with a side that renders.
   */
  suspend fun syncBitmapsForBatch(
    batch: uniffi.wearos_core.WearosDueBatch,
    noteCss: (noteId: Long) -> String? = { null },
  ) {
    val dataClient = Wearable.getDataClient(context)
    val existing = existingContentKeys(dataClient)
    var rendered = 0
    var unchanged = 0
    var removed = 0
    val cssByNote = HashMap<Long, Optional<String>>()
    for (card in batch.cards) {
      // The gate reads the card HTML only; note CSS shapes the render, never the decision.
      val css by lazy { cssByNote.getOrPut(card.noteId) { Optional.ofNullable(noteCss(card.noteId)) } }
      for ((side, rawHtml) in listOf("front" to card.front, "back" to card.back)) {
        val html = prepareForRender(rawHtml, isMediaReadable(rawHtml))
        val path = "$IMAGE_PATH_PREFIX${card.id}/$side"
        if (hasRichSignals(html)) {
          if (syncSide(dataClient, existing, card.id, card.ord, css.orElse(null), side, html)) {
            rendered++
          } else {
            unchanged++
          }
        } else if (existing.containsKey(path) && deleteSideImage(dataClient, path)) {
          // A side that no longer needs rich rendering must not leave the old PNG on the watch.
          removed++
        }
      }
    }
    Log.i(TAG, "Bitmap sync done: $rendered rendered, $unchanged unchanged, $removed removed")
  }
}

private const val MATHJAX_PENDING_CLASS = "mathjax-needs-to-render"

private val OVERLINE_REGEX = Regex("""text-decoration(-line)?\s*:[^;}"']*overline""")
// Any border except an explicit none/0, which note types use to reset tables and inputs.
private val BORDER_REGEX = Regex("""border(-(top|bottom|left|right))?\s*:(?!\s*(none|0(px)?\s*[;}"']))""")
private val SOUND_REF_REGEX = Regex("""\[sound:[^\]]*\]""")
private val IMG_TAG_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val IMG_SRC_REGEX =
  Regex("""<img\b[^>]*?\bsrc\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)

/**
 * Normalize a card side for the bitmap path: drop Anki's sound references (the native text path
 * strips them too; a WebView would print the raw hex filename) and, when the provider serves no
 * media, the `<img>` tags themselves - a bitmap of a broken image is worse than the text the watch
 * renders natively. Pure, so the gate and the renderer can't disagree about what gets painted.
 */
internal fun prepareForRender(html: String, mediaReadable: Boolean): String {
  val spoken = SOUND_REF_REGEX.replace(html, "")
  return if (mediaReadable) spoken else IMG_TAG_REGEX.replace(spoken, "")
}

/**
 * The filename of the first image in [html], or null when there is nothing probeable: no image, an
 * inline `data:` URI (the WebView decodes those itself), or an absolute URL.
 */
private fun mediaFileName(html: String): String? {
  val src = IMG_SRC_REGEX.find(html)?.groupValues?.get(1) ?: return null
  if (src.startsWith("data:") || src.contains("://")) {
    return null
  }
  return src.substringAfterLast('/').substringBefore('?').takeIf { it.isNotEmpty() }
}

/** AnkiDroid loads MathJax only for cards using Anki's delimiters; `$` is not one of them. */
internal fun containsMathJax(html: String): Boolean = html.contains("\\(") || html.contains("\\[")

// Anki's delimiters only. The full build bundles every TeX package, so nothing is fetched later.
private const val MATHJAX_HEAD =
  """
  <script>
    window.MathJax = {
      tex: {
        inlineMath: [["\\(", "\\)"]],
        displayMath: [["\\[", "\\]"]],
        processEscapes: false,
        processEnvironments: false,
      },
      options: { enableMenu: false, enableAssistiveMml: false },
      startup: { typeset: false },
    };
  </script>
  <script src="mathjax/tex-svg-full.js"></script>
  """

// Like AnkiDroid's reviewer: typeset after startup, then drop the pending class the capture polls.
private const val MATHJAX_TYPESET =
  """
  <script>
    (function () {
      var done = function () {
        document.body.classList.remove("$MATHJAX_PENDING_CLASS");
      };
      if (window.MathJax && MathJax.startup && MathJax.startup.promise) {
        MathJax.startup.promise
          .then(function () { return MathJax.typesetPromise([document.body]); })
          .then(done, done);
      } else {
        done();
      }
    })();
  </script>
  """

/**
 * The page the offscreen WebView loads for one card side. Mirrors AnkiDroid's card WebView: body
 * classes `card card{ord+1}` plus night mode (the watch is always dark) so the note type's [css],
 * including its `.nightMode` overrides, applies; and MathJax only when the side uses it. As in
 * AnkiDroid's card_template.html the note's style comes after the base styles, whose night mode
 * colors still win over `.card` by specificity.
 */
internal fun cardPageHtml(html: String, ord: Int, css: String? = null): String {
  val math = containsMathJax(html)
  val bodyClasses =
    "card card${ord + 1} nightMode night_mode" + if (math) " $MATHJAX_PENDING_CLASS" else ""
  // A stray </style> in the note CSS must not end the block and spill the rest into the body.
  val noteStyle =
    css?.let { "<style>${it.replace("</style", "<\\/style", ignoreCase = true)}</style>" }.orEmpty()
  return """
    <!doctype html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <style>
      * {
        max-width: 100%;
        box-sizing: border-box;
      }
      body {
        font-family: sans-serif;
        font-size: 18px;
        text-align: center;
        margin: 0;
        padding: 16px;
        /* Block layout, not a centred flex column: a flex item is sized to its max-content
           width, so a long sentence never wrapped and ran off both edges of the capture. */
        overflow-wrap: anywhere;
        word-break: normal;
      }
      /* AnkiDroid's flashcard.css night mode, which beats the note type's .card colors by
         specificity. Transparent instead of black: the capture keeps the watch background. */
      body.night_mode {
        color: white;
        background-color: transparent;
      }
      .nightMode .latex {
        filter: invert(100%);
      }
      img {
        max-width: 90%;
        max-height: 70%;
        height: auto;
        object-fit: contain;
        margin: 8px 0;
      }
    </style>
    $noteStyle
    ${if (math) MATHJAX_HEAD else ""}
    </head>
    <body class="$bodyClasses">
      $html
    ${if (math) MATHJAX_TYPESET else ""}
    </body>
    </html>
  """
    .trimIndent()
}
