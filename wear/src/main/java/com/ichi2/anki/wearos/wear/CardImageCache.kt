package com.ichi2.anki.wearos.wear

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

  fun getImagePath(context: Context, cardId: Long, side: String): File {
    return File(getImagesDir(context), "img_${cardId}_$side.png")
  }

  fun hasImage(context: Context, cardId: Long, side: String): Boolean {
    return getImagePath(context, cardId, side).exists()
  }

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
