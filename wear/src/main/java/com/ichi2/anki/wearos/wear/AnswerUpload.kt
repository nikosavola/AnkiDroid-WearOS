package com.ichi2.anki.wearos.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uniffi.wearos_core.getBufferedAnswers
import uniffi.wearos_core.serializeAnswers

private const val TAG = "AnswerUpload"
internal const val PATH_ANSWERS = "/answers"
internal const val KEY_ANSWERS_BYTES = "answers_bytes"

/** Outcome of an upload attempt: nothing buffered, or [count] answers handed to the phone. */
internal sealed interface UploadResult {
  data object Empty : UploadResult

  data class Sent(val count: Int) : UploadResult
}

/**
 * Hand every buffered answer to the phone over the Wearable Data Layer. putDataItem is persisted
 * and delivered by the Data Layer when the phone is next reachable, so this works fully offline.
 * Each send varies `synced_at` so re-sending the same buffered answers after a failed write-back
 * isn't dropped by DataClient's dedup. Failures propagate; callers decide to toast or retry.
 */
internal suspend fun uploadBufferedAnswers(
  context: Context,
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): UploadResult =
  withContext(ioDispatcher) {
    val answers = getBufferedAnswers()
    if (answers.isEmpty()) return@withContext UploadResult.Empty

    val bytes = serializeAnswers(answers)
    val putDataReq =
      PutDataMapRequest.create(PATH_ANSWERS)
        .apply {
          dataMap.putByteArray(KEY_ANSWERS_BYTES, bytes)
          dataMap.putLong("synced_at", System.currentTimeMillis())
          setUrgent()
        }
        .asPutDataRequest()

    Wearable.getDataClient(context).putDataItem(putDataReq).await()
    Log.i(TAG, "Broadcasted ${answers.size} answers to Phone Companion.")
    UploadResult.Sent(answers.size)
  }
