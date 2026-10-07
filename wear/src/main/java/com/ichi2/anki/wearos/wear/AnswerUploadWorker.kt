package com.ichi2.anki.wearos.wear

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Pushes buffered reviews to the phone from the background. No network constraint: the Wearable
 * Data Layer queues the put locally and delivers it when the phone is reachable, so this is useful
 * even offline. All work lives in [uploadBufferedAnswers].
 */
class AnswerUploadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result =
    try {
      uploadBufferedAnswers(applicationContext)
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Transient (Bluetooth down): let WorkManager retry with backoff.
      Log.e(TAG, "Answer upload failed; will retry: ${e.message}", e)
      Result.retry()
    }

  companion object {
    private const val TAG = "AnswerUploadWorker"

    const val WORK_NAME = "answer-upload"

    // A burst of grades coalesces into one upload: REPLACE restarts the delay on every grade, so
    // the put happens once the user pauses.
    const val UPLOAD_DEBOUNCE_SECONDS = 20L

    /** Debounced upload after grading; the delay restarts on each call. */
    fun scheduleDebounced(context: Context) {
      enqueue(context, UPLOAD_DEBOUNCE_SECONDS)
    }

    /** Upload right away (leaving the app, or the launch-time catch-up). */
    fun scheduleNow(context: Context) {
      enqueue(context, 0L)
    }

    private fun enqueue(context: Context, delaySeconds: Long) {
      val request =
        OneTimeWorkRequestBuilder<AnswerUploadWorker>()
          .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
          .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
  }
}
