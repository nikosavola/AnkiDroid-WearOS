package com.ichi2.anki.wearos.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.tasks.await

object CardImageCache {
  private const val TAG = "CardImageCache"
  private const val DIR_NAME = "card_images"

  private fun getImagesDir(context: Context): File {
    val dir = File(context.filesDir, DIR_NAME)
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  fun getImagePath(context: Context, cardId: Long, side: String): File =
    File(getImagesDir(context), "img_${cardId}_$side.png")

  fun hasImage(context: Context, cardId: Long, side: String): Boolean =
    getImagePath(context, cardId, side).exists()

  suspend fun saveImage(context: Context, cardId: Long, side: String, asset: Asset) {
    try {
      val dataClient = Wearable.getDataClient(context)
      val fdResult = dataClient.getFdForAsset(asset).await()
      val inputStream = fdResult.inputStream
      val outputFile = getImagePath(context, cardId, side)

      inputStream.use { input ->
        FileOutputStream(outputFile).use { output -> input.copyTo(output) }
      }
      Log.i(TAG, "Successfully cached image locally on watch: img_${cardId}_$side.png")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to cache image asset for card $cardId ($side): ${e.message}", e)
    }
  }

  /**
   * Drop a cached side image the phone has withdrawn (it no longer needs rich rendering). Without
   * this the watch would keep showing the stale PNG forever, since the phone only ever re-sends
   * changed images.
   */
  fun deleteImage(context: Context, cardId: Long, side: String) {
    try {
      if (getImagePath(context, cardId, side).delete()) {
        Log.i(TAG, "Deleted phone-withdrawn card image: img_${cardId}_$side.png")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to delete card image for card $cardId ($side): ${e.message}", e)
    }
  }

  /**
   * Delete cached card-side images whose card id is no longer present in any cached deck. Additive
   * cleanup that keeps the img_<id>_<side>.png filename contract with the phone intact (no key
   * change). Fresh images for still-present cards are untouched. Unchanged images are not re-sent
   * by the phone, so a card that leaves the pool and later returns is recovered by
   * [restoreMissingImages] from the Data Item the phone still holds.
   */
  fun pruneImages(context: Context, validCardIds: Set<Long>) {
    try {
      getImagesDir(context).listFiles()?.forEach { f ->
        val id = f.name.removePrefix("img_").substringBefore('_').toLongOrNull()
        if (id != null && id !in validCardIds) {
          f.delete()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to prune stale card images: ${e.message}", e)
    }
  }

  /**
   * Re-download any missing side image for [cardIds] from the Data Layer, since the phone no longer
   * re-sends unchanged bitmaps. One prefix query covers every card. Never throws.
   */
  suspend fun restoreMissingImages(context: Context, cardIds: Set<Long>) {
    var restored = 0
    try {
      val dataClient = Wearable.getDataClient(context)
      val uri = Uri.Builder().scheme("wear").path("/card_image/").build()
      val buffer = dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).await()
      try {
        for (item in buffer) {
          // Paths are /card_image/<id>/<side>.
          val parts = item.uri.path?.split("/") ?: continue
          val id = parts.getOrNull(2)?.toLongOrNull() ?: continue
          val side = parts.getOrNull(3) ?: continue
          if (id !in cardIds || hasImage(context, id, side)) continue
          val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("image_asset") ?: continue
          saveImage(context, id, side, asset)
          restored++
        }
      } finally {
        buffer.release()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore card images from the Data Layer: ${e.message}", e)
    }
    if (restored > 0) {
      Log.i(TAG, "Restored $restored missing card image(s) from the Data Layer.")
    }
  }

  fun clearAll(context: Context) {
    try {
      val dir = getImagesDir(context)
      dir.listFiles()?.forEach { it.delete() }
      Log.d(TAG, "Cleared all locally cached card images on watch.")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clear card images directory: ${e.message}", e)
    }
  }
}
