package com.ichi2.anki.wearos.phone

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
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class CardBitmapRenderer(private val context: Context) {
  companion object {
    private const val TAG = "CardBitmapRenderer"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private var slowDrawEnabled = false

    // Render square at a resolution that covers the largest current Wear round displays (~450-480px)
    // so the watch downscales (ContentScale.Fit) to a crisp image on any device. True per-device
    // sizing would need the watch to report its width; this fixed high resolution is a safe proxy.
    private const val RENDER_SIZE_PX = 480

    // Bounded poll for readiness (DOM complete + images loaded) instead of a flat post-load delay.
    private const val MAX_CAPTURE_ATTEMPTS = 20
    private const val CAPTURE_POLL_MS = 30L
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

      try {
        val webView = WebView(context)
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
      } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize and run WebView renderer: ${e.message}", e)
        deferred.complete(null)
      }

      deferred.await()
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
    return lower.contains("<img") ||
      lower.contains("overline") ||
      lower.contains("kaishi") ||
      lower.contains("border-top") ||
      lower.contains("text-decoration") ||
      lower.contains("style=")
  }

  suspend fun syncBitmapsForBatch(batch: uniffi.wearos_core.WearosDueBatch) {
    val dataClient = Wearable.getDataClient(context)
    for (card in batch.cards) {
      if (needsRichRendering(card.front)) {
        Log.d(TAG, "Card ${card.id} (front) needs rich rendering. Rendering offscreen...")
        val frontBytes = render(card.front)
        if (frontBytes != null) {
          val asset = Asset.createFromBytes(frontBytes)
          val imagePath = "/card_image/${card.id}/front"
          val putImageReq =
            PutDataMapRequest.create(imagePath)
              .apply {
                dataMap.putAsset("image_asset", asset)
                // Vary the item so re-rendering the same card still propagates; without this the Data
                // Layer dedups a byte-identical re-send and the watch never receives it (same reason
                // the due-batch path stamps synced_at).
                dataMap.putLong("synced_at", System.currentTimeMillis())
                setUrgent()
              }
              .asPutDataRequest()
          dataClient.putDataItem(putImageReq).await()
          Log.d(TAG, "Sent front image asset for card ${card.id} on path $imagePath")
        }
      }

      if (needsRichRendering(card.back)) {
        Log.d(TAG, "Card ${card.id} (back) needs rich rendering. Rendering offscreen...")
        val backBytes = render(card.back)
        if (backBytes != null) {
          val asset = Asset.createFromBytes(backBytes)
          val imagePath = "/card_image/${card.id}/back"
          val putImageReq =
            PutDataMapRequest.create(imagePath)
              .apply {
                dataMap.putAsset("image_asset", asset)
                // See the front path: stamp the item so an identical re-render still reaches the watch.
                dataMap.putLong("synced_at", System.currentTimeMillis())
                setUrgent()
              }
              .asPutDataRequest()
          dataClient.putDataItem(putImageReq).await()
          Log.d(TAG, "Sent back image asset for card ${card.id} on path $imagePath")
        }
      }
    }
  }
}
