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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CardBitmapRenderer(private val context: Context) {
  companion object {
    private const val TAG = "CardBitmapRenderer"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private var slowDrawEnabled = false
  }

  suspend fun render(html: String, width: Int = 400, height: Int = 400): ByteArray? {
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
              // Give a tiny amount of time for reflow/layouts to complete
              Handler(Looper.getMainLooper())
                .postDelayed(
                  {
                    try {
                      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                      val canvas = Canvas(bitmap)
                      webView.draw(canvas)

                      val stream = ByteArrayOutputStream()
                      bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream)
                      bitmap.recycle()

                      deferred.complete(stream.toByteArray())
                    } catch (e: Exception) {
                      Log.e(TAG, "Failed to draw WebView to bitmap: ${e.message}", e)
                      deferred.complete(null)
                    }
                  },
                  60,
                )
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

  fun needsRichRendering(html: String): Boolean {
    val lower = html.lowercase()
    return lower.contains("<img") ||
      lower.contains("overline") ||
      lower.contains("kaishi") ||
      lower.contains("border-top") ||
      lower.contains("text-decoration") ||
      lower.contains("style=")
  }
}
