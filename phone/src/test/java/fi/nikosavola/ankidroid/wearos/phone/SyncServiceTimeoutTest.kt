package fi.nikosavola.ankidroid.wearos.phone

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncServiceTimeoutTest {
  @Test
  fun onTimeout_stopsAndLeavesADismissableNotification() {
    val controller = Robolectric.buildService(SyncService::class.java).create()
    val service = controller.get()

    service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    assertTrue(shadowOf(service).isStoppedBySelf)
    val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val posted = shadowOf(manager).allNotifications.single()
    assertEquals("Sync paused", posted.extras.getString(Notification.EXTRA_TEXT))
    assertFalse(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
    assertTrue(posted.timeoutAfter > 0)
  }
}
