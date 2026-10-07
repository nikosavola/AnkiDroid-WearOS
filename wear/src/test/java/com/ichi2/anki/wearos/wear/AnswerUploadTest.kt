package com.ichi2.anki.wearos.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scheduling of the buffered-answer upload. The GMS put itself needs Play Services and a paired
 * phone, so it can't run headless; these cover the WorkManager bookkeeping only.
 */
@RunWith(RobolectricTestRunner::class)
class AnswerUploadTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var workManager: WorkManager

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    workManager = WorkManager.getInstance(context)
  }

  // REPLACE cancels the previous request; WorkManager keeps reporting it, so drop cancelled work.
  // An immediate request may already have run by the time we look, so don't filter on state alone.
  private fun activeUploads(): List<WorkInfo> =
    workManager.getWorkInfosForUniqueWork(AnswerUploadWorker.WORK_NAME).get().filter {
      it.state != WorkInfo.State.CANCELLED
    }

  @Test
  fun `a burst of grades schedules a single delayed upload`() {
    AnswerUploadWorker.scheduleDebounced(context)
    AnswerUploadWorker.scheduleDebounced(context)

    val uploads = activeUploads()
    assertEquals(1, uploads.size)
    assertEquals(WorkInfo.State.ENQUEUED, uploads.single().state)
    assertEquals(
      TimeUnit.SECONDS.toMillis(AnswerUploadWorker.UPLOAD_DEBOUNCE_SECONDS),
      uploads.single().initialDelayMillis,
    )
  }

  @Test
  fun `leaving the app replaces the delayed upload with an immediate one`() {
    AnswerUploadWorker.scheduleDebounced(context)
    AnswerUploadWorker.scheduleNow(context)

    val uploads = activeUploads()
    assertEquals(1, uploads.size)
    assertEquals(0L, uploads.single().initialDelayMillis)
  }
}
