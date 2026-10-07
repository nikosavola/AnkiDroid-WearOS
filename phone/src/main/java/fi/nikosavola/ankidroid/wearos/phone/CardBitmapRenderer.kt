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
import android.webkit.WebViewClient
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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
    // Data Items with an old content key are treated as stale and re-rendered.
    private const val RENDER_VERSION = 1

    // Render square at a resolution that covers the largest current Wear round displays (~450-480px)
    // so the watch downscales (ContentScale.Fit) to a crisp image on any device. True per-device
    // sizing would need the watch to report its width; this fixed high resolution is a safe proxy.
    private const val RENDER_SIZE_PX = 480

    // Bounded poll for readiness (DOM complete + images loaded) instead of a flat post-load delay.
    private const val MAX_CAPTURE_ATTEMPTS = 20
    private const val CAPTURE_POLL_MS = 30L

    // Schemes the offscreen render is allowed to load; anything else is blocked as egress. `about`
    // covers WebView's own about:blank bootstrap.
    private val ALLOWED_SCHEMES = setOf("file", "data", "content", "about")
  }

  suspend fun render(
    html: String,
    width: Int = RENDER_SIZE_PX,
    height: Int = RENDER_SIZE_PX,
  ): ByteArray? {
    return withContext(Dispatchers.Main) {
      if (!slowDrawEnabled) {
        try {
          WebView.enableSlowWholeDocumentDraw()
          slowDrawEnabled = true
        } catch (e: Exception) {
          Log.e(TAG, "Failed to enable slow whole document draw", e)
        }
      }

      val deferred = CompletableDeferred<ByteArray?>()
      var webViewRef: WebView? = null

      try {
        val webView = WebView(context)
        webViewRef = webView
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        // Configure layout params
        webView.layout(0, 0, width, height)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.setBackgroundColor(Color.TRANSPARENT)

        val wrappedHtml =
          """
          <html>
          <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
          <style>
            body {
              background-color: transparent;
              color: #FFFFFF;
              font-family: 'Roboto', 'Noto Sans CJK JP', sans-serif;
              font-size: 18px;
              text-align: center;
              margin: 0;
              padding: 16px;
              display: flex;
              flex-direction: column;
              justify-content: center;
              align-items: center;
              height: 100vh;
              box-sizing: border-box;
            }
            img {
              max-width: 90%;
              max-height: 70%;
              height: auto;
              object-fit: contain;
              margin: 8px 0;
            }
            /* Pitch-accent (Kaishi) overline classes */
            .overline {
              text-decoration: overline;
              border-top: 1px solid #FFFFFF;
            }
            /* Custom CSS rules typically used in Anki */
            .card {
              font-family: arial;
              font-size: 20px;
              text-align: center;
              color: white;
              background-color: transparent;
            }
          </style>
          </head>
          <body>
            $html
          </body>
          </html>
        """
            .trimIndent()

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
                Log.w(TAG, "Blocked non-local WebView request: $url")
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
                  Log.e(TAG, "Failed to resolve media file via provider: $filename", e)
                  null
                }
              }
              return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              captureWhenReady(webView, width, height, 0) { deferred.complete(it) }
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
        // leaking it. The PNG bytes are already captured by the time await() returns, so it's safe.
        webViewRef?.destroy()
      }
    }
  }

  /**
   * Capture once the page is genuinely ready: DOM complete AND every <img> has finished loading
   * (naturalHeight > 0), polled on a short interval. Falls back to capturing anyway after
   * [MAX_CAPTURE_ATTEMPTS] so a stuck/missing image can't hang the render. Replaces the old flat
   * 60ms delay, which could fire mid-reflow or before media arrived.
   */
  private fun captureWhenReady(
    webView: WebView,
    width: Int,
    height: Int,
    attempt: Int,
    onReady: (ByteArray?) -> Unit,
  ) {
    val readinessJs =
      "(function(){return document.readyState==='complete'&&" +
        "Array.prototype.every.call(document.images,function(i){" +
        "return i.complete&&i.naturalHeight>0;});})()"
    webView.evaluateJavascript(readinessJs) { result ->
      if (result == "true" || attempt >= MAX_CAPTURE_ATTEMPTS) {
        onReady(captureBitmap(webView, width, height))
      } else {
        Handler(Looper.getMainLooper())
          .postDelayed(
            { captureWhenReady(webView, width, height, attempt + 1, onReady) },
            CAPTURE_POLL_MS,
          )
      }
    }
  }

  private fun captureBitmap(webView: WebView, width: Int, height: Int): ByteArray? =
    try {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      webView.draw(Canvas(bitmap))
      val stream = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream)
      bitmap.recycle()
      stream.toByteArray()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to draw WebView to bitmap: ${e.message}", e)
      null
    }

  fun needsRichRendering(html: String): Boolean {
    val lower = html.lowercase()
    // Only signals native rendering genuinely can't reproduce: images and pitch-accent styling
    // (overline / border-top / text-decoration), plus the Kaishi note type by name (its pitch line
    // can arrive without any inline-style marker). A bare `style=` is deliberately NOT a trigger -
    // nearly every card carries one, so matching it forced almost everything through the expensive
    // WebView+PNG+asset path and defeated native rendering as the common case.
    return lower.contains("<img") ||
      lower.contains("overline") ||
      lower.contains("kaishi") ||
      lower.contains("border-top") ||
      lower.contains("text-decoration")
  }

  /** Stable hex SHA-256 of a side's HTML plus the renderer version. */
  private fun contentKey(html: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$RENDER_VERSION:$html".toByteArray(Charsets.UTF_8))
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
   * holds an item with the same content key. Returns true when a render and put happened.
   */
  private suspend fun syncSide(
    dataClient: DataClient,
    existing: Map<String, String>,
    cardId: Long,
    side: String,
    html: String,
  ): Boolean {
    val key = contentKey(html)
    val imagePath = "$IMAGE_PATH_PREFIX$cardId/$side"
    if (existing[imagePath] == key) {
      Log.d(TAG, "Skipping unchanged $side image for card $cardId")
      return false
    }

    Log.d(TAG, "Card $cardId ($side) needs rich rendering. Rendering offscreen...")
    val bytes = render(html)
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

  suspend fun syncBitmapsForBatch(batch: uniffi.wearos_core.WearosDueBatch) {
    val dataClient = Wearable.getDataClient(context)
    val existing = existingContentKeys(dataClient)
    var rendered = 0
    var skipped = 0
    for (card in batch.cards) {
      if (needsRichRendering(card.front)) {
        if (syncSide(dataClient, existing, card.id, "front", card.front)) {
          rendered++
        } else {
          skipped++
        }
      }
      if (needsRichRendering(card.back)) {
        if (syncSide(dataClient, existing, card.id, "back", card.back)) {
          rendered++
        } else {
          skipped++
        }
      }
    }
    Log.i(TAG, "Bitmap sync done: $rendered rendered, $skipped unchanged")
  }
}
