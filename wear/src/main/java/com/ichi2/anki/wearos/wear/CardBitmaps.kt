package com.ichi2.anki.wearos.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decoded card-side images, kept so a side is decoded once off the main thread rather than inside
 * composition. Bitmaps are decoded straight into [Bitmap.Config.HARDWARE] so the pixels are
 * uploaded to a GPU texture on the decoding thread, not on the RenderThread at first draw.
 */
internal object CardBitmaps {
  private const val TAG = "CardBitmaps"
  // Both sides of the current card plus the next couple of cards.
  private const val MAX_ENTRIES = 6
  // Hardware bitmaps are single GL textures; stay inside the smallest max texture size in use.
  private const val MAX_HARDWARE_DIMENSION = 4096

  private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

  private fun key(cardId: Long, side: String) = "${cardId}_$side"

  fun cached(cardId: Long, side: String): ImageBitmap? = cache.get(key(cardId, side))

  /** The side's image scaled down to [targetWidthPx], or null if the watch has none for it. */
  @WorkerThread
  fun load(context: Context, cardId: Long, side: String, targetWidthPx: Int): ImageBitmap? {
    cached(cardId, side)?.let {
      return it
    }
    val file = CardImageCache.getImagePath(context, cardId, side)
    if (!file.exists()) return null
    val path = file.path
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(path, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      val scale = minOf(1f, targetWidthPx.toFloat() / bounds.outWidth)
      val options =
        BitmapFactory.Options().apply {
          if (scale < 1f) {
            inScaled = true
            inDensity = bounds.outWidth
            inTargetDensity = targetWidthPx
          }
          inPreferredConfig =
            if (bounds.outHeight * scale <= MAX_HARDWARE_DIMENSION) {
              Bitmap.Config.HARDWARE
            } else {
              Bitmap.Config.ARGB_8888
            }
        }
      val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
      if (bitmap.config != Bitmap.Config.HARDWARE) bitmap.prepareToDraw()
      bitmap.asImageBitmap().also { cache.put(key(cardId, side), it) }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't decode card image $cardId/$side: ${e.message}")
      null
    }
  }

  /** Warm both sides of [cardIds] so the next card shows without a decode. */
  @WorkerThread
  fun prefetch(context: Context, cardIds: List<Long>, targetWidthPx: Int) {
    for (id in cardIds) {
      for (side in SIDES) load(context, id, side, targetWidthPx)
    }
  }

  fun evict(cardId: Long, side: String) {
    cache.remove(key(cardId, side))
  }

  fun clear() = cache.evictAll()

  private val SIDES = listOf("front", "back")
}
