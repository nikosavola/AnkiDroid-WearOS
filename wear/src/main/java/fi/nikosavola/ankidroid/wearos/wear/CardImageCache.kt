package fi.nikosavola.ankidroid.wearos.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
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
   * Delete cached card-side images whose card id is no longer present in any cached deck. Additive
   * cleanup that keeps the img_<id>_<side>.png filename contract with the phone intact (no key
   * change). Fresh images for still-present cards are untouched, and every sync re-renders and
   * overwrites a card's image, so updated content refreshes without an explicit invalidation.
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
